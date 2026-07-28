package me.fallenbreath.velocitywhitelist.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.argument;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.literal;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.suggestMatching;

import java.util.Optional;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;

import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.WhitelistManager;
import me.fallenbreath.velocitywhitelist.config.IpList;
import net.kyori.adventure.text.Component;

// Class for the IP ban command
public class IpBanCommand {

    private final WhitelistManager manager;

    // Constructs a new IP ban command
    public IpBanCommand(WhitelistManager whitelistManager) {
        this.manager = whitelistManager;
    }

    // Registers the command with the command manager
    @SuppressWarnings("deprecation") // Next time for sure...
    public void register(CommandManager commandManager) {
        var roots = new String[] { "ipban", "vipban" };

        var root = literal(roots[0])
            .requires(s -> s.hasPermission(PluginMeta.ID + ".command"))
            .executes(c -> showStatus(c.getSource()))
            .then(
                literal("add")
                    // Use greedyString instead of string/word as Brigadier's unquoted strings cannot contain colons so IPv6 addresses would otherwise require quoting to parse
                    .then(
                        argument("ip", greedyString()).executes(c ->
                            addIp(c.getSource(), getString(c, "ip"))
                        )
                    )
            )
            .then(
                literal("remove").then(
                    argument("ip", greedyString())
                        .suggests((c, sb) ->
                            suggestMatching(
                                this.manager.getIpBanList().getIps(),
                                sb
                            )
                        )
                        .executes(c ->
                            removeIp(c.getSource(), getString(c, "ip"))
                        )
                )
            )
            .then(literal("list").executes(c -> listIps(c.getSource())))
            .then(literal("reload").executes(c -> reloadList(c.getSource())));

        var rootNode = root.build();

        commandManager.register(new BrigadierCommand(rootNode));

        for (int i = 1; i < roots.length; i++) {
            var alternative = literal(roots[i])
                .requires(s -> s.hasPermission(PluginMeta.ID + ".command"))
                // A bare redirect node is not executable in Brigadier so the alias needs its own executes
                .executes(c -> showStatus(c.getSource()))
                .redirect(rootNode);

            commandManager.register(new BrigadierCommand(alternative.build()));
        }
    }

    // Parses the given command argument into a canonical IP literal and sends an error message to the command source returning empty if the input is not a valid IP address
    private Optional<String> parseIpArgument(
        CommandSource source,
        String ipStr
    ) {
        Optional<String> normalised = IpList.normaliseIpLiteral(ipStr);
        if (normalised.isEmpty()) {
            source.sendMessage(
                Component.text(
                    String.format(
                        "Error: '%s' is not a valid IP address.",
                        ipStr
                    )
                )
            );
        }
        return normalised;
    }

    // Shows the status of the IP ban list
    private int showStatus(CommandSource source) {
        IpList list = this.manager.getIpBanList();
        source.sendMessage(
            Component.text(
                String.format("%s v%s", PluginMeta.NAME, PluginMeta.VERSION)
            )
        );
        source.sendMessage(
            Component.text(
                String.format(
                    "IP Ban Activated: %s (config enabled: %s, load ok: %s)",
                    list.isActivated(),
                    list.isConfigEnabled(),
                    list.isLoadOk()
                )
            )
        );
        source.sendMessage(
            Component.text(
                String.format(
                    "IP Ban Size: %d IP addresses",
                    list.getIps().size()
                )
            )
        );
        return 1;
    }

    // Adds an IP to the ban list
    private int addIp(CommandSource source, String ipStr) {
        IpList list = this.manager.getIpBanList();
        if (!list.isActivated()) {
            source.sendMessage(
                Component.text("IP ban functionality is not activated")
            );
            return 0;
        }

        Optional<String> parsed = this.parseIpArgument(source, ipStr);
        if (parsed.isEmpty()) {
            return 0;
        }

        return this.manager.addIp(source, parsed.get()) ? 1 : 0;
    }

    // Removes an IP from the ban list
    private int removeIp(CommandSource source, String ipStr) {
        IpList list = this.manager.getIpBanList();
        if (!list.isActivated()) {
            source.sendMessage(
                Component.text("IP ban functionality is not activated")
            );
            return 0;
        }

        Optional<String> parsed = this.parseIpArgument(source, ipStr);
        if (parsed.isEmpty()) {
            return 0;
        }

        return this.manager.removeIp(source, parsed.get()) ? 1 : 0;
    }

    // Lists all banned IPs
    private int listIps(CommandSource source) {
        IpList list = this.manager.getIpBanList();
        if (!list.isActivated()) {
            source.sendMessage(
                Component.text("IP ban functionality is not activated")
            );
            return 0;
        }

        var ips = list.getIps();
        source.sendMessage(
            Component.text(String.format("IP Ban size: %d", ips.size()))
        );
        source.sendMessage(
            Component.text(
                String.format("Banned IPs: %s", String.join(", ", ips))
            )
        );
        return ips.size();
    }

    // Reloads the IP ban list
    private int reloadList(CommandSource source) {
        IpList list = this.manager.getIpBanList();
        if (!list.isConfigEnabled()) {
            source.sendMessage(
                Component.text("IP ban functionality is disabled by config")
            );
            return 0;
        }

        synchronized (this.manager.getIpBanLock()) {
            if (this.manager.loadIpList(list)) {
                source.sendMessage(Component.text("IP ban list reloaded"));

                // Scan connected players and disconnect matching players who got banned in the reloaded file
                this.manager.kickIpBannedPlayers();
                return 1;
            } else {
                source.sendMessage(
                    Component.text(
                        "IP ban list reload failed, see console for details"
                    )
                );
                return 0;
            }
        }
    }
}
