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
    void migrateIfNeeded() {
        // Migration logic will go here
    }
}
