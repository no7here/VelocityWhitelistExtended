package me.fallenbreath.velocitywhitelist.config;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Common contract for the yaml-file-backed lists (player lists and IP lists),
 * so they can share the same load / save / reload plumbing in the manager
 */
public interface YamlStoredList<T extends YamlStoredList<T>>
{
	String getName();

	Path getFilePath();

	T createNewEmptyList();

	void resetTo(T newList);

	void load(Logger logger) throws IOException;

	void save() throws IOException;
}
