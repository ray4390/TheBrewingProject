# RayCraft fork maintenance guide

## Purpose and support policy

This repository is RayCraft's production-maintained fork of
`BreweryTeam/TheBrewingProject`. It keeps the upstream project name, package
names, configuration keys, persistent-data keys, database schema, and Git
history so that upstream fixes can still be merged. Stability and data safety
take priority when an upstream-compatible choice is not safe for production.

The qualified production target is **Paper 26.2 build 126 (stable)** on
**Java 25**. Paper 26.3 and later releases are not supported until they have
been deliberately compiled, tested, and exercised on a temporary server. The
runtime compatibility entry point is
`bukkit-impl/src/main/java/dev/jsinco/brewery/bukkit/compat/PaperCompatibility.java`.
Add future version-specific adapters in that package instead of distributing
version parsing throughout gameplay code. Prefer API feature detection when a
stable capability check is available.

Folia remains declared in upstream metadata because the scheduler abstraction
already uses Paper's region APIs, but RayCraft's qualified deployment target is
a normal Paper server. Folia is not part of this fork's production acceptance
matrix.

## Clone and select Java

```bash
git clone https://github.com/ray4390/TheBrewingProject.git
cd TheBrewingProject
git remote add upstream https://github.com/BreweryTeam/TheBrewingProject.git
java -version
```

Install a Java 25 JDK (Temurin is suitable) and set `JAVA_HOME` to it. The
Gradle wrapper downloads the required Gradle version; do not require a global
Gradle installation.

## Build and test

From a clean checkout:

```bash
./gradlew clean build --no-daemon
```

The deployable shaded plugin is written under `bukkit-impl/build/libs/` and is
named `TheBrewingProject-<version>.jar`. The migration companion is under
`migration/build/libs/`; it is not the normal production plugin.

Useful focused commands are:

```bash
./gradlew test
./gradlew :bukkit-impl:test
./gradlew :bukkit-impl:shadowJar
./gradlew :bukkit-impl:runServer
```

CI uses Java 25, runs `clean build`, and uploads the production JAR. Tests cover
brew JSON and PDC round trips (including version-0 data), recipe scoring,
arbitrary multi-step sequences, brew reprocessing, structure matching,
commands, drunken text, metadata, SQLite shutdown draining, and registry
reload cleanup.

The CI job then tests that same shaded JAR (without rebuilding it) on Paper
26.2 build 126. To reproduce the lifecycle suite locally:

```bash
python3 scripts/test-paper-26.2.py \
  --jar bukkit-impl/build/libs/TheBrewingProject-<version>.jar \
  --java "$JAVA_HOME/bin/java"
```

The harness verifies Paper's pinned SHA-256, creates an isolated temporary
server, accepts the EULA only there, and performs fresh start/stop, restart,
five reloads, shutdown, and a final restart. It seeds an upstream schema-v3
fixture containing a version-0 brew in a barrel, distillery, and cauldron plus
a persisted drunken state. The structure coordinates are deliberately in an
unloaded distant chunk; row counts and SQLite integrity must remain unchanged.
It also checks the upstream `900a3f4d` default-resource hashes embedded in the
JAR, deterministic counts (29 recipes and 3 structures), `/tbp version`, and
fatal runtime log patterns. `--optional-plugin /path/to/plugin.jar` is
repeatable; CI runs a second lifecycle suite with PlaceholderAPI 2.12.3.

## Paper API updates

For a later Paper release:

1. Confirm the release is stable and note its required Java version in Paper's
   official documentation.
2. Update `minecraft.version` in `gradle.properties`, the `paper` coordinate in
   `gradle/libs.versions.toml`, `supportedPaperVersions`, `runServer`, and
   `apiVersion` in `bukkit-impl/build.gradle.kts`.
3. Update `PaperCompatibility` only after the version passes the full matrix.
4. Search for old version strings and review every result; do not bulk replace.
5. Run `./gradlew clean build --no-daemon` from an empty Gradle build output.
6. Launch a disposable server with the exact target Paper build and inspect
   startup, reload, normal shutdown, and restart logs.
7. Exercise the manual checks below with both old serialized brews and newly
   created brews before promoting the release.

The resource-pack HTTP compatibility headers are centralized on
`PaperCompatibility.TARGET_MINECRAFT_VERSION`. Item-component, particle,
sound, registry, scheduler, and PDC calls are the most likely Paper 26.3/27.x
review points.

## Upstream workflow

Keep changes thematic and avoid formatting-only rewrites around upstream code.

```bash
git fetch upstream
git checkout master
git merge --ff-only origin/master
git checkout -b raycraft/upstream-YYYY-MM-DD
git merge upstream/master
./gradlew clean build --no-daemon
```

Resolve conflicts by preserving upstream gameplay behavior unless the
RayCraft fix protects stability, compatibility, or stored data. Pay particular
attention to the compatibility package, `TheBrewingProject`, persistence
sessions, brew serialization, and the Gradle version catalog. Record retained
RayCraft behavior in this document and open a PR into the RayCraft primary
branch. Never rebase or force-push a shared production branch.

## Intentional RayCraft divergences

- Paper 26.2/Java 25 is the explicit production baseline.
- Paper compatibility and startup qualification are reported centrally.
- SQLite queues are drained and the pool/executor are closed on shutdown.
  Reload keeps the live database instance used by registered
  listeners, drains open inventories, and flushes writes before rebuilding
  in-memory registries.
- Database shutdown waits for dependent future stages queued behind an earlier
  flush barrier before closing Hikari.
- Barrel and distillery writes stay on the serialized database worker, and
  world unload closes open inventories before detaching their runtime objects.
- Recipe and persisted-world loads carry reload generations, preventing an
  older asynchronous load from publishing into a newer registry.
- Recipe-loaded event dispatch is always asynchronous, including when every
  ingredient future was already complete.
- Optional integrations are isolated: incompatible optional plugins are
  disabled with an actionable warning instead of aborting core brewing.
- Malformed custom structures are skipped individually; valid structures stay
  available. Unsafe distillery timing/inventory values are rejected.
- Structure detection refuses to inspect unloaded chunks and therefore does
  not force-load adjacent chunks.
- Database-loaded inventories are materialized and registered on the server
  thread only after their world is confirmed to still be loaded.
- Brew equality includes metadata so metadata-only changes are persistable.
- Cosmetic resource-pack inspection failures fall back to configured/default
  colors instead of blocking the plugin.
- Resource-pack HTTP handling follows redirects, has a ten-second request
  timeout, preserves interruption, and has no Netty/AWS runtime dependency.

No PDC key, NamespacedKey, brew serialization version, or SQLite schema version
was changed for the Paper 26.2 port. Version-0 brew JSON remains readable.

## Release procedure

1. Fetch upstream and review commits not yet merged.
2. Run `./gradlew clean build --no-daemon` with Java 25.
3. Run `scripts/test-paper-26.2.py` against the shaded JAR (CI performs this
   both without optional plugins and with PlaceholderAPI).
4. Confirm the harness reports all four lifecycles and all five reloads passed.
5. Back up the production `plugins/TheBrewingProject` directory and world
   before deployment. Never test migrations first on the only production copy.
6. Replace the old plugin JAR, retain it and the data backup for rollback,
   start the server, and repeat the log checks.
7. Tag the reviewed commit and attach the exact CI-produced JAR plus checksums
   to the release.

## Residual live observation matrix

MockBukkit, logic tests, and the automated real-Paper lifecycle suite cannot
drive a real player client. These are not a requirement for a second server;
after a normal backup and JAR replacement, observe these paths during ordinary
production use and retain the previous JAR/data backup for rollback:

- add/extract ingredients and brews from heated and unheated cauldrons; test
  invalid ingredients, quantities, under/over-cooking, mixing, and readdition;
- cook, mix, distill, and age in nontraditional multi-step orders, including
  removing/reinserting unfinished brews;
- create, break, reconstruct, overlap, unload, reload, and restart barrels and
  distilleries without item loss, duplication, or forced chunk loading;
- move unfinished/completed/sealed brews through inventories, drops, stacking,
  hoppers where supported, logout, death, shutdown, and restart;
- consume brews and verify alcohol/toxin/addiction modifiers, potion and command
  effects, delayed events, chat distortion, stumbling, vomiting, hallucination,
  hangover/withdrawal, death, logout/login, and restart persistence;
- run major player/admin commands with and without permissions and malformed
  arguments;
- observe normal item colors/models with RayCraft's existing resource pack;
- confirm the existing PlaceholderAPI, Vault/economy provider, LuckPerms,
  Geyser, and Floodgate stack remains enabled. TBP has no Vault integration or
  economy-provider dependency and does not assume Essentials.

Do not deliberately corrupt configuration or exercise destructive failure
scenarios on production. Those cases belong in the automated disposable
harness or a copied backup. Keep the first-start log with the release record.
