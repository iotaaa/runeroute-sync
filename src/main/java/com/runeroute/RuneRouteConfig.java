package com.runeroute;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
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

	@ConfigSection(
		name = "Data to send",
		description = "Choose what the website gets. Anything turned off is removed from the website and shown as turned off",
		position = 10
	)
	String dataSection = "data";

	@ConfigItem(
		keyName = "sendSkills",
		name = "Skills",
		description = "Send your levels and XP. Most suggestions need these",
		section = dataSection,
		position = 11
	)
	default boolean sendSkills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendQuests",
		name = "Quests",
		description = "Send your quest progress and quest points",
		section = dataSection,
		position = 12
	)
	default boolean sendQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendBossKc",
		name = "Boss kill counts",
		description = "Send kill counts the game shows in chat",
		section = dataSection,
		position = 13
	)
	default boolean sendBossKc()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendEquipment",
		name = "Gear",
		description = "Send what you have equipped and a picture of your character",
		section = dataSection,
		position = 14
	)
	default boolean sendEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendInventory",
		name = "Inventory",
		description = "Send what is in your inventory",
		section = dataSection,
		position = 15
	)
	default boolean sendInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendBank",
		name = "Bank",
		description = "Send your bank contents after you open the bank",
		section = dataSection,
		position = 16
	)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendLocation",
		name = "Location",
		description = "Send your current tile with each sync",
		section = dataSection,
		position = 17
	)
	default boolean sendLocation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendActivity",
		name = "Activity",
		description = "Show what you're doing right now on your page, like Woodcutting, Fighting or Banking. Turn on Location too to show where",
		section = dataSection,
		position = 18
	)
	default boolean sendActivity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendOnline",
		name = "Online status",
		description = "Show on your page whether you're online and when you were last seen. Turning this off also hides Activity",
		section = dataSection,
		position = 19
	)
	default boolean sendOnline()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendCollectionLog",
		name = "Collection log",
		description = "Send your collection log. The game only shows it while the log is open, so each page is sent when you look at it",
		section = dataSection,
		position = 20
	)
	default boolean sendCollectionLog()
	{
		return true;
	}
}
