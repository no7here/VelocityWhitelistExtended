package me.fallenbreath.velocitywhitelist.utils;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FileUtils
{
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
