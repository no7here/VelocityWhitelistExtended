package me.fallenbreath.velocitywhitelist;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import me.fallenbreath.velocitywhitelist.command.IpBanCommand;
import me.fallenbreath.velocitywhitelist.command.PluginControlCommand;
import me.fallenbreath.velocitywhitelist.command.WhitelistCommand;
import me.fallenbreath.velocitywhitelist.config.Configuration;

// Represents the main plugin class for VelocityWhitelistExtended
@Plugin(
    id = PluginMeta.ID,
    name = PluginMeta.NAME,
    version = PluginMeta.VERSION,
    url = PluginMeta.REPOSITORY_URL,
    description = "A simple whitelist plugin for velocity",
    authors = { "Fallen_Breath", "no7here" }
)
public class VelocityWhitelistPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFilePath;
    private final Configuration config;
    private final WhitelistManager whitelistManager;
    private boolean activated = false;

    public boolean isActivated() {
        return this.activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
        if (!activated) {
            this.logger.error("=========================================");
            this.logger.error("VELOCITY WHITELIST FAILED TO LOAD CORRECTLY");
            this.logger.error("THE PLUGIN IS NOW IN FAIL-CLOSE MODE.");
            this.logger.error("ALL PLAYERS WILL BE BLOCKED FROM JOINING.");
            this.logger.error("PLEASE CHECK THE ERRORS ABOVE, FIX THE CONFIG OR LISTS,");
            this.logger.error("AND RUN /velocitywhitelist reload TO RECOVER.");
            this.logger.error("=========================================");
        }
    }

    // Initialises the plugin with injected dependencies
    @Inject
    public VelocityWhitelistPlugin(
        ProxyServer server,
        Logger logger,
        @DataDirectory Path dataDirectory
    ) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFilePath = dataDirectory.resolve("config.yml");
        this.config = new Configuration(this.logger, this.configFilePath, () ->
            server.getConfiguration().isOnlineMode()
        );
        this.whitelistManager = new WhitelistManager(
            this,
            logger,
            this.config,
            this.dataDirectory,
            this.server
        );
    }

    // Handles the proxy initialisation event
    @Subscribe
    public void onProxyInitialisation(ProxyInitializeEvent event) {
        boolean configOk = this.prepareConfig();
        boolean listsOk = false;

        if (configOk) {
            listsOk = this.whitelistManager.loadLists();
        }

        this.setActivated(configOk && listsOk);

        this.server
            .getEventManager()
            .register(
                this,
                LoginEvent.class,
                this.whitelistManager::onPlayerLogin
            );
        new WhitelistCommand(this.whitelistManager).register(
            this.server.getCommandManager()
        );
        new IpBanCommand(this.whitelistManager).register(
            this.server.getCommandManager()
        );
        new PluginControlCommand(
            this,
            this.logger,
            this.config,
            this.whitelistManager
        ).register(this.server.getCommandManager());
    }

    // Prepares the plugin configuration and data directory
    private boolean prepareConfig() {
        if (
            !this.dataDirectory.toFile().exists() &&
            !this.dataDirectory.toFile().mkdirs()
        ) {
            this.logger.error("Create data directory failed");
            return false;
        }

        File file = this.configFilePath.toFile();

        if (!file.exists()) {
            try (
                InputStream in = this.getClass()
                    .getClassLoader()
                    .getResourceAsStream("config.yml")
            ) {
                String defaultConfig = new String(
                    Objects.requireNonNull(in).readAllBytes(),
                    StandardCharsets.UTF_8
                );
                Files.writeString(file.toPath(), defaultConfig);
            } catch (Exception e) {
                this.logger.error("Generate default config failed", e);
                return false;
            }
        }

        try {
            this.config.load(Files.readString(file.toPath()));
        } catch (Exception e) {
            this.logger.error("Read config failed", e);
            return false;
        }

        return true;
    }
}
