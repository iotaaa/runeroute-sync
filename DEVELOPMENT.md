# Developing Rune Route Sync

Needs a JDK 11 or newer (17 or 21 recommended). The Gradle wrapper downloads Gradle and RuneLite.

```bash
./gradlew run
```

That starts a developer client with the plugin loaded, syncing to `https://www.runeroute.app`. To point at a local site instead:

```bash
./gradlew run -PsiteUrl=http://localhost:3000
```

Jagex Account login in a dev client: [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

The Plugin Hub builds with its own settings, so `-PsiteUrl` doesn't apply there; Hub builds always use `https://www.runeroute.app`. The Hub also rejects Gradle files with lines longer than 120 characters.

The website lives in [iotaaa/runeroute](https://github.com/iotaaa/runeroute). Keep `SyncPayload.java` in step with `shared/types/player.ts` there.

## Releasing an update

1. Push your changes to this repo
2. In your fork of [runelite/plugin-hub](https://github.com/runelite/plugin-hub), change the `commit=` line in `plugins/rune-route-sync` to the new commit hash (`git rev-parse HEAD`)
3. Open a pull request to `runelite/plugin-hub`
