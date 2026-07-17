package me.fallenbreath.velocitywhitelist.config;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
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
import java.util.Arrays;
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

	public boolean checkIp(String ipStr)
	{
		synchronized (this.lock)
		{
			try
			{
				InetAddress target = InetAddress.getByName(ipStr.trim());
				for (String ip : this.ips)
				{
					try
					{
						InetAddress addr = InetAddress.getByName(ip.trim());
						if (Arrays.equals(target.getAddress(), addr.getAddress()))
						{
							return true;
						}
					}
					catch (UnknownHostException ignored) {}
				}
			}
			catch (UnknownHostException e)
			{
				return this.ips.contains(ipStr.trim());
			}
			return false;
		}
	}

	public boolean addIp(String ipStr)
	{
		synchronized (this.lock)
		{
			String trimmed = ipStr.trim();
			try
			{
				InetAddress addr = InetAddress.getByName(trimmed);
				String normalized = addr.getHostAddress();
				if (checkIp(normalized))
				{
					return false;
				}
				return this.ips.add(normalized);
			}
			catch (UnknownHostException e)
			{
				if (this.ips.contains(trimmed))
				{
					return false;
				}
				return this.ips.add(trimmed);
			}
		}
	}

	public boolean removeIp(String ipStr)
	{
		synchronized (this.lock)
		{
			String trimmed = ipStr.trim();
			try
			{
				InetAddress target = InetAddress.getByName(trimmed);
				String toRemove = null;
				for (String ip : this.ips)
				{
					try
					{
						InetAddress addr = InetAddress.getByName(ip.trim());
						if (Arrays.equals(target.getAddress(), addr.getAddress()))
						{
							toRemove = ip;
							break;
						}
					}
					catch (UnknownHostException ignored) {}
				}
				if (toRemove != null)
				{
					this.ips.remove(toRemove);
					return true;
				}
			}
			catch (UnknownHostException e)
			{
				return this.ips.remove(trimmed);
			}
			return false;
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
			if (options != null && options.get("ips") instanceof List<?> list)
			{
				list.forEach(entry -> this.ips.add(entry.toString().trim()));
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
