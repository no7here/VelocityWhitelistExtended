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
import java.util.UUID;
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
        list.addPlayerName("Steve", true);

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
        list.addPlayerName("Steve", true);

        assertIterableEquals(List.of("Steve"), list.removePlayerName("steve", true));
        assertFalse(list.checkPlayerNameIgnoreCase("Steve"));
        assertTrue(list.getPlayerNames().isEmpty());
    }

    @Test
    void removePlayerName_returnsEmpty_whenNothingMatches(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);
        list.addPlayerName("Steve", true);

        assertTrue(list.removePlayerName("Alex", true).isEmpty());
        assertTrue(list.checkPlayerNameIgnoreCase("steve"));
    }

    @Test
    void addPlayerName_treatsACaseVariantAsAlreadyPresent(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);

        assertTrue(list.addPlayerName("Steve", true));
        assertFalse(
            list.addPlayerName("steve", true),
            "a name differing only by capitalisation is the same entry now, so no new collision may be created"
        );
        assertIterableEquals(List.of("Steve"), list.getPlayerNames());
    }

    // Checks an exactly matched list still accepts a case variant, since on an offline proxy "Steve" and "steve" are two accounts and refusing the second leaves it unlisted
    @Test
    void addPlayerName_withoutFolding_storesACaseVariant(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);

        assertTrue(list.addPlayerName("Steve", false));
        assertTrue(list.addPlayerName("steve", false));
        assertFalse(
            list.addPlayerName("Steve", false),
            "an exact duplicate is still one entry"
        );
        assertIterableEquals(List.of("Steve", "steve"), list.getPlayerNames());
    }

    // Checks an exactly matched list removes only what was named, since taking the other spelling with it would revoke a second account's access
    @Test
    void removePlayerName_withoutFolding_leavesTheOtherSpelling(
        @TempDir Path tempDir
    ) throws Exception {
        PlayerList list = loadedFrom(tempDir, LOGGER, "Steve", "steve");

        assertIterableEquals(
            List.of("Steve"),
            list.removePlayerName("Steve", false)
        );
        assertTrue(list.checkPlayerName("steve"));
        assertFalse(list.checkPlayerName("Steve"));
        assertTrue(
            list.checkPlayerNameIgnoreCase("STEVE"),
            "the index must still describe the spelling left behind"
        );
    }

    @Test
    void removePlayerName_withoutFolding_returnsEmptyForAnotherCasing(
        @TempDir Path tempDir
    ) {
        PlayerList list = newList(tempDir);
        list.addPlayerName("Steve", false);

        assertTrue(list.removePlayerName("steve", false).isEmpty());
        assertTrue(list.checkPlayerName("Steve"));
    }

    // Checks a removal whose save fails puts back every spelling it took, not just the one the admin typed
    @Test
    void restorePlayerNames_putsBackEveryRemovedSpelling(
        @TempDir Path tempDir
    ) throws Exception {
        PlayerList list = loadedFrom(tempDir, LOGGER, "Steve", "steve");

        var removed = list.removePlayerName("STEVE", true);
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

        var removed = list.removePlayerName("steve", true);

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
        live.addPlayerName("OldPlayer", true);

        PlayerList reloaded = loadedFrom(tempDir, LOGGER, "NewPlayer");
        live.resetTo(reloaded);

        assertTrue(live.checkPlayerNameIgnoreCase("newplayer"));
        assertFalse(
            live.checkPlayerNameIgnoreCase("oldplayer"),
            "the index must not still describe the pre-reload state"
        );
        assertIterableEquals(List.of("NewPlayer"), live.removePlayerName("NEWPLAYER", true));
    }

    // Checks the cross-identifier removal clears the same three shapes checkAnyIdentifier matches on
    @Test
    void removeAnyIdentifier_clearsNameEntriesUuidKeysAndLabels(
        @TempDir Path tempDir
    ) throws Exception {
        UUID keyed = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID labelled = UUID.fromString("11111111-2222-3333-4444-555555555555");

        PlayerList list = loadedFrom(tempDir, LOGGER, "Griefer");
        list.putPlayerUUID(keyed, null);
        list.putPlayerUUID(labelled, "griefer");

        var removed = list.removeAnyIdentifier(keyed, "GRIEFER");

        assertIterableEquals(List.of("Griefer"), removed.names());
        assertEquals(2, removed.uuids().size());
        assertFalse(list.checkAnyIdentifier(keyed, "Griefer"));
        assertFalse(list.checkAnyIdentifier(labelled, null));
    }

    // Checks a failed save can put back everything the removal took, across both stores
    @Test
    void restoreIdentifiers_putsBackEveryStore(@TempDir Path tempDir)
        throws Exception {
        UUID labelled = UUID.fromString("11111111-2222-3333-4444-555555555555");

        PlayerList list = loadedFrom(tempDir, LOGGER, "Griefer");
        list.putPlayerUUID(labelled, "Griefer");

        list.restoreIdentifiers(list.removeAnyIdentifier(null, "griefer"));

        assertTrue(list.checkPlayerName("Griefer"));
        assertEquals("Griefer", list.peekPlayerUUID(labelled).name());
        assertTrue(list.checkAnyIdentifier(null, "GRIEFER"));
    }

    // Checks a removal naming only a uuid still sweeps that entry's label, the caller having no name to pass when the banned player is offline
    @Test
    void removeAnyIdentifier_sweepsTheMatchedEntrysOwnLabel(
        @TempDir Path tempDir
    ) throws Exception {
        UUID labelled = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID sibling = UUID.fromString("99999999-8888-7777-6666-555555555555");

        PlayerList list = loadedFrom(tempDir, LOGGER, "Griefer");
        list.putPlayerUUID(labelled, "Griefer");
        list.putPlayerUUID(sibling, "griefer");

        var removed = list.removeAnyIdentifier(labelled, null);

        assertIterableEquals(List.of("Griefer"), removed.names());
        assertEquals(2, removed.uuids().size());
        assertFalse(
            list.checkAnyIdentifier(null, "Griefer"),
            "a name entry left behind would keep banning a player the command reported as removed"
        );
        assertFalse(list.checkAnyIdentifier(sibling, null));
    }

    // Checks the sweep is skipped for a bare uuid entry, which carries no label to widen on
    @Test
    void removeAnyIdentifier_byUuid_leavesUnrelatedNamesAlone(
        @TempDir Path tempDir
    ) throws Exception {
        UUID bare = UUID.fromString("11111111-2222-3333-4444-555555555555");

        PlayerList list = loadedFrom(tempDir, LOGGER, "Griefer");
        list.putPlayerUUID(bare, null);

        var removed = list.removeAnyIdentifier(bare, null);

        assertTrue(removed.names().isEmpty());
        assertEquals(1, removed.uuids().size());
        assertTrue(list.checkPlayerName("Griefer"));
    }

    // Checks a bare uuid entry is untouched by a name that matches nothing, the removal widening only as far as the matcher does
    @Test
    void removeAnyIdentifier_leavesUnrelatedEntriesAlone(@TempDir Path tempDir)
        throws Exception {
        UUID other = UUID.fromString("11111111-2222-3333-4444-555555555555");

        PlayerList list = loadedFrom(tempDir, LOGGER, "Griefer");
        list.putPlayerUUID(other, "SomeoneElse");

        assertTrue(list.removeAnyIdentifier(null, "Innocent").isEmpty());
        assertTrue(list.checkAnyIdentifier(other, "SomeoneElse"));
        assertTrue(list.checkPlayerName("Griefer"));
    }

    // Checks normalisation pins Locale.ROOT, since a Turkish default locale lowercases "I" to the dotless "ı" and would stop this ban matching
    @Test
    void normalisation_isLocaleIndependent(@TempDir Path tempDir) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            PlayerList list = newList(tempDir);
            list.addPlayerName("IanTheGriefer", true);

            assertTrue(list.checkPlayerNameIgnoreCase("ianthegriefer"));
            assertIterableEquals(
                List.of("IanTheGriefer"),
                list.removePlayerName("IANTHEGRIEFER", true)
            );
        } finally {
            Locale.setDefault(previous);
        }
    }
}
