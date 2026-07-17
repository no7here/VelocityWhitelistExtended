package me.fallenbreath.velocitywhitelist.config;

import com.google.common.collect.Maps;
import me.fallenbreath.velocitywhitelist.IdentifyMode;
import me.fallenbreath.velocitywhitelist.PluginMeta;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class Configuration
{
	private final Map<String, Object> options = Maps.newConcurrentMap();
	private final Logger logger;
	private final Path configFilePath;

	private IdentifyMode identifyMode = IdentifyMode.DEFAULT;

	public Configuration(Logger logger, Path configFilePath)
	{
		this.logger = logger;
		this.configFilePath = configFilePath;
	}

	@SuppressWarnings("unchecked")
	public void load(String yamlContent)
	{
		this.options.clear();
		this.options.putAll(new Yaml().loadAs(yamlContent, this.options.getClass()));
		this.migrate();

		this.identifyMode = this.makeIdentifyMode();
	}

	public void reload() throws IOException
	{
		String content = Files.readString(this.configFilePath);
		this.load(content);
	}

	private void migrate()
	{
		boolean migrated = false;
		Object versionObj = this.options.get("_version");
		if (versionObj == null)
		{
			versionObj = this.options.get("version");
		}

		if (versionObj == null)
		{
			// migrate pre-v0.3/v1 -> v2
			this.logger.warn("Migrating config file from legacy version");
			this.logger.warn("Please read the documentation for more information: {}", PluginMeta.REPOSITORY_URL);

			Map<String, Object> newOptions = Maps.newLinkedHashMap();
			newOptions.put("version", 2);
			newOptions.put("identify_mode", "uuid"); // tracking mode defaulted to uuid
			newOptions.put("whitelist_enabled", Optional.ofNullable(this.options.get("whitelist_enabled")).orElse(Optional.ofNullable(this.options.get("enabled")).orElse(true)));
			newOptions.put("whitelist_kick_message", Optional.ofNullable(this.options.get("whitelist_kick_message")).orElse(Optional.ofNullable(this.options.get("kick_message")).orElse("You are not in the whitelist!")));
			newOptions.put("blacklist_enabled", Optional.ofNullable(this.options.get("blacklist_enabled")).orElse(Optional.ofNullable(this.options.get("enabled")).orElse(true)));
			newOptions.put("blacklist_kick_message", Optional.ofNullable(this.options.get("blacklist_kick_message")).orElse("You are banned from the server!"));
			
			// IP bans options
			newOptions.put("ipban_enabled", Optional.ofNullable(this.options.get("ipban_enabled")).orElse(true));
			newOptions.put("ipban_kick_message", Optional.ofNullable(this.options.get("ipban_kick_message")).orElse("Your IP address is banned from the server!"));
			newOptions.put("blacklist_on_ipban_join", Optional.ofNullable(this.options.get("blacklist_on_ipban_join")).orElse(true));

			this.options.clear();
			this.options.putAll(newOptions);
			migrated = true;
		}
		else if (versionObj instanceof Number && ((Number)versionObj).intValue() == 1)
		{
			// migrate v1 -> v2
			this.logger.warn("Migrating config file from v1 to v2");

			Map<String, Object> newOptions = Maps.newLinkedHashMap();
			newOptions.put("version", 2);
			newOptions.put("identify_mode", "uuid"); // tracking mode defaulted to uuid
			newOptions.put("whitelist_enabled", Optional.ofNullable(this.options.get("whitelist_enabled")).orElse(true));
			newOptions.put("whitelist_kick_message", Optional.ofNullable(this.options.get("whitelist_kick_message")).orElse("You are not in the whitelist!"));
			newOptions.put("blacklist_enabled", Optional.ofNullable(this.options.get("blacklist_enabled")).orElse(true));
			newOptions.put("blacklist_kick_message", Optional.ofNullable(this.options.get("blacklist_kick_message")).orElse("You are banned from the server!"));
			
			// IP bans options
			newOptions.put("ipban_enabled", Optional.ofNullable(this.options.get("ipban_enabled")).orElse(true));
			newOptions.put("ipban_kick_message", Optional.ofNullable(this.options.get("ipban_kick_message")).orElse("Your IP address is banned from the server!"));
			newOptions.put("blacklist_on_ipban_join", Optional.ofNullable(this.options.get("blacklist_on_ipban_join")).orElse(true));

			this.options.clear();
			this.options.putAll(newOptions);
			migrated = true;
		}

		if (migrated)
		{
			try
			{
				this.save();
			}
			catch (IOException e)
			{
				this.logger.warn("Could not save the configuration file", e);
			}
		}
	}

	private void save() throws IOException
	{
		FileUtils.dumpYaml(this.configFilePath, this.options);
	}

	private IdentifyMode makeIdentifyMode()
	{
		Object mode = this.options.get("identify_mode");
		if (mode instanceof String)
		{
			try
			{
				return IdentifyMode.valueOf(((String)mode).toUpperCase());
			}
			catch (IllegalArgumentException e)
			{
				this.logger.warn("Invalid identify mode: {}, use default value {}", mode, IdentifyMode.DEFAULT.name().toLowerCase());
			}
		}
		return IdentifyMode.DEFAULT;
	}

	public boolean isWhitelistEnabled()
	{
		Object enabled = this.options.get("whitelist_enabled");
		if (enabled instanceof Boolean)
		{
			return (Boolean)enabled;
		}
		return false;
	}

	public boolean isBlacklistEnabled()
	{
		Object enabled = this.options.get("blacklist_enabled");
		if (enabled instanceof Boolean)
		{
			return (Boolean)enabled;
		}
		return false;
	}

	public boolean isIpBanEnabled()
	{
		Object enabled = this.options.get("ipban_enabled");
		if (enabled instanceof Boolean)
		{
			return (Boolean)enabled;
		}
		return false;
	}

	public boolean isBlacklistOnIpBanJoin()
	{
		Object opt = this.options.get("blacklist_on_ipban_join");
		if (opt instanceof Boolean)
		{
			return (Boolean)opt;
		}
		return false;
	}

	public IdentifyMode getIdentifyMode()
	{
		return this.identifyMode;
	}

	public String getWhitelistKickMessage()
	{
		Object maxPlayer = this.options.get("whitelist_kick_message");
		if (maxPlayer instanceof String)
		{
			return (String)maxPlayer;
		}
		return "You are not in the whitelist!";
	}

	public String getBlacklistKickMessage()
	{
		Object maxPlayer = this.options.get("blacklist_kick_message");
		if (maxPlayer instanceof String)
		{
			return (String)maxPlayer;
		}
		return "You are banned from the server!";
	}

	public String getIpBanKickMessage()
	{
		Object msg = this.options.get("ipban_kick_message");
		if (msg instanceof String)
		{
			return (String)msg;
		}
		return "Your IP address is banned from the server!";
	}
}
