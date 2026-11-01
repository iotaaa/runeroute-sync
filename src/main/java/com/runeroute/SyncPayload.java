package com.runeroute;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** JSON body for POST /api/sync. Matches SyncPayload in web/shared/types/player.ts */
@Data
class SyncPayload
{
	/** RuneLite's account hash, as a string so JavaScript doesn't round it */
	String accountHash;
	String username;
	/** normal, ironman, ultimate, hardcore, group, hardcore_group or unranked_group */
	String accountType;
	Integer combatLevel;
	Integer world;
	Integer questPoints;
	Map<String, SkillData> skills;
	Map<String, String> quests;
	Map<String, Integer> bossKc;
	List<ItemStack> bank;
	List<ItemStack> inventory;
	List<ItemStack> equipment;
	Location location;
	/** Picture of the player's character (base64 PNG), sent when their look changes. Part of "Gear" */
	String appearance;
	/** The character's 3D model for the website's viewer, sent with the picture. See AvatarRenderer.encodeModelBase64 */
	String model;
	/** What the player is doing right now. Part of "Activity" */
	Status status;
	/** Collection log pages seen since the last sync, plus totals. Part of "Collection log" */
	CollectionLog collectionLog;
	/**
	 * Categories the player turned off in the plugin settings, so the website can say so
	 * and delete what it stored before. Values match DataCategory in web/shared/types/player.ts
	 */
	List<String> disabled;

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class SkillData
	{
		int level;
		int xp;
		int boosted;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class ItemStack
	{
		int id;
		String name;
		int qty;
		/** Worn items only: RuneLite's equipment slot index (0 head ... 13 ammo). Null elsewhere */
		Integer slot;

		ItemStack(int id, String name, int qty)
		{
			this(id, name, qty, null);
		}
	}

	/** Live status. Values match PlayerStatus in web/shared/types/player.ts */
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class Status
	{
		/** idle, afk, walking, running, skilling, combat, banking, trading, shopping, offline, or just online when Activity is off */
		String activity;
		/** Lowercase skill name while skilling */
		String skill;
		/** Who they're fighting */
		String target;
	}

	/** JSON body for POST /api/status: a small update sent when the status changes between syncs */
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class StatusUpdate
	{
		String accountHash;
		Status status;
		/** Only when "Location" is on */
		Location location;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class Location
	{
		int x;
		int y;
		int plane;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class CollectionLog
	{
		/** Items logged and how many there are, from the game's own counters. Null until the game has sent them */
		Integer obtained;
		Integer total;
		/** Pages opened since the last sync. The website keeps pages it already has */
		List<CollectionPage> pages;
		/** Items logged per tab (bosses, raids, clues, minigames, other), from the game's counters. Known without opening the log */
		Map<String, TabCount> tabs;
		/** Items from "New item added to your collection log" chat messages since the last sync */
		List<String> newItems;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class TabCount
	{
		int obtained;
		int total;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class CollectionPage
	{
		/** bosses, raids, clues, minigames or other */
		String tab;
		String name;
		/** Lines like "Zulrah kills: 123" from the page header */
		List<CountLine> counts;
		/** Every item on the page; qty 0 means not obtained yet */
		List<CollectionItem> items;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class CountLine
	{
		String label;
		int count;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class CollectionItem
	{
		int id;
		String name;
		int qty;
	}
}
