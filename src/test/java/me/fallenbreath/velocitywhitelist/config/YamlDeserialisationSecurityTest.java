package me.fallenbreath.velocitywhitelist.config;

import java.net.URL;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Demonstrates CVE-2022-1471 (unrestricted deserialization) against the exact SnakeYAML version
 * this project resolves - 1.33, the last version before the fix in 2.0 - via `velocity-api`'s
 * own dependency graph. Configuration/PlayerList/IpList all currently call `new Yaml()`
 * (SnakeYAML's default, unsafe Constructor) to parse config.yml/whitelist.yml/blacklist.yml/ipbans.yml,
 * files that live in a directory admins and admin tooling routinely hand-edit.
 *
 * Unlike the other tests in this suite, this one currently PASSES - it is a positive proof
 * that the vulnerability is real and reachable today, not a regression guard. Once
 * Configuration/PlayerList/IpList switch to {@code new Yaml(new SafeConstructor(...))}, this
 * exact input should start throwing a constructor exception instead, and this test should be
 * inverted to assertThrows to become the permanent regression guard against reintroducing
 * unsafe parsing.
 *
 * This deliberately never calls a method that would trigger a real side effect (e.g.
 * URL#equals/openConnection perform a DNS lookup) - it only proves that construction of an
 * attacker-chosen type is possible, which is the vulnerability itself, independent of whether
 * a full RCE gadget chain happens to be reachable on a given classpath.
 */
class YamlDeserializationSecurityTest
{
	@Test
	void defaultYamlConstructor_instantiatesArbitraryTypeFromTag_insteadOfRejectingIt()
	{
		String maliciousWhitelistYaml = "names: !!java.net.URL [\"http://example.invalid/\"]\n";

		Object parsed = new Yaml().load(maliciousWhitelistYaml);
		Object namesValue = ((Map<?, ?>)parsed).get("names");

		assertInstanceOf(URL.class, namesValue,
				"a whitelist.yml value should never be able to make the parser instantiate an arbitrary Java type - " +
				"this is exactly what CVE-2022-1471 is about, and SafeConstructor prevents it");
	}
}
