package me.fallenbreath.velocitywhitelist.config;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IpListTest
{
	@Test
	void normalizeIpLiteral_acceptsPlainIPv6()
	{
		assertTrue(IpList.normalizeIpLiteral("::1").isPresent(), "plain IPv6 loopback should parse");
	}

	@Test
	void normalizeIpLiteral_acceptsBracketedIPv6()
	{
		// Bracket notation ("[::1]") is how IPv6 addresses appear in URLs, and how they show up
		// glued to a port in log lines (e.g. "[::1]:25565") that an admin would copy-paste when
		// banning an address. normalizeIpLiteral currently only strips a trailing "%scope" suffix
		// and never strips surrounding brackets, so this fails today.
		Optional<String> result = IpList.normalizeIpLiteral("[::1]");
		assertTrue(result.isPresent(), "bracket-notation IPv6 should be accepted, same as the unbracketed form");
	}

	@Test
	void addIp_and_checkIp_roundTrip_forBracketedIPv6(@TempDir Path tempDir)
	{
		IpList list = new IpList("IpBanList", tempDir.resolve("ipbans.yml"), () -> true);

		boolean added = list.addIp("[2001:db8::1]");
		assertTrue(added, "adding a bracketed IPv6 ban should succeed");
		assertTrue(list.checkIp("2001:db8::1"), "a ban entered with brackets should still match the plain address a connecting player presents");
	}
}
