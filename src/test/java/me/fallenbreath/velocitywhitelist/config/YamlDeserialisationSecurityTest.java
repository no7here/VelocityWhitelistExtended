package me.fallenbreath.velocitywhitelist.config;

import java.net.URL;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.ConstructorException;

import me.fallenbreath.velocitywhitelist.utils.FileUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CVE-2022-1471 (unrestricted deserialization) against the exact SnakeYAML version this project
 * resolves - 1.33, the last version before the fix in 2.0 - via `velocity-api`'s own dependency
 * graph. Configuration/PlayerList/IpList parse config.yml/whitelist.yml/blacklist.yml/ipbans.yml,
 * files that live in a directory admins and admin tooling routinely hand-edit.
 * <p>
 * This deliberately never calls a method that would trigger a real side effect (e.g.
 * URL#equals/openConnection perform a DNS lookup) - it only proves that construction of an
 * attacker-chosen type is possible/blocked, which is the vulnerability itself, independent of
 * whether a full RCE gadget chain happens to be reachable on a given classpath.
 */
class YamlDeserializationSecurityTest
{
	private static final String MALICIOUS_WHITELIST_YAML = "names: !!java.net.URL [\"http://example.invalid/\"]\n";

	@Test
	void plainSnakeYamlConstructor_instantiatesArbitraryTypeFromTag()
	{
		// Documents the underlying library behaviour the CVE is about: SnakeYAML's default,
		// no-arg Yaml()/Constructor() will happily construct an arbitrary Java type from a
		// "!!fully.qualified.ClassName" tag. This is inherent to SnakeYAML < 2.0 itself, not
		// something this project's own fix changes - it's why Configuration/PlayerList/IpList
		// no longer use this constructor.
		Object parsed = new Yaml().load(MALICIOUS_WHITELIST_YAML);
		Object namesValue = ((Map<?, ?>)parsed).get("names");

		assertInstanceOf(URL.class, namesValue,
				"plain new Yaml() is expected to construct arbitrary types - that's exactly the vulnerability");
	}

	@Test
	void safeYaml_rejectsArbitraryTypeTag()
	{
		// The actual regression guard: FileUtils.newSafeYaml() is what Configuration/PlayerList/
		// IpList now use to load every on-disk file, and it must refuse the same payload instead
		// of constructing it.
		assertThrows(ConstructorException.class, () -> FileUtils.newSafeYaml().load(MALICIOUS_WHITELIST_YAML),
				"a whitelist.yml value should never be able to make the parser instantiate an arbitrary Java type");
	}
}
