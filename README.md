# Rune Route Sync

Rune Route Sync links your account to [runeroute.app](https://www.runeroute.app), a free companion site that tells you what to do next in Old School RuneScape.

## What you get on runeroute.app

- **What next:** skill goals, quests you can start or are close to, and account milestones, ranked for your account
- **Overview:** your levels, total XP, quest points and an in-game style skills tab
- **Recommended bosses:** bosses your stats and quests are ready for, and what you're missing for the next ones
- **Bank:** a searchable copy of your bank, sortable by Grand Exchange value
- **Collection log:** every tab and page, with what you have and what's missing
- **Armoury:** a public page with your character in 3D, your gear and your progress, to share with friends at `runeroute.app/armoury/<your name>`

## Getting started

1. Install **Rune Route Sync** from the Plugin Hub and accept the warning
2. Log in to the game
3. Within about a minute, open [runeroute.app](https://www.runeroute.app) and pick your account

There's nothing to sign up for. Your page stays up to date while you play.

## What it sends

Only **your** account is sent, never other players. While you're logged in, the plugin syncs about once a minute, and sooner after things like a kill-count message, opening the bank or changing gear:

- Name, combat level, world, account type (normal or ironman mode) and quest points
- Skill levels and XP
- Quest progress
- Boss and minigame kill counts
- Worn gear and inventory, plus a picture and 3D model of your character when your look changes
- Bank contents, after you open the bank
- Collection log totals, and each page you open
- Your current location and what you're doing (idle, skilling, in combat, banking and so on)

To make sure only your client can update your page, it also sends RuneLite's account hash (never shown publicly) and a random key stored in your RuneLite settings.

RuneLite shows a warning on install because this data goes to runeroute.app, which isn't run or checked by the RuneLite developers.

## Turning things off

Every kind of data has its own switch in the plugin's settings, under **Data to send**. Turning one off stops sending it **and deletes it from the website**. Your page then shows that section as turned off.

| Setting | Default | What it sends |
|---|---|---|
| Skills | On | Levels and XP |
| Quests | On | Quest progress and quest points |
| Boss kill counts | On | Kill counts shown in chat |
| Gear | On | Equipped items, character picture and 3D model |
| Inventory | On | What you're carrying |
| Bank | On | Bank contents after you open it |
| Location | On | Your current tile |
| Activity | On | What you're doing right now |
| Online status | On | Online or last seen. Turning this off also hides Activity |
| Collection log | On | Log totals and pages you've opened |

Other settings:

| Setting | Default | What it does |
|---|---|---|
| Sync every | 60 s | How often a full update is sent while you're logged in |
| Website URL | `https://www.runeroute.app` | Leave this as it is unless you run your own copy of the site |

## Help and source

- Website: [runeroute.app](https://www.runeroute.app)
- Plugin source: [iotaaa/runeroute-sync](https://github.com/iotaaa/runeroute-sync)
- Problems or ideas: [open an issue](https://github.com/iotaaa/runeroute-sync/issues)

Rune Route is a fan project and isn't affiliated with Jagex. Item, skill and boss images come from the [Old School RuneScape Wiki](https://oldschool.runescape.wiki).

## License

BSD 2-Clause. See [LICENSE](LICENSE).
