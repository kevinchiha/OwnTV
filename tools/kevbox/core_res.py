"""Locate OwnTV Core's Android resources at the version this app pins.

KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).

The app depends on Core as a Maven artifact, so Core's string resources are not in this repo.
These helpers read the pin from gradle/libs.versions.toml and fetch Core's source tarball for that
tag once into build/kevbox/ (ignored by git), so tools can read Core's res folder.
"""

from __future__ import annotations

import re
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path

CORE_TARBALL_URL = "https://github.com/ahXN00/OwnTV_Core/archive/refs/tags/core-{pin}.tar.gz"
_PIN_LINE = re.compile(r'^\s*owntvCore\s*=\s*"([^"]+)"\s*$', re.MULTILINE)


def core_pin(repo_root: Path) -> str:
    """Return the owntvCore version from gradle/libs.versions.toml."""
    toml = (Path(repo_root) / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
    match = _PIN_LINE.search(toml)
    if match is None:
        raise RuntimeError("owntvCore not found in gradle/libs.versions.toml")
    return match.group(1)


def core_res_dir(repo_root: Path) -> Path:
    """Return core/src/main/res of OwnTV_Core at tag core-<pin>.

    The tarball is downloaded and unpacked once into build/kevbox/core-<pin>/ and reused while the
    pin stays the same. A bumped pin gets its own folder.
    """
    repo_root = Path(repo_root)
    pin = core_pin(repo_root)
    target = repo_root / "build" / "kevbox" / f"core-{pin}"
    res = target / f"OwnTV_Core-core-{pin}" / "core" / "src" / "main" / "res"
    if res.is_dir():
        return res

    target.parent.mkdir(parents=True, exist_ok=True)
    # Unpack into a sibling temp folder and rename it into place, so an interrupted download never
    # leaves a half-filled folder that a later run would mistake for a complete one.
    staging = Path(tempfile.mkdtemp(prefix=f"core-{pin}-", dir=target.parent))
    try:
        archive = staging / "core.tar.gz"
        with urllib.request.urlopen(CORE_TARBALL_URL.format(pin=pin), timeout=120) as response:
            with archive.open("wb") as out:
                shutil.copyfileobj(response, out)
        unpacked = staging / "unpacked"
        with tarfile.open(archive, "r:gz") as tar:
            tar.extractall(unpacked, filter="data")
        if target.exists():
            shutil.rmtree(target)
        unpacked.rename(target)
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    if not res.is_dir():
        raise RuntimeError(f"Core tarball for core-{pin} has no core/src/main/res")
    return res
