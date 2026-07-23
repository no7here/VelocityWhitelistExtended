package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import com.google.common.base.Supplier;
import com.google.common.collect.Maps;

import me.fallenbreath.velocitywhitelist.IdentifyMode;
import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;

public class Configuration
{
	private static final int CONFIG_VERSION = 2;

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
	private final Supplier<Boolean> proxyOnlineModeGetter;

	public Configuration(Logger logger, Path configFilePath, Supplier<Boolean> proxyOnlineModeGetter)
	{
		this.logger = logger;
		this.configFilePath = configFilePath;
		this.proxyOnlineModeGetter = proxyOnlineModeGetter;
	}

	@SuppressWarnings("unchecked")
	public void load(String yamlContent)
	{
		// Parse and migrate into a staging map before publishing, so a malformed config during a reload
		// keeps the previous state enforced, and concurrent logins never see a half-built option set
		Map<String, Object> loadedOptions = (Map<String, Object>)new Yaml().load(yamlContent);

		Map<String, Object> stagedOptions = Maps.newLinkedHashMap();
		if (loadedOptions != null)  // an empty config file parses to null
		{
			stagedOptions.putAll(loadedOptions);
		}
		stagedOptions = this.migrate(stagedOptions);
		stagedOptions = Collections.unmodifiableMap(stagedOptions);

		this.snapshot = new Snapshot(stagedOptions, makeIdentifyMode(stagedOptions, this.logger));
		this.warnAboutRiskyOptions();
	}

	public void reload() throws IOException
	{
		String content = Files.readString(this.configFilePath);
		this.load(content);
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
	 * Migrates the given staging options to the current config version, returning the map to publish
	 */
	private Map<String, Object> migrate(Map<String, Object> options)
	{
		Object versionObj = options.get("_version");  // key used by config v1
		if (versionObj == null)
		{
			versionObj = options.get("version");
		}
		int version = versionObj instanceof Number number ? number.intValue() : 0;
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

	/**
	 * blacklist_on_ipban_join lets an unauthenticated network peer (anyone joining from a banned IP)
	 * trigger a blacklist write, so it's only safe when player identities are actually verified: uuid
	 * identify_mode, on a proxy that's genuinely running in online mode. Both are hard-required - checked
	 * here rather than trusted from config - so a bad config can't silently reopen the risk.
	 */
	private boolean meetsBlacklistOnIpBanJoinRequirements(IdentifyMode identifyMode)
	{
		return identifyMode == IdentifyMode.UUID && this.isProxyOnlineMode();
	}

	private static boolean isBlacklistOnIpBanJoinConfigured(Map<String, Object> options)
	{
		Object opt = options.get("blacklist_on_ipban_join");
		return opt instanceof Boolean && (Boolean)opt;
	}

	private void warnAboutRiskyOptions()
	{
		Snapshot snapshot = this.snapshot;
		if (!isBlacklistOnIpBanJoinConfigured(snapshot.options) || this.meetsBlacklistOnIpBanJoinRequirements(snapshot.identifyMode))
		{
			return;
		}

		this.logger.warn("blacklist_on_ipban_join is enabled in the config, but its requirements are not met, so it has been forced off:");
		if (snapshot.identifyMode != IdentifyMode.UUID)
		{
			this.logger.warn("- identify_mode must be uuid (currently: {})", snapshot.identifyMode.name().toLowerCase());
		}
		if (!this.isProxyOnlineMode())
		{
			this.logger.warn("- the proxy must be running in online mode");
		}
		this.logger.warn("Unverified identities would let anyone joining from a banned IP get an arbitrary name/account blacklisted. See the config comments / README for more information: {}", PluginMeta.REPOSITORY_URL);
	}

	private boolean isProxyOnlineMode()
	{
		return this.proxyOnlineModeGetter.get();
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
				logger.warn("Invalid identify mode: {}, use default value {}", mode, IdentifyMode.DEFAULT.name().toLowerCase());
			}
		}
		return IdentifyMode.DEFAULT;
	}

	public boolean isWhitelistEnabled()
	{
		Object enabled = this.snapshot.options.get("whitelist_enabled");
		if (enabled instanceof Boolean)
		{
			return (Boolean)enabled;
		}
		return false;
	}

	public boolean isBlacklistEnabled()
	{
		Object enabled = this.snapshot.options.get("blacklist_enabled");
		if (enabled instanceof Boolean)
		{
			return (Boolean)enabled;
		}
		return false;
	}

	public boolean isIpBanEnabled()
	{
		Object enabled = this.snapshot.options.get("ipban_enabled");
		if (enabled instanceof Boolean)
		{
			return (Boolean)enabled;
		}
		return false;
	}

	public boolean isBlacklistOnIpBanJoin()
	{
		Snapshot snapshot = this.snapshot;
		return isBlacklistOnIpBanJoinConfigured(snapshot.options) && this.meetsBlacklistOnIpBanJoinRequirements(snapshot.identifyMode);
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
