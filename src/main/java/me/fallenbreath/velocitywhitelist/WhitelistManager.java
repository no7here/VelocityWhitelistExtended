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

// Manages the whitelist, blacklist and IP ban lists for the proxy
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

	// Initialises the whitelist manager with required dependencies
	public WhitelistManager(Logger logger, Configuration config, Path dataDirectory, ProxyServer server)
	{
		this.logger = logger;
		this.config = config;
		this.whitelist = new PlayerList("Whitelist", dataDirectory.resolve("whitelist.yml"), this.config::isWhitelistEnabled);
		this.blacklist = new PlayerList("Blacklist", dataDirectory.resolve("blacklist.yml"), this.config::isBlacklistEnabled);
		this.ipBanList = new IpList("IpBanList", dataDirectory.resolve("ipbans.yml"), this.config::isIpBanEnabled);
		this.server = server;
	}

	// Retrieves the configuration
	public Configuration getConfig()
	{
		return this.config;
	}

	// Retrieves the proxy server instance
	public ProxyServer getServer()
	{
		return this.server;
	}

	// Retrieves the whitelist
	public PlayerList getWhitelist()
	{
		return this.whitelist;
	}

	// Retrieves the blacklist
	public PlayerList getBlacklist()
	{
		return this.blacklist;
	}

	// Retrieves the IP ban list
	public IpList getIpBanList()
	{
		return this.ipBanList;
	}

	// Retrieves the lock object for IP ban operations
	public Object getIpBanLock()
	{
		return this.ipBanLock;
	}

	// Loads all lists from disk and returns true if all loaded successfully
	public boolean loadLists()
	{
		boolean ok1 = this.loadOneList(this.whitelist);
		boolean ok2 = this.loadOneList(this.blacklist);
		boolean ok3 = this.loadIpList(this.ipBanList);
		return ok1 && ok2 && ok3;
	}

	// Checks if a player profile is present in a specific list based on the configured identification mode
	private boolean isPlayerInList(GameProfile profile, PlayerList list)
	{
		return switch (this.config.getIdentifyMode())
		{
			case NAME -> list.checkPlayerName(profile.getName());
			case UUID -> list.checkPlayerUUID(profile.getId());
		};
	}

	// Checks if a player is in the whitelist
	public boolean isPlayerInWhitelist(GameProfile profile)
	{
		return this.isPlayerInList(profile, this.whitelist);
	}

	// Checks if a player is in the blacklist
	public boolean isPlayerInBlacklist(GameProfile profile)
	{
		return this.isPlayerInList(profile, this.blacklist);
	}

	// Formats a UUID and optional name into a readable string
	private static String pretty(@NotNull UUID uuid, @Nullable String name)
	{
		return name != null ? String.format("%s (%s)", name, uuid) : uuid.toString();
	}

	// Retrieves a list of values to suggest for removal from a player list
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

	// Retrieves a list of formatted values for displaying a player list
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

	// Functional interface for handling operations in name mode
	private interface NameModeHandler
	{
		boolean handle(@Nullable UUID uuid, @NotNull String playerName);
	}

	// Functional interface for handling operations in UUID mode
	private interface UuidHandler
	{
		boolean handle(@NotNull UUID uuid, @Nullable String playerName, @NotNull String displayName);
	}

	// Executes a player operation by resolving the player profile and delegating to the appropriate handler
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
		Optional<GameProfile> profile = this.server.getPlayer(value).map(Player::getGameProfile);

		if (uuid.isEmpty())
		{
			uuid = profile.map(GameProfile::getId);
		}
		if (uuid.isEmpty() && profile.isEmpty())
		{
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

		return switch (this.config.getIdentifyMode())
		{
			case NAME -> {
				if (inputUuid.isPresent())
				{
					source.sendPlainMessage("WARN: Trying to use UUID in NAME mode. Nothing will happen");
					yield false;
				}
				// Prefer the resolved profile name over raw command argument for canonical casing so storing the admin's possibly differently cased input here avoids silently breaking matches
				yield handleNameMode.handle(profile.map(GameProfile::getId).orElse(null), profile.map(GameProfile::getName).orElse(value));
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

	// Saves the list to disk or rolls back the mutation and runs a failure callback if saving fails ensuring the in-memory list is never out of sync with disk state
	private boolean saveOrRollback(YamlStoredList<?> list, Runnable rollback, Runnable onFailure)
	{
		if (this.saveList(list))
		{
			return true;
		}
		rollback.run();
		onFailure.run();
		return false;
	}

	// Adds a player to the specified list
	public boolean addPlayer(CommandSource source, PlayerList list, String value)
	{
		boolean isBlacklist = list == this.getBlacklist();
		return this.operatePlayer(
				source, value,
				(uuid, playerName) -> {
					boolean added;

					synchronized (this.saveLock)
					{
						added = list.addPlayerName(playerName);
						if (added && !this.saveOrRollback(list, () -> list.removePlayerName(playerName), () ->
								source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())))))
						{
							return false;
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

					synchronized (this.saveLock)
					{
						oldEntry = list.peekPlayerUUID(uuid);
						addedNew = !oldEntry.exists();
						nameChanged = oldEntry.exists() && playerName != null && !playerName.equals(oldEntry.name());

						if (addedNew || nameChanged)
						{
							list.putPlayerUUID(uuid, playerName);
							if (!this.saveOrRollback(list, () -> this.rollbackUuidEntry(list, uuid, oldEntry), () ->
									source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())))))
							{
								return false;
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

					if (isBlacklist)
					{
						this.server.getPlayer(uuid).ifPresent(this::handlePlayerAddedToBlacklist);
					}
					return addedNew || nameChanged;
				}
		);
	}

	// Removes a player from the specified list
	public boolean removePlayer(CommandSource source, PlayerList list, String value)
	{
		return this.operatePlayer(
				source, value,
				(uuid, playerName) -> {
					synchronized (this.saveLock)
					{
						if (list.removePlayerName(playerName))
						{
							if (this.saveOrRollback(list, () -> list.addPlayerName(playerName), () ->
									source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())))))
							{
								source.sendMessage(Component.text(String.format("Removed player %s from the %s", playerName, list.getName())));
								return true;
							}
							return false;
						}
					}
					source.sendMessage(Component.text(String.format("Player %s is not in the %s", playerName, list.getName())));
					return false;
				},
				(uuid, playerName, displayName) -> {
					synchronized (this.saveLock)
					{
						PlayerList.UuidEntry oldEntry = list.peekPlayerUUID(uuid);
						if (oldEntry.exists())
						{
							list.removePlayerUUID(uuid);
							if (this.saveOrRollback(list, () -> this.rollbackUuidEntry(list, uuid, oldEntry), () ->
									source.sendMessage(Component.text(String.format("Failed to save the %s to disk. Action was not applied.", list.getName())))))
							{
								source.sendMessage(Component.text(String.format("Removed player %s from the %s", displayName, list.getName())));
								return true;
							}
							return false;
						}
					}
					source.sendMessage(Component.text(String.format("Player %s is not in the %s", displayName, list.getName())));
					return false;
				}
		);
	}

	// Restores a UUID mapping to its previous state for undoing failed mutations
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

	// Disconnects an online player who has been added to the blacklist
	private void handlePlayerAddedToBlacklist(Player player)
	{
		var profile = player.getGameProfile();
		this.logger.info("Kicking player {} ({}) since it's being added to the blacklist", profile.getName(), profile.getId());
		Component message = MiniMessage.miniMessage().deserialize(this.config.getBlacklistKickMessage());
		player.disconnect(message);
	}

	// Kicks all connected players whose IP address matches an entry in the ban list
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
			if (address != null && address.getAddress() != null)
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

	// Adds an IP to the ban list and saves it
	public boolean addIp(CommandSource source, String ip)
	{
		synchronized (this.ipBanLock)
		{
			if (this.ipBanList.addIp(ip))
			{
				if (this.saveOrRollback(this.ipBanList, () -> this.ipBanList.removeIp(ip), () ->
						source.sendMessage(Component.text("Error: Failed to save the IP ban list to disk. Action was not applied."))))
				{
					source.sendMessage(Component.text(String.format("Added IP %s to the IP ban list", ip)));
					this.kickIpBannedPlayers();
					return true;
				}
				return false;
			}
		}
		source.sendMessage(Component.text(String.format("IP %s is already in the IP ban list", ip)));
		return false;
	}

	// Removes an IP from the ban list and saves it
	public boolean removeIp(CommandSource source, String ip)
	{
		synchronized (this.ipBanLock)
		{
			if (this.ipBanList.removeIp(ip))
			{
				if (this.saveOrRollback(this.ipBanList, () -> this.ipBanList.addIp(ip), () ->
						source.sendMessage(Component.text("Error: Failed to save the IP ban list to disk. Action was not applied."))))
				{
					source.sendMessage(Component.text(String.format("Removed IP %s from the IP ban list", ip)));
					return true;
				}
				return false;
			}
		}
		source.sendMessage(Component.text(String.format("IP %s is not in the IP ban list", ip)));
		return false;
	}

	// Evaluates incoming connections against the IP ban list, blacklist and whitelist (in that order)
	public void onPlayerLogin(LoginEvent event)
	{
		Player player = event.getPlayer();
		GameProfile profile = player.getGameProfile();
		InetSocketAddress remoteAddress = player.getRemoteAddress();

		if (this.ipBanList.isActivated() && remoteAddress != null && remoteAddress.getAddress() != null)
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

		if (this.blacklist.isActivated() && this.isPlayerInBlacklist(profile))
		{
			Component message = MiniMessage.miniMessage().deserialize(this.config.getBlacklistKickMessage());
			event.setResult(ResultedEvent.ComponentResult.denied(message));

			this.logger.info("Kicking player {} ({}) since it's in the blacklist", profile.getName(), profile.getId());
			return;
		}

		if (this.whitelist.isActivated() && !this.isPlayerInWhitelist(profile))
		{
			Component message = MiniMessage.miniMessage().deserialize(this.config.getWhitelistKickMessage());
			event.setResult(ResultedEvent.ComponentResult.denied(message));

			this.logger.info("Kicking player {} ({}) since it's not in the whitelist", profile.getName(), profile.getId());
		}
	}

	// Automatically adds the profile to the blacklist if they join from a banned IP (only when blacklist_on_ipban_join is enabled & working or rate limit isn't exhausted)
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
			if (!this.tryAcquireAutoBlacklistQuota())
			{
				return;
			}
			this.autoBlacklistByUuid(profile);
		}
	}

	// Determines if auto-blacklisting this profile requires a blacklist write. Must be called while holding the save lock.
	private boolean blacklistEntryNeedsUpdate(GameProfile profile)
	{
		PlayerList.UuidEntry entry = this.blacklist.peekPlayerUUID(profile.getId());
		return !entry.exists() || (profile.getName() != null && !profile.getName().equals(entry.name()));
	}

	// Consumes one unit of the auto-blacklist rate limit quota if available. Must be called while holding the save lock.
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

	// Writes a profile to the blacklist automatically. Must be called while holding the save lock.
	private void autoBlacklistByUuid(GameProfile profile)
	{
		PlayerList.UuidEntry oldEntry = this.blacklist.peekPlayerUUID(profile.getId());
		this.blacklist.putPlayerUUID(profile.getId(), profile.getName());
		if (this.saveOrRollback(this.blacklist, () -> this.rollbackUuidEntry(this.blacklist, profile.getId(), oldEntry), () -> {}))
		{
			this.logger.info("Automatically added player UUID {} ({}) to the blacklist due to joining on banned IP", profile.getId(), profile.getName());
		}
	}

	// Loads a specific yaml stored list safely from disk
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
			catch (Exception e)
			{
				String msg = String.format("Failed to load the %s, the plugin might not work correctly!", newList.getName());
				this.logger.error(msg, e);
				return false;
			}
		}
	}

	// Loads a player list from disk
	public boolean loadOneList(PlayerList destList)
	{
		return this.loadListImpl(destList, this.saveLock);
	}

	// Loads an IP ban list from disk
	public boolean loadIpList(IpList destList)
	{
		return this.loadListImpl(destList, this.ipBanLock);
	}

	// Saves a generic yaml stored list to disk
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
