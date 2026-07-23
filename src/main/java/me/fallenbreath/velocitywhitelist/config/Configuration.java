package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;

import com.google.common.base.Supplier;
import com.google.common.collect.Maps;

import me.fallenbreath.velocitywhitelist.IdentifyMode;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;

public class Configuration
{
	/**
	 * Bundles every field derived from a single load/reload, so it can be published as one unit
	 */
	private static final class Snapshot
	{
		private static final Snapshot EMPTY = new Snapshot(Collections.emptyMap(), IdentifyMode.DEFAULT);

		private final Map<String, Object> options;
		private final IdentifyMode identifyMode;

		private Snapshot(Map<String, Object> options, IdentifyMode identifyMode)
		{
			this.options = options;
			this.identifyMode = identifyMode;
		}
	}

	// Replaced atomically on every (re)load with a single volatile write, so login-time readers
	// never observe options and identifyMode from two different loads mixed together
	private volatile Snapshot snapshot = Snapshot.EMPTY;
	private final Logger logger;
	private final Path configFilePath;
	private final ConfigMigrator migrator;
	private final ConfigWarnings warnings;

	public Configuration(Logger logger, Path configFilePath, Supplier<Boolean> proxyOnlineModeGetter)
	{
		this.logger = logger;
		this.configFilePath = configFilePath;
		this.migrator = new ConfigMigrator(logger, configFilePath);
		this.warnings = new ConfigWarnings(logger, proxyOnlineModeGetter);
	}

	@SuppressWarnings("unchecked")
	public void load(String yamlContent)
	{
		// Parse and migrate into a staging map before publishing, so a malformed config during a reload
		// keeps the previous state enforced, and concurrent logins never see a half-built option set
		Map<String, Object> loadedOptions = (Map<String, Object>)FileUtils.newSafeYaml().load(yamlContent);

		Map<String, Object> stagedOptions = Maps.newLinkedHashMap();
		if (loadedOptions != null)  // an empty config file parses to null
		{
			stagedOptions.putAll(loadedOptions);
		}
		stagedOptions = this.migrator.migrate(stagedOptions);
		stagedOptions = Collections.unmodifiableMap(stagedOptions);

		IdentifyMode identifyMode = makeIdentifyMode(stagedOptions, this.logger);
		this.snapshot = new Snapshot(stagedOptions, identifyMode);
		this.warnings.warnAboutRiskyOptions(stagedOptions, identifyMode);
		this.warnings.warnAboutInvalidBooleanOptions(stagedOptions);
	}

	public void reload() throws IOException
	{
		String content = Files.readString(this.configFilePath);
		this.load(content);
	}

	private static IdentifyMode makeIdentifyMode(Map<String, Object> options, Logger logger)
	{
		Object mode = options.get("identify_mode");
		if (mode instanceof String)
		{
			try
			{
				return IdentifyMode.valueOf(((String)mode).toUpperCase());
			}
			catch (IllegalArgumentException e)
			{
				logger.warn("Invalid identify mode: {}, use default value {}", mode, IdentifyMode.DEFAULT.name().toLowerCase(Locale.ROOT));
			}
		}
		return IdentifyMode.DEFAULT;
	}

	public boolean isWhitelistEnabled()
	{
		return this.getBooleanOption("whitelist_enabled");
	}

	public boolean isBlacklistEnabled()
	{
		return this.getBooleanOption("blacklist_enabled");
	}

	public boolean isIpBanEnabled()
	{
		return this.getBooleanOption("ipban_enabled");
	}

	/**
	 * Reads a boolean config option, defaulting to false (disabled) if it's absent or not actually
	 * a Boolean. Invalid values are warned about once per load in ConfigWarnings, not here: this
	 * getter is called on every single login (via each list's isActivated()), so it must stay a
	 * pure read with no logging side effect
	 */
	private boolean getBooleanOption(String key)
	{
		Object value = this.snapshot.options.get(key);
		return value instanceof Boolean b && b;
	}

	public boolean isBlacklistOnIpBanJoin()
	{
		Snapshot snapshot = this.snapshot;
		return ConfigWarnings.isBlacklistOnIpBanJoinConfigured(snapshot.options) && this.warnings.meetsBlacklistOnIpBanJoinRequirements(snapshot.identifyMode);
	}

	public IdentifyMode getIdentifyMode()
	{
		return this.snapshot.identifyMode;
	}

	public String getWhitelistKickMessage()
	{
		Object message = this.snapshot.options.get("whitelist_kick_message");
		if (message instanceof String)
		{
			return (String)message;
		}
		return "You are not in the whitelist!";
	}

	public String getBlacklistKickMessage()
	{
		Object message = this.snapshot.options.get("blacklist_kick_message");
		if (message instanceof String)
		{
			return (String)message;
		}
		return "You are banned from the server!";
	}

	public String getIpBanKickMessage()
	{
		Object message = this.snapshot.options.get("ipban_kick_message");
		if (message instanceof String)
		{
			return (String)message;
		}
		return "Your IP address is banned from the server!";
	}
}
