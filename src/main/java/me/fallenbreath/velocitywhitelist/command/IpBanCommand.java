package me.fallenbreath.velocitywhitelist.command;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.WhitelistManager;
import me.fallenbreath.velocitywhitelist.config.IpList;
import net.kyori.adventure.text.Component;

import java.util.Optional;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.*;

public class IpBanCommand
{
	private final WhitelistManager manager;

	public IpBanCommand(WhitelistManager whitelistManager)
	{
		this.manager = whitelistManager;
	}

	@SuppressWarnings("deprecation")  // Next time for sure...
	public void register(CommandManager commandManager)
	{
		var roots = new String[]{"ipban", "vipban"};

		var root = literal(roots[0]).
				requires(s -> s.hasPermission(PluginMeta.ID + ".command")).
				executes(c -> showStatus(c.getSource())).
				then(literal("add").
						then(argument("ip", string()).
								executes(c -> addIp(c.getSource(), getString(c, "ip")))
						)
				).
				then(literal("remove").
						then(argument("ip", string()).
								suggests((c, sb) -> suggestMatching(this.manager.getIpBanList().getIps(), sb)).
								executes(c -> removeIp(c.getSource(), getString(c, "ip")))
						)
				).
				then(literal("list").
						executes(c -> listIps(c.getSource()))
				).
				then(literal("reload").
						executes(c -> reloadList(c.getSource()))
				);

		commandManager.register(new BrigadierCommand(root.build()));

		for (int i = 1; i < roots.length; i++)
		{
			var alternative = literal(roots[i]).
					requires(s -> s.hasPermission(PluginMeta.ID + ".command")).
					redirect(root.build());

			commandManager.register(new BrigadierCommand(alternative.build()));
		}
	}

	/**
	 * Parses the given command argument into a canonical IP literal.
	 * Sends an error message to the command source and returns empty if the input is not a valid IP address
	 */
	private Optional<String> parseIpArgument(CommandSource source, String ipStr)
	{
		Optional<String> normalized = IpList.normalizeIpLiteral(ipStr);
		if (normalized.isEmpty())
		{
			source.sendMessage(Component.text(String.format("Error: '%s' is not a valid IP address.", ipStr)));
		}
		return normalized;
	}

	private int showStatus(CommandSource source)
	{
		IpList list = this.manager.getIpBanList();
		source.sendMessage(Component.text(String.format("%s v%s", PluginMeta.NAME, PluginMeta.VERSION)));
		source.sendMessage(Component.text(String.format("IP Ban Activated: %s (config enabled: %s, load ok: %s)", list.isActivated(), list.isConfigEnabled(), list.isLoadOk())));
		source.sendMessage(Component.text(String.format("IP Ban Size: %d IP addresses", list.getIps().size())));
		return 1;
	}

	private int addIp(CommandSource source, String ipStr)
	{
		IpList list = this.manager.getIpBanList();
		if (!list.isActivated())
		{
			source.sendMessage(Component.text("IP ban functionality is not activated"));
			return 0;
		}

		Optional<String> parsed = this.parseIpArgument(source, ipStr);
		if (parsed.isEmpty())
		{
			return 0;
		}
		String targetIp = parsed.get();

		synchronized (this.manager.getIpBanLock())
		{
			if (list.addIp(targetIp))
			{
				if (this.manager.saveList(list))
				{
					source.sendMessage(Component.text(String.format("Added IP %s to the IP ban list", targetIp)));

					// Automatically disconnect anyone connected on a banned IP
					this.manager.kickIpBannedPlayers();
					return 1;
				}
				else
				{
					list.removeIp(targetIp); // rollback
					source.sendMessage(Component.text("Error: Failed to save the IP ban list to disk. Action was not applied."));
					return 0;
				}
			}
		}

		source.sendMessage(Component.text(String.format("IP %s is already in the IP ban list", targetIp)));
		return 0;
	}

	private int removeIp(CommandSource source, String ipStr)
	{
		IpList list = this.manager.getIpBanList();
		if (!list.isActivated())
		{
			source.sendMessage(Component.text("IP ban functionality is not activated"));
			return 0;
		}

		Optional<String> parsed = this.parseIpArgument(source, ipStr);
		if (parsed.isEmpty())
		{
			return 0;
		}
		String targetIp = parsed.get();

		synchronized (this.manager.getIpBanLock())
		{
			if (list.removeIp(targetIp))
			{
				if (this.manager.saveList(list))
				{
					source.sendMessage(Component.text(String.format("Removed IP %s from the IP ban list", targetIp)));
					return 1;
				}
				else
				{
					list.addIp(targetIp); // rollback
					source.sendMessage(Component.text("Error: Failed to save the IP ban list to disk. Action was not applied."));
					return 0;
				}
			}
		}

		source.sendMessage(Component.text(String.format("IP %s is not in the IP ban list", targetIp)));
		return 0;
	}

	private int listIps(CommandSource source)
	{
		IpList list = this.manager.getIpBanList();
		if (!list.isActivated())
		{
			source.sendMessage(Component.text("IP ban functionality is not activated"));
			return 0;
		}

		var ips = list.getIps();
		source.sendMessage(Component.text(String.format("IP Ban size: %d", ips.size())));
		source.sendMessage(Component.text(String.format("Banned IPs: %s", String.join(", ", ips))));
		return ips.size();
	}

	private int reloadList(CommandSource source)
	{
		IpList list = this.manager.getIpBanList();
		if (!list.isConfigEnabled())
		{
			source.sendMessage(Component.text("IP ban functionality is disabled by config"));
			return 0;
		}

		synchronized (this.manager.getIpBanLock())
		{
			if (this.manager.loadIpList(list))
			{
				source.sendMessage(Component.text("IP ban list reloaded"));

				// Scan connected players and disconnect matching players who got banned in the reloaded file
				this.manager.kickIpBannedPlayers();
				return 1;
			}
			else
			{
				source.sendMessage(Component.text("IP ban list reload failed, see console for details"));
				return 0;
			}
		}
	}
}
