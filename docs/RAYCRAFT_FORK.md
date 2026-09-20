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

No PDC key, NamespacedKey, brew serialization version, or SQLite schema version
was changed for the Paper 26.2 port. Version-0 brew JSON remains readable.

## Release procedure

1. Fetch upstream and review commits not yet merged.
2. Run `./gradlew clean build --no-daemon` with Java 25.
3. Start a new disposable Paper 26.2 build 126 server with the shaded JAR.
4. Confirm startup reports `supported=true`, expected recipe/structure counts,
   and only the optional integrations actually installed.
5. Run `/tbp reload`, stop normally, restart, and inspect all three log phases.
6. Back up the production `plugins/TheBrewingProject` directory and world
   before deployment. Never test migrations first on the only production copy.
7. Copy the shaded JAR, retain the previous JAR and data backup for rollback,
   start the server, and repeat the log checks.
8. Tag the reviewed commit and attach the exact CI-produced JAR plus checksums
   to the release.

## Manual live-server acceptance matrix

MockBukkit and logic tests cannot prove client rendering, real chunk lifecycle,
inventory packet behavior, or interaction with third-party plugins. Before a
production promotion, verify:

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
- run with no resource pack, the RayCraft pack, a missing pack, and invalid
  color/model definitions;
- run with no optional plugins, then with RayCraft's exact PlaceholderAPI,
  Vault/economy provider, LuckPerms, Geyser, and Floodgate stack. TBP has no
  hard dependency on an economy provider and must not assume Essentials;
- corrupt one copied recipe/structure configuration intentionally and confirm
  diagnostics are actionable while unrelated valid content still loads.

Keep the completed checklist and server log with the release record. Any test
that changes stored data must run against a disposable copy, never the live
world or its only backup.
