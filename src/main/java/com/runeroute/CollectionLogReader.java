package com.runeroute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Collection log helpers.
 *
 * What the game lets a plugin see:
 * - The log's layout (tabs, pages and the items on each page) is in the game files, so we can
 *   read all of it at any time. See {@link #readLayout}.
 * - Which items you have is only sent while the log is open: script 4100 runs once for each
 *   obtained item shown, and clicking the log's Search button shows every item at once.
 * - Totals per tab are player variables, there from login.
 * - New items show a chat message and/or a popup, depending on the game's settings.
 */
final class CollectionLogReader
{
	/** Script that draws one obtained item in the log. Args: [1] item id, [2] quantity. */
	static final int ITEM_SCRIPT = 4100;
	/** Popup notification start and end scripts; the popup text is in varcstrs 387 (title) and 388 (body). */
	static final int POPUP_START_SCRIPT = 3346;
	static final int POPUP_END_SCRIPT = 3347;
	static final int POPUP_TITLE_VARC = 387;
	static final int POPUP_TEXT_VARC = 388;

	/** Cache ids for the log's layout (the same ones the Wiki's WikiSync plugin uses). */
	private static final int TABS_ENUM = 2102;
	private static final int TAB_PAGES_PARAM = 683;
	private static final int PAGE_NAME_PARAM = 689;
	private static final int PAGE_ITEMS_PARAM = 690;
	/** Items the log shows under a different id than the cache lists. Keys are replaced by values */
	private static final int ITEM_REPLACEMENTS_ENUM = 3721;

	static final String[] TAB_NAMES = {"bosses", "raids", "clues", "minigames", "other"};

	/** "New item added to your collection log: Dragon pickaxe". */
	static final Pattern NEW_ITEM = Pattern.compile("^New item added to your collection log: (.{1,64})$");
	static final String POPUP_PREFIX = "New item:";

	/** Header lines such as "Zulrah kills: 123" or "Hard clues completed: 4". */
	private static final Pattern COUNT_LINE = Pattern.compile("^(.{1,48}?): ([\\d,]+)$");
	private static final int MAX_COUNTS = 8;

	private static final int[][] TAB_COUNTERS = {
		{VarPlayerID.COLLECTION_COUNT_BOSSES, VarPlayerID.COLLECTION_COUNT_BOSSES_MAX},
		{VarPlayerID.COLLECTION_COUNT_RAIDS, VarPlayerID.COLLECTION_COUNT_RAIDS_MAX},
		{VarPlayerID.COLLECTION_COUNT_CLUES, VarPlayerID.COLLECTION_COUNT_CLUES_MAX},
		{VarPlayerID.COLLECTION_COUNT_MINIGAMES, VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX},
		{VarPlayerID.COLLECTION_COUNT_OTHER, VarPlayerID.COLLECTION_COUNT_OTHER_MAX},
	};

	/** One page of the log as the game files describe it. */
	static final class PageDef
	{
		final String tab;
		final String name;
		final int[] itemIds;

		PageDef(String tab, String name, int[] itemIds)
		{
			this.tab = tab;
			this.name = name;
			this.itemIds = itemIds;
		}

		String key()
		{
			return tab + "/" + name;
		}
	}

	private CollectionLogReader()
	{
	}

	/** Every tab, page and item in the log, from the game files. Must run on the client thread. */
	static List<PageDef> readLayout(Client client)
	{
		Map<Integer, Integer> replace = new HashMap<>();
		EnumComposition replacements = client.getEnum(ITEM_REPLACEMENTS_ENUM);
		if (replacements != null)
		{
			int[] keys = replacements.getKeys();
			int[] vals = replacements.getIntVals();
			for (int i = 0; i < keys.length && i < vals.length; i++)
			{
				replace.put(keys[i], vals[i]);
			}
		}

		List<PageDef> pages = new ArrayList<>();
		int[] tabs = client.getEnum(TABS_ENUM).getIntVals();
		for (int t = 0; t < tabs.length; t++)
		{
			String tab = t < TAB_NAMES.length ? TAB_NAMES[t] : "other";
			StructComposition tabStruct = client.getStructComposition(tabs[t]);
			for (int pageId : client.getEnum(tabStruct.getIntValue(TAB_PAGES_PARAM)).getIntVals())
			{
				StructComposition page = client.getStructComposition(pageId);
				String name = clean(page.getStringValue(PAGE_NAME_PARAM));
				int[] items = client.getEnum(page.getIntValue(PAGE_ITEMS_PARAM)).getIntVals().clone();
				for (int i = 0; i < items.length; i++)
				{
					items[i] = replace.getOrDefault(items[i], items[i]);
				}
				if (!name.isEmpty() && items.length > 0)
				{
					pages.add(new PageDef(tab, name, items));
				}
			}
		}
		return pages;
	}

	/** Title and kill count lines of the page open on screen, or null. Must run on the client thread. */
	static Map.Entry<String, List<SyncPayload.CountLine>> readOpenHeader(Client client)
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER);
		if (header == null || header.isHidden())
		{
			return null;
		}
		Widget[] lines = header.getDynamicChildren();
		if (lines == null || lines.length == 0)
		{
			lines = header.getChildren();
		}
		if (lines == null || lines.length == 0 || lines[0] == null)
		{
			return null;
		}

		String title = clean(lines[0].getText());
		List<SyncPayload.CountLine> counts = new ArrayList<>();
		for (int i = 1; i < lines.length && counts.size() < MAX_COUNTS; i++)
		{
			String text = lines[i] == null ? "" : clean(lines[i].getText());
			Matcher m = COUNT_LINE.matcher(text);
			if (!text.startsWith("Obtained") && m.matches())
			{
				try
				{
					counts.add(new SyncPayload.CountLine(m.group(1), Integer.parseInt(m.group(2).replace(",", ""))));
				}
				catch (NumberFormatException ignored)
				{
					// Bigger than an int; not a real count
				}
			}
		}
		return title.isEmpty() ? null : new java.util.AbstractMap.SimpleEntry<>(title, counts);
	}

	/** Total items logged and the most there are. 0 until known. */
	static int[] totals(Client client)
	{
		return new int[]{
			client.getVarpValue(VarPlayerID.COLLECTION_COUNT),
			client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX),
		};
	}

	/** Items logged per tab, or an empty map if the game hasn't sent the counters. */
	static Map<String, SyncPayload.TabCount> tabTotals(Client client)
	{
		Map<String, SyncPayload.TabCount> tabs = new LinkedHashMap<>();
		for (int i = 0; i < TAB_COUNTERS.length; i++)
		{
			int max = client.getVarpValue(TAB_COUNTERS[i][1]);
			if (max > 0)
			{
				tabs.put(TAB_NAMES[i], new SyncPayload.TabCount(Math.min(client.getVarpValue(TAB_COUNTERS[i][0]), max), max));
			}
		}
		return tabs;
	}

	static String clean(String text)
	{
		return text == null ? "" : Text.removeTags(text).replace(' ', ' ').trim();
	}
}
