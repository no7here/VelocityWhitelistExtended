package me.fallenbreath.velocitywhitelist.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import me.fallenbreath.velocitywhitelist.utils.FileUtils;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.constructor.ConstructorException;

// Regression guard for CVE-2022-1471: Configs are routinely hand-edited so the loader must refuse to construct attacker-chosen Java types from tags. This deliberately never triggers a real side-effect as it only needs to prove that arbitrary type construction is blocked independent of reachable RCE gadget chains
class YamlDeserialisationSecurityTest {

    // Test that safeYaml rejects arbitrary type tags
    @Test
    void safeYaml_rejectsArbitraryTypeTag() {
        String maliciousWhitelistYaml =
            "names: !!java.net.URL [\"http://example.invalid/\"]\n";

        assertThrows(
            ConstructorException.class,
            () -> FileUtils.newSafeYaml().load(maliciousWhitelistYaml),
            "a whitelist.yml value should never be able to make the parser instantiate an arbitrary Java type"
        );
    }
}
