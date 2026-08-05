package me.fallenbreath.velocitywhitelist.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.argument;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.literal;
import static me.fallenbreath.velocitywhitelist.command.CommandUtils.suggestMatching;

import java.util.function.Consumer;

import com.google.common.base.Joiner;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.WhitelistManager;
import me.fallenbreath.velocitywhitelist.config.PlayerList;
import net.kyori.adventure.text.Component;

// Handles the registration and execution of whitelist and blacklist commands
public class WhitelistCommand {

    private final WhitelistManager manager;

    // Initialises the whitelist command with the given manager
    public WhitelistCommand(WhitelistManager whitelistManager) {
        this.manager = whitelistManager;
    }

    @SuppressWarnings("deprecation") // Next time for sure...
    private void registerOne(
        CommandManager commandManager,
        String[] roots,
        PlayerList list,
        Consumer<Player> postAddHook
    ) {
        if (roots.length == 0) {
            throw new IllegalArgumentException();
        }

        var root = literal(roots[0])
            .requires(s -> s.hasPermission(PluginMeta.COMMAND_PERMISSION))
            .executes(c -> showListStatus(c.getSource(), list))
            .then(
                literal("add").then(
                    argument("uuid", word()).executes(c ->
                        addPlayer(c.getSource(), list, getString(c, "uuid"), postAddHook)
                    )
                )
            )
            .then(
                literal("remove").then(
                    argument("uuid", word())
                        .suggests((c, sb) ->
                            suggestMatching(
                                this.manager.getValuesForRemovalSuggestion(
                                    list
                                ),
                                sb
                            )
                        )
                        .executes(c ->
                            removePlayer(
                                c.getSource(),
                                list,
                                getString(c, "uuid")
                            )
                        )
                )
            )
            .then(
                literal("list").executes(c -> listPlayers(c.getSource(), list))
            )
            .then(
                literal("reload").executes(c -> reloadList(c.getSource(), list))
            );
        var rootNode = root.build();
        commandManager.register(new BrigadierCommand(rootNode));

        for (int i = 1; i < roots.length; i++) {
            var alternative = literal(roots[i])
                .requires(s -> s.hasPermission(PluginMeta.COMMAND_PERMISSION))
                // A bare redirect node is not executable in brigadier so the alias needs its own executes
                .executes(c -> showListStatus(c.getSource(), list))
                .redirect(rootNode);

            commandManager.register(new BrigadierCommand(alternative.build()));
        }
    }

    // Registers both the whitelist and blacklist commands
    public void register(CommandManager commandManager) {
        this.registerOne(
            commandManager,
            new String[] { "whitelist", "vwhitelist" },
            this.manager.getWhitelist(),
            null
        );
        this.registerOne(
            commandManager,
            new String[] { "blacklist", "vblacklist" },
            this.manager.getBlacklist(),
            this.manager::handlePlayerAddedToBlacklist
        );
    }

    // Shows the current status of the list to the command source
    private int showListStatus(CommandSource source, PlayerList list) {
        source.sendMessage(
            Component.text(
                String.format("%s v%s", PluginMeta.NAME, PluginMeta.VERSION)
            )
        );
        showListStatus(source, list, list.getName() + " ");
        return 1;
    }

    // Shows the detailed status of the list including activation and size
    protected static void showListStatus(
        CommandSource source,
        PlayerList list,
        String prefix
    ) {
        source.sendMessage(
            Component.text(
                String.format(
                    "%sActivated: %s (config enabled: %s, load ok: %s)",
                    prefix,
                    list.isActivated(),
                    list.isConfigEnabled(),
                    list.isLoadOk()
                )
            )
        );
        source.sendMessage(
            Component.text(
                String.format(
                    "%sSize: %d player names, %d player UUIDs",
                    prefix,
                    list.getPlayerNames().size(),
                    list.getPlayerUuidMappingEntries().size()
                )
            )
        );
    }

    // Adds a player to the specified list
    private int addPlayer(
        CommandSource source,
        PlayerList list,
        String playerName,
        Consumer<Player> postAddHook
    ) {
        if (!list.isActivated()) {
            source.sendMessage(
                Component.text(
                    String.format("%s is not activated", list.getName())
                )
            );
            return 0;
        }

        WhitelistManager.ModifyResult result = this.manager.addPlayer(source, list, playerName, postAddHook);
        return result != WhitelistManager.ModifyResult.ERROR ? 1 : 0;
    }

    // Removes a player from the specified list
    private int removePlayer(
        CommandSource source,
        PlayerList list,
        String playerName
    ) {
        if (!list.isActivated()) {
            source.sendMessage(
                Component.text(
                    String.format("%s is not activated", list.getName())
                )
            );
            return 0;
        }

        WhitelistManager.ModifyResult result = this.manager.removePlayer(source, list, playerName);
        return result != WhitelistManager.ModifyResult.ERROR ? 1 : 0;
    }

    // Lists all players in the specified list
    private int listPlayers(CommandSource source, PlayerList list) {
        if (!list.isActivated()) {
            source.sendMessage(
                Component.text(
                    String.format("%s is not activated", list.getName())
                )
            );
            return 0;
        }

        var players = this.manager.getValuesForListing(list);
        source.sendMessage(
            Component.text(
                String.format("%s size: %d", list.getName(), players.size())
            )
        );
        source.sendMessage(
            Component.text(
                String.format(
                    "%s players: %s",
                    list.getName(),
                    Joiner.on(", ").join(players)
                )
            )
        );
        return 1;
    }

    // Reloads the specified list from configuration
    private int reloadList(CommandSource source, PlayerList list) {
        if (!list.isConfigEnabled()) {
            source.sendMessage(
                Component.text(
                    String.format("%s is disabled by config", list.getName())
                )
            );
            return 0;
        }

        if (this.manager.loadOneList(list)) {
            source.sendMessage(
                Component.text(String.format("%s reloaded", list.getName()))
            );
            return 1;
        } else {
            source.sendMessage(
                Component.text(
                    String.format(
                        "%s reload failed, see console for more information",
                        list.getName()
                    )
                )
            );
            return 0;
        }
    }
}
