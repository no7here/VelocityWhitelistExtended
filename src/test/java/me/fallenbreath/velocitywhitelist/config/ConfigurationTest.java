package me.fallenbreath.velocitywhitelist.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConfigurationTest
{
	@Mock
	Logger logger;

	@Test
	void whitelistEnabled_withNonBooleanValue_shouldWarnInsteadOfFailingSilently(@TempDir Path tempDir)
	{
		Configuration config = new Configuration(logger, tempDir.resolve("config.yml"), () -> true);

		// A hand-edited config quoting the boolean (`whitelist_enabled: "true"`) parses as a
		// String under SnakeYAML, not a Boolean. isWhitelistEnabled() must not swallow this with
		// zero log output and silently turn off the whitelist with no diagnostic at all - it
		// should warn, same as the identify_mode handling a few lines away in the same class.
		config.load("version: 2\nwhitelist_enabled: \"true\"\nblacklist_enabled: true\nipban_enabled: true\n");
		config.isWhitelistEnabled();

		verify(logger, atLeastOnce()).warn(anyString(), any(), any());
	}

	@Test
	void migrate_acceptsVersionAsQuotedString(@TempDir Path tempDir)
	{
		Configuration config = new Configuration(logger, tempDir.resolve("config.yml"), () -> true);

		// version detection must not only accept a YAML Number for "version"/"_version" - a
		// hand-quoted `version: "2"` parses as a String, and an already-current config must not
		// be treated as legacy and re-migrated (including rewriting the file) on every load.
		config.load("version: \"2\"\nidentify_mode: uuid\nwhitelist_enabled: true\nblacklist_enabled: true\nipban_enabled: true\n");

		verify(logger, never()).warn(org.mockito.ArgumentMatchers.eq("Migrating config file from {} to v{}"), any(), any());
	}

	@Test
	void isBlacklistOnIpBanJoin_requiresUuidModeAndOnlineMode(@TempDir Path tempDir)
	{
		Configuration config = new Configuration(logger, tempDir.resolve("config.yml"), () -> false);
		config.load("version: 2\nidentify_mode: uuid\nblacklist_on_ipban_join: true\nwhitelist_enabled: true\nblacklist_enabled: true\nipban_enabled: true\n");

		// sanity check on an already-defended piece of logic: offline-mode proxy must force this off
		assertTrue(!config.isBlacklistOnIpBanJoin(), "blacklist_on_ipban_join must stay off when the proxy isn't in online mode");
	}
}
