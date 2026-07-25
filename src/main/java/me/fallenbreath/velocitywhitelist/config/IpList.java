package me.fallenbreath.velocitywhitelist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;

import me.fallenbreath.velocitywhitelist.utils.FileUtils;

// Represents a list of IP addresses stored in a YAML file
public class IpList implements YamlStoredList<IpList> {

    private final Set<String> ips = Sets.newLinkedHashSet();
    private final String name;
    private final Path filePath;
    private final Supplier<Boolean> configEnableGetter;
    private boolean loadOk = false;
    private final Object lock = new Object();

    // Initialises a new IP list with a given name, file path and config supplier
    public IpList(
        String name,
        Path filePath,
        Supplier<Boolean> configEnableGetter
    ) {
        this.name = name;
        this.filePath = filePath;
        this.configEnableGetter = configEnableGetter;
    }

    // Gets the name of the list
    @Override
    public String getName() {
        return this.name;
    }

    // Gets the file path of the list
    @Override
    public Path getFilePath() {
        return this.filePath;
    }

    // Checks if the list was loaded successfully
    public boolean isLoadOk() {
        synchronized (this.lock) {
            return this.loadOk;
        }
    }

    // Checks if the list is enabled in the configuration
    public boolean isConfigEnabled() {
        return this.configEnableGetter.get();
    }

    // Checks if the list is both loaded and enabled
    public boolean isActivated() {
        return this.isLoadOk() && this.isConfigEnabled();
    }

    // Gets an immutable copy of the current IPs
    public ImmutableList<String> getIps() {
        synchronized (this.lock) {
            return ImmutableList.copyOf(this.ips);
        }
    }

    // Strips the scope ID from an IPv6 address if present
    private static String stripScopeId(String ip) {
        int pct = ip.indexOf('%');
        return pct != -1 ? ip.substring(0, pct) : ip;
    }

    // Strictly parses an IP literal into its canonical textual form and returns empty for anything else so no DNS lookup can ever happen
    public static Optional<String> normalizeIpLiteral(String ipStr) {
        String cleanIp = ipStr.trim();
        // Bracket notation is how IPv6 addresses appear in URLs and log lines so it is worth accepting even though it is not a literal IP by itself
        if (
            cleanIp.length() >= 2 &&
            cleanIp.startsWith("[") &&
            cleanIp.endsWith("]")
        ) {
            cleanIp = cleanIp.substring(1, cleanIp.length() - 1);
        }
        cleanIp = stripScopeId(cleanIp);
        if (InetAddresses.isInetAddress(cleanIp)) {
            return Optional.of(
                InetAddresses.forString(cleanIp).getHostAddress()
            );
        }
        return Optional.empty();
    }

    // Checks if a given IP address is in the list
    public boolean checkIp(String ipStr) {
        Optional<String> normalized = normalizeIpLiteral(ipStr);
        if (normalized.isEmpty()) {
            return false;
        }
        synchronized (this.lock) {
            return this.ips.contains(normalized.get());
        }
    }

    // Adds an IP address to the list
    public boolean addIp(String ipStr) {
        Optional<String> normalized = normalizeIpLiteral(ipStr);
        if (normalized.isEmpty()) {
            return false;
        }
        synchronized (this.lock) {
            return this.ips.add(normalized.get());
        }
    }

    // Removes an IP address from the list
    public boolean removeIp(String ipStr) {
        Optional<String> normalized = normalizeIpLiteral(ipStr);
        if (normalized.isEmpty()) {
            return false;
        }
        synchronized (this.lock) {
            return this.ips.remove(normalized.get());
        }
    }

    // Resets the current list to match a newly loaded list
    @Override
    public void resetTo(@NotNull IpList newList) {
        synchronized (this.lock) {
            if (!this.name.equals(newList.getName())) {
                throw new IllegalArgumentException(
                    "Attempted to reset to an IP list with different name"
                );
            }
            if (!this.filePath.equals(newList.getFilePath())) {
                throw new IllegalArgumentException(
                    "Attempted to reset to an IP list with different filePath"
                );
            }
            if (!newList.loadOk) {
                throw new IllegalArgumentException(
                    "Attempted to reset to an IP list with loadOk == false"
                );
            }
            this.ips.clear();
            this.ips.addAll(newList.ips);
            this.loadOk = true;
        }
    }

    // Creates a new empty IP list with the same configuration
    @Override
    public IpList createNewEmptyList() {
        return new IpList(this.name, this.filePath, this.configEnableGetter);
    }

    // Loads the IP list from the YAML file
    @Override
    @SuppressWarnings("unchecked")
    public void load(Logger logger) throws IOException {
        String yamlContent = Files.readString(this.filePath);

        // Use a plain load and cast because an explicit tag would be rejected by the safe constructor and an empty file parses to null
        Map<String, Object> options = (Map<
            String,
            Object
        >) FileUtils.newSafeYaml().load(yamlContent);

        synchronized (this.lock) {
            this.ips.clear();
            int skipped = 0;

            Object ipsVal = options != null ? options.get("ips") : null;
            if (ipsVal != null) {
                // A present but non-list value means the file is structurally corrupt so fail the whole load
                if (!(ipsVal instanceof List<?> list)) {
                    throw new IOException(
                        "The 'ips' field in the config is malformed (not a YAML list)"
                    );
                }
                for (Object entry : list) {
                    if (entry == null) {
                        logger.warn("Skipping null/empty IP ban entry");
                        skipped++;
                        continue;
                    }
                    String rawIp = entry.toString();
                    Optional<String> normalized = normalizeIpLiteral(rawIp);
                    if (normalized.isPresent()) {
                        this.ips.add(normalized.get());
                    } else {
                        logger.warn("Skipping invalid IP ban entry: {}", rawIp);
                        skipped++;
                    }
                }
            }
            this.loadOk = true;
            YamlStoredList.logSkippedEntries(logger, this.name, skipped);
            logger.info(
                "{} loaded with {} IP addresses",
                this.name,
                this.ips.size()
            );
        }
    }

    // Saves the current IP list to the YAML file
    @Override
    public void save() throws IOException {
        Map<String, Object> options = Maps.newLinkedHashMap();

        synchronized (this.lock) {
            options.put("ips", Lists.newArrayList(this.ips));
        }

        FileUtils.dumpYaml(this.filePath, options);
    }
}
