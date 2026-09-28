#!/usr/bin/env python3
"""Boot-test the exact RayCraft Paper 26.3 qualified target without a human test server.

The fixture data is derived from upstream commit 900a3f4d:
* schema version 3: core/src/main/resources/database/sqlite/create_all_tables.sql
* legacy brew JSON: BrewSerializerTest.version0Conversion()

The same temporary world, configuration, and database are reused through every
lifecycle.  The tested plugin JAR is never rebuilt by this script.
"""

from __future__ import annotations

import argparse
import hashlib
import gzip
import json
import os
import pathlib
import queue
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
import zipfile


PAPER_VERSION = "26.3"
PAPER_BUILD = 134
PAPER_COMMIT = "643a11a"
PAPER_API_VERSION = "26.3.build.134-beta"
PAPER_BUILDS_API = f"https://fill.papermc.io/v3/projects/paper/versions/{PAPER_VERSION}/builds"
PAPER_USER_AGENT = "RayCraft-TBP-integration-test/2 (https://github.com/ray4390/TheBrewingProject)"
LEGACY_BREW_JSON = '[{"type":"cook","brew_time":20,"cauldron_type":"brewery:lava","ingredients":{"minecraft:wheat":1}},{"type":"distill","runs":3},{"type":"age","age":20,"barrel_type":"brewery:acacia"}]'
IDENTITY_TRANSFORM = "[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0]"
ANSI_ESCAPE = re.compile(r"\x1b(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
READY = "Done ("
PLUGIN_READY = "Startup status: supported=true, recipes=29, structures=3"
RELOAD_OK = "Reloaded The Brewing Project!"
VERSION_DIAGNOSTIC = "RayCraft fork | target Minecraft 26.3 | detected Minecraft 26.3 | qualified=true"
UPSTREAM_RESOURCE_SHA256 = {
    "recipes.yml": "885c85be28f730acf9dd99aec1df559986c852923933bc752209b2c6dc8d4482",
    "incomplete-recipes.yml": "d0307f817d848899ae7e6d971b137a37c873f822c9541e9e7c47ddf5265ffeb0",
    "barrel_types.yml": "e070fb54de39b30c966bf31c53bd7cef422a6d511725fb7bcaa99111034f49c8",
    "structures.yml": "b7070c12529516c1e352267c267650504111eeea626ca70450ae1a7e0d3e83e2",
    "named_drunk_events.json": "c0a7cf218b1336be661a259ee393ce5962effd4824fb7c0c292d44c656d0e25f",
}

FATAL_PATTERNS = tuple(re.compile(pattern, re.IGNORECASE) for pattern in (
    r"UnsupportedClassVersionError",
    r"NoSuchMethodError",
    r"NoClassDefFoundError",
    r"ClassNotFoundException",
    r"LinkageError",
    r"RejectedExecutionException",
    r"plugin failed to (?:enable|load)",
    r"error occurred while (?:enabling|disabling) TheBrewingProject",
    r"Could not load .*TheBrewingProject",
    r"(?:SQLException|database is closed|connection is closed).*(?:brewery|TheBrewingProject)",
    r"(?:brewery|TheBrewingProject).*(?:SQLException|database is closed|connection is closed)",
    r"AsyncCatcher|asynchronous .* (?:world|block|entity|inventory)",
    r"Exception.*(?:TheBrewingProject|dev\.jsinco\.brewery)",
))


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_paper_download() -> tuple[str, str, str]:
    request = urllib.request.Request(
        PAPER_BUILDS_API,
        headers={"User-Agent": PAPER_USER_AGENT, "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        builds = json.load(response)
    if not isinstance(builds, list):
        raise RuntimeError(f"Unexpected Fill builds response for Paper {PAPER_VERSION}: {type(builds).__name__}")

    matches = [build for build in builds if build.get("id") == PAPER_BUILD]
    if len(matches) != 1:
        raise RuntimeError(f"Fill did not return exactly one Paper {PAPER_VERSION} build {PAPER_BUILD}: {len(matches)} matches")

    build = matches[0]
    if str(build.get("channel", "")).upper() != "BETA":
        raise RuntimeError(
            f"Paper {PAPER_VERSION} build {PAPER_BUILD} is no longer reported as BETA: {build.get('channel')!r}"
        )

    download = build.get("downloads", {}).get("server:default")
    if not isinstance(download, dict):
        raise RuntimeError(f"Fill build {PAPER_BUILD} has no server:default download")
    url = download.get("url")
    name = download.get("name")
    expected_name = f"paper-{PAPER_VERSION}-{PAPER_BUILD}.jar"
    expected_sha = download.get("checksums", {}).get("sha256")
    if name != expected_name:
        raise RuntimeError(f"Unexpected Paper artifact name: expected {expected_name}, got {name!r}")
    if not isinstance(url, str) or not url.startswith("https://fill-data.papermc.io/"):
        raise RuntimeError(f"Unexpected Paper artifact URL from Fill: {url!r}")
    if not isinstance(expected_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
        raise RuntimeError(f"Unexpected Paper SHA-256 from Fill: {expected_sha!r}")

    print(f"Resolved Paper {PAPER_VERSION} build {PAPER_BUILD} ({PAPER_API_VERSION})")
    print(f"  artifact: {url}")
    print(f"  sha256:   {expected_sha}")
    return url, expected_sha, name


def download_paper(destination: pathlib.Path, url: str, expected_sha: str) -> None:
    print(f"Downloading Paper {PAPER_VERSION} build {PAPER_BUILD} to {destination}")
    request = urllib.request.Request(url, headers={"User-Agent": PAPER_USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(destination)
    if actual != expected_sha:
        raise RuntimeError(f"Paper SHA-256 mismatch: expected {expected_sha}, got {actual}")


def assert_upstream_resources(jar: pathlib.Path, server_dir: pathlib.Path | None = None) -> None:
    with zipfile.ZipFile(jar) as plugin:
        for name, expected in UPSTREAM_RESOURCE_SHA256.items():
            # Git stores these fixtures with LF. A Windows checkout may hand
            # Gradle CRLF resources, which are semantically identical and must
            # not make the cross-platform release test disagree with Linux CI.
            content = plugin.read(name).replace(b"\r\n", b"\n")
            actual = hashlib.sha256(content).hexdigest()
            if actual != expected:
                raise RuntimeError(f"Packaged {name} differs from upstream 900a3f4d: expected {expected}, got {actual}")
    if server_dir is not None:
        data = server_dir / "plugins" / "TheBrewingProject"
        for name in ("recipes.yml", "incomplete-recipes.yml"):
            generated = data / name
            if not generated.is_file() or generated.stat().st_size == 0:
                raise RuntimeError(f"Paper did not generate a usable {name}")


class PaperProcess:
    def __init__(self, java: pathlib.Path, server_dir: pathlib.Path, paper_jar: pathlib.Path, log_name: str):
        self.log_path = server_dir / log_name
        self.log = self.log_path.open("w", encoding="utf-8", newline="\n")
        self.process = subprocess.Popen(
            [str(java), "-Xms512M", "-Xmx1G", "-Dfile.encoding=UTF-8", "-jar", str(paper_jar), "--nogui"],
            cwd=server_dir,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        self.lines: list[str] = []
        self.events: queue.Queue[str] = queue.Queue()
        self.reader = threading.Thread(target=self._read, name="paper-log-reader", daemon=True)
        self.reader.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for raw in self.process.stdout:
            line = ANSI_ESCAPE.sub("", raw.rstrip("\r\n"))
            self.lines.append(line)
            self.log.write(line + "\n")
            self.log.flush()
            self.events.put(line)

    def send(self, command: str) -> None:
        if self.process.poll() is not None:
            raise RuntimeError(f"Paper exited before command {command!r}; see {self.log_path}")
        assert self.process.stdin is not None
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def wait_for(self, text: str, timeout: float, after: int = 0) -> int:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for index in range(after, len(self.lines)):
                if text in self.lines[index]:
                    return index + 1
            if self.process.poll() is not None:
                raise RuntimeError(f"Paper exited with {self.process.returncode} while waiting for {text!r}; see {self.log_path}")
            try:
                self.events.get(timeout=min(0.25, deadline - time.monotonic()))
            except queue.Empty:
                pass
        raise TimeoutError(f"Timed out waiting for {text!r}; see {self.log_path}")

    def wait_for_pattern(self, pattern: re.Pattern[str], timeout: float, after: int = 0) -> int:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for index in range(after, len(self.lines)):
                if pattern.search(self.lines[index]):
                    return index + 1
            if self.process.poll() is not None:
                raise RuntimeError(
                    f"Paper exited with {self.process.returncode} while waiting for {pattern.pattern!r}; see {self.log_path}"
                )
            try:
                self.events.get(timeout=min(0.25, deadline - time.monotonic()))
            except queue.Empty:
                pass
        raise TimeoutError(f"Timed out waiting for {pattern.pattern!r}; see {self.log_path}")

    def stop(self, timeout: float = 60) -> None:
        self.send("stop")
        try:
            return_code = self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.process.kill()
            raise TimeoutError(f"Paper did not stop within {timeout}s; killed process; see {self.log_path}")
        finally:
            self.reader.join(timeout=5)
            self.log.close()
        if return_code != 0:
            raise RuntimeError(f"Paper exited with {return_code}; see {self.log_path}")

    def assert_clean(self) -> None:
        for line in self.lines:
            if any(pattern.search(line) for pattern in FATAL_PATTERNS):
                raise RuntimeError(f"Fatal log pattern in {self.log_path}: {line}")


def boot(java: pathlib.Path, server_dir: pathlib.Path, paper_jar: pathlib.Path, name: str) -> PaperProcess:
    paper = PaperProcess(java, server_dir, paper_jar, f"integration-{name}.log")
    try:
        cursor = paper.wait_for(READY, 180)
        paper.wait_for(PLUGIN_READY, 60, max(0, cursor - 200))

        version_start = len(paper.lines)
        paper.send("version")
        paper_version_cursor = paper.wait_for_pattern(
            re.compile(re.escape(PAPER_COMMIT), re.IGNORECASE), 30, version_start
        )
        version_line = paper.lines[paper_version_cursor - 1]
        if PAPER_VERSION not in version_line or str(PAPER_BUILD) not in version_line:
            raise RuntimeError(
                f"Paper version output contains commit {PAPER_COMMIT} but not expected "
                f"version/build {PAPER_VERSION}/{PAPER_BUILD}: {version_line}"
            )

        paper.send("tbp version")
        paper.wait_for(VERSION_DIAGNOSTIC, 30)
        return paper
    except Exception:
        if paper.process.poll() is None:
            try:
                paper.stop(timeout=30)
            except Exception:
                paper.process.kill()
                paper.process.wait(timeout=10)
        raise


def world_uuid(server_dir: pathlib.Path) -> uuid.UUID:
    # Paper 26.3 stores each dimension UUID in its namespaced metadata saved
    # data instead of the legacy Bukkit uid.dat file.
    metadata = (server_dir / "world" / "dimensions" / "minecraft" / "overworld"
                / "data" / "paper" / "metadata.dat")
    data = gzip.decompress(metadata.read_bytes())
    marker = b"\x0b\x00\x04uuid\x00\x00\x00\x04"  # NBT int-array tag, length four
    offset = data.find(marker)
    if offset < 0 or len(data) < offset + len(marker) + 16:
        raise RuntimeError(f"Could not read Paper world UUID from {metadata}")
    return uuid.UUID(bytes=data[offset + len(marker):offset + len(marker) + 16])


STRUCTURAL_TABLES = ("barrels", "barrel_brews", "distilleries", "distillery_brews", "cauldrons")
ACTIVE_DRUNK_PLAYER = uuid.UUID("12345678-1234-5678-1234-567812345678")
ACTIVE_DRUNK_MODIFIER = "alcohol_addiction"
ACTIVE_DRUNK_VALUE = 100.0


def assert_sqlite_integrity(connection: sqlite3.Connection) -> None:
    integrity = connection.execute("PRAGMA integrity_check").fetchone()
    if integrity != ("ok",):
        raise RuntimeError(f"SQLite integrity check failed: {integrity}")


def structural_snapshot(connection: sqlite3.Connection) -> dict[str, tuple[tuple[object, ...], ...]]:
    snapshot: dict[str, tuple[tuple[object, ...], ...]] = {}
    for table in STRUCTURAL_TABLES:
        rows = tuple(connection.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall())
        if len(rows) != 1:
            raise RuntimeError(f"Expected exactly one fixture row in {table}, got {len(rows)}")
        snapshot[table] = rows
    return snapshot


def seed_upstream_v3_fixture(
    server_dir: pathlib.Path,
) -> tuple[dict[str, tuple[tuple[object, ...], ...]], int]:
    database = server_dir / "plugins" / "TheBrewingProject" / "brewery.db"
    uid = world_uuid(server_dir).bytes
    far = 1_000_000  # Deliberately unloaded; loading must not force this chunk.
    with sqlite3.connect(database) as connection:
        assert_sqlite_integrity(connection)
        version = connection.execute("SELECT version FROM version").fetchone()
        if version != (3,):
            raise RuntimeError(f"Expected upstream schema version 3, got {version}")
        current_time_row = connection.execute("SELECT time FROM time").fetchone()
        if current_time_row is None:
            raise RuntimeError("TBP internal time row is missing")
        current_time = int(current_time_row[0])

        connection.execute("INSERT INTO barrels VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           (far, 80, far, far, 81, far, uid, IDENTITY_TRANSFORM, "small_barrel", "brewery:oak", 9))
        connection.execute("INSERT INTO barrel_brews VALUES (?, ?, ?, ?, ?, ?)",
                           (far, 81, far, uid, 0, LEGACY_BREW_JSON))
        connection.execute("INSERT INTO distilleries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           (far + 32, 80, far, far + 32, 81, far, uid, IDENTITY_TRANSFORM, "bamboo_distillery", 0))
        connection.execute("INSERT INTO distillery_brews VALUES (?, ?, ?, ?, ?, ?, ?)",
                           (far + 32, 81, far, uid, 0, 0, LEGACY_BREW_JSON))
        connection.execute("INSERT INTO cauldrons VALUES (?, ?, ?, ?, ?, ?)",
                           (far + 64, 80, far, uid, LEGACY_BREW_JSON, "brewery:lava"))

        # Seed a genuinely active state at TBP's current internal clock. The
        # default alcohol_addiction modifier decays by one point per 10,000
        # brewery ticks, so value 100 cannot legitimately expire during this
        # suite's 30-minute CI timeout.
        player = ACTIVE_DRUNK_PLAYER.bytes
        connection.execute("INSERT INTO drunk_states_v2 VALUES (?, ?, ?)", (player, -1, current_time))
        connection.execute("INSERT INTO modifiers VALUES (?, ?, ?)",
                           (player, ACTIVE_DRUNK_MODIFIER, ACTIVE_DRUNK_VALUE))
        connection.commit()

        assert_sqlite_integrity(connection)
        return structural_snapshot(connection), current_time


def assert_fixture_persistence(
    server_dir: pathlib.Path,
    expected_structures: dict[str, tuple[tuple[object, ...], ...]],
    seeded_drunk_time: int,
) -> None:
    database = server_dir / "plugins" / "TheBrewingProject" / "brewery.db"
    with sqlite3.connect(database) as connection:
        assert_sqlite_integrity(connection)
        actual_structures = structural_snapshot(connection)
        if actual_structures != expected_structures:
            raise RuntimeError(
                f"Brewery structural persistence changed unexpectedly: "
                f"expected {expected_structures}, got {actual_structures}"
            )

        player = ACTIVE_DRUNK_PLAYER.bytes
        state = connection.execute(
            "SELECT kicked_timestamp, time_stamp FROM drunk_states_v2 WHERE player_uuid = ?", (player,)
        ).fetchone()
        modifier = connection.execute(
            "SELECT value FROM modifiers WHERE player_uuid = ? AND modifier_name = ?",
            (player, ACTIVE_DRUNK_MODIFIER),
        ).fetchone()
        state_count = connection.execute("SELECT COUNT(*) FROM drunk_states_v2").fetchone()[0]
        modifier_count = connection.execute("SELECT COUNT(*) FROM modifiers").fetchone()[0]
        current_time_row = connection.execute("SELECT time FROM time").fetchone()

        if state_count != 1 or modifier_count != 1 or state is None or modifier is None:
            raise RuntimeError(
                f"Active drunk-state fixture did not persist: states={state_count}, modifiers={modifier_count}, "
                f"state={state}, modifier={modifier}"
            )
        if state[0] != -1:
            raise RuntimeError(f"Active drunk-state fixture unexpectedly changed kicked_timestamp: {state[0]}")
        if state[1] < seeded_drunk_time:
            raise RuntimeError(
                f"Active drunk-state timestamp moved backwards: seed={seeded_drunk_time}, actual={state[1]}"
            )
        value = float(modifier[0])
        if not (0.0 < value <= ACTIVE_DRUNK_VALUE):
            raise RuntimeError(
                f"Active {ACTIVE_DRUNK_MODIFIER} fixture is no longer semantically active: {value}"
            )
        if current_time_row is None or state[1] > int(current_time_row[0]):
            raise RuntimeError(
                f"Active drunk-state timestamp is ahead of TBP clock: state={state[1]}, time={current_time_row}"
            )

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=pathlib.Path, help="Already-built shaded TBP JAR (the exact release artifact)")
    parser.add_argument("--java", type=pathlib.Path, default=pathlib.Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    parser.add_argument("--paper-jar", type=pathlib.Path, help="Optional cached Paper 26.3 build 134 JAR")
    parser.add_argument("--optional-plugin", type=pathlib.Path, action="append", default=[],
                        help="Optional integration plugin JAR to copy into the disposable server (repeatable)")
    parser.add_argument("--keep", action="store_true", help="Keep the disposable server directory")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    jar = args.jar.resolve()
    java = args.java.resolve()
    if not jar.is_file() or not java.is_file():
        raise FileNotFoundError(f"Missing JAR or Java executable: jar={jar}, java={java}")
    assert_upstream_resources(jar)

    retained = pathlib.Path(tempfile.mkdtemp(prefix="raycraft-tbp-paper-26.3-"))
    active_server: PaperProcess | None = None
    try:
        paper_url, paper_sha256, paper_name = resolve_paper_download()
        paper_jar = retained / paper_name
        if args.paper_jar:
            shutil.copy2(args.paper_jar.resolve(), paper_jar)
            actual_sha = sha256(paper_jar)
            if actual_sha != paper_sha256:
                raise RuntimeError(
                    f"Provided Paper JAR is not exact Paper {PAPER_VERSION} build {PAPER_BUILD}: "
                    f"expected {paper_sha256}, got {actual_sha}"
                )
        else:
            download_paper(paper_jar, paper_url, paper_sha256)
        (retained / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (retained / "plugins").mkdir()
        shutil.copy2(jar, retained / "plugins" / jar.name)
        for optional_plugin in args.optional_plugin:
            optional_plugin = optional_plugin.resolve()
            if not optional_plugin.is_file():
                raise FileNotFoundError(f"Missing optional plugin JAR: {optional_plugin}")
            shutil.copy2(optional_plugin, retained / "plugins" / optional_plugin.name)

        print(f"TEST 1 fresh start/stop in {retained}")
        active_server = boot(java, retained, paper_jar, "01-fresh")
        active_server.stop()
        active_server.assert_clean()
        active_server = None
        assert_upstream_resources(jar, retained)

        structural_fixture, drunk_seed_time = seed_upstream_v3_fixture(retained)
        print(
            f"Seeded upstream-v3 compatibility fixture: structural tables={list(structural_fixture)}, "
            f"active {ACTIVE_DRUNK_MODIFIER} at TBP time {drunk_seed_time}"
        )

        print("TEST 2 restart with same world/config/database and upstream fixture")
        active_server = boot(java, retained, paper_jar, "02-upgrade-restart")
        active_server.stop()
        active_server.assert_clean()
        active_server = None
        assert_fixture_persistence(retained, structural_fixture, drunk_seed_time)

        print("TEST 3 five reloads, clean stop, and persistence check")
        active_server = boot(java, retained, paper_jar, "03-reload-stress")
        for reload_number in range(1, 6):
            command_start = len(active_server.lines)
            active_server.send("tbp reload")
            active_server.wait_for(RELOAD_OK, 60, command_start)
            active_server.wait_for(PLUGIN_READY, 60, command_start)
            print(f"  reload {reload_number}/5 passed")
        active_server.stop()
        active_server.assert_clean()
        active_server = None
        assert_fixture_persistence(retained, structural_fixture, drunk_seed_time)

        integration_mode = "with supplied optional integrations" if args.optional_plugin else "without optional integrations"
        print(f"TEST 4 final restart {integration_mode}")
        active_server = boot(java, retained, paper_jar, "04-final-restart")
        active_server.stop()
        active_server.assert_clean()
        active_server = None
        assert_fixture_persistence(retained, structural_fixture, drunk_seed_time)

        print(
            f"PASS Paper {PAPER_VERSION} build {PAPER_BUILD} / {PAPER_COMMIT} / Java 25 lifecycle suite; "
            f"JAR SHA-256 {sha256(jar)}"
        )
        return 0
    finally:
        if active_server is not None and active_server.process.poll() is None:
            try:
                active_server.stop(timeout=30)
            except Exception:
                active_server.process.kill()
                active_server.process.wait(timeout=10)
        if args.keep:
            print(f"Kept disposable server: {retained}")
        else:
            shutil.rmtree(retained, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exception:
        print(f"FAIL: {exception}", file=sys.stderr)
        raise
