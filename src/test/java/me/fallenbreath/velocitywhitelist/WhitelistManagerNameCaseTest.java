package me.fallenbreath.velocitywhitelist;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;

import me.fallenbreath.velocitywhitelist.config.Configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhitelistManagerNameCaseTest
{
	@Test
	void addPlayer_inNameMode_storesResolvedCanonicalCase_notRawAdminInput(@TempDir Path tempDir) throws Exception
	{
		Logger logger = LoggerFactory.getLogger(WhitelistManagerNameCaseTest.class);

		Configuration config = new Configuration(logger, tempDir.resolve("config.yml"), () -> true);
		config.load(String.join("\n",
				"version: 2",
				"identify_mode: name",
				"whitelist_enabled: true",
				"blacklist_enabled: true",
				"ipban_enabled: true"
		));

		ProxyServer server = mock(ProxyServer.class);
		Player onlinePlayer = mock(Player.class);
		// Velocity's own online-player lookup is case-insensitive, so an admin typing the wrong
		// case still resolves to the real, canonically-cased profile - the plugin just has to
		// use it.
		GameProfile canonicalProfile = new GameProfile(UUID.randomUUID(), "Steve", List.of());
		when(onlinePlayer.getGameProfile()).thenReturn(canonicalProfile);
		when(server.getPlayer("steve")).thenReturn(Optional.of(onlinePlayer));

		WhitelistManager manager = new WhitelistManager(logger, config, tempDir, server);
		assertTrue(manager.loadLists());

		CommandSource source = mock(CommandSource.class);
		boolean added = manager.addPlayer(source, manager.getWhitelist(), "steve");

		assertTrue(added, "adding the player should succeed");
		// operatePlayer resolves canonicalProfile.getName() == "Steve" via the online player
		// lookup, but the NAME-mode switch case in WhitelistManager currently stores the raw
		// command argument ("steve") instead of that resolved name. A real login's
		// GameProfile#getName() would be "Steve" (case-sensitive match against checkPlayerName),
		// so the player who was just "added" would still fail the whitelist check.
		assertTrue(manager.getWhitelist().checkPlayerName("Steve"),
				"the whitelist should store the resolved canonical-case name so a real login (profile.getName() == \"Steve\") actually matches");
	}
}
