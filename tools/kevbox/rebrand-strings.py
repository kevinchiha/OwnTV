#!/usr/bin/env python3
"""Generate app-level string overrides that replace "OwnTV" with "KevBox Live".

KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).

Core ships the app's strings as a library, and an app resource overrides a library resource of
the same name and type. For every values* folder in Core's res at the pinned version, each
<string>, <plurals> and <string-array> whose text mentions OwnTV (and whose name is not in
brand-allowlist.txt) is copied into app/src/main/res/<folder>/kevbox_brand_strings.xml with the
brand swapped. Elements are copied as raw XML, so attributes, placeholders, escapes, every plural
quantity and every array item stay exactly as Core wrote them. In English folders "an OwnTV"
becomes "a KevBox Live"; in Hungarian folders "az OwnTV" becomes "a KevBox Live" (Hungarian uses
"az" only before a vowel sound).

A few strings tell the member to bring their own Xtream, M3U or Stalker source, which the locked
KevBox Live app does not allow. FIXED_TEXT replaces those in every folder where Core defines them,
whatever their language, with English text saying the admin sets the playlist up.

    python3 tools/kevbox/rebrand-strings.py           # regenerate the overrides
    python3 tools/kevbox/rebrand-strings.py --check   # exit 1 if regenerating would change a file

Never edit the generated files by hand; rerun this after a Core bump.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from core_res import core_pin, core_res_dir  # noqa: E402

BRAND = "OwnTV"
REPLACEMENT = "KevBox Live"
OUTPUT_NAME = "kevbox_brand_strings.xml"
REPO_ROOT = Path(__file__).resolve().parents[2]
APP_RES = REPO_ROOT / "app" / "src" / "main" / "res"
ALLOWLIST = Path(__file__).resolve().parent / "brand-allowlist.txt"

NAMESPACES = {
    "xliff": "urn:oasis:names:tc:xliff:document:1.2",
    "tools": "http://schemas.android.com/tools",
}

_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
# string-array goes before string so the alternation never stops at the shorter name.
_ELEMENT = re.compile(
    r"<(?P<tag>string-array|string|plurals)(?=[\s>/])(?P<attrs>[^>]*?)"
    r"(?:/>|>(?P<body>.*?)</(?P=tag)\s*>)",
    re.DOTALL,
)
_NAME = re.compile(r'\bname\s*=\s*"([^"]*)"')
_TAG = re.compile(r"(<[^>]*>)")


def load_allowlist(path: Path) -> set[str]:
    names = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        name = line.split("#", 1)[0].strip()
        if name:
            names.add(name)
    return names


def _text_parts(body: str) -> list[tuple[bool, str]]:
    """Split raw element content into (is_text, chunk) pairs; tags are never text."""
    return [(not part.startswith("<"), part) for part in _TAG.split(body) if part]


def _mentions_brand(body: str) -> bool:
    return any(is_text and BRAND in part for is_text, part in _text_parts(body))


# Strings whose meaning, not just their brand, is wrong for KevBox Live: Core tells the member to
# add their own source, but the admin assigns every playlist. Same English text in every locale.
FIXED_TEXT = {
    "setup_byo_source_description": "KevBox Live plays the playlist your admin sets up for you.",
    "setup_disclaimer": (
        "KevBox Live is a media player. It includes no channels or content of its own. "
        "Your playlist is set up by the admin, so there is no source for you to add."
    ),
}

# Indefinite article before the brand, per language, mapped to the article "KevBox Live" takes.
# "an OwnTV backup" would read "an KevBox Live backup", and Hungarian "az OwnTV" would read
# "az KevBox Live"; both languages need the short article before a K.
_ARTICLES = {
    "en": re.compile(r"\b([Aa])n " + BRAND),
    "hu": re.compile(r"\b([Aa])z " + BRAND),
}


def _swap(text: str, language: str) -> str:
    article = _ARTICLES.get(language)
    if article is not None:
        text = article.sub(lambda m: f"{m.group(1)} {REPLACEMENT}", text)
    return text.replace(BRAND, REPLACEMENT)


def _rebrand(body: str, language: str) -> str:
    return "".join(
        _swap(part, language) if is_text else part for is_text, part in _text_parts(body)
    )


def _language(folder: Path) -> str:
    """values/ is Core's en-US source; values-<lang>-* folders name their language first."""
    qualifiers = folder.name.split("-")[1:]
    return qualifiers[0] if qualifiers else "en"


def overrides_for_folder(folder: Path, allowlist: set[str]) -> list[tuple[str, str, str]]:
    """Return sorted (name, tag, xml) overrides for one Core values* folder."""
    found: dict[tuple[str, str], str] = {}
    language = _language(folder)
    for xml_file in sorted(folder.glob("*.xml")):
        source = _COMMENT.sub("", xml_file.read_text(encoding="utf-8"))
        for match in _ELEMENT.finditer(source):
            body = match.group("body")
            if body is None:
                continue
            name_match = _NAME.search(match.group("attrs"))
            if name_match is None:
                continue
            name = name_match.group(1)
            tag = match.group("tag")
            if tag == "string" and name in FIXED_TEXT:
                found[(name, tag)] = f"<{tag}{match.group('attrs')}>{FIXED_TEXT[name]}</{tag}>"
                continue
            if not _mentions_brand(body) or name in allowlist:
                continue
            found[(name, tag)] = f"<{tag}{match.group('attrs')}>{_rebrand(body, language)}</{tag}>"
    return [(name, tag, xml) for (name, tag), xml in sorted(found.items())]


def render(pin: str, overrides: list[tuple[str, str, str]]) -> str:
    elements = "".join(f"    {xml}\n" for _, _, xml in overrides)
    declarations = "".join(
        f' xmlns:{prefix}="{uri}"' for prefix, uri in NAMESPACES.items() if f"{prefix}:" in elements
    )
    header = (
        f"<!-- KevBox: generated by tools/kevbox/rebrand-strings.py from OwnTV Core core-{pin}. "
        "Do not edit; rerun after a Core bump. -->\n"
    )
    return f"{header}<resources{declarations}>\n{elements}</resources>\n"


def expected_files(core_res: Path, pin: str, allowlist: set[str]) -> dict[Path, str]:
    files = {}
    for folder in sorted(p for p in core_res.iterdir() if p.is_dir() and p.name.startswith("values")):
        overrides = overrides_for_folder(folder, allowlist)
        if overrides:
            files[APP_RES / folder.name / OUTPUT_NAME] = render(pin, overrides)
    return files


def existing_files() -> set[Path]:
    return set(APP_RES.glob(f"values*/{OUTPUT_NAME}"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if regenerating would change any file")
    args = parser.parse_args(argv)

    pin = core_pin(REPO_ROOT)
    expected = expected_files(core_res_dir(REPO_ROOT), pin, load_allowlist(ALLOWLIST))
    stale = sorted(existing_files() - expected.keys())
    changed = sorted(
        path
        for path, content in expected.items()
        if not path.is_file() or path.read_text(encoding="utf-8") != content
    )

    if args.check:
        for path in changed:
            print(f"out of date: {path.relative_to(REPO_ROOT)}")
        for path in stale:
            print(f"should not exist: {path.relative_to(REPO_ROOT)}")
        if changed or stale:
            print("Run python3 tools/kevbox/rebrand-strings.py to regenerate.")
            return 1
        print(f"{len(expected)} override files match OwnTV Core core-{pin}.")
        return 0

    for path in changed:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(expected[path], encoding="utf-8")
    for path in stale:
        path.unlink()
    print(
        f"OwnTV Core core-{pin}: {len(expected)} override files, "
        f"{len(changed)} written, {len(stale)} removed."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
