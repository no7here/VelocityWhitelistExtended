package me.fallenbreath.velocitywhitelist;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.google.common.collect.Lists;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;

import me.fallenbreath.velocitywhitelist.config.Configuration;
import me.fallenbreath.velocitywhitelist.config.IpList;
import me.fallenbreath.velocitywhitelist.config.PlayerList;
import me.fallenbreath.velocitywhitelist.config.YamlStoredList;
import me.fallenbreath.velocitywhitelist.utils.MojangAPI;
import me.fallenbreath.velocitywhitelist.utils.UuidUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Manages the whitelist, blacklist and IP ban lists for the proxy.
 *
 * Locking tiers and invariants:
 * 1. list.lock: Lowest level lock, internal to YamlStoredList implementations. Protects only the in-memory sets/maps.
 * 2. manager.saveLock: Protects atomic mutations to whitelist/blacklist that involve disk writes. Must not be held across blocking operations (e.g. HTTP calls).
 * 3. manager.ipBanLock: Protects atomic mutations to the ip ban list.
 */
public class WhitelistManager {

    private final Object plugin;
    private final Logger logger;
    private final Configuration config;
    private final ProxyServer server;
    private final PlayerList whitelist;
    private final PlayerList blacklist;
    private final IpList ipBanList;
    private final Object saveLock = new Object();
    private final Object ipBanLock = new Object();

    // Rate limiting auto-blacklist writes to prevent disk exhaustion or I/O flooding attacks
    private long lastAutoBlacklistReset = 0;
    private int autoBlacklistCount = 0;
    private static final int MAX_AUTO_BLACKLISTS_PER_WINDOW = 5;
    private static final long RATE_LIMIT_WINDOW_MS = 10000;

    // Track the last time a rate-limited skip warning was printed to avoid spamming console
    private long lastSkipWarningLogTime = 0;
    private static final long SKIP_WARNING_LOG_COOLDOWN_MS = 5000;

    public WhitelistManager(
        Object plugin,
        Logger logger,
        Configuration config,
        Path dataDirectory,
        ProxyServer server
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.config = config;
        this.whitelist = new PlayerList(
            "Whitelist",
            dataDirectory.resolve("whitelist.yml"),
            this.config::isWhitelistEnabled
        );
        this.blacklist = new PlayerList(
            "Blacklist",
            dataDirectory.resolve("blacklist.yml"),
            this.config::isBlacklistEnabled
        );
        this.ipBanList = new IpList(
            "IpBanList",
            dataDirectory.resolve("ipbans.yml"),
            this.config::isIpBanEnabled
        );
        this.server = server;
    }

    public PlayerList getWhitelist() {
        return this.whitelist;
    }

    public PlayerList getBlacklist() {
        return this.blacklist;
    }

    public IpList getIpBanList() {
        return this.ipBanList;
    }

    public void runAsync(Runnable task) {
        this.server.getScheduler().buildTask(this.plugin, task).schedule();
    }

    public boolean reloadIpBansAndKick(CommandSource source) {
        boolean success;
        synchronized (this.ipBanLock) {
            success = this.loadIpList(this.ipBanList);
        }
        if (success) {
            source.sendMessage(Component.text("IP ban list reloaded"));
            this.kickIpBannedPlayers();
        } else {
            source.sendMessage(Component.text("IP ban list reload failed, see console for details"));
        }
        return success;
    }

    public boolean loadLists() {
        boolean whitelistSuccess = this.loadOneList(this.whitelist);
        boolean blacklistSuccess = this.loadOneList(this.blacklist);
        boolean ipBanSuccess = this.loadIpList(this.ipBanList);
        return whitelistSuccess && blacklistSuccess && ipBanSuccess;
    }

    private boolean isPlayerInList(GameProfile profile, PlayerList list) {
        return switch (this.config.getIdentifyMode()) {
            case NAME -> list.checkPlayerName(profile.getName());
            case UUID -> list.checkPlayerUUID(profile.getId());
        };
    }

    public boolean isPlayerInWhitelist(GameProfile profile) {
        return this.isPlayerInList(profile, this.whitelist);
    }

    public boolean isPlayerInBlacklist(GameProfile profile) {
        return this.isPlayerInList(profile, this.blacklist);
    }

    private static String pretty(@NotNull UUID uuid, @Nullable String name) {
        return name != null
            ? String.format("%s (%s)", name, uuid)
            : uuid.toString();
    }

    public List<String> getValuesForRemovalSuggestion(PlayerList list) {
        return switch (this.config.getIdentifyMode()) {
            case NAME -> list.getPlayerNames();
            case UUID -> {
                List<String> values = Lists.newArrayList();
                var entries = list.getPlayerUuidMappingEntries();
                entries.forEach(e -> values.add(e.getKey().toString()));
                entries.forEach(e -> {
                    var name = e.getValue();
                    if (name != null) {
                        values.add(name);
                    }
                });
                yield values;
            }
        };
    }

    public List<String> getValuesForListing(PlayerList list) {
        return switch (this.config.getIdentifyMode()) {
            case NAME -> list.getPlayerNames();
            case UUID -> list.getPlayerUuidMappingEntries()
                .stream()
                .map(e -> pretty(e.getKey(), e.getValue()))
                .toList();
        };
    }

    private record ResolvedIdentity(
        @Nullable UUID uuid,
        @Nullable String playerName
    ) {}

    private Optional<ResolvedIdentity> resolveTarget(
        CommandSource source,
        String value
    ) {
        final Optional<UUID> inputUuid = UuidUtils.tryParseUuid(value);
        final boolean isUuidInput = inputUuid.isPresent();

        final Optional<GameProfile> profile;
        if (isUuidInput) {
            // Input is a UUID - try to get the profile from an online player
            profile = inputUuid
                .flatMap(this.server::getPlayer)
                .map(Player::getGameProfile);
        } else {
            // Input is a name - try to get the profile from an online player, or fallback to Mojang API / offline mode
            Optional<GameProfile> onlineProfile = this.server
                .getPlayer(value)
                .map(Player::getGameProfile);

            if (onlineProfile.isPresent()) {
                profile = onlineProfile;
            } else if (this.server.getConfiguration().isOnlineMode()) {
                profile = MojangAPI.queryPlayerByName(
                    this.logger,
                    this.server,
                    value
                ).map(r ->
                    new GameProfile(r.uuid(), r.playerName(), List.of())
                );
            } else {
                UUID offlineUuid = UuidUtils.getOfflinePlayerUuid(value);
                source.sendPlainMessage(
                    String.format(
                        "Inferred offline uuid from player name %s: %s",
                        value,
                        offlineUuid
                    )
                );
                profile = Optional.of(
                    new GameProfile(offlineUuid, value, List.of())
                );
            }
        }

        final Optional<UUID> uuid = isUuidInput
            ? inputUuid
            : profile.map(GameProfile::getId);

        return switch (this.config.getIdentifyMode()) {
            case NAME -> {
                if (isUuidInput) {
                    source.sendPlainMessage(
                        "WARN: Trying to use UUID in NAME mode. Nothing will happen"
                    );
                    yield Optional.empty();
                }
                // Prefers the resolved profile's name over the raw input: it's the canonical, case-preserved name checkPlayerName compares against at login, so storing the admin's possibly differently-cased input here would silently break the whitelist/blacklist match.
                yield Optional.of(new ResolvedIdentity(
                    uuid.orElse(null),
                    profile.map(GameProfile::getName).orElse(value)
                ));
            }
            case UUID -> {
                if (uuid.isEmpty()) {
                    source.sendPlainMessage(
                        "WARN: Trying to use a player name in UUID mode, and the player is not valid. Nothing will happen"
                    );
                    yield Optional.empty();
                }

                yield Optional.of(new ResolvedIdentity(
                    uuid.get(),
                    profile.map(GameProfile::getName).orElse(null)
                ));
            }
        };
    }

    private boolean saveOrRollback(
        YamlStoredList<?> list,
        Runnable rollback,
        Runnable onFailure
    ) {
        if (this.saveList(list)) {
            return true;
        }
        // Save failed: undo the mutation via `rollback`, then run `onFailure` (a command-source message, or a silent no-op for background callers like the auto-blacklist), so a failed save never leaves the in-memory list out of sync with disk
        rollback.run();
        onFailure.run();
        return false;
    }

    public enum ModifyResult {
        SUCCESS,
        NO_CHANGE,
        ERROR
    }

    public ModifyResult addPlayer(
        CommandSource source,
        PlayerList list,
        String value,
        Consumer<Player> postAddHook
    ) {
        Optional<ResolvedIdentity> targetOpt = this.resolveTarget(source, value);
        if (targetOpt.isEmpty()) {
            return ModifyResult.ERROR;
        }
        ResolvedIdentity target = targetOpt.get();

        return switch (this.config.getIdentifyMode()) {
            case NAME -> {
                String playerName = target.playerName();
                boolean added;

                synchronized (this.saveLock) {
                    added = list.addPlayerName(playerName);
                    if (
                        added &&
                        !this.saveOrRollback(
                            list,
                            () -> list.removePlayerName(playerName),
                            () ->
                                source.sendMessage(
                                    Component.text(
                                        String.format(
                                            "Failed to save the %s to disk. Action was not applied.",
                                            list.getName()
                                        )
                                    )
                                )
                        )
                    ) {
                        // Skips the blacklist kick since the change was not saved
                        yield ModifyResult.ERROR;
                    }
                }

                if (added) {
                    source.sendMessage(
                        Component.text(
                            String.format(
                                "Added player %s to the %s",
                                playerName,
                                list.getName()
                            )
                        )
                    );
                } else {
                    source.sendMessage(
                        Component.text(
                            String.format(
                                "Player %s is already in the %s",
                                playerName,
                                list.getName()
                            )
                        )
                    );
                }

                // Kick only once the blacklist state is confirmed: freshly added and saved, or already listed
                if (postAddHook != null) {
                    this.server
                        .getPlayer(playerName)
                        .ifPresent(postAddHook);
                }
                yield added ? ModifyResult.SUCCESS : ModifyResult.NO_CHANGE;
            }
            case UUID -> {
                UUID uuid = target.uuid();
                String playerName = target.playerName();
                String displayName = pretty(uuid, playerName);
                boolean addedNew;
                boolean nameChanged;
                PlayerList.UuidEntry oldEntry;

                synchronized (this.saveLock) {
                    oldEntry = list.peekPlayerUUID(uuid);
                    addedNew = !oldEntry.exists();
                    nameChanged =
                        oldEntry.exists() &&
                        playerName != null &&
                        !playerName.equals(oldEntry.name());

                    if (addedNew || nameChanged) {
                        list.putPlayerUUID(uuid, playerName);
                        if (
                            !this.saveOrRollback(
                                list,
                                () ->
                                    this.rollbackUuidEntry(
                                        list,
                                        uuid,
                                        oldEntry
                                    ),
                                () ->
                                    source.sendMessage(
                                        Component.text(
                                            String.format(
                                                "Failed to save the %s to disk. Action was not applied.",
                                                list.getName()
                                            )
                                        )
                                    )
                            )
                        ) {
                            // Skips the blacklist kick since the change was not saved
                            yield ModifyResult.ERROR;
                        }
                    }
                }

                if (addedNew) {
                    source.sendMessage(
                        Component.text(
                            String.format(
                                "Added player %s to the %s",
                                displayName,
                                list.getName()
                            )
                        )
                    );
                } else if (nameChanged) {
                    source.sendMessage(
                        Component.text(
                            String.format(
                                "Player %s is already in the %s, updated player name for this uuid from %s to %s",
                                displayName,
                                list.getName(),
                                oldEntry.name(),
                                playerName
                            )
                        )
                    );
                } else {
                    source.sendMessage(
                        Component.text(
                            String.format(
                                "Player %s is already in the %s",
                                displayName,
                                list.getName()
                            )
                        )
                    );
                }

                // Kick only once the blacklist state is confirmed: freshly added and saved, or already listed
                if (postAddHook != null) {
                    this.server
                        .getPlayer(uuid)
                        .ifPresent(postAddHook);
                }
                yield (addedNew || nameChanged) ? ModifyResult.SUCCESS : ModifyResult.NO_CHANGE;
            }
        };
    }

    // Removes a player from the specified list
    public ModifyResult removePlayer(
        CommandSource source,
        PlayerList list,
        String value
    ) {
        Optional<ResolvedIdentity> targetOpt = this.resolveTarget(source, value);
        if (targetOpt.isEmpty()) {
            return ModifyResult.ERROR;
        }
        ResolvedIdentity target = targetOpt.get();

        return switch (this.config.getIdentifyMode()) {
            case NAME -> {
                String playerName = target.playerName();
                synchronized (this.saveLock) {
                    if (list.removePlayerName(playerName)) {
                        if (
                            this.saveOrRollback(
                                list,
                                () -> list.addPlayerName(playerName),
                                () ->
                                    source.sendMessage(
                                        Component.text(
                                            String.format(
                                                "Failed to save the %s to disk. Action was not applied.",
                                                list.getName()
                                            )
                                        )
                                    )
                            )
                        ) {
                            source.sendMessage(
                                Component.text(
                                    String.format(
                                        "Removed player %s from the %s",
                                        playerName,
                                        list.getName()
                                    )
                                )
                            );
                            yield ModifyResult.SUCCESS;
                        }
                        yield ModifyResult.ERROR;
                    }
                }
                source.sendMessage(
                    Component.text(
                        String.format(
                            "Player %s is not in the %s",
                            playerName,
                            list.getName()
                        )
                    )
                );
                yield ModifyResult.NO_CHANGE;
            }
            case UUID -> {
                UUID uuid = target.uuid();
                String playerName = target.playerName();
                String displayName = pretty(uuid, playerName);

                synchronized (this.saveLock) {
                    PlayerList.UuidEntry oldEntry = list.peekPlayerUUID(uuid);
                    if (oldEntry.exists()) {
                        list.removePlayerUUID(uuid);
                        if (
                            this.saveOrRollback(
                                list,
                                () ->
                                    this.rollbackUuidEntry(
                                        list,
                                        uuid,
                                        oldEntry
                                    ),
                                () ->
                                    source.sendMessage(
                                        Component.text(
                                            String.format(
                                                "Failed to save the %s to disk. Action was not applied.",
                                                list.getName()
                                            )
                                        )
                                    )
                            )
                        ) {
                            source.sendMessage(
                                Component.text(
                                    String.format(
                                        "Removed player %s from the %s",
                                        displayName,
                                        list.getName()
                                    )
                                )
                            );
                            yield ModifyResult.SUCCESS;
                        }
                        yield ModifyResult.ERROR;
                    }
                }
                source.sendMessage(
                    Component.text(
                        String.format(
                            "Player %s is not in the %s",
                            displayName,
                            list.getName()
                        )
                    )
                );
                yield ModifyResult.NO_CHANGE;
            }
        };
    }

    // Restores a UUID mapping to its previous state for undoing failed mutations
    private void rollbackUuidEntry(
        PlayerList list,
        UUID uuid,
        PlayerList.UuidEntry oldEntry
    ) {
        if (oldEntry.exists()) {
            list.putPlayerUUID(uuid, oldEntry.name());
        } else {
            list.removePlayerUUID(uuid);
        }
    }

    // Disconnects an online player who has been added to the blacklist
    public void handlePlayerAddedToBlacklist(Player player) {
        var profile = player.getGameProfile();
        this.logger.info(
            "Kicking player {} ({}) since it's being added to the blacklist",
            profile.getName(),
            profile.getId()
        );
        Component message = MiniMessage.miniMessage().deserialize(
            this.config.getBlacklistKickMessage()
        );
        player.disconnect(message);
    }

    // Kicks all connected players whose IP address matches an entry in the ban list
    public void kickIpBannedPlayers() {
        List<Player> toKick = Lists.newArrayList();
        synchronized (this.ipBanLock) {
            if (!this.ipBanList.isActivated()) {
                return;
            }
            for (Player player : this.server.getAllPlayers()) {
                InetSocketAddress address = player.getRemoteAddress();
                // getAddress() returns null for unresolved socket addresses
                if (address != null && address.getAddress() != null) {
                    String ipString = address.getAddress().getHostAddress();
                    if (this.ipBanList.checkIp(ipString)) {
                        toKick.add(player);
                    }
                }
            }
        }

        if (toKick.isEmpty()) {
            return;
        }

        Component message = MiniMessage.miniMessage().deserialize(
            this.config.getIpBanKickMessage()
        );

        for (Player player : toKick) {
            InetSocketAddress address = player.getRemoteAddress();
            if (address != null && address.getAddress() != null) {
                String ipString = address.getAddress().getHostAddress();
                this.logger.info(
                    "Kicking connected player {} ({}) since their IP ({}) is banned",
                    player.getUsername(),
                    player.getUniqueId(),
                    ipString
                );
                player.disconnect(message);
            }
        }
    }

    // Adds an IP to the ban list and saves it, matching addPlayer()'s mutate/save/rollback shape
    public ModifyResult addIp(CommandSource source, String ip) {
        boolean success = false;
        synchronized (this.ipBanLock) {
            if (this.ipBanList.addIp(ip)) {
                if (
                    this.saveOrRollback(
                        this.ipBanList,
                        () -> this.ipBanList.removeIp(ip),
                        () ->
                            source.sendMessage(
                                Component.text(
                                    "Error: Failed to save the IP ban list to disk. Action was not applied."
                                )
                            )
                    )
                ) {
                    source.sendMessage(
                        Component.text(
                            String.format("Added IP %s to the IP ban list", ip)
                        )
                    );
                    success = true;
                } else {
                    return ModifyResult.ERROR;
                }
            } else {
                source.sendMessage(
                    Component.text(
                        String.format("IP %s is already in the IP ban list", ip)
                    )
                );
                return ModifyResult.NO_CHANGE;
            }
        }
        if (success) {
            this.kickIpBannedPlayers();
            return ModifyResult.SUCCESS;
        }
        return ModifyResult.ERROR;
    }

    // Removes an IP from the ban list and saves it, matching removePlayer()'s mutate/save/rollback shape
    public ModifyResult removeIp(CommandSource source, String ip) {
        synchronized (this.ipBanLock) {
            if (this.ipBanList.removeIp(ip)) {
                if (
                    this.saveOrRollback(
                        this.ipBanList,
                        () -> this.ipBanList.addIp(ip),
                        () ->
                            source.sendMessage(
                                Component.text(
                                    "Error: Failed to save the IP ban list to disk. Action was not applied."
                                )
                            )
                    )
                ) {
                    source.sendMessage(
                        Component.text(
                            String.format(
                                "Removed IP %s from the IP ban list",
                                ip
                            )
                        )
                    );
                    return ModifyResult.SUCCESS;
                }
                return ModifyResult.ERROR;
            }
        }
        source.sendMessage(
            Component.text(String.format("IP %s is not in the IP ban list", ip))
        );
        return ModifyResult.NO_CHANGE;
    }

    // Evaluates incoming connections against the IP ban list, blacklist and whitelist (in that order)
    public void onPlayerLogin(LoginEvent event) {
        Player player = event.getPlayer();
        GameProfile profile = player.getGameProfile();
        InetSocketAddress remoteAddress = player.getRemoteAddress();

        // getAddress() returns null for unresolved socket addresses
        if (
            this.ipBanList.isActivated() &&
            remoteAddress != null &&
            remoteAddress.getAddress() != null
        ) {
            String ipString = remoteAddress.getAddress().getHostAddress();
            if (this.ipBanList.checkIp(ipString)) {
                Component message = MiniMessage.miniMessage().deserialize(
                    this.config.getIpBanKickMessage()
                );
                event.setResult(ResultedEvent.ComponentResult.denied(message));
                this.logger.info(
                    "Kicking player {} ({}) since their IP ({}) is banned",
                    profile.getName(),
                    profile.getId(),
                    ipString
                );

                this.autoBlacklistOnBannedIpJoin(profile);
                return;
            }
        }

        if (this.blacklist.isActivated() && this.isPlayerInBlacklist(profile)) {
            Component message = MiniMessage.miniMessage().deserialize(
                this.config.getBlacklistKickMessage()
            );
            event.setResult(ResultedEvent.ComponentResult.denied(message));

            this.logger.info(
                "Kicking player {} ({}) since it's in the blacklist",
                profile.getName(),
                profile.getId()
            );
            return;
        }

        if (
            this.whitelist.isActivated() && !this.isPlayerInWhitelist(profile)
        ) {
            Component message = MiniMessage.miniMessage().deserialize(
                this.config.getWhitelistKickMessage()
            );
            event.setResult(ResultedEvent.ComponentResult.denied(message));

            this.logger.info(
                "Kicking player {} ({}) since it's not in the whitelist",
                profile.getName(),
                profile.getId()
            );
        }
    }

    // Automatically adds the profile to the blacklist if it joined from a banned IP
    private void autoBlacklistOnBannedIpJoin(GameProfile profile) {
        // Does nothing if blacklist_on_ipban_join is disabled or its UUID / online mode requirements aren't met (see Configuration#isBlacklistOnIpBanJoin()) or if blacklist failed to load
        if (
            !this.config.isBlacklistOnIpBanJoin() || !this.blacklist.isLoadOk()
        ) {
            return;
        }

        this.runAsync(() -> {
            synchronized (this.saveLock) {
                // Nothing to do if already blacklisted with an up-to-date name
                if (!this.blacklistEntryNeedsUpdate(profile)) {
                    return;
                }
                // Quota is only consumed when a write is actually needed
                if (!this.tryAcquireAutoBlacklistQuota()) {
                    return;
                }
                this.autoBlacklistByUuid(profile);
            }
        });
    }

    // Must be called while holding saveLock
    private boolean blacklistEntryNeedsUpdate(GameProfile profile) {
        // Auto-blacklist only ever runs in UUID identify mode (see Configuration#isBlacklistOnIpBanJoin()), so only a UUID lookup is needed here
        PlayerList.UuidEntry entry = this.blacklist.peekPlayerUUID(
            profile.getId()
        );
        // Needs a write if the entry is missing entirely, or if its stored name is stale
        return (
            !entry.exists() ||
            (profile.getName() != null &&
                !profile.getName().equals(entry.name()))
        );
    }

    // Consumes one unit of the auto-blacklist rate limit quota if available. Must be called while holding the save lock.
    private boolean tryAcquireAutoBlacklistQuota() {
        long now = System.currentTimeMillis();
        if (now - this.lastAutoBlacklistReset > RATE_LIMIT_WINDOW_MS) {
            this.lastAutoBlacklistReset = now;
            this.autoBlacklistCount = 0;
        }
        if (this.autoBlacklistCount < MAX_AUTO_BLACKLISTS_PER_WINDOW) {
            this.autoBlacklistCount++;
            return true;
        }
        if (now - this.lastSkipWarningLogTime > SKIP_WARNING_LOG_COOLDOWN_MS) {
            this.lastSkipWarningLogTime = now;
            this.logger.warn(
                "Skipping automatic blacklist additions due to rate-limit protection (IP ban is still enforced)"
            );
        }
        return false;
    }

    // Writes a profile to the blacklist automatically. Must be called while holding the save lock.
    private void autoBlacklistByUuid(GameProfile profile) {
        PlayerList.UuidEntry oldEntry = this.blacklist.peekPlayerUUID(
            profile.getId()
        );
        this.blacklist.putPlayerUUID(profile.getId(), profile.getName());
        if (
            this.saveOrRollback(
                this.blacklist,
                () ->
                    this.rollbackUuidEntry(
                        this.blacklist,
                        profile.getId(),
                        oldEntry
                    ),
                () -> {}
            )
        ) {
            this.logger.info(
                "Automatically added player UUID {} ({}) to the blacklist due to joining on banned IP",
                profile.getId(),
                profile.getName()
            );
        }
    }

    private <T extends YamlStoredList<T>> boolean loadListImpl(
        T destList,
        Object lock
    ) {
        // Acquire transaction lock during reload to prevent concurrent modification or overwrite conflicts
        synchronized (lock) {
            T newList = destList.createNewEmptyList();
            try {
                if (!newList.getFilePath().toFile().isFile()) {
                    this.logger.info(
                        "Creating default empty {} file",
                        newList.getName()
                    );
                    newList.save();
                }
                newList.load(this.logger);

                destList.resetTo(newList);
                return true;
            } catch (Exception e) {
                // The YAML library can throw its own exception types on a bad file
                String msg = String.format(
                    "Failed to load the %s, the plugin might not work correctly!",
                    newList.getName()
                );
                this.logger.error(msg, e);
                return false;
            }
        }
    }

    public boolean loadOneList(PlayerList destList) {
        return this.loadListImpl(destList, this.saveLock);
    }

    public boolean loadIpList(IpList destList) {
        return this.loadListImpl(destList, this.ipBanLock);
    }

    public boolean saveList(YamlStoredList<?> list) {
        try {
            list.save();
            return true;
        } catch (IOException e) {
            String msg = String.format("Failed to save the %s", list.getName());
            this.logger.error(msg, e);
            return false;
        }
    }
}
