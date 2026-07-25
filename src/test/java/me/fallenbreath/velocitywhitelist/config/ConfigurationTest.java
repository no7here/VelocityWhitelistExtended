package me.fallenbreath.velocitywhitelist.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

// Tests for the configuration class
@ExtendWith(MockitoExtension.class)
class ConfigurationTest {

    @Mock
    Logger logger;

    // Ensure that a non-boolean value for whitelist_enabled warns instead of failing silently
    @Test
    void whitelistEnabled_withNonBooleanValue_shouldWarnInsteadOfFailingSilently(
        @TempDir Path tempDir
    ) {
        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> true
        );

        // Configs with quoted with booleans parse as strings under SnakeYAML. This prevents it being swallowed with no log output & a silently disabled list.
        config.load(
            "version: 2\nwhitelist_enabled: \"true\"\nblacklist_enabled: true\nipban_enabled: true\n"
        );

        config.isWhitelistEnabled();

        verify(logger, atLeastOnce()).warn(anyString(), any(), any());
    }

    // Ensure that a warning is only logged once on load and not on every getter call
    @Test
    void whitelistEnabled_withNonBooleanValue_warnsOnceOnLoad_notOnEveryGetterCall(
        @TempDir Path tempDir
    ) {
        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> true
        );

        // This is read on every single login via each list's isActivated(), so a warning from the getter would repeat itself constantly vs. once per config load.
        config.load(
            "version: 2\nwhitelist_enabled: \"true\"\nblacklist_enabled: true\nipban_enabled: true\n"
        );
        verify(logger, times(1)).warn(anyString(), any(), any());

        config.isWhitelistEnabled();
        config.isWhitelistEnabled();
        config.isWhitelistEnabled();

        verify(logger, times(1)).warn(anyString(), any(), any());
    }

    // Ensure that migration accepts the version as a quoted string
    @Test
    void migrate_acceptsVersionAsQuotedString(@TempDir Path tempDir) {
        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> true
        );

        // Version detection must not only accept a YAML number for version but also a hand-quoted string, and an already-current config must not be treated as legacy and re-migrated on every load
        config.load(
            "version: \"2\"\nidentify_mode: uuid\nwhitelist_enabled: true\nblacklist_enabled: true\nipban_enabled: true\n"
        );

        verify(logger, never()).warn(
            eq("Migrating config file from {} to v{}"),
            any(),
            any()
        );
    }

    // Ensure that migration accepts an oversized quoted version without throwing an exception
    @Test
    void migrate_acceptsOversizedQuotedVersion_withoutThrowing(
        @TempDir Path tempDir
    ) {
        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> true
        );

        // A digit-only quoted version larger than Integer.MAX_VALUE still matches regex check, so it is treated as an already-current version
        assertDoesNotThrow(() ->
            config.load(
                "version: \"99999999999999999999\"\nidentify_mode: uuid\nwhitelist_enabled: true\nblacklist_enabled: true\nipban_enabled: true\n"
            )
        );

        verify(logger, never()).warn(
            eq("Migrating config file from {} to v{}"),
            any(),
            any()
        );
    }

    // Ensure that blacklist_on_ipban_join with a non-boolean value logs a warning
    @Test
    void blacklistOnIpBanJoin_withNonBooleanValue_shouldWarn(
        @TempDir Path tempDir
    ) {
        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> true
        );

        // blacklist_on_ipban_join is a boolean option just like the others and warnAboutInvalidBooleanOptions() must cover it too
        config.load(
            "version: 2\nidentify_mode: uuid\nblacklist_on_ipban_join: \"true\"\nwhitelist_enabled: true\nblacklist_enabled: true\nipban_enabled: true\n"
        );

        verify(logger, atLeastOnce()).warn(anyString(), any(), any());
    }

    // Ensure that isBlacklistOnIpBanJoin requires UUID mode and online mode
    @Test
    void isBlacklistOnIpBanJoin_requiresUuidModeAndOnlineMode(
        @TempDir Path tempDir
    ) {
        Configuration config = new Configuration(
            logger,
            tempDir.resolve("config.yml"),
            () -> false
        );
        config.load(
            "version: 2\nidentify_mode: uuid\nblacklist_on_ipban_join: true\nwhitelist_enabled: true\nblacklist_enabled: true\nipban_enabled: true\n"
        );

        // Sanity check on an already-defended piece of logic to ensure the offline-mode proxy forces this off
        assertTrue(
            !config.isBlacklistOnIpBanJoin(),
            "blacklist_on_ipban_join must stay off when the proxy isn't in online mode"
        );
    }
}
