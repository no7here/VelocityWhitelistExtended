package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;

import com.google.common.collect.Maps;

import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;

// Detects a loaded config's version and migrates it in memory and rewritten to disk to CONFIG_VERSION, filling in newly-introduced options with the values that preserve pre-migration behaviour
final class ConfigMigrator {

    static final int CONFIG_VERSION = 2;

    private final Logger logger;
    private final Path configFilePath;

    ConfigMigrator(Logger logger, Path configFilePath) {
        this.logger = logger;
        this.configFilePath = configFilePath;
    }

    // Returns the value of the given option in the given map or the given default if the option is absent
    private static Object option(
        Map<String, Object> options,
        String key,
        Object defaultValue
    ) {
        Object value = options.get(key);
        return value != null ? value : defaultValue;
    }

    // Parses a config version value that should be a YAML Number but also accepts a hand-quoted numeric string (e.g. `version: "2"`) so an already-current config doesn't get misdetected as legacy and needlessly re-migrated on every load
    private static int parseVersion(Object versionObj) {
        if (versionObj instanceof Number number) {
            return number.intValue();
        }
        if (versionObj instanceof String s && s.matches("\\d+")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                // A digit-only string too large to fit in an int is necessarily >= CONFIG_VERSION so treat it the same as any other already-current version rather than letting the whole config load fail over an oversized version number
                return Integer.MAX_VALUE;
            }
        }
        return 0;
    }

    // Migrates the configuration file to the current version if necessary
    @SuppressWarnings("unchecked")
    void migrateIfNeeded() {
        if (!java.nio.file.Files.exists(this.configFilePath)) {
            return;
        }

        String content;
        try {
            content = java.nio.file.Files.readString(this.configFilePath);
        } catch (IOException e) {
            this.logger.error("Failed to read configuration file for migration check", e);
            return;
        }

        Map<String, Object> options;
        try {
            options = (Map<String, Object>) FileUtils.newSafeYaml().load(content);
            if (options == null) {
                return; // Empty file
            }
        } catch (Exception e) {
            // Malformed YAML will fail later in Configuration#load, no need to migrate
            return;
        }

        Object versionObj = options.get("_version");
        if (versionObj == null) {
            versionObj = options.get("version");
        }
        int version = parseVersion(versionObj);
        if (version >= CONFIG_VERSION) {
            return;
        }

        this.logger.warn(
            "Migrating config file from {} to v{}",
            version == 0 ? "a legacy version" : "v" + version,
            CONFIG_VERSION
        );
        this.logger.warn(
            "Please read the documentation for more information: {}",
            PluginMeta.REPOSITORY_URL
        );

        // Backup legacy config
        try {
            java.nio.file.Path backupPath = this.configFilePath.resolveSibling("config.yml.v1.bak");
            FileUtils.safeWrite(backupPath, content);
            this.logger.info("Created a backup of the legacy configuration at {}", backupPath.getFileName());
        } catch (IOException e) {
            this.logger.error("Failed to backup legacy configuration. Aborting migration.", e);
            return;
        }

        boolean regexSuccess = false;
        try {
            String newContent = content;
            newContent = newContent.replaceAll("(?m)^_version:(.*?)$", "version: " + CONFIG_VERSION);
            newContent = newContent.replaceAll("(?m)^version:(.*?)$", "version: " + CONFIG_VERSION);
            // Replace enabled with whitelist_enabled and blacklist_enabled
            newContent = newContent.replaceAll("(?m)^enabled:(.*?)$", "whitelist_enabled:$1\nblacklist_enabled:$1");
            newContent = newContent.replaceAll("(?m)^kick_message:(.*?)$", "whitelist_kick_message:$1");

            // Inject missing new keys at the end
            newContent += "\n\n# --- Automatically added by v2 migration ---\n";
            newContent += "\n# Kick message for blacklisted players\n";
            newContent += "blacklist_kick_message: \"" + Configuration.DEFAULT_BLACKLIST_KICK_MESSAGE + "\"\n";
            newContent += "\n# Enable or disable the IP ban feature\n";
            newContent += "ipban_enabled: true\n";
            newContent += "\n# Kick message for IP banned players\n";
            newContent += "ipban_kick_message: \"" + Configuration.DEFAULT_IPBAN_KICK_MESSAGE + "\"\n";
            newContent += "\n# Automatically blacklist players who join from a banned IP\n";
            newContent += "blacklist_on_ipban_join: false\n";

            // Verify structurally valid
            Map<String, Object> testParse = (Map<String, Object>) FileUtils.newSafeYaml().load(newContent);
            if (testParse != null && testParse.containsKey("version") && testParse.containsKey("whitelist_enabled")) {
                // Configs from before the UUID default switch behaved as name mode when identify_mode was absent
                if (!testParse.containsKey("identify_mode")) {
                    newContent += "\n# Identify mode (name or uuid)\n";
                    newContent += "identify_mode: \"name\"\n";
                }

                FileUtils.safeWrite(this.configFilePath, newContent);
                regexSuccess = true;
                this.logger.info("Successfully migrated configuration to v2 while preserving comments.");
            }
        } catch (Exception e) {
            // Fallthrough to map-based fallback
        }

        if (!regexSuccess) {
            this.logger.warn("A complex configuration required a fallback migration. Your comments may have been removed, please check your new config (a backup is saved in config.yml.v1.bak).");
            Map<String, Object> newOptions = fallbackMapMigration(options);
            try {
                FileUtils.dumpYaml(this.configFilePath, newOptions);
                this.logger.info("Successfully migrated configuration to v2 using map fallback.");
            } catch (IOException e) {
                this.logger.error("Could not save the migrated configuration file", e);
            }
        }
    }

    private Map<String, Object> fallbackMapMigration(Map<String, Object> options) {
        Map<String, Object> newOptions = com.google.common.collect.Maps.newLinkedHashMap();
        newOptions.put("version", CONFIG_VERSION);
        newOptions.put(
            "identify_mode",
            option(options, "identify_mode", "name")
        );
        newOptions.put(
            "whitelist_enabled",
            option(
                options,
                "whitelist_enabled",
                option(options, "enabled", true)
            )
        );
        newOptions.put(
            "whitelist_kick_message",
            option(
                options,
                "whitelist_kick_message",
                option(options, "kick_message", Configuration.DEFAULT_WHITELIST_KICK_MESSAGE)
            )
        );
        newOptions.put(
            "blacklist_enabled",
            option(
                options,
                "blacklist_enabled",
                option(options, "enabled", true)
            )
        );
        newOptions.put(
            "blacklist_kick_message",
            option(
                options,
                "blacklist_kick_message",
                Configuration.DEFAULT_BLACKLIST_KICK_MESSAGE
            )
        );
        newOptions.put("ipban_enabled", option(options, "ipban_enabled", true));
        newOptions.put(
            "ipban_kick_message",
            option(
                options,
                "ipban_kick_message",
                Configuration.DEFAULT_IPBAN_KICK_MESSAGE
            )
        );
        newOptions.put(
            "blacklist_on_ipban_join",
            option(options, "blacklist_on_ipban_join", false)
        );
        return newOptions;
    }
}
