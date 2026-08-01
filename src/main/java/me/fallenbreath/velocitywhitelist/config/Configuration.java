package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.google.common.collect.Maps;

import me.fallenbreath.velocitywhitelist.IdentifyMode;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;

// Manages the configuration settings for the VelocityWhitelist plugin
public class Configuration {

    public static final String DEFAULT_WHITELIST_KICK_MESSAGE = "You are not in the whitelist!";
    public static final String DEFAULT_BLACKLIST_KICK_MESSAGE = "You are banned from the server!";
    public static final String DEFAULT_IPBAN_KICK_MESSAGE = "Your IP address is banned from the server!";
    public static final String DEFAULT_MAINTENANCE_KICK_MESSAGE = "<red>Server is currently in maintenance mode due to a configuration error. Please contact the administrator.</red>";

    // Bundles every field derived from a single load or reload so it can be published as one unit
    private static final class Snapshot {

        // Defines an empty snapshot used as the initial state
        private static final Snapshot EMPTY = new Snapshot(
            Collections.emptyMap(),
            IdentifyMode.DEFAULT
        );

        private final Map<String, Object> options;
        private final IdentifyMode identifyMode;

        // Initialises a new configuration snapshot with the given options and identify mode
        private Snapshot(
            Map<String, Object> options,
            IdentifyMode identifyMode
        ) {
            this.options = options;
            this.identifyMode = identifyMode;
        }
    }

    // Replaced atomically on every reload with a single volatile write so login-time readers never observe options and identify mode from two different loads mixed together
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final Logger logger;
    private final Path configFilePath;
    private final ConfigMigrator migrator;
    private final ConfigWarnings warnings;

    // Initialises the configuration manager with the required dependencies
    public Configuration(
        Logger logger,
        Path configFilePath,
        Supplier<Boolean> proxyOnlineModeGetter
    ) {
        this.logger = logger;
        this.configFilePath = configFilePath;
        this.migrator = new ConfigMigrator(logger, configFilePath);
        this.warnings = new ConfigWarnings(logger, proxyOnlineModeGetter);
    }

    // Parses the YAML content and updates the configuration state safely
    @SuppressWarnings("unchecked")
    public void load(String yamlContent) {
        // Parses and migrates into a staging map before publishing so a malformed config during a reload keeps the previous state enforced and concurrent logins never see a half-built option set
        Map<String, Object> loadedOptions = (Map<String, Object>) FileUtils.newSafeYaml().load(yamlContent);

        Map<String, Object> stagedOptions = Maps.newLinkedHashMap();
        if (loadedOptions != null) {
            stagedOptions.putAll(loadedOptions);
        }
        stagedOptions = Collections.unmodifiableMap(stagedOptions);

        IdentifyMode identifyMode = makeIdentifyMode(
            stagedOptions,
            this.logger
        );
        this.snapshot = new Snapshot(stagedOptions, identifyMode);
        this.warnings.warnAboutRiskyOptions(stagedOptions, identifyMode);
        this.warnings.warnAboutInvalidBooleanOptions(stagedOptions);
    }

    // Reads the configuration file from disk and loads it into memory
    public void reload() throws IOException {
        this.migrator.migrateIfNeeded();
        String content = Files.readString(this.configFilePath);
        this.load(content);
    }

    // Extracts and validates the identify mode from the configuration options
    private static IdentifyMode makeIdentifyMode(
        Map<String, Object> options,
        Logger logger
    ) {
        Object mode = options.get("identify_mode");
        if (mode instanceof String) {
            try {
                return IdentifyMode.valueOf(((String) mode).toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn(
                    "Invalid identify mode: {}, use default value {}",
                    mode,
                    IdentifyMode.DEFAULT.name().toLowerCase(Locale.ROOT)
                );
            }
        }
        return IdentifyMode.DEFAULT;
    }

    // Checks if the whitelist feature is enabled in the configuration
    public boolean isWhitelistEnabled() {
        return this.getBooleanOption("whitelist_enabled");
    }

    // Checks if the blacklist feature is enabled in the configuration
    public boolean isBlacklistEnabled() {
        return this.getBooleanOption("blacklist_enabled");
    }

    // Checks if the IP ban feature is enabled in the configuration
    public boolean isIpBanEnabled() {
        return this.getBooleanOption("ipban_enabled");
    }

    // Invalid values are warned about once per load in ConfigWarnings, not here as this getter is called on every login, so it must stay a pure read with no logging side effects
    private boolean getBooleanOption(String key) {
        Object value = this.snapshot.options.get(key);
        return value instanceof Boolean b && b;
    }

    // Checks if players should be automatically added to the blacklist when they attempt to join with a banned IP
    public boolean isBlacklistOnIpBanJoin() {
        Snapshot snapshot = this.snapshot;
        return (
            ConfigWarnings.isBlacklistOnIpBanJoinConfigured(snapshot.options) &&
            this.warnings.meetsBlacklistOnIpBanJoinRequirements(
                snapshot.identifyMode
            )
        );
    }

    // Retrieves the currently configured identify mode
    public IdentifyMode getIdentifyMode() {
        return this.snapshot.identifyMode;
    }

    // Retrieves the kick message displayed to players who are not on the whitelist
    public String getWhitelistKickMessage() {
        Object message = this.snapshot.options.get("whitelist_kick_message");
        if (message instanceof String) {
            return (String) message;
        }
        return DEFAULT_WHITELIST_KICK_MESSAGE;
    }

    // Retrieves the kick message displayed to players who are on the blacklist
    public String getBlacklistKickMessage() {
        Object message = this.snapshot.options.get("blacklist_kick_message");
        if (message instanceof String) {
            return (String) message;
        }
        return DEFAULT_BLACKLIST_KICK_MESSAGE;
    }

    // Retrieves the kick message displayed to players attempting to join from a banned IP address
    public String getIpBanKickMessage() {
        Object message = this.snapshot.options.get("ipban_kick_message");
        if (message instanceof String) {
            return (String) message;
        }
        return DEFAULT_IPBAN_KICK_MESSAGE;
    }

    // Retrieves the kick message displayed to players when the plugin is in fail-close maintenance mode
    public String getMaintenanceKickMessage() {
        // This message is intentionally not customisable to avoid configuration errors breaking the fail-close maintenance message itself.
        return DEFAULT_MAINTENANCE_KICK_MESSAGE;
    }
}
