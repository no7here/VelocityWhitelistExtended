package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import me.fallenbreath.velocitywhitelist.utils.FileUtils;
import me.fallenbreath.velocitywhitelist.utils.UuidUtils;

public class PlayerList implements YamlStoredList<PlayerList>
{
	private final Set<String> names = Sets.newLinkedHashSet();
	private final Map<UUID, @Nullable String> uuids = Maps.newLinkedHashMap();
	private final String name;
	private final Path filePath;
	private final Supplier<Boolean> configEnableGetter;
	private boolean loadOk = false;
	private final Object lock = new Object();

	public PlayerList(String name, Path filePath, Supplier<Boolean> configEnableGetter)
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

	public ImmutableList<String> getPlayerNames()
	{
		synchronized (this.lock)
		{
			return ImmutableList.copyOf(this.names);
		}
	}

	public boolean checkPlayerName(String name)
	{
		synchronized (this.lock)
		{
			return this.names.contains(name);
		}
	}

	public boolean addPlayerName(String name)
	{
		synchronized (this.lock)
		{
			return this.names.add(name);
		}
	}

	public boolean removePlayerName(String name)
	{
		synchronized (this.lock)
		{
			return this.names.remove(name);
		}
	}

	public ImmutableList<Map.Entry<UUID, @Nullable String>> getPlayerUuidMappingEntries()
	{
		synchronized (this.lock)
		{
			// Snapshot each entry's value rather than copying the live Map.Entry objects: a repeat
			// putPlayerUUID() for the same key mutates the existing HashMap node's value in place, so
			// a plain ImmutableList.copyOf(entrySet()) would still let a previously-returned entry's
			// value change after the fact. Maps.immutableEntry (unlike Map.entry()) tolerates a null
			// value, which a bare-uuid entry legitimately has.
			return this.uuids.entrySet().stream()
					.map(e -> Maps.<UUID, String>immutableEntry(e.getKey(), e.getValue()))
					.collect(ImmutableList.toImmutableList());
		}
	}

	public boolean checkPlayerUUID(UUID uuid)
	{
		synchronized (this.lock)
		{
			return this.uuids.containsKey(uuid);
		}
	}

	/**
	 * A snapshot of one uuid mapping. {@code exists} is needed alongside the name,
	 * since a stored uuid may legally map to a null name (bare uuid entry in the yaml file)
	 */
	public record UuidEntry(boolean exists, @Nullable String name)
	{
	}

	public UuidEntry peekPlayerUUID(UUID uuid)
	{
		synchronized (this.lock)
		{
			return new UuidEntry(this.uuids.containsKey(uuid), this.uuids.get(uuid));
		}
	}

	public void putPlayerUUID(UUID uuid, @Nullable String playerName)
	{
		synchronized (this.lock)
		{
			this.uuids.put(uuid, playerName);
		}
	}

	public @Nullable String removePlayerUUID(UUID uuid)
	{
		synchronized (this.lock)
		{
			return this.uuids.remove(uuid);
		}
	}

	@Override
	public void resetTo(@NotNull PlayerList newList)
	{
		synchronized (this.lock)
		{
			if (!this.name.equals(newList.getName()))
			{
				throw new IllegalArgumentException("Attempted to reset to a player list with different name");
			}
			if (!this.filePath.equals(newList.getFilePath()))
			{
				throw new IllegalArgumentException("Attempted to reset to a player list with different filePath");
			}
			if (!newList.loadOk)
			{
				throw new IllegalArgumentException("Attempted to reset to a player list with loadOk == false");
			}
			this.names.clear();
			this.names.addAll(newList.names);
			this.uuids.clear();
			this.uuids.putAll(newList.uuids);
			this.loadOk = true;
		}
	}

	@Override
	public PlayerList createNewEmptyList()
	{
		return new PlayerList(this.name, this.filePath, this.configEnableGetter);
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
			this.names.clear();
			this.uuids.clear();
			int skipped = 0;

			// A present but non-list value means the file is structurally corrupt. Fail the whole load
			// so a reload keeps the previous state, instead of silently replacing the list with an empty one
			Object namesVal = options != null ? options.get("names") : null;
			if (namesVal != null)
			{
				if (!(namesVal instanceof List<?> namesList))
				{
					throw new IOException("The 'names' field in the file is malformed (not a YAML list)");
				}
				for (Object entry : namesList)
				{
					if (entry == null)
					{
						logger.warn("Skipping null/empty player name entry");
						skipped++;
						continue;
					}
					this.names.add(entry.toString());
				}
			}

			Object uuidsVal = options != null ? options.get("uuids") : null;
			if (uuidsVal != null)
			{
				if (!(uuidsVal instanceof List<?> uuidsList))
				{
					throw new IOException("The 'uuids' field in the file is malformed (not a YAML list)");
				}
				for (Object item : uuidsList)
				{
					if (item instanceof String s)
					{
						Optional<UUID> uuid = UuidUtils.tryParseUuid(s);
						if (uuid.isPresent())
						{
							this.uuids.put(uuid.get(), null);
						}
						else
						{
							logger.warn("Skipping invalid UUID \"{}\"", s);
							skipped++;
						}
					}
					else if (item instanceof Map<?, ?> map)
					{
						if (map.size() != 1)
						{
							logger.warn("Skipping invalid map item with size {}", map.size());
							skipped++;
							continue;
						}
						Map.Entry<?, ?> entry = map.entrySet().iterator().next();
						if (entry.getKey() instanceof String s && (entry.getValue() instanceof String || entry.getValue() == null))
						{
							String name = (String)entry.getValue();
							Optional<UUID> uuid = UuidUtils.tryParseUuid(s);
							if (uuid.isPresent())
							{
								this.uuids.put(uuid.get(), name);
							}
							else
							{
								logger.warn("Skipping invalid UUID \"{}\" ({})", s, name);
								skipped++;
							}
						}
						else
						{
							logger.warn("Skipping invalid UUID list item {}", item);
							skipped++;
						}
					}
					else
					{
						logger.warn("Skipping invalid UUID list item {}", item);
						skipped++;
					}
				}
			}

			this.loadOk = true;
			YamlStoredList.logSkippedEntries(logger, this.name, skipped);
			logger.info("{} loaded with {} names and {} uuids", this.name, this.names.size(), this.uuids.size());
		}
	}

	@Override
	public void save() throws IOException
	{
		Map<String, Object> options = Maps.newLinkedHashMap();

		synchronized (this.lock)
		{
			options.put("names", Lists.newArrayList(this.names));
			List<Object> uuidList = this.uuids.entrySet().stream()
					.map(e -> e.getValue() != null ? Map.of(e.getKey().toString(), e.getValue()) : e.getKey().toString())
					.toList();
			options.put("uuids", uuidList);
		}

		FileUtils.dumpYaml(this.filePath, options);
	}
}
