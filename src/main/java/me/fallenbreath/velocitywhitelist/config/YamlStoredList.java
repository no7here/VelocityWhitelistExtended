package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;

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

	/**
	 * Loudly reports entries that were skipped during a load. Individual malformed entries are
	 * skipped rather than failing the whole load, so that a single typo in a hand-edited file
	 * cannot deactivate an entire list on the next proxy restart (for the whitelist, that would
	 * mean an open server). This summary makes the degradation impossible to miss in the console
	 */
	static void logSkippedEntries(Logger logger, String listName, int skippedCount)
	{
		if (skippedCount > 0)
		{
			logger.error("{}: {} invalid entries were skipped and are NOT being enforced! Fix the file and reload it", listName, skippedCount);
		}
	}
}
