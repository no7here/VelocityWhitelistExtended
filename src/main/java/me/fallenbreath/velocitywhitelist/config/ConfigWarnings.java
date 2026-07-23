package me.fallenbreath.velocitywhitelist.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;

import com.google.common.base.Supplier;

import me.fallenbreath.velocitywhitelist.IdentifyMode;
import me.fallenbreath.velocitywhitelist.PluginMeta;

/**
 * Validates a freshly-loaded config's options and logs warnings for anything suspicious, once per
 * load/reload (never from a hot-path getter - see the callers in Configuration for why)
 */
final class ConfigWarnings
{
	private final Logger logger;
	private final Supplier<Boolean> proxyOnlineModeGetter;

	ConfigWarnings(Logger logger, Supplier<Boolean> proxyOnlineModeGetter)
	{
		this.logger = logger;
		this.proxyOnlineModeGetter = proxyOnlineModeGetter;
	}

	/**
	 * blacklist_on_ipban_join lets an unauthenticated network peer (anyone joining from a banned IP)
	 * trigger a blacklist write, so it's only safe when player identities are actually verified: uuid
	 * identify_mode, on a proxy that's genuinely running in online mode. Both are hard-required - checked
	 * here rather than trusted from config - so a bad config can't silently reopen the risk.
	 */
	boolean meetsBlacklistOnIpBanJoinRequirements(IdentifyMode identifyMode)
	{
		return identifyMode == IdentifyMode.UUID && this.isProxyOnlineMode();
	}

	static boolean isBlacklistOnIpBanJoinConfigured(Map<String, Object> options)
	{
		Object opt = options.get("blacklist_on_ipban_join");
		return opt instanceof Boolean && (Boolean)opt;
	}

	void warnAboutRiskyOptions(Map<String, Object> options, IdentifyMode identifyMode)
	{
		if (!isBlacklistOnIpBanJoinConfigured(options) || this.meetsBlacklistOnIpBanJoinRequirements(identifyMode))
		{
			return;
		}

		this.logger.warn("blacklist_on_ipban_join is enabled in the config, but its requirements are not met, so it has been forced off:");
		if (identifyMode != IdentifyMode.UUID)
		{
			this.logger.warn("- identify_mode must be uuid (currently: {})", identifyMode.name().toLowerCase(Locale.ROOT));
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

	/**
	 * Warns once per load/reload about any boolean option that's present but not actually a
	 * Boolean (e.g. a hand-quoted "true" string parses as a String under SnakeYAML). This must be
	 * called once from load()/reload() rather than from the getters themselves:
	 * isWhitelistEnabled/isBlacklistEnabled/isIpBanEnabled all delegate to a getter that's read on
	 * every single login via each list's isActivated(), so a warning in the getter would repeat on
	 * every connection instead of once per config load
	 */
	void warnAboutInvalidBooleanOptions(Map<String, Object> options)
	{
		for (String key : List.of("whitelist_enabled", "blacklist_enabled", "ipban_enabled", "blacklist_on_ipban_join"))
		{
			Object value = options.get(key);
			if (value != null && !(value instanceof Boolean))
			{
				this.logger.warn("Invalid value for {}: {} (expected true/false), treating as disabled", key, value);
			}
		}
	}
}
