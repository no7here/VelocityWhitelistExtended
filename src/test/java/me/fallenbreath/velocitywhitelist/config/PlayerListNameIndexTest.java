package me.fallenbreath.velocitywhitelist.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Tests the case-insensitive name index, which backs add, remove and lookup rather than just matching
class PlayerListNameIndexTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        PlayerListNameIndexTest.class
    );

    private static PlayerList newList(Path tempDir) {
        return new PlayerList(
            "Blacklist",
            tempDir.resolve("blacklist.yml"),
            () -> true
        );
    }

    private static PlayerList loadedFrom(
        Path tempDir,
        Logger logger,
        String... names
    ) throws Exception {
        StringBuilder yaml = new StringBuilder("names:\n");
        for (String name : names) {
            yaml.append("  - ").append(name).append("\n");
        }
        yaml.append("uuids: []\n");
        Files.writeString(tempDir.resolve("blacklist.yml"), yaml.toString());

        PlayerList list = newList(tempDir);
        list.load(logger);
        return list;
    }

    @Test
    void checkPlayerNameIgnoreCase_matchesAnyCasing(@TempDir Path tempDir) {
        PlayerList list = newList(tempDir);
        list.addPlayerName("Steve");

        assertTrue(list.checkPlayerNameIgnoreCase("steve"));
        assertTrue(list.checkPlayerNameIgnoreCase("STEVE"));
        assertTrue(list.checkPlayerNameIgnoreCase("Steve"));
        assertFalse(list.checkPlayerNameIgnoreCase("Steven"));

        // The exact matcher is unchanged and still available for the paths that need it
        assertTrue(list.checkPlayerName("Steve"));
        assertFalse(list.checkPlayerName("steve"));
    }

    // Guards against an admin typing the wrong case, the removal missing, and them being told the player is not listed while the entry keeps matching
    @Test
    void removePlayerName_ignoresCase_andReportsTheStoredSpelling(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);
        list.addPlayerName("Steve");

        assertIterableEquals(List.of("Steve"), list.removePlayerName("steve"));
        assertFalse(list.checkPlayerNameIgnoreCase("Steve"));
        assertTrue(list.getPlayerNames().isEmpty());
    }

    @Test
    void removePlayerName_returnsEmpty_whenNothingMatches(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);
        list.addPlayerName("Steve");

        assertTrue(list.removePlayerName("Alex").isEmpty());
        assertTrue(list.checkPlayerNameIgnoreCase("steve"));
    }

    @Test
    void addPlayerName_treatsACaseVariantAsAlreadyPresent(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);

        assertTrue(list.addPlayerName("Steve"));
        assertFalse(
            list.addPlayerName("steve"),
            "a name differing only by capitalisation is the same entry now, so no new collision may be created"
        );
        assertIterableEquals(List.of("Steve"), list.getPlayerNames());
    }

    // Checks a removal whose save fails puts back every spelling it took, not just the one the admin typed
    @Test
    void restorePlayerNames_putsBackEveryRemovedSpelling(
        @TempDir Path tempDir
    ) throws Exception {
        PlayerList list = loadedFrom(tempDir, LOGGER, "Steve", "steve");

        var removed = list.removePlayerName("STEVE");
        assertEquals(2, removed.size());

        list.restorePlayerNames(removed);

        assertTrue(list.checkPlayerName("Steve"));
        assertTrue(list.checkPlayerName("steve"));
        assertTrue(list.checkPlayerNameIgnoreCase("sTeVe"));
    }

    // Checks a file holding both spellings keeps them, since that is what an operator does to counter case evasion
    @Test
    void preExistingCollision_keepsBothEntriesAndWarns(@TempDir Path tempDir)
        throws Exception {
        Logger logger = mock(Logger.class);
        PlayerList list = loadedFrom(tempDir, logger, "Steve", "steve");

        assertIterableEquals(List.of("Steve", "steve"), list.getPlayerNames());
        assertTrue(list.checkPlayerNameIgnoreCase("STEVE"));
        verify(logger, atLeastOnce()).warn(anyString(), any(), any());
    }

    @Test
    void preExistingCollision_isRemovedAsOneEntry(@TempDir Path tempDir)
        throws Exception {
        PlayerList list = loadedFrom(tempDir, LOGGER, "Steve", "steve");

        var removed = list.removePlayerName("steve");

        assertEquals(2, removed.size(), "both spellings are one entry now");
        assertTrue(removed.contains("Steve"));
        assertTrue(removed.contains("steve"));
        assertTrue(list.getPlayerNames().isEmpty());
    }

    @Test
    void noCollision_doesNotWarn(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        loadedFrom(tempDir, logger, "Steve", "Alex");

        verify(logger, never()).warn(anyString(), any(), any());
    }

    // Checks the index is rebuilt on resetTo, the path a reload swaps state through
    @Test
    void nameIndex_isRebuiltOnResetTo(@TempDir Path tempDir) throws Exception {
        PlayerList live = newList(tempDir);
        live.addPlayerName("OldPlayer");

        PlayerList reloaded = loadedFrom(tempDir, LOGGER, "NewPlayer");
        live.resetTo(reloaded);

        assertTrue(live.checkPlayerNameIgnoreCase("newplayer"));
        assertFalse(
            live.checkPlayerNameIgnoreCase("oldplayer"),
            "the index must not still describe the pre-reload state"
        );
        assertIterableEquals(List.of("NewPlayer"), live.removePlayerName("NEWPLAYER"));
    }

    // Checks normalisation pins Locale.ROOT, since a Turkish default locale lowercases "I" to the dotless "ı" and would stop this ban matching
    @Test
    void normalisation_isLocaleIndependent(@TempDir Path tempDir) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            PlayerList list = newList(tempDir);
            list.addPlayerName("IanTheGriefer");

            assertTrue(list.checkPlayerNameIgnoreCase("ianthegriefer"));
            assertIterableEquals(
                List.of("IanTheGriefer"),
                list.removePlayerName("IANTHEGRIEFER")
            );
        } finally {
            Locale.setDefault(previous);
        }
    }
}
