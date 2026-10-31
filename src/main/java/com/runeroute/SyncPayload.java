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
}
