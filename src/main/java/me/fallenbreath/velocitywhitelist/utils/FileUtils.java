package me.fallenbreath.velocitywhitelist.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// Provides utility methods for file operations
public class FileUtils {

    // Creates a Yaml instance that refuses to instantiate anything beyond plain scalars, lists and maps to avoid vulnerabilities such as CVE-2022-1471
    public static Yaml newSafeYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    // Safely writes string content to a file by writing to a temporary file first and then moving it atomically
    public static void safeWrite(Path path, String content) throws IOException {
        Path tempPath = path.resolveSibling(
            path.getFileName().toString() + ".tmp"
        );
        Files.writeString(tempPath, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                tempPath,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException e) {
            // If filesystem cannot replace the file atomically, fall back to a plain move
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Dumps an object to a file in YAML format
    public static void dumpYaml(Path path, Object data) throws IOException {
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        String yamlContent = new Yaml(dumperOptions).dump(data);
        safeWrite(path, yamlContent);
    }
}
