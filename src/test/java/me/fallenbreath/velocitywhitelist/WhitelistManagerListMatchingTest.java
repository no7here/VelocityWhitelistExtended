package me.fallenbreath.velocitywhitelist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import me.fallenbreath.velocitywhitelist.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Tests the asymmetry between the two lists, the deny list matching on any identifier it holds and the allow list only on the one identify_mode designates
class WhitelistManagerListMatchingTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        WhitelistManagerListMatchingTest.class
    );

    private static final UUID LISTED_UUID = UUID.fromString(
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );

    private static WhitelistManager managerWith(
        Path tempDir,
        String identifyMode,
        String listFileName,
        String... listLines
    ) throws Exception {
        Files.writeString(
            tempDir.resolve(listFileName),
            String.join("\n", listLines) + "\n"
        );

        Configuration config = new Configuration(
            LOGGER,
            tempDir.resolve("config.yml"),
            () -> true
        );
        config.load(
            String.join(
                "\n",
                "version: 2",
                "identify_mode: " + identifyMode,
                "whitelist_enabled: true",
                "blacklist_enabled: true",
                "ipban_enabled: true"
            )
        );

        WhitelistManager manager = new WhitelistManager(
            mock(VelocityWhitelistPlugin.class),
            LOGGER,
            config,
            tempDir,
            mock(ProxyServer.class)
        );
        assertTrue(manager.loadLists());
        return manager;
    }

    private static GameProfile profile(UUID uuid, String name) {
        return new GameProfile(uuid, name, List.of());
    }

    // Checks a ban keyed on a uuid the account no longer connects with is still caught by the name recorded beside it
    @Test
    void blacklist_matchesTheNameLabelOfAUuidEntry_inUuidMode(
        @TempDir Path tempDir
    ) throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "blacklist.yml",
            "names: []",
            "uuids:",
            "  - " + LISTED_UUID + ": Griefer"
        );

        assertTrue(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "Griefer"))
        );
    }

    // Checks name labels are matched ignoring capitalisation so a banned player cannot return by changing one character
    @Test
    void blacklist_matchesTheNameLabel_ignoringCase(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "blacklist.yml",
            "names: []",
            "uuids:",
            "  - " + LISTED_UUID + ": Griefer"
        );

        assertTrue(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "griefer"))
        );
        assertTrue(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "GRIEFER"))
        );
    }

    // Checks a plain name entry stops a login while the plugin is in uuid mode
    @Test
    void blacklist_matchesAPlainNameEntry_inUuidMode(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "blacklist.yml",
            "names:",
            "  - Griefer",
            "uuids: []"
        );

        assertTrue(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "Griefer"))
        );
        assertTrue(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "grIEfer"))
        );
    }

    // Checks a bare uuid entry, which has no label, is still reachable by its uuid in name mode
    @Test
    void blacklist_matchesABareUuidEntry_inNameMode(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "name",
            "blacklist.yml",
            "names: []",
            "uuids:",
            "  - " + LISTED_UUID
        );

        assertTrue(manager.isPlayerInBlacklist(profile(LISTED_UUID, "Griefer")));
        assertFalse(
            manager.isPlayerInBlacklist(
                profile(UUID.randomUUID(), "SomeoneElse")
            )
        );
    }

    // Checks an unlisted player is not caught by any of the widened matching
    @Test
    void blacklist_doesNotMatchAnUnlistedPlayer(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "blacklist.yml",
            "names:",
            "  - Griefer",
            "uuids:",
            "  - " + LISTED_UUID + ": Griefer"
        );

        assertFalse(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "Innocent"))
        );
    }

    // Checks the allow list stays strict, since matching a name label would let anyone typing a whitelisted name straight in
    @Test
    void whitelist_doesNotMatchTheNameLabelOfAUuidEntry_inUuidMode(
        @TempDir Path tempDir
    ) throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "whitelist.yml",
            "names: []",
            "uuids:",
            "  - " + LISTED_UUID + ": Notch"
        );

        assertFalse(
            manager.isPlayerInWhitelist(profile(UUID.randomUUID(), "Notch")),
            "only the uuid may satisfy a uuid-mode whitelist"
        );
        assertTrue(manager.isPlayerInWhitelist(profile(LISTED_UUID, "Notch")));
    }
}
