package me.fallenbreath.velocitywhitelist;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.fallenbreath.velocitywhitelist.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Test class for verifying name casing behaviour in the WhitelistManager
class WhitelistManagerNameCaseTest {

    // Test method to verify that adding a player in name mode stores the resolved canonical case
    @Test
    void addPlayer_inNameMode_storesResolvedCanonicalCase_notRawAdminInput(
        @TempDir Path tempDir
    ) throws Exception {
        Logger logger = LoggerFactory.getLogger(
            WhitelistManagerNameCaseTest.class
        );

        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> true
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

        ProxyServer server = mock(ProxyServer.class);
        Player onlinePlayer = mock(Player.class);
        // Velocity's own online-player lookup is case-insensitive so an admin typing the wrong case still resolves to the real canonically-cased profile which the plugin just has to use
        GameProfile canonicalProfile = new GameProfile(
            UUID.randomUUID(),
            "Steve",
            List.of()
        );
        when(onlinePlayer.getGameProfile()).thenReturn(canonicalProfile);
        when(server.getPlayer("steve")).thenReturn(Optional.of(onlinePlayer));

        WhitelistManager manager = new WhitelistManager(
            logger,
            config,
            tempDir,
            server
        );
        assertTrue(manager.loadLists());

        CommandSource source = mock(CommandSource.class);
        WhitelistManager.ModifyResult result = manager.addPlayer(
            source,
            manager.getWhitelist(),
            "steve",
            null
        );

        assertTrue(result == WhitelistManager.ModifyResult.SUCCESS, "adding the player should succeed");
        assertTrue(
            manager.getWhitelist().checkPlayerName("Steve"),
            "the whitelist should store the resolved canonical-case name so a real login (profile.getName() == \"Steve\") actually matches"
        );
    }
}
