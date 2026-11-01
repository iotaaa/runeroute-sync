package com.runeroute;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Works out what the player is doing right now, for the live status on the website.
 * Call {@link #update} once per game tick and {@link #onXp} on each stat change, both on the client thread.
 *
 * Skilling is spotted from XP drops rather than animation ids, so it works for every skill
 * and doesn't break when Jagex adds new tools. Each state is held for a few ticks so the
 * status doesn't flicker between actions (a tree falling, a fishing spot moving).
 */
class ActivityTracker
{
	/** No mouse or keyboard for this many client ticks (20 ms each) counts as AFK: 90 seconds. */
	private static final int AFK_CLIENT_TICKS = 90_000 / 20;
	/** Still skilling for this many game ticks after the last XP drop (about 7 seconds)... */
	private static final int SKILL_XP_TICKS = 12;
	/** ...or up to this long (about 30 seconds) while still animating, for slow actions like fishing. */
	private static final int SKILL_BUSY_TICKS = 50;
	/** Still fighting for this many ticks after the last sign of combat. */
	private static final int COMBAT_TICKS = 8;

	/** Skills whose XP means a fight rather than skilling. Magic counts as skilling unless hitpoints XP comes with it. */
	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.HITPOINTS, Skill.SLAYER);

	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private int tick;
	private Skill xpSkill;
	private int xpTick = -1000;
	private int combatXpTick = -1000;
	private int combatTick = -1000;
	private String combatTarget;
	private WorldPoint lastPos;
	private int moveTick = -1000;
	private int runTick = -1000;
	private int animTick = -1000;

	void reset()
	{
		lastXp.clear();
		xpSkill = null;
		combatTarget = null;
		lastPos = null;
		tick = 0;
		xpTick = combatXpTick = combatTick = moveTick = runTick = animTick = -1000;
	}

	void onXp(Skill skill, int xp)
	{
		Integer before = lastXp.put(skill, xp);
		// The first value after login is the starting total, not a drop
		if (before == null || xp <= before)
		{
			return;
		}
		if (COMBAT_SKILLS.contains(skill))
		{
			combatXpTick = tick;
		}
		else
		{
			xpSkill = skill;
			xpTick = tick;
		}
	}

	SyncPayload.Status update(Client client, Player me, WorldPoint pos)
	{
		tick++;

		if (pos != null && lastPos != null && pos.getPlane() == lastPos.getPlane())
		{
			int d = Math.max(Math.abs(pos.getX() - lastPos.getX()), Math.abs(pos.getY() - lastPos.getY()));
			// More than 2 tiles in a tick is a teleport, not walking
			if (d == 1 || d == 2)
			{
				moveTick = tick;
				if (d == 2)
				{
					runTick = tick;
				}
			}
		}
		lastPos = pos;

		if (me.getAnimation() != -1)
		{
			animTick = tick;
		}

		Actor target = me.getInteracting();
		if (target instanceof NPC)
		{
			NPC npc = (NPC) target;
			boolean recentHit = tick - combatXpTick <= COMBAT_TICKS;
			if (npc.getCombatLevel() > 0 && (npc.getInteracting() == me || recentHit))
			{
				combatTick = tick;
				combatTarget = clean(npc.getName());
			}
		}
		else if (target instanceof Player && target.getInteracting() == me)
		{
			combatTick = tick;
			combatTarget = "a player"; // other players' names aren't ours to share
		}

		if (isOpen(client, InterfaceID.BANKMAIN) || isOpen(client, InterfaceID.BANK_DEPOSITBOX))
		{
			return new SyncPayload.Status("banking", null, null);
		}
		if (isOpen(client, InterfaceID.GE_OFFERS))
		{
			return new SyncPayload.Status("trading", null, null);
		}
		if (isOpen(client, InterfaceID.SHOPMAIN))
		{
			return new SyncPayload.Status("shopping", null, null);
		}
		if (tick - combatTick <= COMBAT_TICKS)
		{
			return new SyncPayload.Status("combat", null, combatTarget);
		}
		if (xpSkill != null)
		{
			int since = tick - xpTick;
			boolean busy = tick - animTick <= 3 || (xpSkill == Skill.AGILITY && tick - moveTick <= 3);
			if (since <= SKILL_XP_TICKS || (since <= SKILL_BUSY_TICKS && busy))
			{
				return new SyncPayload.Status("skilling", xpSkill.getName().toLowerCase(), null);
			}
		}
		if (tick - moveTick <= 1)
		{
			return new SyncPayload.Status(tick - runTick <= 2 ? "running" : "walking", null, null);
		}
		int idleInput = Math.min(client.getMouseIdleTicks(), client.getKeyboardIdleTicks());
		return new SyncPayload.Status(idleInput >= AFK_CLIENT_TICKS ? "afk" : "idle", null, null);
	}

	boolean isMoving()
	{
		return tick - moveTick <= 1;
	}

	private static boolean isOpen(Client client, int groupId)
	{
		Widget w = client.getWidget(groupId, 0);
		return w != null && !w.isHidden();
	}

	private static String clean(String name)
	{
		if (name == null)
		{
			return null;
		}
		String s = Text.removeTags(name).replace(' ', ' ').trim();
		return s.length() > 48 ? s.substring(0, 48) : s;
	}
}
