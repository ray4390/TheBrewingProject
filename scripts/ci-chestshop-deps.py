#!/usr/bin/env python3
"""Fetch only pinned real economy plugins for disposable ChestShop startup tests.

Never used in production. Both files remain inside the temporary CI working directory.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import urllib.request

AGENT = "RayCraft-TheBrewingProject-CI/1.0"
ESSENTIALS_SHA256 = "bda4685105977fca2e209820a9f0ad24275bd103390a03236f38e59bfdac58e6"
ESSENTIALS_URL = "https://github.com/EssentialsX/Essentials/releases/download/2.22.0/EssentialsX-2.22.0.jar"
VAULT_PROJECT = "https://api.modrinth.com/v2/project/vaultunlocked/version"


def download(url: str, output: pathlib.Path, sha256: str) -> None:
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": AGENT}), timeout=90) as response:
        data = response.read(12_000_000)
    if hashlib.sha256(data).hexdigest() != sha256:
        raise ValueError(f"Unexpected file checksum for {output.name}")
    output.write_bytes(data)
    print(f"Verified {output.name}, sha256={sha256}", flush=True)


def main() -> None:
    output = pathlib.Path(".").resolve()
    download(ESSENTIALS_URL, output / "EssentialsX-2.22.0.jar", ESSENTIALS_SHA256)
    with urllib.request.urlopen(urllib.request.Request(VAULT_PROJECT, headers={"User-Agent": AGENT}), timeout=30) as response:
        versions = json.load(response)
    matches = [v for v in versions if v.get("version_number") == "2.20.3"]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one VaultUnlocked 2.20.3 version, got {len(matches)}")
    primary = [f for f in matches[0]["files"] if f.get("primary") and f.get("filename", "").endswith(".jar")]
    if len(primary) != 1:
        raise ValueError("Ambiguous VaultUnlocked primary JAR")
    artifact = primary[0]
    url = artifact["url"]
    if not url.startswith(("https://cdn.modrinth.com/", "https://cdn.modrinth.com")):
        raise ValueError("Unexpected VaultUnlocked download hostname")
    sha256 = artifact["hashes"]["sha256"]
    if len(sha256) != 64:
        raise ValueError("Untrusted VaultUnlocked SHA256 length")
    download(url, output / "VaultUnlocked-2.20.3.jar", sha256)


if __name__ == "__main__":
    main()
