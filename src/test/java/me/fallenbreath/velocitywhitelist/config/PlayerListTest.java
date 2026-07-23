package me.fallenbreath.velocitywhitelist.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerListTest
{
	@Test
	void getPlayerUuidMappingEntries_returnsTrueSnapshot_notLiveViewIntoTheMap(@TempDir Path tempDir)
	{
		PlayerList list = new PlayerList("Whitelist", tempDir.resolve("whitelist.yml"), () -> true);
		UUID uuid = UUID.randomUUID();
		list.putPlayerUUID(uuid, "OldName");

		// "ImmutableList.copyOf(map.entrySet())" only freezes the list container - the Map.Entry
		// objects inside are still the live nodes backing the HashMap. A repeat put() for the
		// same key mutates the existing node's value in place rather than allocating a new one,
		// so an entry handed out earlier (e.g. to a tab-complete/listing command) can silently
		// change value after the fact.
		Map.Entry<UUID, String> entry = list.getPlayerUuidMappingEntries().get(0);
		assertEquals("OldName", entry.getValue());

		list.putPlayerUUID(uuid, "NewName");

		assertEquals("OldName", entry.getValue(),
				"a previously returned entry must not change value when the list is mutated afterwards");
	}
}
