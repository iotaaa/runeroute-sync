package com.runeroute;

import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Boss kill counts the game keeps in player variables, so they're known from login
 * without waiting for a kill count message in chat. Names match the game's chat messages
 * and the hiscores, which is what the website uses.
 */
final class BossKills
{
	private static final Map<String, Integer> VARPS = new LinkedHashMap<>();

	static
	{
		VARPS.put("Barrows", VarPlayerID.TOTAL_BARROWS_CHESTS);
		VARPS.put("Kree'Arra", VarPlayerID.TOTAL_ARMADYL_KILLS);
		VARPS.put("General Graardor", VarPlayerID.TOTAL_BANDOS_KILLS);
		VARPS.put("Commander Zilyana", VarPlayerID.TOTAL_SARADOMIN_KILLS);
		VARPS.put("K'ril Tsutsaroth", VarPlayerID.TOTAL_ZAMORAK_KILLS);
		VARPS.put("Dagannoth Prime", VarPlayerID.TOTAL_PRIME_KILLS);
		VARPS.put("Dagannoth Rex", VarPlayerID.TOTAL_REX_KILLS);
		VARPS.put("Dagannoth Supreme", VarPlayerID.TOTAL_SUPREME_KILLS);
		VARPS.put("Callisto", VarPlayerID.TOTAL_CALLISTO_KILLS);
		VARPS.put("Venenatis", VarPlayerID.TOTAL_VENENATIS_KILLS);
		VARPS.put("Vet'ion", VarPlayerID.TOTAL_VETION_KILLS);
		VARPS.put("Chaos Elemental", VarPlayerID.TOTAL_CHAOSELE_KILLS);
		VARPS.put("King Black Dragon", VarPlayerID.TOTAL_KBD_KILLS);
		VARPS.put("Giant Mole", VarPlayerID.TOTAL_MOLE_KILLS);
		VARPS.put("Kalphite Queen", VarPlayerID.TOTAL_KALPHITE_KILLS);
		VARPS.put("Corporeal Beast", VarPlayerID.TOTAL_CORP_KILLS);
		VARPS.put("Zulrah", VarPlayerID.TOTAL_SNAKEBOSS_KILLS);
		VARPS.put("Chaos Fanatic", VarPlayerID.TOTAL_CHAOSFANATIC_KILLS);
		VARPS.put("Scorpia", VarPlayerID.TOTAL_SCORPIA_KILLS);
		VARPS.put("Crazy Archaeologist", VarPlayerID.TOTAL_CRAZYARCHAEOLOGIST_KILLS);
		VARPS.put("TzTok-Jad", VarPlayerID.TOTAL_JAD_KILLS);
		VARPS.put("Kraken", VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS);
		VARPS.put("Thermonuclear Smoke Devil", VarPlayerID.TOTAL_THERMY_KILLS);
		VARPS.put("Cerberus", VarPlayerID.TOTAL_CERBERUS_KILLS);
		VARPS.put("Abyssal Sire", VarPlayerID.TOTAL_ABYSSALSIRE_KILLS);
		VARPS.put("Skotizo", VarPlayerID.TOTAL_CATA_BOSS_KILLS);
		VARPS.put("Wintertodt", VarPlayerID.TOTAL_WINTERTODT_KILLS);
		VARPS.put("Obor", VarPlayerID.TOTAL_HILLGIANT_BOSS_KILLS);
		VARPS.put("TzKal-Zuk", VarPlayerID.TOTAL_ZUK_KILLS);
		VARPS.put("Deranged Archaeologist", VarPlayerID.TOTAL_DERANGEDARCHAEOLOGIST_KILLS);
		VARPS.put("Grotesque Guardians", VarPlayerID.TOTAL_GARGBOSS_KILLS);
		VARPS.put("Vorkath", VarPlayerID.TOTAL_VORKATH_KILLS);
		VARPS.put("Bryophyta", VarPlayerID.TOTAL_BRYOPHYTA_KILLS);
		VARPS.put("Alchemical Hydra", VarPlayerID.TOTAL_HYDRABOSS_KILLS);
		VARPS.put("Hespori", VarPlayerID.TOTAL_HESPORI_KILLS);
		VARPS.put("Mimic", VarPlayerID.TOTAL_MIMIC_KILLS);
		VARPS.put("Sarachnis", VarPlayerID.TOTAL_SARACHNIS_KILLS);
		VARPS.put("Zalcano", VarPlayerID.TOTAL_ZALCANO_KILLS);
		VARPS.put("Nightmare", VarPlayerID.TOTAL_NIGHTMARE_KILLS);
		VARPS.put("Phosani's Nightmare", VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS);
		VARPS.put("Tempoross", VarPlayerID.TOTAL_TEMPOROSS_KILLS);
		VARPS.put("Nex", VarPlayerID.TOTAL_NEX_KILLS);
		VARPS.put("Guardians of the Rift", VarPlayerID.TOTAL_GOTR_KILLS);
		VARPS.put("Phantom Muspah", VarPlayerID.TOTAL_MUSPAH_KILLS);
		VARPS.put("Artio", VarPlayerID.TOTAL_ARTIO_KILLS);
		VARPS.put("Spindel", VarPlayerID.TOTAL_SPINDEL_KILLS);
		VARPS.put("Calvar'ion", VarPlayerID.TOTAL_CALVARION_KILLS);
		VARPS.put("Duke Sucellus", VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS);
		VARPS.put("The Leviathan", VarPlayerID.TOTAL_LEVIATHAN_KILLS);
		VARPS.put("The Whisperer", VarPlayerID.TOTAL_WHISPERER_KILLS);
		VARPS.put("Vardorvis", VarPlayerID.TOTAL_VARDORVIS_KILLS);
		VARPS.put("Scurrius", VarPlayerID.TOTAL_RAT_BOSS_KILLS);
		VARPS.put("Lunar Chests", VarPlayerID.TOTAL_PMOON_CHESTS);
		VARPS.put("Sol Heredit", VarPlayerID.TOTAL_SOL_KILLS);
		VARPS.put("Araxxor", VarPlayerID.TOTAL_ARAXXOR_KILLS);
		VARPS.put("Amoxliatl", VarPlayerID.TOTAL_AMOXLIATL_KILLS);
		VARPS.put("The Hueycoatl", VarPlayerID.TOTAL_HUEY_KILLS);
		VARPS.put("Royal Titans", VarPlayerID.TOTAL_ROYAL_TITAN_KILLS);
		VARPS.put("Yama", VarPlayerID.TOTAL_YAMA_KILLS);
	}

	private BossKills()
	{
	}

	/** Every kill count above zero. Must run on the client thread. */
	static Map<String, Integer> read(Client client)
	{
		Map<String, Integer> kc = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : VARPS.entrySet())
		{
			int n = client.getVarpValue(e.getValue());
			if (n > 0 && n < 10_000_000)
			{
				kc.put(e.getKey(), n);
			}
		}
		return kc;
	}
}
