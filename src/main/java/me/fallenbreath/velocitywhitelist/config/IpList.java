package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;

import me.fallenbreath.velocitywhitelist.utils.FileUtils;

public class IpList implements YamlStoredList<IpList>
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

	@Override
	public String getName()
	{
		return this.name;
	}

	@Override
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
		return this.configEnableGetter.get();
	}

	public boolean isActivated()
	{
		return this.isLoadOk() && this.isConfigEnabled();
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
		return pct != -1 ? ip.substring(0, pct) : ip;
	}

	/**
	 * Strictly parses an IP literal (IPv4 or IPv6, optionally with a %scope suffix)
	 * into its canonical textual form, e.g. "2001:DB8::1" -> "2001:db8:0:0:0:0:0:1".
	 * Returns empty for anything else (hostnames, malformed input), so no DNS lookup can ever happen
	 */
	public static Optional<String> normalizeIpLiteral(String ipStr)
	{
		String cleanIp = stripScopeId(ipStr.trim());
		if (InetAddresses.isInetAddress(cleanIp))
		{
			return Optional.of(InetAddresses.forString(cleanIp).getHostAddress());
		}
		return Optional.empty();
	}

	public boolean checkIp(String ipStr)
	{
		Optional<String> normalized = normalizeIpLiteral(ipStr);
		if (normalized.isEmpty())
		{
			return false;
		}
		synchronized (this.lock)
		{
			return this.ips.contains(normalized.get());
		}
	}

	public boolean addIp(String ipStr)
	{
		Optional<String> normalized = normalizeIpLiteral(ipStr);
		if (normalized.isEmpty())
		{
			return false;
		}
		synchronized (this.lock)
		{
			return this.ips.add(normalized.get());
		}
	}

	public boolean removeIp(String ipStr)
	{
		Optional<String> normalized = normalizeIpLiteral(ipStr);
		if (normalized.isEmpty())
		{
			return false;
		}
		synchronized (this.lock)
		{
			return this.ips.remove(normalized.get());
		}
	}

	@Override
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

	@Override
	public IpList createNewEmptyList()
	{
		return new IpList(this.name, this.filePath, this.configEnableGetter);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void load(Logger logger) throws IOException
	{
		String yamlContent = Files.readString(this.filePath);

		// Plain load() + cast rather than loadAs(..., HashMap.class): loadAs asks SafeConstructor to
		// construct the root via an explicit "!!java.util.HashMap" tag, which isn't on its safe
		// allowlist (only implicit/core YAML tags like a plain mapping are) and throws. An empty file
		// parses to null, same as Configuration's config.yml handling.
		Map<String, Object> options = (Map<String, Object>)FileUtils.newSafeYaml().load(yamlContent);

		synchronized (this.lock)
		{
			this.ips.clear();
			int skipped = 0;

			Object ipsVal = options != null ? options.get("ips") : null;
			if (ipsVal != null)
			{
				// A present but non-list value means the file is structurally corrupt. Fail the whole load
				// so a reload keeps the previous state, instead of silently replacing the list with an empty one
				if (!(ipsVal instanceof List<?> list))
				{
					throw new IOException("The 'ips' field in the config is malformed (not a YAML list)");
				}
				for (Object entry : list)
				{
					if (entry == null)
					{
						logger.warn("Skipping null/empty IP ban entry");
						skipped++;
						continue;
					}
					String rawIp = entry.toString();
					Optional<String> normalized = normalizeIpLiteral(rawIp);
					if (normalized.isPresent())
					{
						this.ips.add(normalized.get());
					}
					else
					{
						logger.warn("Skipping invalid IP ban entry: {}", rawIp);
						skipped++;
					}
				}
			}
			this.loadOk = true;
			YamlStoredList.logSkippedEntries(logger, this.name, skipped);
			logger.info("{} loaded with {} IP addresses", this.name, this.ips.size());
		}
	}

	@Override
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
