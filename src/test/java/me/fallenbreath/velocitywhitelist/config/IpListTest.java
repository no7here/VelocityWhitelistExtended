package me.fallenbreath.velocitywhitelist.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Test class for IpList functionality
class IpListTest {

    // Tests if normalizeIpLiteral accepts a plain IPv6 address
    @Test
    void normalizeIpLiteral_acceptsPlainIPv6() {
        assertTrue(
            IpList.normalizeIpLiteral("::1").isPresent(),
            "plain IPv6 loopback should parse"
        );
    }

    // Tests if normalizeIpLiteral accepts a bracketed IPv6 address
    @Test
    void normalizeIpLiteral_acceptsBracketedIPv6() {
        Optional<String> result = IpList.normalizeIpLiteral("[::1]");
        assertTrue(
            result.isPresent(),
            "bracket-notation IPv6 should be accepted, same as the unbracketed form"
        );
    }

    // Tests if adding and checking a bracketed IPv6 address works correctly
    @Test
    void addIp_and_checkIp_roundTrip_forBracketedIPv6(@TempDir Path tempDir) {
        IpList list = new IpList(
            "IpBanList",
            tempDir.resolve("ipbans.yml"),
            () -> true
        );

        boolean added = list.addIp("[2001:db8::1]");
        assertTrue(added, "adding a bracketed IPv6 ban should succeed");
        assertTrue(
            list.checkIp("2001:db8::1"),
            "a ban entered with brackets should still match the plain address a connecting player presents"
        );
    }
}
