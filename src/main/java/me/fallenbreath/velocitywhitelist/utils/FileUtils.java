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

public class FileUtils
{
	/**
	 * A Yaml instance that refuses to instantiate anything beyond plain scalars/lists/maps.
	 * SnakeYAML's default (no-arg) Yaml()/Constructor() will happily construct an arbitrary Java
	 * type from a "!!fully.qualified.ClassName" tag (CVE-2022-1471, fixed only in SnakeYAML 2.0);
	 * this project's own files (config.yml/whitelist.yml/blacklist.yml/ipbans.yml) are exactly the
	 * kind of hand-edited files that vulnerability targets, so every load goes through this instead
	 */
	public static Yaml newSafeYaml()
	{
		return new Yaml(new SafeConstructor(new LoaderOptions()));
	}

	public static void safeWrite(Path path, String content) throws IOException
	{
		Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.writeString(tempPath, content, StandardCharsets.UTF_8);
		try
		{
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			// The filesystem cannot replace the file atomically; fall back to a plain move.
			// The worst case is then a torn write on crash, same as before this option was added
			Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void dumpYaml(Path path, Object data) throws IOException
	{
		DumperOptions dumperOptions = new DumperOptions();
		dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

		String yamlContent = new Yaml(dumperOptions).dump(data);
		safeWrite(path, yamlContent);
	}
}
