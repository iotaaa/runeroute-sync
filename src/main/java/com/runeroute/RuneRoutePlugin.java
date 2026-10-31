package com.runeroute;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@PluginDescriptor(
	name = "Rune Route Sync",
	description = "Sends your stats, quests, kill counts and bank to your Rune Route website",
	tags = {"progress", "goals", "route", "tracker"}
)
public class RuneRoutePlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/**
	 * Matches kill count messages such as
	 * "Your Zulrah kill count is: 12.", "Your Barrows chest count is: 5.",
	 * "Your subdued Wintertodt count is: 40.", "Your completed Chambers of Xeric count is: 3."
	 */
	private static final Pattern KC_PATTERN = Pattern.compile(
		"^Your (?:completed |subdued )?(.+?) (?:kill |chest |completion |success )?count is: ([\\d,]+)");

	/** Quest states are read with client scripts, so refresh them at most this often unless something changed. */
	private static final long QUEST_REFRESH_MS = 5 * 60_000L;

	/** Wait a few seconds after login so skills and varps are loaded. */
	private static final long LOGIN_DELAY_MS = 5_000L;

	/** The website refuses syncs closer together than this. */
	private static final long MIN_GAP_MS = 6_000L;

	/** Per-account secret, kept in RuneLite's per-account settings. Not shown in the config panel. */
	private static final String SECRET_KEY = "syncSecret";
	private static final SecureRandom RANDOM = new SecureRandom();

	@Inject
	private Client client;

	@Inject
	private RuneRouteConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private ConfigManager configManager;

	private long nextSyncAt;
	private volatile long lastSentAt;
	/** Set after the website refuses us, so we don't keep retrying (cleared on next login). */
	private volatile long pausedUntil;
	private boolean warnedNameConflict;
	private long lastQuestRefresh;
	private volatile boolean questsDirty;
	private volatile boolean requestInFlight;
	private boolean warnedOffline;

	private final Map<String, Integer> bossKc = new HashMap<>();
	private volatile List<SyncPayload.ItemStack> pendingBank;

	@Provides
	RuneRouteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneRouteConfig.class);
	}

	@Override
	protected void startUp()
	{
		questsDirty = true;
		nextSyncAt = System.currentTimeMillis() + LOGIN_DELAY_MS;
		log.debug("Rune Route started, syncing to {}", config.apiUrl());
	}

	@Override
	protected void shutDown()
	{
		bossKc.clear();
		pendingBank = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			questsDirty = true;
			pausedUntil = 0;
			nextSyncAt = System.currentTimeMillis() + LOGIN_DELAY_MS;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			bossKc.clear();
			pendingBank = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN || requestInFlight)
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now < nextSyncAt || now < pausedUntil || now - lastSentAt < MIN_GAP_MS)
		{
			return;
		}

		Player me = client.getLocalPlayer();
		long accountHash = client.getAccountHash();
		if (me == null || me.getName() == null || accountHash == -1)
		{
			return;
		}

		String secret = getOrCreateSecret();
		if (secret == null)
		{
			return; // RuneLite hasn't loaded this account's settings yet
		}

		SyncPayload payload = buildPayload(me, accountHash, now);
		if (payload == null)
		{
			return;
		}
		nextSyncAt = now + config.syncInterval() * 1000L;
		send(payload, secret);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String msg = Text.removeTags(event.getMessage());

		Matcher m = KC_PATTERN.matcher(msg);
		if (m.find())
		{
			String boss = m.group(1).trim();
			int kc = Integer.parseInt(m.group(2).replace(",", ""));
			bossKc.put(boss, kc);
			syncSoon();
			return;
		}

		if (msg.contains("Quest complete") || msg.contains("completed a quest"))
		{
			questsDirty = true;
			syncSoon();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK || !config.sendBank())
		{
			return;
		}

		pendingBank = readContainer(event.getItemContainer(), true);
		syncSoon();
	}

	private void syncSoon()
	{
		// Debounce: several events often arrive together (e.g. bank open + kc message)
		long soon = Math.max(System.currentTimeMillis() + 2_000L, lastSentAt + MIN_GAP_MS);
		nextSyncAt = Math.min(nextSyncAt, soon);
	}

	/**
	 * The secret that proves to the website that later syncs come from the same install.
	 * Made once per account and stored with that account's RuneLite settings. Returns null
	 * until RuneLite can store it, so we never sync with a secret that wasn't saved.
	 */
	private String getOrCreateSecret()
	{
		String secret = configManager.getRSProfileConfiguration(RuneRouteConfig.GROUP, SECRET_KEY);
		if (secret != null && !secret.isEmpty())
		{
			return secret;
		}

		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		configManager.setRSProfileConfiguration(RuneRouteConfig.GROUP, SECRET_KEY, secret);

		return secret.equals(configManager.getRSProfileConfiguration(RuneRouteConfig.GROUP, SECRET_KEY)) ? secret : null;
	}

	/** Must run on the client thread (we call it from GameTick). Returns null if stats aren't loaded yet. */
	private SyncPayload buildPayload(Player me, long accountHash, long now)
	{
		SyncPayload p = new SyncPayload();
		p.setAccountHash(Long.toString(accountHash));
		p.setUsername(me.getName().replace('\u00A0', ' ').trim());
		p.setCombatLevel(me.getCombatLevel());
		p.setWorld(client.getWorld());
		p.setQuestPoints(client.getVarpValue(VarPlayerID.QP));

		Map<String, SyncPayload.SkillData> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			skills.put(skill.getName().toLowerCase(), new SyncPayload.SkillData(
				client.getRealSkillLevel(skill),
				client.getSkillExperience(skill),
				client.getBoostedSkillLevel(skill)));
		}
		p.setSkills(skills);

		long totalXp = 0;
		for (SyncPayload.SkillData s : skills.values())
		{
			totalXp += s.getXp();
		}
		if (totalXp == 0)
		{
			return null;
		}

		if (questsDirty || now - lastQuestRefresh > QUEST_REFRESH_MS)
		{
			Map<String, String> quests = new LinkedHashMap<>();
			for (Quest quest : Quest.values())
			{
				quests.put(quest.getName(), quest.getState(client).name());
			}
			p.setQuests(quests);
			questsDirty = false;
			lastQuestRefresh = now;
		}

		if (!bossKc.isEmpty())
		{
			p.setBossKc(new HashMap<>(bossKc));
		}

		p.setInventory(readContainer(client.getItemContainer(InventoryID.INV), false));
		p.setEquipment(readContainer(client.getItemContainer(InventoryID.WORN), false));

		if (pendingBank != null)
		{
			p.setBank(pendingBank);
		}

		if (config.sendLocation())
		{
			WorldPoint wp = me.getWorldLocation();
			if (wp != null)
			{
				p.setLocation(new SyncPayload.Location(wp.getX(), wp.getY(), wp.getPlane()));
			}
		}

		return p;
	}

	private List<SyncPayload.ItemStack> readContainer(ItemContainer container, boolean merge)
	{
		if (container == null)
		{
			return null;
		}

		Map<Integer, SyncPayload.ItemStack> byId = new LinkedHashMap<>();
		List<SyncPayload.ItemStack> list = new ArrayList<>();
		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}

			ItemComposition comp = itemManager.getItemComposition(item.getId());
			if (comp.getPlaceholderTemplateId() != -1)
			{
				continue; // bank placeholder
			}

			int id = merge ? itemManager.canonicalize(item.getId()) : item.getId();
			String name = merge && id != item.getId() ? itemManager.getItemComposition(id).getName() : comp.getName();

			if (merge)
			{
				SyncPayload.ItemStack existing = byId.get(id);
				if (existing != null)
				{
					existing.setQty(existing.getQty() + item.getQuantity());
					continue;
				}
			}

			SyncPayload.ItemStack stack = new SyncPayload.ItemStack(id, name, item.getQuantity());
			byId.put(id, stack);
			list.add(stack);
		}
		return list;
	}

	private void send(SyncPayload payload, String secret)
	{
		String base = config.apiUrl().trim().replaceAll("/+$", "");
		HttpUrl url = HttpUrl.parse(base + "/api/sync");
		if (url == null)
		{
			log.warn("Rune Route: invalid website URL '{}'", config.apiUrl());
			return;
		}

		Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + secret)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		final List<SyncPayload.ItemStack> sentBank = payload.getBank();
		final boolean sentQuests = payload.getQuests() != null;
		requestInFlight = true;
		lastSentAt = System.currentTimeMillis();

		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				requestInFlight = false;
				if (sentQuests)
				{
					questsDirty = true;
				}
				if (!warnedOffline)
				{
					log.warn("Rune Route: could not reach {} ({}). Is the website running?", base, e.getMessage());
					warnedOffline = true;
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					JsonObject json = readJson(response.body());
					if (response.isSuccessful())
					{
						warnedOffline = false;
						// Only clear the bank if it has not changed again since we sent it
						if (sentBank != null && pendingBank == sentBank)
						{
							pendingBank = null;
						}
						if (json != null && json.has("nameConflict") && json.get("nameConflict").getAsBoolean() && !warnedNameConflict)
						{
							warnedNameConflict = true;
							log.warn("Rune Route: another account recently used this name on the website, so your page isn't public yet");
						}
						return;
					}

					questsDirty |= sentQuests;
					String reason = json != null && json.has("statusMessage") ? json.get("statusMessage").getAsString() : "";
					switch (response.code())
					{
						case 401:
							// Another install already linked this account; retrying won't help until it's reset
							pausedUntil = Long.MAX_VALUE;
							log.warn("Rune Route: the website refused this account ({}). Syncing paused until next login", reason);
							break;
						case 429:
							pausedUntil = System.currentTimeMillis() + 60_000L;
							log.debug("Rune Route: rate limited, waiting a minute");
							break;
						default:
							log.warn("Rune Route: sync failed with HTTP {} {}", response.code(), reason);
					}
				}
				finally
				{
					requestInFlight = false;
				}
			}
		});
	}

	private JsonObject readJson(ResponseBody body)
	{
		if (body == null)
		{
			return null;
		}
		try
		{
			return gson.fromJson(body.string(), JsonObject.class);
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
