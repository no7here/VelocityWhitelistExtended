package me.fallenbreath.velocitywhitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import me.fallenbreath.velocitywhitelist.config.Configuration;
import me.fallenbreath.velocitywhitelist.config.PlayerList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Tests that a deny-list removal reaches every identifier the deny-list matcher consults, an entry the command cannot clear being one that keeps banning a player reported as not listed
class WhitelistManagerDenyListRemovalTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        WhitelistManagerDenyListRemovalTest.class
    );

    private static final UUID LISTED_UUID = UUID.fromString(
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    );

    private static WhitelistManager managerWith(
        Path tempDir,
        String identifyMode,
        String... blacklistLines
    ) throws Exception {
        Files.writeString(
            tempDir.resolve("blacklist.yml"),
            String.join("\n", blacklistLines) + "\n"
        );
        Files.writeString(
            tempDir.resolve("whitelist.yml"),
            "names: []\nuuids: []\n"
        );
        Files.writeString(tempDir.resolve("ipbans.yml"), "ips: []\n");

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
                "whitelist_enabled: false",
                "blacklist_enabled: true",
                "ipban_enabled: false"
            )
        );

        // Pins the proxy offline so resolveDenyListTarget infers a uuid locally rather than calling out to Mojang from a unit test
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        when(proxyConfig.isOnlineMode()).thenReturn(false);
        ProxyServer server = mock(ProxyServer.class);
        when(server.getConfiguration()).thenReturn(proxyConfig);

        WhitelistManager manager = new WhitelistManager(
            mock(VelocityWhitelistPlugin.class),
            LOGGER,
            config,
            tempDir,
            server
        );
        assertTrue(manager.loadLists());
        return manager;
    }

    private static GameProfile profile(UUID uuid, String name) {
        return new GameProfile(uuid, name, List.of());
    }

    // Checks the plain name entry that only started enforcing once the deny list began matching every identifier can also be lifted again
    @Test
    void removesAPlainNameEntry_inUuidMode(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "names:",
            "  - Griefer",
            "uuids: []"
        );
        PlayerList blacklist = manager.getBlacklist();

        assertEquals(
            WhitelistManager.ModifyResult.SUCCESS,
            manager.removePlayer(mock(CommandSource.class), blacklist, "Griefer")
        );
        assertFalse(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "Griefer"))
        );
        assertTrue(blacklist.getPlayerNames().isEmpty());
    }

    // Checks the removal ignores capitalisation exactly as the matcher does, so an admin typing the wrong case is not told the player is unlisted while the ban keeps firing
    @Test
    void removesAPlainNameEntry_ignoringCase(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "names:",
            "  - Griefer",
            "uuids: []"
        );

        assertEquals(
            WhitelistManager.ModifyResult.SUCCESS,
            manager.removePlayer(
                mock(CommandSource.class),
                manager.getBlacklist(),
                "grIEfer"
            )
        );
        assertFalse(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "Griefer"))
        );
    }

    // Checks a uuid entry enforcing through its name label is reachable in name mode, where the mode-scoped removal would only have looked at the names store
    @Test
    void removesAUuidEntryByItsNameLabel_inNameMode(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "name",
            "names: []",
            "uuids:",
            "  - " + LISTED_UUID + ": Griefer"
        );
        PlayerList blacklist = manager.getBlacklist();

        assertEquals(
            WhitelistManager.ModifyResult.SUCCESS,
            manager.removePlayer(mock(CommandSource.class), blacklist, "Griefer")
        );
        assertFalse(manager.isPlayerInBlacklist(profile(LISTED_UUID, "Griefer")));
        assertTrue(blacklist.getPlayerUuidMappingEntries().isEmpty());
    }

    // Checks a raw uuid is accepted in name mode, which the mode-scoped resolver refused outright even though such an entry now bans
    @Test
    void removesABareUuidEntryByUuid_inNameMode(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "name",
            "names: []",
            "uuids:",
            "  - " + LISTED_UUID
        );

        assertEquals(
            WhitelistManager.ModifyResult.SUCCESS,
            manager.removePlayer(
                mock(CommandSource.class),
                manager.getBlacklist(),
                LISTED_UUID.toString()
            )
        );
        assertFalse(manager.isPlayerInBlacklist(profile(LISTED_UUID, "Griefer")));
    }

    // Checks one removal clears every identifier at once, since leaving either behind leaves the player banned
    @Test
    void removesTheNameEntryAndTheUuidEntryTogether(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "names:",
            "  - Griefer",
            "uuids:",
            "  - " + LISTED_UUID + ": griefer"
        );
        PlayerList blacklist = manager.getBlacklist();

        assertEquals(
            WhitelistManager.ModifyResult.SUCCESS,
            manager.removePlayer(mock(CommandSource.class), blacklist, "GRIEFER")
        );
        assertTrue(blacklist.getPlayerNames().isEmpty());
        assertTrue(blacklist.getPlayerUuidMappingEntries().isEmpty());
    }

    // Checks an unlisted player still reports no change rather than being swept up by the widened removal
    @Test
    void reportsNoChangeForAnUnlistedPlayer(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            "uuid",
            "names:",
            "  - Griefer",
            "uuids: []"
        );

        assertEquals(
            WhitelistManager.ModifyResult.NO_CHANGE,
            manager.removePlayer(
                mock(CommandSource.class),
                manager.getBlacklist(),
                "Innocent"
            )
        );
        assertTrue(
            manager.isPlayerInBlacklist(profile(UUID.randomUUID(), "Griefer"))
        );
    }
}
