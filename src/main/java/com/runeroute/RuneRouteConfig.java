package com.runeroute;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(RuneRouteConfig.GROUP)
public interface RuneRouteConfig extends Config
{
	String GROUP = "runeroute";

	@ConfigItem(
		keyName = "apiUrl",
		name = "Website URL",
		description = "Where the Rune Route website is. Only change this if you run your own copy",
		position = 1
	)
	default String apiUrl()
	{
		return BuildInfo.DEFAULT_URL;
	}

	@Range(min = 15, max = 3600)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "syncInterval",
		name = "Sync every",
		description = "How often to send your data while logged in",
		position = 3
	)
	default int syncInterval()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "sendBank",
		name = "Send bank",
		description = "Send your bank contents after you open the bank",
		position = 4
	)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendLocation",
		name = "Send location",
		description = "Send your current tile with each sync",
		position = 5
	)
	default boolean sendLocation()
	{
		return false;
	}
}
