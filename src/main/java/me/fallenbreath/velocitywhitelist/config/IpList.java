package me.fallenbreath.velocitywhitelist.config;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import me.fallenbreath.velocitywhitelist.utils.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IpList
{
	private final Set<String> ips = Sets.newLinkedHashSet();
	private final String name;
	private final Path filePath;
	private final Supplier<Boolean> configEnableGetter;
	private boolean loadOk = false;
	private final Object lock = new Object();

	public IpList(String name, Path filePath, Supplier<Boolean> configEnableGetter)
	{
		this.name = name;
		this.filePath = filePath;
		this.configEnableGetter = configEnableGetter;
	}

	public String getName()
	{
		return this.name;
	}

	public Path getFilePath()
	{
		return this.filePath;
	}

	public boolean isLoadOk()
	{
		synchronized (this.lock)
		{
			return this.loadOk;
		}
	}

	public boolean isConfigEnabled()
	{
		synchronized (this.lock)
		{
			return this.configEnableGetter.get();
		}
	}

	public boolean isActivated()
	{
		synchronized (this.lock)
		{
			return this.isLoadOk() && this.isConfigEnabled();
		}
	}

	public ImmutableList<String> getIps()
	{
		synchronized (this.lock)
		{
			return ImmutableList.copyOf(this.ips);
		}
	}

	private static String stripScopeId(String ip)
	{
		int pct = ip.indexOf('%');
		if (pct != -1)
		{
			return ip.substring(0, pct);
		}
		return ip;
	}

	public boolean checkIp(String ipStr)
	{
		synchronized (this.lock)
		{
			String cleanIp = stripScopeId(ipStr.trim());
			if (com.google.common.net.InetAddresses.isInetAddress(cleanIp))
			{
				InetAddress target = com.google.common.net.InetAddresses.forString(cleanIp);
				return this.ips.contains(target.getHostAddress());
			}
			try
			{
				InetAddress target = InetAddress.getByName(cleanIp);
				String normalized = target.getHostAddress();
				return this.ips.contains(normalized);
			}
			catch (UnknownHostException e)
			{
				return this.ips.contains(cleanIp);
			}
		}
	}

	public boolean addIp(String ipStr)
	{
		synchronized (this.lock)
		{
			String trimmed = stripScopeId(ipStr.trim());
			if (com.google.common.net.InetAddresses.isInetAddress(trimmed))
			{
				InetAddress addr = com.google.common.net.InetAddresses.forString(trimmed);
				return this.ips.add(addr.getHostAddress());
			}
			try
			{
				InetAddress addr = InetAddress.getByName(trimmed);
				String normalized = addr.getHostAddress();
				return this.ips.add(normalized);
			}
			catch (UnknownHostException e)
			{
				return false;
			}
		}
	}

	public boolean removeIp(String ipStr)
	{
		synchronized (this.lock)
		{
			String trimmed = stripScopeId(ipStr.trim());
			if (com.google.common.net.InetAddresses.isInetAddress(trimmed))
			{
				InetAddress target = com.google.common.net.InetAddresses.forString(trimmed);
				return this.ips.remove(target.getHostAddress());
			}
			try
			{
				InetAddress target = InetAddress.getByName(trimmed);
				String normalized = target.getHostAddress();
				return this.ips.remove(normalized);
			}
			catch (UnknownHostException e)
			{
				return this.ips.remove(trimmed);
			}
		}
	}

	public void resetTo(@NotNull IpList newList)
	{
		synchronized (this.lock)
		{
			if (!this.name.equals(newList.getName()))
			{
				throw new IllegalArgumentException("Attempted to reset to an IP list with different name");
			}
			if (!this.filePath.equals(newList.getFilePath()))
			{
				throw new IllegalArgumentException("Attempted to reset to an IP list with different filePath");
			}
			if (!newList.loadOk)
			{
				throw new IllegalArgumentException("Attempted to reset to an IP list with loadOk == false");
			}
			this.ips.clear();
			this.ips.addAll(newList.ips);
			this.loadOk = true;
		}
	}

	public IpList createNewEmptyList()
	{
		return new IpList(this.name, this.filePath, this.configEnableGetter);
	}

	@SuppressWarnings("unchecked")
	public void load(Logger logger) throws IOException
	{
		Map<String, Object> options = Maps.newHashMap();
		String yamlContent = Files.readString(this.filePath);

		options = new Yaml().loadAs(yamlContent, options.getClass());

		synchronized (this.lock)
		{
			this.ips.clear();
			if (options != null)
			{
				Object ipsVal = options.get("ips");
				if (ipsVal != null)
				{
					if (!(ipsVal instanceof List<?> list))
					{
						throw new IOException("The 'ips' field in the config is malformed (not a YAML list)");
					}
					list.forEach(entry -> {
						if (entry == null)
						{
							logger.warn("Skipping null/empty IP ban entry");
							return;
						}
						String rawIp = entry.toString().trim();
						String cleanIp = stripScopeId(rawIp);
						
						// Strict offline IP-literal validation using Guava to prevent DNS name lookups or empty string resolution to loopback
						if (!com.google.common.net.InetAddresses.isInetAddress(cleanIp))
						{
							logger.warn("Skipping invalid/unresolvable IP ban entry: {}", rawIp);
							return;
						}
						
						InetAddress addr = com.google.common.net.InetAddresses.forString(cleanIp);
						this.ips.add(addr.getHostAddress());
					});
				}
			}
			this.loadOk = true;
			logger.info("{} loaded with {} IP addresses", this.name, this.ips.size());
		}
	}

	public void save() throws IOException
	{
		Map<String, Object> options = Maps.newLinkedHashMap();

		synchronized (this.lock)
		{
			options.put("ips", Lists.newArrayList(this.ips));
		}

		FileUtils.dumpYaml(this.filePath, options);
	}
}
