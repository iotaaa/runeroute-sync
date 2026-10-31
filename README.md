# Rune Route Sync

Syncs your account to [runeroute.app](https://runeroute.app), which suggests what to do next: skill unlocks, quests you can start and bosses you're ready for.

Install it from the Plugin Hub and log in. Your stats, quests, kill counts and bank are sent about once a minute while you play, and your page appears at runeroute.app under your name. There's nothing to sign up for.

- Only your own account's data is sent
- Turn off **Send bank** in the plugin settings if you'd rather not share it

## What the plugin sends

- RuneLite's account hash (an ID that isn't shown publicly) and a random secret made by the plugin
- Username, combat level, world, quest points
- Every skill: real level, boosted level, XP
- Every quest's state (refreshed on login, after finishing a quest, and every 5 minutes)
- Boss and minigame kill counts, read from chat messages like "Your Zulrah kill count is: 12"
- Inventory and worn equipment
- Bank contents (after you open the bank)

It sends while you're logged in, every 60 seconds and a few seconds after a kill count message or opening your bank. The secret is stored in RuneLite's per-account settings, so it survives restarts and follows you between computers if you're signed in to a RuneLite account.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Website URL | set at build time (`-PsiteUrl`), `http://localhost:3000` by default | Where the site is running |
| Sync every | 60 s | How often to send while logged in |
| Send bank | on | Sends your bank after you open it |
| Send location | off | Sends your current tile |

## Development

The website lives in its own repo, [runeroute](https://github.com/iotaaa/runeroute). Run it locally first (see its README), then:

Needs a JDK (11 or newer; 17 or 21 recommended). The Gradle wrapper downloads Gradle and RuneLite itself.

```bash
./gradlew run
```

That starts a developer copy of RuneLite with **Rune Route** already loaded. Log in and your account appears on the website within about a minute. IntelliJ also works: open this folder and run `RuneRoutePluginTest`.

To hand a test copy to someone before the plugin is on the Plugin Hub, `./gradlew shadowJar -PsiteUrl=https://your-site` makes a single file in `build/libs/` with RuneLite and the plugin built in. They need Java 17 or newer and start it with `java -ea -jar rune-route-0.1.0-all.jar --developer-mode` (`--developer-mode` turns on the Jagex Accounts login in the sidebar).

If you use a Jagex Account, a development client needs a one-time setup to log in. Follow the "Using Jagex Accounts" guide on the RuneLite GitHub wiki.

## Getting onto the Plugin Hub

Once it's on the Plugin Hub, players install it inside normal RuneLite with a search and a click: no download, no Java, no settings. The official steps are in the [plugin-hub README](https://github.com/runelite/plugin-hub).

1. **Host the website first.** Plugin Hub players need a real address, not localhost.
2. **The hosted address is already the plugin's default.** The Plugin Hub builds plugins with its own standard `build.gradle`, so the `-PsiteUrl` build option doesn't apply there. Instead the plugin falls back to `https://runeroute.app` (in `BuildInfo.java`). Local builds still point at `http://localhost:3000`.
3. **This repo is already laid out for it:** `runelite-plugin.properties`, `build.gradle` and `LICENSE` sit at the top. Optionally add an `icon.png` no larger than 48x72 px.
4. **Submit it.** Fork [runelite/plugin-hub](https://github.com/runelite/plugin-hub), add a file `plugins/runeroute-sync` like the one below, and open a pull request. Plugins that send data to an outside server carry a `warning` line, which RuneLite shows before anyone installs it.
   ```
   repository=https://github.com/iotaaa/runeroute-sync.git
   commit=<full 40-character commit hash>
   warning=This plugin submits your IP address and account data to a server not controlled or verified by the RuneLite developers.
   ```
5. **Wait for review.** An automatic build runs on the pull request, then RuneLite's developers check the plugin isn't malicious, follows Jagex's third-party client rules and isn't a rejected feature. Sending only your own account's data is fine; collecting data about other players is not allowed. Each later update is a pull request that changes `commit=`.
