package me.fallenbreath.velocitywhitelist;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

public class WhitelistManager
{
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

	public WhitelistManager(Logger logger, Configuration config, Path dataDirectory, ProxyServer server)
	{
		this.logger = logger;
		this.config = config;
		this.whitelist = new PlayerList("Whitelist", dataDirectory.resolve("whitelist.yml"), this.config::isWhitelistEnabled);
		this.blacklist = new PlayerList("Blacklist", dataDirectory.resolve("blacklist.yml"), this.config::isBlacklistEnabled);
		this.ipBanList = new IpList("IpBanList", dataDirectory.resolve("ipbans.yml"), this.config::isIpBanEnabled);
		this.server = server;
	}

	public Configuration getConfig()
	{
		return this.config;
	}

	public ProxyServer getServer()
	{
		return this.server;
	}

	public PlayerList getWhitelist()
	{
		return this.whitelist;
	}

	public PlayerList getBlacklist()
	{
		return this.blacklist;
	}

	public IpList getIpBanList()
	{
		return this.ipBanList;
	}

	public Object getIpBanLock()
	{
		return this.ipBanLock;
	}

	public boolean loadLists()
	{
		boolean ok1 = this.loadOneList(this.whitelist);
		boolean ok2 = this.loadOneList(this.blacklist);
		boolean ok3 = this.loadIpList(this.ipBanList);
		return ok1 && ok2 && ok3;
	}

	private boolean isPlayerInList(GameProfile profile, PlayerList list)
	{
		return switch (this.config.getIdentifyMode())
		{
			case NAME -> list.checkPlayerName(profile.getName());
			case UUID -> list.checkPlayerUUID(profile.getId());
		};
	}

	public boolean isPlayerInWhitelist(GameProfile profile)
	{
		return this.isPlayerInList(profile, this.whitelist);
	}

	public boolean isPlayerInBlacklist(GameProfile profile)
	{
		return this.isPlayerInList(profile, this.blacklist);
	}

	private static String pretty(@NotNull UUID uuid, @Nullable String name)
	{
		return name != null ? String.format("%s (%s)", name, uuid) : uuid.toString();
	}

	public List<String> getValuesForRemovalSuggestion(PlayerList list)
	{
		return switch (this.config.getIdentifyMode())
		{
			case NAME -> list.getPlayerNames();
			case UUID -> {
				List<String> values = Lists.newArrayList();
				var entries = list.getPlayerUuidMappingEntries();
				entries.forEach(e -> values.add(e.getKey().toString()));
				entries.forEach(e -> {
					var name = e.getValue();
					if (name != null)
					{
						values.add(name);
					}
				});
				yield values;
			}
		};
	}

	public List<String> getValuesForListing(PlayerList list)
	{
		return switch (this.config.getIdentifyMode())
		{
			case NAME -> list.getPlayerNames();
			case UUID -> list.getPlayerUuidMappingEntries().stream()
					.map(e -> pretty(e.getKey(), e.getValue()))
					.toList();
		};
	}

	private interface NameModeHandler
	{
		boolean handle(@Nullable UUID uuid, @NotNull String playerName);
	}

	private interface UuidHandler
	{
		boolean handle(@NotNull UUID uuid, @Nullable String playerName, @NotNull String displayName);
	}

	@SuppressWarnings("EnhancedSwitchMigration")
	private boolean operatePlayer(
			CommandSource source,
			String value,
			NameModeHandler handleNameMode,
			UuidHandler handleUuidMode
	)
	{
		final Optional<UUID> inputUuid = UuidUtils.tryParseUuid(value);

		Optional<UUID> uuid = inputUuid;
		Optional<GameProfile> profile = this.server.getPlayer(value).map(Player::getGameProfile);  // get online player by name

		if (uuid.isEmpty())
		{
			uuid = profile.map(GameProfile::getId);
		}
		if (uuid.isEmpty() && profile.isEmpty() && this.config.getIdentifyMode() != IdentifyMode.NAME)  // no need to lookup for name mode
		{
			// uuid == null && profile == null  -> input is name, player not online
			if (this.server.getConfiguration().isOnlineMode())
			{
				profile = MojangAPI.queryPlayerByName(this.logger, this.server, value)
						.map(r -> new GameProfile(r.uuid(), r.playerName(), List.of()));
			}
			else
			{
				UUID offlineUuid = UuidUtils.getOfflinePlayerUuid(value);
				profile = Optional.of(new GameProfile(offlineUuid, value, List.of()));
				source.sendPlainMessage(String.format("Inferred offline uuid from player name %s: %s", value, offlineUuid));
			}
		}
		if (uuid.isEmpty())
		{
			uuid = profile.map(GameProfile::getId);
		}
		if (profile.isEmpty())
		{
			profile = uuid.flatMap(this.server::getPlayer).map(Player::getGameProfile);
		}

		// uuid: get from value directly, or mojang api (looked up by input value)
		// profile: get from server online player, looked up by input value (name / uuid)

		return switch (this.config.getIdentifyMode())
		{
			case NAME -> {
				if (inputUuid.isPresent())
				{
					source.sendPlainMessage("WARN: Trying to use UUID in NAME mode. Nothing will happen");
					yield false;
				}
				yield handleNameMode.handle(profile.map(GameProfile::getId).orElse(null), value);
			}

			case UUID -> {
				if (uuid.isEmpty() && profile.isEmpty())
				{
					source.sendPlainMessage("WARN: Trying to use a player name in UUID mode, and the player is not valid. Nothing will happen");
					yield false;
				}

				UUID playerUuid = uuid.isPresent() ? uuid.get() : profile.get().getId();
				String playerName = profile.map(GameProfile::getName).orElse(null);
				yield handleUuidMode.handle(playerUuid, playerName, pretty(playerUuid, playerName));
			}
		};
	}

	public boolean addPlayer(CommandSource source, PlayerList list, String value)
	{
		boolean isBlacklist = list == this.getBlacklist();
		return this.operatePlayer(
				source, value,
				(uuid, playerName) -> {
					boolean added = false;

					// Lock is acquired only after operatePlayer (which executes Mojang synchronous lookup) completes
					synchronized (this.saveLock)
					{
						if (list.addPlayerName(playerName))
						{
							if (this.saveList(list))
							{
								added = true;
							}
							else
							{
								list.removePlayerName(playerName); // rollback
								source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())));
								return false;  // the action was not applied, so no blacklist kick either
							}
						}
					}

					if (added)
					{
						source.sendMessage(Component.text(String.format("Added player %s to the %s", playerName, list.getName())));
					}
					else
					{
						source.sendMessage(Component.text(String.format("Player %s is already in the %s", playerName, list.getName())));
					}

					// Kick only once the blacklist state is confirmed: freshly added and saved, or already listed
					if (isBlacklist)
					{
						this.server.getPlayer(playerName).ifPresent(this::handlePlayerAddedToBlacklist);
					}
					return added;
				},
				(uuid, playerName, displayName) -> {
					boolean addedNew;
					boolean nameChanged;
					PlayerList.UuidEntry oldEntry;

					// Lock is acquired only after operatePlayer (which executes Mojang synchronous lookup) completes
					synchronized (this.saveLock)
					{
						oldEntry = list.peekPlayerUUID(uuid);
						addedNew = !oldEntry.exists();
						nameChanged = oldEntry.exists() && playerName != null && !playerName.equals(oldEntry.name());

						if (addedNew || nameChanged)
						{
							list.putPlayerUUID(uuid, playerName);
							if (!this.saveList(list))
							{
								this.rollbackUuidEntry(list, uuid, oldEntry);
								source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())));
								return false;  // the action was not applied, so no blacklist kick either
							}
						}
					}

					if (addedNew)
					{
						source.sendMessage(Component.text(String.format("Added player %s to the %s", displayName, list.getName())));
					}
					else if (nameChanged)
					{
						source.sendMessage(Component.text(String.format(
								"Player %s is already in the %s, updated player name for this uuid from %s to %s",
								displayName, list.getName(), oldEntry.name(), playerName
						)));
					}
					else
					{
						source.sendMessage(Component.text(String.format("Player %s is already in the %s", displayName, list.getName())));
					}

					// Kick only once the blacklist state is confirmed: freshly added and saved, or already listed
					if (isBlacklist)
					{
						this.server.getPlayer(uuid).ifPresent(this::handlePlayerAddedToBlacklist);
					}
					return addedNew || nameChanged;
				}
		);
	}

	public boolean removePlayer(CommandSource source, PlayerList list, String value)
	{
		return this.operatePlayer(
				source, value,
				(uuid, playerName) -> {
					// Lock is acquired only after operatePlayer (which executes Mojang synchronous lookup) completes
					synchronized (this.saveLock)
					{
						if (list.removePlayerName(playerName))
						{
							if (this.saveList(list))
							{
								source.sendMessage(Component.text(String.format("Removed player %s from the %s", playerName, list.getName())));
								return true;
							}
							else
							{
								list.addPlayerName(playerName); // rollback
								source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())));
								return false;
							}
						}
					}
					source.sendMessage(Component.text(String.format("Player %s is not in the %s", playerName, list.getName())));
					return false;
				},
				(uuid, playerName, displayName) -> {
					// Lock is acquired only after operatePlayer (which executes Mojang synchronous lookup) completes
					synchronized (this.saveLock)
					{
						PlayerList.UuidEntry oldEntry = list.peekPlayerUUID(uuid);
						if (oldEntry.exists())
						{
							list.removePlayerUUID(uuid);
							if (this.saveList(list))
							{
								source.sendMessage(Component.text(String.format("Removed player %s from the %s", displayName, list.getName())));
								return true;
							}
							else
							{
								this.rollbackUuidEntry(list, uuid, oldEntry);
								source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())));
								return false;
							}
						}
					}
					source.sendMessage(Component.text(String.format("Player %s is not in the %s", displayName, list.getName())));
					return false;
				}
		);
	}

	/**
	 * Restores a uuid mapping to the given previous state, for undoing a mutation whose save failed
	 */
	private void rollbackUuidEntry(PlayerList list, UUID uuid, PlayerList.UuidEntry oldEntry)
	{
		if (oldEntry.exists())
		{
			list.putPlayerUUID(uuid, oldEntry.name());
		}
		else
		{
			list.removePlayerUUID(uuid);
		}
	}

	private void handlePlayerAddedToBlacklist(Player player)
	{
		var profile = player.getGameProfile();
		this.logger.info("Kicking player {} ({}) since it's being added to the blacklist", profile.getName(), profile.getId());
		Component message = MiniMessage.miniMessage().deserialize(this.config.getBlacklistKickMessage());
		player.disconnect(message);
	}

	public void kickIpBannedPlayers()
	{
		if (!this.ipBanList.isActivated())
		{
			return;
		}

		Component message = MiniMessage.miniMessage().deserialize(this.config.getIpBanKickMessage());

		for (Player player : this.server.getAllPlayers())
		{
			InetSocketAddress address = player.getRemoteAddress();
			if (address != null && address.getAddress() != null)  // getAddress() is null for unresolved socket addresses
			{
				String ipString = address.getAddress().getHostAddress();
				if (this.ipBanList.checkIp(ipString))
				{
					this.logger.info("Kicking connected player {} ({}) since their IP ({}) is banned", player.getUsername(), player.getUniqueId(), ipString);
					player.disconnect(message);
				}
			}
		}
	}

	public void onPlayerLogin(LoginEvent event)
	{
		Player player = event.getPlayer();
		GameProfile profile = player.getGameProfile();
		InetSocketAddress remoteAddress = player.getRemoteAddress();

		// 1. Evaluate IP ban list FIRST
		if (this.ipBanList.isActivated() && remoteAddress != null && remoteAddress.getAddress() != null)  // getAddress() is null for unresolved socket addresses
		{
			String ipString = remoteAddress.getAddress().getHostAddress();
			if (this.ipBanList.checkIp(ipString))
			{
				Component message = MiniMessage.miniMessage().deserialize(this.config.getIpBanKickMessage());
				event.setResult(ResultedEvent.ComponentResult.denied(message));
				this.logger.info("Kicking player {} ({}) since their IP ({}) is banned", profile.getName(), profile.getId(), ipString);

				this.autoBlacklistOnBannedIpJoin(profile);
				return;
			}
		}

		// 2. Evaluate blacklist SECOND
		if (this.blacklist.isActivated() && this.isPlayerInBlacklist(profile))
		{
			Component message = MiniMessage.miniMessage().deserialize(this.config.getBlacklistKickMessage());
			event.setResult(ResultedEvent.ComponentResult.denied(message));

			this.logger.info("Kicking player {} ({}) since it's in the blacklist", profile.getName(), profile.getId());
			return;
		}

		// 3. Evaluate whitelist THIRD
		if (this.whitelist.isActivated() && !this.isPlayerInWhitelist(profile))
		{
			Component message = MiniMessage.miniMessage().deserialize(this.config.getWhitelistKickMessage());
			event.setResult(ResultedEvent.ComponentResult.denied(message));

			this.logger.info("Kicking player {} ({}) since it's not in the whitelist", profile.getName(), profile.getId());
		}
	}

	/**
	 * Automatically adds the given profile to the blacklist, in response to a join attempt from a banned IP.
	 * Does nothing if the blacklist_on_ipban_join option is disabled - including when its uuid identify mode /
	 * online mode requirements aren't met, see {@link Configuration#isBlacklistOnIpBanJoin()} - if the blacklist
	 * failed to load, if the profile is already blacklisted with an up-to-date name, or if the rate limit quota
	 * is exhausted
	 */
	private void autoBlacklistOnBannedIpJoin(GameProfile profile)
	{
		if (!this.config.isBlacklistOnIpBanJoin() || !this.blacklist.isLoadOk())
		{
			return;
		}

		synchronized (this.saveLock)
		{
			if (!this.blacklistEntryNeedsUpdate(profile))
			{
				return;
			}
			// Rate limit quota is checked and consumed ONLY when a write is actually needed
			if (!this.tryAcquireAutoBlacklistQuota())
			{
				return;
			}
			this.autoBlacklistByUuid(profile);
		}
	}

	/**
	 * Whether auto-blacklisting this profile requires a blacklist write: the entry is missing,
	 * or its stored player name is outdated. Auto-blacklist only ever runs in uuid identify mode
	 * (see {@link Configuration#isBlacklistOnIpBanJoin()}), so only that lookup is needed here.
	 * Must be called while holding {@link #saveLock}
	 */
	private boolean blacklistEntryNeedsUpdate(GameProfile profile)
	{
		PlayerList.UuidEntry entry = this.blacklist.peekPlayerUUID(profile.getId());
		return !entry.exists() || (profile.getName() != null && !profile.getName().equals(entry.name()));
	}

	/**
	 * Consumes one unit of the auto-blacklist rate limit quota if available.
	 * Must be called while holding {@link #saveLock}
	 */
	private boolean tryAcquireAutoBlacklistQuota()
	{
		long now = System.currentTimeMillis();
		if (now - this.lastAutoBlacklistReset > RATE_LIMIT_WINDOW_MS)
		{
			this.lastAutoBlacklistReset = now;
			this.autoBlacklistCount = 0;
		}
		if (this.autoBlacklistCount < MAX_AUTO_BLACKLISTS_PER_WINDOW)
		{
			this.autoBlacklistCount++;
			return true;
		}
		if (now - this.lastSkipWarningLogTime > SKIP_WARNING_LOG_COOLDOWN_MS)
		{
			this.lastSkipWarningLogTime = now;
			this.logger.warn("Skipping automatic blacklist additions due to rate-limit protection (IP ban is still enforced)");
		}
		return false;
	}

	/**
	 * Must be called while holding {@link #saveLock}
	 */
	private void autoBlacklistByUuid(GameProfile profile)
	{
		PlayerList.UuidEntry oldEntry = this.blacklist.peekPlayerUUID(profile.getId());
		this.blacklist.putPlayerUUID(profile.getId(), profile.getName());
		if (this.saveList(this.blacklist))
		{
			this.logger.info("Automatically added player UUID {} ({}) to the blacklist due to joining on banned IP", profile.getId(), profile.getName());
		}
		else
		{
			this.rollbackUuidEntry(this.blacklist, profile.getId(), oldEntry); // rollback on failed save
		}
	}

	private <T extends YamlStoredList<T>> boolean loadListImpl(T destList, Object lock)
	{
		// Acquire transaction lock during reload to prevent concurrent modification or overwrite conflicts
		synchronized (lock)
		{
			T newList = destList.createNewEmptyList();
			try
			{
				if (!newList.getFilePath().toFile().isFile())
				{
					this.logger.info("Creating default empty {} file", newList.getName());
					newList.save();
				}
				newList.load(this.logger);

				destList.resetTo(newList);
				return true;
			}
			catch (Exception e) // Catch generic Exception to handle SnakeYAML runtime YAMLException and parsing failures
			{
				String msg = String.format("Failed to load the %s, the plugin might not work correctly!", newList.getName());
				this.logger.error(msg, e);
				return false;
			}
		}
	}

	public boolean loadOneList(PlayerList destList)
	{
		return this.loadListImpl(destList, this.saveLock);
	}

	public boolean loadIpList(IpList destList)
	{
		return this.loadListImpl(destList, this.ipBanLock);
	}

	public boolean saveList(YamlStoredList<?> list)
	{
		try
		{
			list.save();
			return true;
		}
		catch (IOException e)
		{
			String msg = String.format("Failed to save the %s", list.getName());
			this.logger.error(msg, e);
			return false;
		}
	}
}
