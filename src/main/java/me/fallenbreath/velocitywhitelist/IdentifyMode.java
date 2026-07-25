package me.fallenbreath.velocitywhitelist;

// Defines how players should be identified within the whitelist
public enum IdentifyMode
{
	NAME,
	UUID;

	public static final IdentifyMode DEFAULT = UUID;
}
