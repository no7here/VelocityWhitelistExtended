package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;

// Common contract for the yaml-file-backed lists (player lists and IP lists) so they can share the same load, save and reload plumbing in the manager
public interface YamlStoredList<T extends YamlStoredList<T>> {
    String getName();

    Path getFilePath();

    T createNewEmptyList();

    void resetTo(T newList);

    void load(Logger logger) throws IOException;

    void save() throws IOException;

    // Loudly reports entries skipped during a load - if individual malformed entries caused whole load to fail, a single typo in a file would cause it to de-activate an entire list on next proxy restart (for whitelist, that would mean an open server)
    static void logSkippedEntries(
        Logger logger,
        String listName,
        int skippedCount
    ) {
        if (skippedCount > 0) {
            logger.error(
                "{}: {} invalid entries were skipped and are NOT being enforced! Fix the file and reload it",
                listName,
                skippedCount
            );
        }
    }
}
