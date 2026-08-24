package me.fallenbreath.velocitywhitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Tests the online-mode gate on case-insensitive whitelist matching, which exists because the two proxy modes disagree about what a name is
class WhitelistManagerNameCaseMatchingTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        WhitelistManagerNameCaseMatchingTest.class
    );

    private static WhitelistManager managerWith(
        Path tempDir,
        boolean proxyOnlineMode,
        String listFileName,
        String... names
    ) throws Exception {
        StringBuilder yaml = new StringBuilder("names:\n");
        for (String name : names) {
            yaml.append("  - ").append(name).append("\n");
        }
        yaml.append("uuids: []\n");
        Files.writeString(tempDir.resolve(listFileName), yaml.toString());

        Configuration config = new Configuration(
            LOGGER,
            tempDir.resolve("config.yml"),
            () -> proxyOnlineMode
        );
        config.load(
            String.join(
                "\n",
                "version: 2",
                "identify_mode: name",
                "whitelist_enabled: true",
                "blacklist_enabled: true",
                "ipban_enabled: true"
            )
        );

        // Mirrors the proxy's own mode into the mock so the command paths, which read it straight off the server, agree with what the matcher was configured with
        ProxyConfig proxyConfig = mock(ProxyConfig.class);
        when(proxyConfig.isOnlineMode()).thenReturn(proxyOnlineMode);
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

    private static GameProfile named(String name) {
        return new GameProfile(UUID.randomUUID(), name, List.of());
    }

    @Test
    void whitelist_onOnlineProxy_matchesRegardlessOfCase(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            true,
            "whitelist.yml",
            "Steve"
        );

        assertTrue(manager.isPlayerInWhitelist(named("Steve")));
        assertTrue(
            manager.isPlayerInWhitelist(named("steve")),
            "Mojang cannot issue Steve and steve to two accounts, so folding case widens nothing here"
        );
        assertFalse(manager.isPlayerInWhitelist(named("Steven")));
    }

    // Guards the gate itself, since without it a stored "Steve" would become a whitelist entry that "steve" also satisfies on every offline-mode install
    @Test
    void whitelist_onOfflineProxy_staysCaseSensitive(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            false,
            "whitelist.yml",
            "Steve"
        );

        assertTrue(manager.isPlayerInWhitelist(named("Steve")));
        assertFalse(
            manager.isPlayerInWhitelist(named("steve")),
            "on an offline proxy Steve and steve are different accounts, so this must stay denied exactly as it is today"
        );
    }

    // Checks mutation follows the matcher, an offline whitelist that refused "steve" beside "Steve" reporting the account as listed while its login stayed denied
    @Test
    void whitelist_onOfflineProxy_storesBothSpellings(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            false,
            "whitelist.yml",
            "Steve"
        );

        assertEquals(
            WhitelistManager.ModifyResult.SUCCESS,
            manager.addPlayer(
                mock(CommandSource.class),
                manager.getWhitelist(),
                "steve",
                null
            )
        );
        assertTrue(manager.isPlayerInWhitelist(named("steve")));
        assertTrue(manager.isPlayerInWhitelist(named("Steve")));
    }

    // Checks the deny list folds on the same offline proxy, where a second spelling adds nothing because the matcher already catches it
    @Test
    void blacklist_onOfflineProxy_refusesACaseVariant(@TempDir Path tempDir)
        throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            false,
            "blacklist.yml",
            "Griefer"
        );

        assertEquals(
            WhitelistManager.ModifyResult.NO_CHANGE,
            manager.addPlayer(
                mock(CommandSource.class),
                manager.getBlacklist(),
                "griefer",
                null
            )
        );
        assertIterableEquals(
            List.of("Griefer"),
            manager.getBlacklist().getPlayerNames()
        );
    }

    // Checks the deny side is not gated, since catching both spellings is the fix for case evasion
    @Test
    void blacklist_onOfflineProxy_stillMatchesRegardlessOfCase(
        @TempDir Path tempDir
    ) throws Exception {
        WhitelistManager manager = managerWith(
            tempDir,
            false,
            "blacklist.yml",
            "Griefer"
        );

        assertTrue(manager.isPlayerInBlacklist(named("Griefer")));
        assertTrue(
            manager.isPlayerInBlacklist(named("griefer")),
            "a banned player must not walk back in by changing one character"
        );
    }
}
