package me.fallenbreath.velocitywhitelist.utils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

// Utility class for UUID operations
public class UuidUtils
{
	// Inserts dashes into a raw UUID string without dashes
	private static String insertDashesIntoUUIDString(String uuid)
	{
		StringBuilder sb = new StringBuilder(uuid);
		sb.insert(8, "-");
		sb.insert(13, "-");
		sb.insert(18, "-");
		sb.insert(23, "-");
		return sb.toString();
	}

	// Attempts to parse a UUID from a string
	public static Optional<UUID> tryParseUuid(String value)
	{
		try
		{
			return Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException ignored)
		{
		}

		if (value.length() == 32)
		{
			return tryParseUuid(insertDashesIntoUUIDString(value));
		}

		return Optional.empty();
	}

	// Generates an offline player UUID from a player name
	public static UUID getOfflinePlayerUuid(String playerName)
	{
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
	}
}
