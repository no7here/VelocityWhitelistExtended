package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.Supplier;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import me.fallenbreath.velocitywhitelist.utils.FileUtils;
import me.fallenbreath.velocitywhitelist.utils.UuidUtils;

// Represents a list of players stored in a Yaml file
public class PlayerList implements YamlStoredList<PlayerList> {

    private final Set<String> names = Sets.newLinkedHashSet();
    private final Map<UUID, @Nullable String> uuids = Maps.newLinkedHashMap();
    // Maps a normalised name to every spelling of it stored, a multimap because an older file can hold both "Steve" and "steve" and discarding either would throw away a ban
    private final Multimap<String, String> nameIndex = HashMultimap.create();
    // Tracks the name labels attached to uuid entries so a deny-list lookup by name stays O(1) on the login path, a multiset because two uuid entries can carry the same label once a name changes hands
    private final Multiset<String> labelIndex = HashMultiset.create();
    private final String name;
    private final Path filePath;
    private final Supplier<Boolean> configEnableGetter;
    private boolean loadOk = false;
    private final Object lock = new Object();

    // Constructs a new PlayerList instance
    public PlayerList(
        String name,
        Path filePath,
        Supplier<Boolean> configEnableGetter
    ) {
        this.name = name;
        this.filePath = filePath;
        this.configEnableGetter = configEnableGetter;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public Path getFilePath() {
        return this.filePath;
    }

    public boolean isLoadOk() {
        synchronized (this.lock) {
            return this.loadOk;
        }
    }

    public boolean isConfigEnabled() {
        return this.configEnableGetter.get();
    }

    public boolean isActivated() {
        return this.isLoadOk() && this.isConfigEnabled();
    }

    // Gets an immutable list of all player names
    public ImmutableList<String> getPlayerNames() {
        synchronized (this.lock) {
            return ImmutableList.copyOf(this.names);
        }
    }

    // Normalises a player name for case-insensitive lookup, pinned to Locale.ROOT since a Turkish default locale lowercases "I" to "ı" and a ban would quietly stop matching
    static String normaliseName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // Checks if a player name exists in the list, comparing exactly
    public boolean checkPlayerName(String name) {
        synchronized (this.lock) {
            return this.names.contains(name);
        }
    }

    // Checks if a player name exists in the list, ignoring capitalisation
    public boolean checkPlayerNameIgnoreCase(String name) {
        synchronized (this.lock) {
            return this.nameIndex.containsKey(normaliseName(name));
        }
    }

    // Checks whether any identifier held for a profile appears in this list, as a uuid key, a name entry or the name label on a uuid entry, deliberately over-matching since this backs the deny list only
    public boolean checkAnyIdentifier(
        @Nullable UUID uuid,
        @Nullable String name
    ) {
        synchronized (this.lock) {
            if (uuid != null && this.uuids.containsKey(uuid)) {
                return true;
            }
            if (name == null) {
                return false;
            }
            String normalised = normaliseName(name);
            return (
                this.nameIndex.containsKey(normalised) ||
                this.labelIndex.contains(normalised)
            );
        }
    }

    /**
     * Adds a player name to the list, treating an existing entry that differs only by
     * capitalisation as already present when foldCase is set, so no new case collision is created.
     *
     * foldCase must follow the matcher this list is read with. Folding it on a list that is matched
     * exactly refuses a name no stored entry will ever match, telling the caller the account is
     * already listed while it stays unlisted.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public boolean addPlayerName(String name, boolean foldCase) {
        synchronized (this.lock) {
            boolean present = foldCase
                ? this.nameIndex.containsKey(normaliseName(name))
                : this.names.contains(name);
            if (present) {
                return false;
            }
            this.nameIndex.put(normaliseName(name), name);
            return this.names.add(name);
        }
    }

    /**
     * Removes a player name, every stored spelling of it when foldCase is set and only the exact
     * string otherwise.
     *
     * Returns the spellings actually removed rather than a boolean: under case-insensitive matching
     * "Steve" and "steve" are one entry, so a single removal can clear more than one stored string,
     * and the caller needs the full set both to report it and to restore it if the save fails.
     * foldCase follows the matcher for the same reason as addPlayerName, in the opposite direction:
     * folding it on an exactly matched list deletes an entry the caller never asked about.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public ImmutableList<String> removePlayerName(String name, boolean foldCase) {
        synchronized (this.lock) {
            if (!foldCase) {
                if (!this.names.remove(name)) {
                    return ImmutableList.of();
                }
                this.nameIndex.remove(normaliseName(name), name);
                return ImmutableList.of(name);
            }
            Collection<String> removed = this.nameIndex.removeAll(
                normaliseName(name)
            );
            ImmutableList<String> spellings = ImmutableList.copyOf(removed);
            this.names.removeAll(spellings);
            return spellings;
        }
    }

    /**
     * Restores previously removed spellings verbatim, for undoing a removal whose save failed.
     * Unlike addPlayerName this does not reject case collisions, since it is putting back exactly
     * what was there.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public void restorePlayerNames(Collection<String> spellings) {
        synchronized (this.lock) {
            for (String spelling : spellings) {
                this.names.add(spelling);
                this.nameIndex.put(normaliseName(spelling), spelling);
            }
        }
    }

    // Everything a single cross-identifier removal took, kept so a failed save can put it back exactly as it stood
    public record RemovedIdentifiers(
        ImmutableList<String> names,
        ImmutableList<Map.Entry<UUID, @Nullable String>> uuids
    ) {
        public boolean isEmpty() {
            return this.names.isEmpty() && this.uuids.isEmpty();
        }
    }

    /**
     * Removes every identifier this list holds for a profile: the uuid key itself, every stored
     * spelling of the name and every uuid entry carrying that name as its label.
     *
     * Mirrors checkAnyIdentifier so that anything able to make this list match is also something a
     * removal can lift. Without it a deny-list entry the command cannot reach keeps banning a player
     * the operator has just been told is not listed.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public RemovedIdentifiers removeAnyIdentifier(
        @Nullable UUID uuid,
        @Nullable String name
    ) {
        synchronized (this.lock) {
            List<Map.Entry<UUID, @Nullable String>> uuidsToRemove =
                Lists.newArrayList();
            String sweepName = name;
            if (uuid != null && this.uuids.containsKey(uuid)) {
                String label = this.uuids.get(uuid);
                uuidsToRemove.add(Maps.immutableEntry(uuid, label));
                // Falls back to the matched entry's own label, since a removal by raw uuid for an offline player carries no name and would otherwise leave a same-named entry behind still banning them
                if (sweepName == null) {
                    sweepName = label;
                }
            }

            ImmutableList<String> namesToRemove = ImmutableList.of();
            if (sweepName != null) {
                String normalised = normaliseName(sweepName);
                // Copies out of the multimap's live view before mutating anything below
                namesToRemove = ImmutableList.copyOf(
                    this.nameIndex.get(normalised)
                );
                for (Map.Entry<UUID, String> entry : this.uuids.entrySet()) {
                    // Skips a uuid already collected above so a failed save does not restore it twice
                    if (
                        entry.getValue() != null &&
                        !entry.getKey().equals(uuid) &&
                        normaliseName(entry.getValue()).equals(normalised)
                    ) {
                        uuidsToRemove.add(
                            Maps.immutableEntry(entry.getKey(), entry.getValue())
                        );
                    }
                }
            }

            for (String spelling : namesToRemove) {
                this.names.remove(spelling);
                this.nameIndex.remove(normaliseName(spelling), spelling);
            }
            for (Map.Entry<UUID, @Nullable String> entry : uuidsToRemove) {
                this.removePlayerUUID(entry.getKey());
            }

            return new RemovedIdentifiers(
                namesToRemove,
                ImmutableList.copyOf(uuidsToRemove)
            );
        }
    }

    /**
     * Restores everything a cross-identifier removal took, for undoing one whose save failed.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public void restoreIdentifiers(RemovedIdentifiers removed) {
        synchronized (this.lock) {
            this.restorePlayerNames(removed.names());
            for (Map.Entry<UUID, @Nullable String> entry : removed.uuids()) {
                this.putPlayerUUID(entry.getKey(), entry.getValue());
            }
        }
    }

    // Gets an immutable list of player UUID mapping entries
    public ImmutableList<
        Map.Entry<UUID, @Nullable String>
    > getPlayerUuidMappingEntries() {
        synchronized (this.lock) {
            // Snapshot each entry's value rather than copying the live Map.Entry objects as a repeat putPlayerUUID() for the same key mutates the existing HashMap node's value in place so a plain ImmutableList.copyOf(entrySet()) would still let a previously-returned entry's value change after the fact, Maps.immutableEntry (unlike Map.entry()) tolerates a null value which a bare-UUID entry legitimately has
            return this.uuids
                .entrySet()
                .stream()
                .map(e ->
                    Maps.<UUID, String>immutableEntry(e.getKey(), e.getValue())
                )
                .collect(ImmutableList.toImmutableList());
        }
    }

    // Checks if a player UUID exists in the list
    public boolean checkPlayerUUID(UUID uuid) {
        synchronized (this.lock) {
            return this.uuids.containsKey(uuid);
        }
    }

    // A snapshot of one UUID mapping where exists is needed alongside the name since a stored UUID may legally map to a null name (bare UUID entry in the yaml file)
    public record UuidEntry(boolean exists, @Nullable String name) {}

    // Peeks at a player UUID to get its mapping entry
    public UuidEntry peekPlayerUUID(UUID uuid) {
        synchronized (this.lock) {
            return new UuidEntry(
                this.uuids.containsKey(uuid),
                this.uuids.get(uuid)
            );
        }
    }

    /**
     * Adds or updates a player UUID and their associated name in the list.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public void putPlayerUUID(UUID uuid, @Nullable String playerName) {
        synchronized (this.lock) {
            String previousName = this.uuids.put(uuid, playerName);
            if (previousName != null) {
                this.labelIndex.remove(normaliseName(previousName));
            }
            if (playerName != null) {
                this.labelIndex.add(normaliseName(playerName));
            }
        }
    }

    /**
     * Removes a player UUID from the list.
     *
     * @apiNote Internal use only. Do not call this directly outside WhitelistManager as it bypasses save atomicity.
     */
    @ApiStatus.Internal
    public @Nullable String removePlayerUUID(UUID uuid) {
        synchronized (this.lock) {
            String removedName = this.uuids.remove(uuid);
            if (removedName != null) {
                // Removes a single occurrence so a label shared with another uuid entry keeps matching
                this.labelIndex.remove(normaliseName(removedName));
            }
            return removedName;
        }
    }

    // Resets the current list state to match a new list
    @Override
    public void resetTo(@NotNull PlayerList newList) {
        synchronized (this.lock) {
            if (!this.name.equals(newList.getName())) {
                throw new IllegalArgumentException(
                    "Attempted to reset to a player list with different name"
                );
            }
            if (!this.filePath.equals(newList.getFilePath())) {
                throw new IllegalArgumentException(
                    "Attempted to reset to a player list with different filePath"
                );
            }
            if (!newList.loadOk) {
                throw new IllegalArgumentException(
                    "Attempted to reset to a player list with loadOk == false"
                );
            }
            this.names.clear();
            this.names.addAll(newList.names);
            this.uuids.clear();
            this.uuids.putAll(newList.uuids);
            // Reload swaps state through here rather than load() so the indexes must be rebuilt on this path too, or they would keep describing the previous file
            this.rebuildIndexes();
            this.loadOk = true;
        }
    }

    // Rebuilds both normalised indexes from the stored entries. Must be called while holding the lock.
    private void rebuildIndexes() {
        this.nameIndex.clear();
        for (String storedName : this.names) {
            this.nameIndex.put(normaliseName(storedName), storedName);
        }
        this.labelIndex.clear();
        for (String label : this.uuids.values()) {
            if (label != null) {
                this.labelIndex.add(normaliseName(label));
            }
        }
    }

    // Reports names differing only by capitalisation, which are kept rather than merged and behave as one entry only where the list folds name case. Must be called while holding the lock.
    private void warnAboutCaseCollisions(Logger logger) {
        for (String normalised : this.nameIndex.keySet()) {
            Collection<String> spellings = this.nameIndex.get(normalised);
            if (spellings.size() > 1) {
                logger.warn(
                    "{}: {} differ only by capitalisation. They are all kept. A deny list matches them as one entry and removes them together; an allow list does so only when the proxy is in online mode, and otherwise keeps treating them as separate accounts",
                    this.name,
                    String.join(" / ", spellings)
                );
            }
        }
    }

    // Creates a new empty player list with the same configuration
    @Override
    public PlayerList createNewEmptyList() {
        return new PlayerList(
            this.name,
            this.filePath,
            this.configEnableGetter
        );
    }

    // Loads the player list from its Yaml file
    @Override
    @SuppressWarnings("unchecked")
    public void load(Logger logger) throws IOException {
        String yamlContent = Files.readString(this.filePath);

        // Load as a map instead of using loadAs with HashMap class because loadAs asks SafeConstructor to construct the root via an explicit tag which is not on its safe allowlist and throws, an empty file parses to null the same as Configuration's config.yml handling
        Map<String, Object> options = (Map<String, Object>) FileUtils.newSafeYaml().load(yamlContent);

        synchronized (this.lock) {
            this.names.clear();
            this.nameIndex.clear();
            this.uuids.clear();
            this.labelIndex.clear();
            int skipped = 0;

            // Extract the names value if options is not null, a present but non-list value means the file is structurally corrupt so fail the whole load so a reload keeps the previous state instead of silently replacing the list with an empty one
            Object namesVal = options != null ? options.get("names") : null;
            if (namesVal != null) {
                if (!(namesVal instanceof List<?> namesList)) {
                    throw new IOException(
                        "The 'names' field in the file is malformed (not a YAML list)"
                    );
                }
                for (Object entry : namesList) {
                    if (entry == null) {
                        logger.warn("Skipping null/empty player name entry");
                        skipped++;
                        continue;
                    }
                    this.names.add(entry.toString());
                }
            }

            Object uuidsVal = options != null ? options.get("uuids") : null;
            if (uuidsVal != null) {
                if (!(uuidsVal instanceof List<?> uuidsList)) {
                    throw new IOException(
                        "The 'uuids' field in the file is malformed (not a YAML list)"
                    );
                }
                for (Object item : uuidsList) {
                    if (item instanceof String s) {
                        Optional<UUID> uuid = UuidUtils.tryParseUuid(s);
                        if (uuid.isPresent()) {
                            this.uuids.put(uuid.get(), null);
                        } else {
                            logger.warn("Skipping invalid UUID \"{}\"", s);
                            skipped++;
                        }
                    } else if (item instanceof Map<?, ?> map) {
                        if (map.size() != 1) {
                            logger.warn(
                                "Skipping invalid map item with size {}",
                                map.size()
                            );
                            skipped++;
                            continue;
                        }
                        Map.Entry<?, ?> entry = map
                            .entrySet()
                            .iterator()
                            .next();
                        if (
                            entry.getKey() instanceof String s &&
                            (entry.getValue() instanceof String ||
                                entry.getValue() == null)
                        ) {
                            String name = (String) entry.getValue();
                            Optional<UUID> uuid = UuidUtils.tryParseUuid(s);
                            if (uuid.isPresent()) {
                                this.uuids.put(uuid.get(), name);
                            } else {
                                logger.warn(
                                    "Skipping invalid UUID \"{}\" ({})",
                                    s,
                                    name
                                );
                                skipped++;
                            }
                        } else {
                            logger.warn(
                                "Skipping invalid UUID list item {}",
                                item
                            );
                            skipped++;
                        }
                    } else {
                        logger.warn("Skipping invalid UUID list item {}", item);
                        skipped++;
                    }
                }
            }

            this.rebuildIndexes();
            this.warnAboutCaseCollisions(logger);

            this.loadOk = true;
            YamlStoredList.logSkippedEntries(logger, this.name, skipped);
            logger.info(
                "{} loaded with {} names and {} uuids",
                this.name,
                this.names.size(),
                this.uuids.size()
            );
        }
    }

    // Saves the player list to its Yaml file
    @Override
    public void save() throws IOException {
        Map<String, Object> options = Maps.newLinkedHashMap();

        synchronized (this.lock) {
            options.put("names", Lists.newArrayList(this.names));
            List<Object> uuidList = this.uuids
                .entrySet()
                .stream()
                .map(e ->
                    e.getValue() != null
                        ? Map.of(e.getKey().toString(), e.getValue())
                        : e.getKey().toString()
                )
                .toList();
            options.put("uuids", uuidList);
        }

        FileUtils.dumpYaml(this.filePath, options);
    }
}
