package me.fallenbreath.velocitywhitelist;

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
import me.fallenbreath.velocitywhitelist.utils.MojangAPI;
import me.fallenbreath.velocitywhitelist.utils.UuidUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class WhitelistManager
{
	private final Logger logger;
	private final Configuration config;
	private final ProxyServer server;
	private final PlayerList whitelist;
	private final PlayerList blacklist;
	private final IpList ipBanList;

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

	public void loadLists()
	{
		this.loadOneList(this.whitelist);
		this.loadOneList(this.blacklist);
		this.loadIpList(this.ipBanList);
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

		// uuid: get from value directly, or mojang api (lookuped by input value)
		// profile: get from server online player, lookuped by input value (name / uuid)

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
					if (isBlacklist)
					{
						this.server.getPlayer(playerName).ifPresent(this::handlePlayerAddedToBlacklist);
					}

					if (list.addPlayerName(playerName))
					{
						source.sendMessage(Component.text(String.format("Added player %s to the %s", playerName, list.getName())));
						return true;
					}
					source.sendMessage(Component.text(String.format("Player %s is already in the %s", playerName, list.getName())));
					return false;
				},
				(uuid, playerName, displayName) -> {
					if (isBlacklist)
					{
						this.server.getPlayer(uuid).ifPresent(this::handlePlayerAddedToBlacklist);
					}

					return list.computePlayerUUID(uuid, (exists, oldName) -> {
						var result = new PlayerList.PlayerUUIDComputeResult<Boolean>();
						if (exists)
						{
							result.ret = false;
							if (playerName != null && (oldName == null || !oldName.equals(playerName)))
							{
								// set player name as a comment
								result.addNewValue = true;
								result.newValue = playerName;
								source.sendMessage(Component.text(String.format(
										"Player %s is already in the %s, updated player name for this uuid from %s to %s",
										displayName, list.getName(), oldName, playerName
								)));
							}
							else
							{
								// don't modify
								result.addNewValue = false;
								source.sendMessage(Component.text(String.format("Player %s is already in the %s", displayName, list.getName())));
							}
						}
						else  // not exists
						{
							result.addNewValue = true;
							result.newValue = playerName;
							result.ret = true;
							source.sendMessage(Component.text(String.format("Added player %s to the %s", displayName, list.getName())));
						}
						return result;
					});
				}
		);
	}

	private void handlePlayerAddedToBlacklist(Player player)
	{
		var profile = player.getGameProfile();
		this.logger.info("Kicking player {} ({}) since it's being added to the blacklist", profile.getName(), profile.getId());
		Component message = MiniMessage.miniMessage().deserialize(this.config.getBlacklistKickMessage());
		player.disconnect(message);
	}

	public boolean removePlayer(CommandSource source, PlayerList list, String value)
	{
		return this.operatePlayer(
				source, value,
				(uuid, playerName) -> {
					if (list.removePlayerName(playerName))
					{
						source.sendMessage(Component.text(String.format("Removed player %s from the %s", playerName, list.getName())));
						return true;
					}
					source.sendMessage(Component.text(String.format("Player %s is already in the %s", playerName, list.getName())));
					return false;
				},
				(uuid, playerName, displayName) -> {
					if (list.removePlayerUUID(uuid) != null)
					{
						source.sendMessage(Component.text(String.format("Removed player %s from the %s", displayName, list.getName())));
						return true;
					}
					source.sendMessage(Component.text(String.format("Player %s is not in the %s", displayName, list.getName())));
					return false;
				}
		);

	}

	public void kickPlayersOnIp(String ipStr)
	{
		InetAddress targetAddr = null;
		try
		{
			targetAddr = InetAddress.getByName(ipStr.trim());
		}
		catch (UnknownHostException ignored) {}

		Component message = MiniMessage.miniMessage().deserialize(this.config.getIpBanKickMessage());

		for (Player player : this.server.getAllPlayers())
		{
			InetSocketAddress address = player.getRemoteAddress();
			if (address != null)
			{
				InetAddress playerAddr = address.getAddress();
				boolean match = false;
				if (targetAddr != null)
				{
					match = Arrays.equals(playerAddr.getAddress(), targetAddr.getAddress());
				}
				else
				{
					match = playerAddr.getHostAddress().equalsIgnoreCase(ipStr.trim());
				}

				if (match)
				{
					this.logger.info("Kicking connected player {} ({}) due to IP ban on {}", player.getUsername(), player.getUniqueId(), ipStr);
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

		// 1. Evaluate IP Ban list
		if (this.ipBanList.isActivated() && remoteAddress != null)
		{
			InetAddress ipAddress = remoteAddress.getAddress();
			String ipString = ipAddress.getHostAddress();
			if (this.ipBanList.checkIp(ipString))
			{
				Component message = MiniMessage.miniMessage().deserialize(this.config.getIpBanKickMessage());
				event.setResult(ResultedEvent.ComponentResult.denied(message));
				this.logger.info("Kicking player {} ({}) since their IP ({}) is banned", profile.getName(), profile.getId(), ipString);

				// Blacklist on join toggle
				if (this.config.isBlacklistOnIpBanJoin())
				{
					// Add player to blacklist
					if (this.config.getIdentifyMode() == IdentifyMode.NAME)
					{
						if (this.blacklist.addPlayerName(profile.getName()))
						{
							this.logger.info("Automatically added player name {} to the blacklist due to joining on banned IP", profile.getName());
							this.saveList(this.blacklist);
						}
					}
					else
					{
						this.blacklist.computePlayerUUID(profile.getId(), (exists, oldName) -> {
							var res = new PlayerList.PlayerUUIDComputeResult<Void>();
							if (!exists || profile.getName() != null && (oldName == null || !oldName.equals(profile.getName())))
							{
								res.addNewValue = true;
								res.newValue = profile.getName();
								this.logger.info("Automatically added player UUID {} ({}) to the blacklist due to joining on banned IP", profile.getId(), profile.getName());
							}
							return res;
						});
						this.saveList(this.blacklist);
					}
				}
				return; // Exit early if IP banned
			}
		}

		// 2. Evaluate Blacklist SECOND
		if (this.blacklist.isActivated() && this.isPlayerInBlacklist(profile))
		{
			Component message = MiniMessage.miniMessage().deserialize(this.config.getBlacklistKickMessage());
			event.setResult(ResultedEvent.ComponentResult.denied(message));

			this.logger.info("Kicking player {} ({}) since it's in the blacklist", profile.getName(), profile.getId());
			return; // Exit early if blacklisted
		}

		// 3. Evaluate Whitelist THIRD
		if (this.whitelist.isActivated() && !this.isPlayerInWhitelist(profile))
		{
			Component message = MiniMessage.miniMessage().deserialize(this.config.getWhitelistKickMessage());
			event.setResult(ResultedEvent.ComponentResult.denied(message));

			this.logger.info("Kicking player {} ({}) since it's not in the whitelist", profile.getName(), profile.getId());
		}
	}

	public boolean loadOneList(PlayerList destList)
	{
		PlayerList newList = destList.createNewEmptyList();
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
		catch (IOException e)
		{
			String msg = String.format("Failed to load the %s, the plugin might not work correctly!", newList.getName());
			this.logger.error(msg, e);
			return false;
		}
	}

	public boolean loadIpList(IpList destList)
	{
		IpList newList = destList.createNewEmptyList();
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
		catch (IOException e)
		{
			String msg = String.format("Failed to load the %s, the plugin might not work correctly!", newList.getName());
			this.logger.error(msg, e);
			return false;
		}
	}

	public void saveList(PlayerList list)
	{
		try
		{
			list.save();
		}
		catch (IOException e)
		{
			String msg = String.format("Failed to save the %s", list.getName());
			this.logger.error(msg, e);
		}
	}

	public void saveIpList(IpList list)
	{
		try
		{
			list.save();
		}
		catch (IOException e)
		{
			String msg = String.format("Failed to save the %s", list.getName());
			this.logger.error(msg, e);
		}
	}
}
