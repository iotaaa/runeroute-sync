package com.runeroute;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import net.runelite.api.PlayerComposition;
import net.runelite.api.Quest;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
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

	/** Live status updates between syncs: at most one per this gap... */
	private static final long STATUS_MIN_GAP_MS = 5_000L;
	/** ...and one at least this often even if nothing changed, so the website knows we're still on. */
	private static final long STATUS_HEARTBEAT_MS = 60_000L;
	/** More often while moving, so the area on the website keeps up. */
	private static final long STATUS_HEARTBEAT_MOVING_MS = 20_000L;

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
	/** Every page of the collection log, read from the game files once per session. */
	private List<CollectionLogReader.PageDef> logLayout;
	private final Map<Integer, List<CollectionLogReader.PageDef>> logPagesByItem = new HashMap<>();
	/** Items we've seen the player has (item id to quantity), from the log's item script. */
	private final Map<Integer, Integer> logObtained = new ConcurrentHashMap<>();
	/** Kill count lines from page headers, by page name. */
	private final Map<String, List<SyncPayload.CountLine>> logCounts = new ConcurrentHashMap<>();
	/** Pages the website needs again, with a version so a page changed mid-sync isn't marked done. */
	private final Map<String, Integer> dirtyLogPages = new ConcurrentHashMap<>();
	/** Items from "New item added to your collection log" messages and popups, waiting for the next sync. */
	private final List<String> pendingLogItems = new CopyOnWriteArrayList<>();
	/** Page versions in the payload being built. */
	private Map<String, Integer> pendingLogVersions = Map.of();
	/** Set while a game popup is showing, so we can read it when it ends. */
	private boolean logPopupOpen;
	/** The whole log is about 200 pages; more than this in one sync is waiting for the next. */
	private static final int MAX_LOG_PAGES_PER_SYNC = 300;
	/** Look of the character the website last received a picture of (0 = none this session). */
	private volatile int sentAppearanceSig;
	/** Look of the picture in the payload being built. */
	private int pendingAppearanceSig;
	/** The character was moving when we wanted a picture, so sync again soon. */
	private boolean retryAppearance;

	private final ActivityTracker activity = new ActivityTracker();
	/** What the player is doing this tick. */
	private SyncPayload.Status currentStatus;
	/** The last status the website accepted. */
	private volatile SyncPayload.Status sentStatus;
	private volatile long lastStatusAt;
	private volatile long statusPausedUntil;
	/** The website only takes status updates for accounts it already has, so wait for the first sync. */
	private volatile boolean syncedOnce;
	/** Kept from the last sync so we can say "offline" after logging out, when the client no longer has them. */
	private volatile String lastAccountHash;
	private volatile String lastSecret;

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
		resetCollectionLog();
		logLayout = null;
		logPagesByItem.clear();
		sentAppearanceSig = 0;
		activity.reset();
		currentStatus = null;
		sentStatus = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			questsDirty = true;
			sentAppearanceSig = 0;
			pausedUntil = 0;
			statusPausedUntil = 0;
			nextSyncAt = System.currentTimeMillis() + LOGIN_DELAY_MS;
			activity.reset();
			currentStatus = null;
			sentStatus = null;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			bossKc.clear();
			pendingBank = null;
			// The next login may be a different account
			resetCollectionLog();
			sendOffline();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player me = client.getLocalPlayer();
		if (me != null)
		{
			currentStatus = activity.update(client, me, playerTile(me));
		}
		if (requestInFlight)
		{
			return;
		}

		long accountHash = client.getAccountHash();
		if (me == null || me.getName() == null || accountHash == -1)
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now < nextSyncAt || now < pausedUntil || now - lastSentAt < MIN_GAP_MS)
		{
			maybeSendStatus(me, accountHash, now);
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
		if (retryAppearance)
		{
			retryAppearance = false;
			syncSoon();
		}
		send(payload, secret);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		activity.onXp(event.getSkill(), event.getXp());
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

		Matcher logItem = CollectionLogReader.NEW_ITEM.matcher(msg);
		if (logItem.matches() && config.sendCollectionLog())
		{
			addLogItem(logItem.group(1).trim());
			return;
		}

		if (msg.contains("Quest complete") || msg.contains("completed a quest"))
		{
			questsDirty = true;
			syncSoon();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RuneRouteConfig.GROUP.equals(event.getGroup()) || SECRET_KEY.equals(event.getKey()))
		{
			return;
		}

		if (!config.sendBank())
		{
			pendingBank = null;
		}
		if (config.sendQuests())
		{
			questsDirty = true;
		}
		if (config.sendEquipment())
		{
			sentAppearanceSig = 0;
		}
		if (!config.sendCollectionLog())
		{
			pendingLogItems.clear();
		}
		// Turning it off deletes the log on the website, so send every page again when it's back on
		markAllLogPagesDirty();
		sentStatus = null;
		// Let the website know straight away, so turned-off data disappears quickly
		syncSoon();
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

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// A log page was drawn: its header has the kill counts for that page
		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST || !config.sendCollectionLog())
		{
			return;
		}

		Map.Entry<String, List<SyncPayload.CountLine>> header = CollectionLogReader.readOpenHeader(client);
		if (header == null || header.getValue().isEmpty() || header.getValue().equals(logCounts.get(header.getKey())))
		{
			return;
		}
		logCounts.put(header.getKey(), header.getValue());
		if (logLayout != null)
		{
			for (CollectionLogReader.PageDef page : logLayout)
			{
				if (page.name.equals(header.getKey()))
				{
					markLogPageDirty(page);
				}
			}
		}
		syncSoon();
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (!config.sendCollectionLog())
		{
			return;
		}

		int id = event.getScriptId();
		if (id == CollectionLogReader.ITEM_SCRIPT && event.getScriptEvent() != null)
		{
			// The log draws each item the player has with this script. Opening a page shows that page's
			// items; the log's Search button shows every item at once.
			Object[] args = event.getScriptEvent().getArguments();
			if (args == null || args.length < 2 || !(args[1] instanceof Integer))
			{
				return;
			}
			int itemId = (Integer) args[1];
			int qty = args.length > 2 && args[2] instanceof Integer ? Math.max(1, (Integer) args[2]) : 1;
			Integer before = logObtained.put(itemId, qty);
			if (before == null || before != qty)
			{
				for (CollectionLogReader.PageDef page : logPagesByItem.getOrDefault(itemId, List.of()))
				{
					markLogPageDirty(page);
				}
				syncSoon();
			}
		}
		else if (id == CollectionLogReader.POPUP_START_SCRIPT)
		{
			logPopupOpen = true;
		}
		else if (id == CollectionLogReader.POPUP_END_SCRIPT && logPopupOpen)
		{
			// The "New item" popup, for players who chose popups instead of chat messages
			logPopupOpen = false;
			String title = client.getVarcStrValue(CollectionLogReader.POPUP_TITLE_VARC);
			String text = CollectionLogReader.clean(client.getVarcStrValue(CollectionLogReader.POPUP_TEXT_VARC));
			if ("Collection log".equalsIgnoreCase(title) && text.startsWith(CollectionLogReader.POPUP_PREFIX))
			{
				addLogItem(text.substring(CollectionLogReader.POPUP_PREFIX.length()).trim());
			}
		}
	}

	private void addLogItem(String name)
	{
		if (!name.isEmpty() && name.length() <= 64 && pendingLogItems.size() < 20 && !pendingLogItems.contains(name))
		{
			pendingLogItems.add(name);
			syncSoon();
		}
	}

	private void markLogPageDirty(CollectionLogReader.PageDef page)
	{
		dirtyLogPages.merge(page.key(), 1, Integer::sum);
	}

	private void markAllLogPagesDirty()
	{
		if (logLayout != null)
		{
			logLayout.forEach(this::markLogPageDirty);
		}
	}

	private void resetCollectionLog()
	{
		logObtained.clear();
		logCounts.clear();
		dirtyLogPages.clear();
		pendingLogItems.clear();
		logPopupOpen = false;
		// Send every page once per login, so the website has the whole layout
		markAllLogPagesDirty();
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		// Fires when a player's look changes, e.g. you equip something
		if (event.getPlayer() == client.getLocalPlayer() && config.sendEquipment() && appearanceSig(event.getPlayer()) != sentAppearanceSig)
		{
			syncSoon();
		}
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
		p.setWorld(client.getWorld());
		p.setAccountType(accountType(client.getVarbitValue(VarbitID.IRONMAN)));

		// Make sure stats have loaded before the first sync, even if skills aren't sent
		long totalXp = 0;
		for (Skill skill : Skill.values())
		{
			totalXp += client.getSkillExperience(skill);
		}
		if (totalXp == 0)
		{
			return null;
		}

		List<String> disabled = new ArrayList<>();

		if (config.sendSkills())
		{
			p.setCombatLevel(me.getCombatLevel());
			Map<String, SyncPayload.SkillData> skills = new LinkedHashMap<>();
			for (Skill skill : Skill.values())
			{
				skills.put(skill.getName().toLowerCase(), new SyncPayload.SkillData(
					client.getRealSkillLevel(skill),
					client.getSkillExperience(skill),
					client.getBoostedSkillLevel(skill)));
			}
			p.setSkills(skills);
		}
		else
		{
			disabled.add("skills");
		}

		if (!config.sendQuests())
		{
			disabled.add("quests");
		}
		else
		{
			p.setQuestPoints(client.getVarpValue(VarPlayerID.QP));
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
		}

		if (!config.sendBossKc())
		{
			disabled.add("bossKc");
		}
		else
		{
			// Kill counts the game keeps for most bosses, plus any from chat messages (raids, clues...)
			Map<String, Integer> kc = BossKills.read(client);
			bossKc.forEach((boss, n) -> kc.merge(boss, n, Math::max));
			if (!kc.isEmpty())
			{
				p.setBossKc(kc);
			}
		}

		if (config.sendInventory())
		{
			p.setInventory(readContainer(client.getItemContainer(InventoryID.INV), false));
		}
		else
		{
			disabled.add("inventory");
		}

		if (config.sendEquipment())
		{
			p.setEquipment(readEquipment(client.getItemContainer(InventoryID.WORN)));
			String picture = renderAppearanceIfChanged(me);
			if (picture != null)
			{
				p.setAppearance(picture);
				p.setModel(AvatarRenderer.encodeModelBase64(me.getModel()));
			}
		}
		else
		{
			disabled.add("equipment");
		}

		if (!config.sendBank())
		{
			disabled.add("bank");
		}
		else if (pendingBank != null)
		{
			p.setBank(pendingBank);
		}

		if (config.sendLocation())
		{
			p.setLocation(location(me));
		}
		else
		{
			disabled.add("location");
		}

		if (!config.sendActivity())
		{
			disabled.add("activity");
		}

		if (config.sendCollectionLog())
		{
			p.setCollectionLog(collectionLog());
		}
		else
		{
			disabled.add("collectionLog");
		}
		if (config.sendOnline())
		{
			p.setStatus(statusToSend());
		}
		else
		{
			disabled.add("online");
		}

		p.setDisabled(disabled);
		return p;
	}

	/** Totals from the game's counters plus any pages opened since the last sync. Null if there's nothing to send. */
	private SyncPayload.CollectionLog collectionLog()
	{
		if (logLayout == null)
		{
			try
			{
				logLayout = CollectionLogReader.readLayout(client);
			}
			catch (RuntimeException e)
			{
				log.warn("Rune Route: couldn't read the collection log layout", e);
				logLayout = List.of();
			}
			for (CollectionLogReader.PageDef page : logLayout)
			{
				for (int id : page.itemIds)
				{
					logPagesByItem.computeIfAbsent(id, k -> new ArrayList<>()).add(page);
				}
			}
			markAllLogPagesDirty();
		}

		List<SyncPayload.CollectionPage> pages = new ArrayList<>();
		Map<String, Integer> versions = new HashMap<>();
		for (CollectionLogReader.PageDef def : logLayout)
		{
			Integer version = dirtyLogPages.get(def.key());
			if (version == null || pages.size() >= MAX_LOG_PAGES_PER_SYNC)
			{
				continue;
			}
			List<SyncPayload.CollectionItem> items = new ArrayList<>();
			for (int id : def.itemIds)
			{
				// 0 means "not seen yet": the website keeps the highest number it has, so this never un-ticks an item
				items.add(new SyncPayload.CollectionItem(id, itemManager.getItemComposition(id).getName(), logObtained.getOrDefault(id, 0)));
			}
			List<SyncPayload.CountLine> counts = logCounts.get(def.name);
			pages.add(new SyncPayload.CollectionPage(def.tab, def.name, counts, items));
			versions.put(def.key(), version);
		}
		pendingLogVersions = versions;

		int[] totals = CollectionLogReader.totals(client);
		Map<String, SyncPayload.TabCount> tabs = CollectionLogReader.tabTotals(client);
		List<String> newItems = new ArrayList<>(pendingLogItems);
		if (totals[1] <= 0 && pages.isEmpty() && tabs.isEmpty() && newItems.isEmpty())
		{
			return null;
		}
		SyncPayload.CollectionLog log = new SyncPayload.CollectionLog();
		log.setObtained(totals[1] > 0 ? totals[0] : null);
		log.setTotal(totals[1] > 0 ? totals[1] : null);
		log.setPages(pages.isEmpty() ? null : pages);
		log.setTabs(tabs.isEmpty() ? null : tabs);
		log.setNewItems(newItems.isEmpty() ? null : newItems);
		return log;
	}

	/** The player's tile. Inside instances (raids, boss rooms) this is the real map tile, not the temporary copy. */
	private WorldPoint playerTile(Player me)
	{
		LocalPoint lp = me.getLocalLocation();
		if (lp != null && client.isInInstancedRegion())
		{
			return WorldPoint.fromLocalInstance(client, lp);
		}
		return me.getWorldLocation();
	}

	private SyncPayload.Location location(Player me)
	{
		WorldPoint wp = playerTile(me);
		return wp == null ? null : new SyncPayload.Location(wp.getX(), wp.getY(), wp.getPlane());
	}

	/**
	 * Between syncs, send a small status update when the activity changes, plus a heartbeat
	 * so the website can tell the player is still online. Never more often than every few seconds.
	 */
	private void maybeSendStatus(Player me, long accountHash, long now)
	{
		SyncPayload.Status status = statusToSend();
		if (!config.sendOnline() || !syncedOnce || status == null || now < pausedUntil || now < statusPausedUntil
			|| now - lastStatusAt < STATUS_MIN_GAP_MS)
		{
			return;
		}
		long heartbeat = activity.isMoving() ? STATUS_HEARTBEAT_MOVING_MS : STATUS_HEARTBEAT_MS;
		if (status.equals(sentStatus) && now - lastStatusAt < heartbeat)
		{
			return;
		}
		// A full sync is due any moment and carries the status anyway
		if (nextSyncAt - now < 3_000L && now - lastSentAt >= MIN_GAP_MS - 3_000L)
		{
			return;
		}
		String secret = getOrCreateSecret();
		if (secret == null)
		{
			return;
		}
		SyncPayload.StatusUpdate update = new SyncPayload.StatusUpdate(
			Long.toString(accountHash), status, config.sendLocation() ? location(me) : null);
		sendStatus(update, secret, true);
	}

	/** What we're doing, or just "online" when Activity is turned off. */
	private SyncPayload.Status statusToSend()
	{
		SyncPayload.Status status = currentStatus;
		if (status == null || config.sendActivity())
		{
			return status;
		}
		return new SyncPayload.Status("online", null, null);
	}

	/** After logout, tell the website straight away instead of waiting for the status to go stale. */
	private void sendOffline()
	{
		String hash = lastAccountHash;
		String secret = lastSecret;
		if (!syncedOnce || hash == null || secret == null || !config.sendOnline())
		{
			return;
		}
		syncedOnce = false;
		sentStatus = null;
		sendStatus(new SyncPayload.StatusUpdate(hash, new SyncPayload.Status("offline", null, null), null), secret, false);
	}

	private void sendStatus(SyncPayload.StatusUpdate update, String secret, boolean track)
	{
		HttpUrl url = siteUrl("/api/status");
		if (url == null)
		{
			return;
		}
		Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + secret)
			.post(RequestBody.create(JSON, gson.toJson(update)))
			.build();

		lastStatusAt = System.currentTimeMillis();
		if (track)
		{
			requestInFlight = true;
		}
		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (track)
				{
					requestInFlight = false;
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (!track)
					{
						return;
					}
					if (response.isSuccessful())
					{
						sentStatus = update.getStatus();
					}
					else if (response.code() == 404)
					{
						syncedOnce = false; // the website lost the account; the next sync recreates it
					}
					else if (response.code() == 401)
					{
						pausedUntil = Long.MAX_VALUE;
					}
					else
					{
						statusPausedUntil = System.currentTimeMillis() + 15_000L;
					}
				}
				finally
				{
					if (track)
					{
						requestInFlight = false;
					}
				}
			}
		});
	}

	private HttpUrl siteUrl(String path)
	{
		String base = config.apiUrl().trim().replaceAll("/+$", "");
		HttpUrl url = HttpUrl.parse(base + path);
		if (url == null)
		{
			log.warn("Rune Route: invalid website URL '{}'", config.apiUrl());
		}
		return url;
	}

	/** The game's account type varbit as a name the website understands. */
	private static String accountType(int value)
	{
		switch (value)
		{
			case 1: return "ironman";
			case 2: return "ultimate";
			case 3: return "hardcore";
			case 4: return "group";
			case 5: return "hardcore_group";
			case 6: return "unranked_group";
			default: return "normal";
		}
	}

	/** A number that changes whenever the character's gear, body or colours change. */
	private static int appearanceSig(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return 0;
		}
		int sig = 31 * Arrays.hashCode(comp.getEquipmentIds()) + Arrays.hashCode(comp.getColors());
		return sig == 0 ? 1 : sig;
	}

	/**
	 * A picture of the character for the website, only when their look changed since the last one
	 * we sent. Waits until they stand still so the picture isn't mid-step or mid-swing.
	 */
	private String renderAppearanceIfChanged(Player me)
	{
		int sig = appearanceSig(me);
		if (sig == 0 || sig == sentAppearanceSig)
		{
			return null;
		}
		boolean idle = me.getAnimation() == -1 && me.getPoseAnimation() == me.getIdlePoseAnimation();
		if (!idle)
		{
			retryAppearance = true; // try again shortly
			return null;
		}
		String png = AvatarRenderer.renderBase64(me.getModel());
		if (png != null)
		{
			pendingAppearanceSig = sig;
		}
		return png;
	}

	/** Worn items with their slot, so the website can lay them out like the equipment tab. */
	private List<SyncPayload.ItemStack> readEquipment(ItemContainer container)
	{
		if (container == null)
		{
			return null;
		}

		List<SyncPayload.ItemStack> list = new ArrayList<>();
		Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			String name = itemManager.getItemComposition(item.getId()).getName();
			list.add(new SyncPayload.ItemStack(item.getId(), name, item.getQuantity(), slot));
		}
		return list;
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
		final Map<String, Integer> sentLogVersions = payload.getCollectionLog() != null && payload.getCollectionLog().getPages() != null ? pendingLogVersions : Map.of();
		final List<String> sentLogItems = payload.getCollectionLog() != null ? payload.getCollectionLog().getNewItems() : null;
		final boolean sentQuests = payload.getQuests() != null;
		final int sentSig = payload.getAppearance() != null ? pendingAppearanceSig : 0;
		final SyncPayload.Status sentStatusNow = payload.getStatus();
		requestInFlight = true;
		lastSentAt = System.currentTimeMillis();
		if (sentStatusNow != null)
		{
			lastStatusAt = lastSentAt;
		}

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
						syncedOnce = true;
						lastAccountHash = payload.getAccountHash();
						lastSecret = secret;
						if (sentStatusNow != null)
						{
							sentStatus = sentStatusNow;
						}
						// The site keeps one picture per account and ignores new ones within a minute of the last
						if (sentSig != 0 && json != null && json.has("avatarSaved") && json.get("avatarSaved").getAsBoolean())
						{
							sentAppearanceSig = sentSig;
						}
						// Only clear the bank if it has not changed again since we sent it
						if (sentBank != null && pendingBank == sentBank)
						{
							pendingBank = null;
						}
						if (sentLogItems != null)
						{
							for (String item : sentLogItems)
							{
								pendingLogItems.remove(item);
							}
						}
						// Only if the page hasn't changed again since we sent it
						sentLogVersions.forEach(dirtyLogPages::remove);
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
