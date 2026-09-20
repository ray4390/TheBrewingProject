#!/usr/bin/env python3
"""Boot-test the exact RayCraft production target without a human test server.

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


PAPER_URL = "https://fill-data.papermc.io/v1/objects/9c95eb088b903d9ed96cd457838f3078f02407a965dc8ae36d2c4e551a04355a/paper-26.2-126.jar"
PAPER_SHA256 = "9c95eb088b903d9ed96cd457838f3078f02407a965dc8ae36d2c4e551a04355a"
LEGACY_BREW_JSON = '[{"type":"cook","brew_time":20,"cauldron_type":"brewery:lava","ingredients":{"minecraft:wheat":1}},{"type":"distill","runs":3},{"type":"age","age":20,"barrel_type":"brewery:acacia"}]'
IDENTITY_TRANSFORM = "[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0]"
ANSI_ESCAPE = re.compile(r"\x1b(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
READY = "Done ("
PLUGIN_READY = "Startup status: supported=true, recipes=29, structures=3"
RELOAD_OK = "Reloaded The Brewing Project!"
VERSION_DIAGNOSTIC = "RayCraft fork | target Minecraft 26.2 | detected Minecraft 26.2 | qualified=true"
UPSTREAM_RESOURCE_SHA256 = {
    "recipes.yml": "f1d095e419b36d3420549efd9378ca4f38b1f6325fe682dc2e4c2832538c1344",
    "incomplete-recipes.yml": "766c28932865b6bbe106688ccb91d33a38f1b3af2563beff9c5bbe1d291fb4c1",
    "barrel_types.yml": "2ac69422c31061862ddbb61908614730e7f51b912b611f05920bbc5b1e61477e",
    "structures.yml": "02f8aaf557fca64842e0598cacb58bb5f0dd72519da79d7b7709b0b91254a905",
    "named_drunk_events.json": "6f39effc2d3ce09182ef5fc2b89113dea3c4f8ed489663b414d7cba41e1a7ad4",
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


def download_paper(destination: pathlib.Path) -> None:
    print(f"Downloading Paper 26.2 build 126 to {destination}")
    request = urllib.request.Request(PAPER_URL, headers={"User-Agent": "RayCraft-TBP-integration-test/1"})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(destination)
    if actual != PAPER_SHA256:
        raise RuntimeError(f"Paper SHA-256 mismatch: expected {PAPER_SHA256}, got {actual}")


def assert_upstream_resources(jar: pathlib.Path, server_dir: pathlib.Path | None = None) -> None:
    with zipfile.ZipFile(jar) as plugin:
        for name, expected in UPSTREAM_RESOURCE_SHA256.items():
            actual = hashlib.sha256(plugin.read(name)).hexdigest()
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
    # Paper 26.2 stores each dimension UUID in its namespaced metadata saved
    # data instead of the legacy Bukkit uid.dat file.
    metadata = (server_dir / "world" / "dimensions" / "minecraft" / "overworld"
                / "data" / "paper" / "metadata.dat")
    data = gzip.decompress(metadata.read_bytes())
    marker = b"\x0b\x00\x04uuid\x00\x00\x00\x04"  # NBT int-array tag, length four
    offset = data.find(marker)
    if offset < 0 or len(data) < offset + len(marker) + 16:
        raise RuntimeError(f"Could not read Paper world UUID from {metadata}")
    return uuid.UUID(bytes=data[offset + len(marker):offset + len(marker) + 16])


def seed_upstream_v3_fixture(server_dir: pathlib.Path) -> dict[str, int]:
    database = server_dir / "plugins" / "TheBrewingProject" / "brewery.db"
    uid = world_uuid(server_dir).bytes
    far = 1_000_000  # Deliberately unloaded; loading must not force this chunk.
    with sqlite3.connect(database) as connection:
        version = connection.execute("SELECT version FROM version").fetchone()
        if version != (3,):
            raise RuntimeError(f"Expected upstream schema version 3, got {version}")
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
        player = uuid.UUID("12345678-1234-5678-1234-567812345678").bytes
        connection.execute("INSERT INTO drunk_states_v2 VALUES (?, ?, ?)", (player, -1, 0))
        connection.execute("INSERT INTO modifiers VALUES (?, ?, ?)", (player, "alcohol", 50.0))
        connection.execute("INSERT OR REPLACE INTO time VALUES (?, 0)", (12345,))
        connection.commit()
    return database_counts(database)


def database_counts(database: pathlib.Path) -> dict[str, int]:
    tables = ("barrels", "barrel_brews", "distilleries", "distillery_brews", "cauldrons", "drunk_states_v2", "modifiers")
    with sqlite3.connect(database) as connection:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()
        if integrity != ("ok",):
            raise RuntimeError(f"SQLite integrity check failed: {integrity}")
        return {table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in tables}


def assert_database_unchanged(server_dir: pathlib.Path, expected: dict[str, int]) -> None:
    database = server_dir / "plugins" / "TheBrewingProject" / "brewery.db"
    actual = database_counts(database)
    if actual != expected:
        raise RuntimeError(f"Persistence fixture changed unexpectedly: expected {expected}, got {actual}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=pathlib.Path, help="Already-built shaded TBP JAR (the exact release artifact)")
    parser.add_argument("--java", type=pathlib.Path, default=pathlib.Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    parser.add_argument("--paper-jar", type=pathlib.Path, help="Optional cached Paper 26.2 build 126 JAR")
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

    retained = pathlib.Path(tempfile.mkdtemp(prefix="raycraft-tbp-paper-26.2-"))
    active_server: PaperProcess | None = None
    try:
        paper_jar = retained / "paper-26.2-126.jar"
        if args.paper_jar:
            shutil.copy2(args.paper_jar.resolve(), paper_jar)
            if sha256(paper_jar) != PAPER_SHA256:
                raise RuntimeError("Provided Paper JAR is not Paper 26.2 build 126")
        else:
            download_paper(paper_jar)
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

        fixture = seed_upstream_v3_fixture(retained)
        print(f"Seeded upstream-v3 compatibility fixture: {fixture}")

        print("TEST 2 restart with same world/config/database and upstream fixture")
        active_server = boot(java, retained, paper_jar, "02-upgrade-restart")
        active_server.stop()
        active_server.assert_clean()
        active_server = None
        assert_database_unchanged(retained, fixture)

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
        assert_database_unchanged(retained, fixture)

        integration_mode = "with supplied optional integrations" if args.optional_plugin else "without optional integrations"
        print(f"TEST 4 final restart {integration_mode}")
        active_server = boot(java, retained, paper_jar, "04-final-restart")
        active_server.stop()
        active_server.assert_clean()
        active_server = None
        assert_database_unchanged(retained, fixture)

        print(f"PASS Paper 26.2 build 126 / Java 25 lifecycle suite; JAR SHA-256 {sha256(jar)}")
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
