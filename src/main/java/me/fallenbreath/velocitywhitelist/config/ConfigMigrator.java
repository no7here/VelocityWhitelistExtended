package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;

import com.google.common.collect.Maps;

import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;

/**
 * Detects a loaded config's version and migrates it - in memory, and rewritten to disk - to
 * CONFIG_VERSION, filling in newly-introduced options with the values that preserve pre-migration
 * behaviour
 */
final class ConfigMigrator
{
	static final int CONFIG_VERSION = 2;

	private final Logger logger;
	private final Path configFilePath;

	ConfigMigrator(Logger logger, Path configFilePath)
	{
		this.logger = logger;
		this.configFilePath = configFilePath;
	}

	/**
	 * Returns the value of the given option in the given map, or the given default if the option is absent
	 */
	private static Object option(Map<String, Object> options, String key, Object defaultValue)
	{
		Object value = options.get(key);
		return value != null ? value : defaultValue;
	}

	/**
	 * Parses a config version value that should be a YAML Number, but also accepts a hand-quoted
	 * numeric string (e.g. `version: "2"`) so an already-current config doesn't get misdetected as
	 * legacy and needlessly re-migrated (including rewriting the file) on every load
	 */
	private static int parseVersion(Object versionObj)
	{
		if (versionObj instanceof Number number)
		{
			return number.intValue();
		}
		if (versionObj instanceof String s && s.matches("\\d+"))
		{
			try
			{
				return Integer.parseInt(s);
			}
			catch (NumberFormatException e)
			{
				// A digit-only string too large to fit in an int is necessarily >= CONFIG_VERSION,
				// so treat it the same as any other already-current version rather than letting the
				// whole config load fail over an oversized (if nonsensical) version number
				return Integer.MAX_VALUE;
			}
		}
		return 0;
	}

	/**
	 * Migrates the given staging options to the current config version, returning the map to publish
	 */
	Map<String, Object> migrate(Map<String, Object> options)
	{
		Object versionObj = options.get("_version");  // key used by config v1
		if (versionObj == null)
		{
			versionObj = options.get("version");
		}
		int version = parseVersion(versionObj);
		if (version >= CONFIG_VERSION)
		{
			return options;
		}

		this.logger.warn("Migrating config file from {} to v{}", version == 0 ? "a legacy version" : "v" + version, CONFIG_VERSION);
		this.logger.warn("Please read the documentation for more information: {}", PluginMeta.REPOSITORY_URL);

		// Configs from before the uuid default switch behaved as name mode when identify_mode was absent,
		// so "name" must stay the fallback here, or migration would silently stop name-based lists from matching.
		// uuid is the default for newly generated configs only.
		Map<String, Object> newOptions = Maps.newLinkedHashMap();
		newOptions.put("version", CONFIG_VERSION);
		newOptions.put("identify_mode", option(options, "identify_mode", "name"));
		newOptions.put("whitelist_enabled", option(options, "whitelist_enabled", option(options, "enabled", true)));
		newOptions.put("whitelist_kick_message", option(options, "whitelist_kick_message", option(options, "kick_message", "You are not in the whitelist!")));
		newOptions.put("blacklist_enabled", option(options, "blacklist_enabled", option(options, "enabled", true)));
		newOptions.put("blacklist_kick_message", option(options, "blacklist_kick_message", "You are banned from the server!"));
		newOptions.put("ipban_enabled", option(options, "ipban_enabled", true));
		newOptions.put("ipban_kick_message", option(options, "ipban_kick_message", "Your IP address is banned from the server!"));
		newOptions.put("blacklist_on_ipban_join", option(options, "blacklist_on_ipban_join", false));

		try
		{
			FileUtils.dumpYaml(this.configFilePath, newOptions);
		}
		catch (IOException e)
		{
			this.logger.warn("Could not save the migrated configuration file", e);
		}
		return newOptions;
	}
}
