package me.fallenbreath.velocitywhitelist.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Unit tests for the PlayerList class
class PlayerListTest {

    // Test to ensure getPlayerUuidMappingEntries returns a true snapshot and not a live view into the map
    @Test
    void getPlayerUuidMappingEntries_returnsTrueSnapshot_notLiveViewIntoTheMap(
        @TempDir Path tempDir
    ) {
        PlayerList list = new PlayerList(
            "Whitelist",
            tempDir.resolve("whitelist.yml"),
            () -> true
        );
        UUID uuid = UUID.randomUUID();
        list.putPlayerUUID(uuid, "OldName");

        // "ImmutableList.copyOf(map.entrySet())" only freezes list container - Map.Entry objects inside are still live nodes. A repeat put() for same key mutates existing node's value, so an entry handed out earlier can silently change value after the fact.
        Map.Entry<UUID, String> entry = list
            .getPlayerUuidMappingEntries()
            .get(0);
        assertEquals("OldName", entry.getValue());

        list.putPlayerUUID(uuid, "NewName");

        assertEquals(
            "OldName",
            entry.getValue(),
            "a previously returned entry must not change value when the list is mutated afterwards"
        );
    }
}
