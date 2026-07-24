package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;

// Common contract for the yaml-file-backed lists (player lists and IP lists) so they can share the same load, save and reload plumbing in the manager
public interface YamlStoredList<T extends YamlStoredList<T>>
{
	// Gets the name of the list
	String getName();

	// Gets the path to the backing file
	Path getFilePath();

	// Creates a new empty instance of the list
	T createNewEmptyList();

	// Resets the current list to match the provided new list
	void resetTo(T newList);

	// Loads the list from the backing file
	void load(Logger logger) throws IOException;

	// Saves the list to the backing file
	void save() throws IOException;

	// Loudly reports entries that were skipped during a load, as individual malformed entries are skipped rather than failing the whole load (for the whitelist that would mean an open server)
	static void logSkippedEntries(Logger logger, String listName, int skippedCount)
	{
		if (skippedCount > 0)
		{
			logger.error("{}: {} invalid entries were skipped and are NOT being enforced! Fix the file and reload it", listName, skippedCount);
		}
	}
}
