#!/usr/bin/env python3
"""
Helper for keeping ZigBrains in sync with the latest CLion / IntelliJ IDEA.

Stdlib only, so it runs the same on a GitHub runner and locally.

Subcommands:
  current   print the platform versions the build currently targets
  latest    ask the JetBrains release API for the newest CLion / IDEA
  check     compare "latest" with "current" (exit 0, sets update_needed)
  bump      rewrite gradle/libs.versions.toml and gradle.properties

Every subcommand prints JSON to stdout and, when running inside GitHub
Actions, also appends key=value pairs to $GITHUB_OUTPUT.

Examples (run from the repository root):
  .github/scripts/platform_versions.py current
  .github/scripts/platform_versions.py latest --channel eap
  .github/scripts/platform_versions.py check
  .github/scripts/platform_versions.py bump --clion 2026.3 --idea 2026.3 --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

RELEASES_API = "https://data.services.jetbrains.com/products/releases"
PLUGIN_LIST_API = "https://plugins.jetbrains.com/plugins/list"
NATIVE_DEBUG_PLUGIN_ID = "com.intellij.nativeDebug"

# JetBrains product codes: CLion, IntelliJ IDEA (unified distribution).
CLION_CODE = "CL"
IDEA_CODE = "IIU"

TOML_KEYS = {
    "clion": "intellij-clion",
    "idea": "intellij-idea",
    "native_debug": "intellij-nativeDebug",
}


# --------------------------------------------------------------------------- #
# File helpers
# --------------------------------------------------------------------------- #


def _toml_pattern(key: str) -> re.Pattern[str]:
    return re.compile(rf'^(?P<prefix>{re.escape(key)}\s*=\s*")(?P<value>[^"]*)(?P<suffix>".*)$', re.M)


def _props_pattern(key: str) -> re.Pattern[str]:
    return re.compile(rf"^(?P<prefix>{re.escape(key)}\s*=[ \t]*)(?P<value>[^\r\n]*)$", re.M)


def read_toml_value(text: str, key: str) -> str:
    m = _toml_pattern(key).search(text)
    if not m:
        raise SystemExit(f"error: '{key}' not found in libs.versions.toml")
    return m.group("value")


def replace_toml_value(text: str, key: str, value: str) -> str:
    read_toml_value(text, key)  # existence check
    return _toml_pattern(key).sub(lambda m: m.group("prefix") + value + m.group("suffix"), text, count=1)


def read_prop(text: str, key: str) -> str:
    m = _props_pattern(key).search(text)
    if not m:
        raise SystemExit(f"error: '{key}' not found in gradle.properties")
    return m.group("value").strip()


def replace_prop(text: str, key: str, value: str) -> str:
    read_prop(text, key)
    return _props_pattern(key).sub(lambda m: m.group("prefix") + value, text, count=1)


def branch_of(version_or_build: str) -> str:
    """'262.9437.65' -> '262', '2026.2.0.1' -> '262', '2025.3' -> '253'."""
    parts = version_or_build.split(".")
    if len(parts[0]) == 4 and len(parts) > 1:  # marketing version YYYY.N
        return f"{parts[0][2:]}{parts[1]}"
    return parts[0]


def version_key(v: str) -> tuple:
    """Sortable key; numeric components compare numerically, anything else (EAP-SNAPSHOT...) last."""
    out = []
    for p in re.split(r"[.\-]", v):
        out.append((0, int(p), "") if p.isdigit() else (1, 0, p))
    return tuple(out)


class Repo:
    def __init__(self, root: Path):
        self.root = root
        self.toml_path = root / "gradle" / "libs.versions.toml"
        self.props_path = root / "gradle.properties"

    def current(self) -> dict:
        toml = self.toml_path.read_text(encoding="utf-8")
        props = self.props_path.read_text(encoding="utf-8")
        return {
            "clion": read_toml_value(toml, TOML_KEYS["clion"]),
            "idea": read_toml_value(toml, TOML_KEYS["idea"]),
            "native_debug": read_toml_value(toml, TOML_KEYS["native_debug"]),
            "since_build": read_prop(props, "pluginSinceBuild"),
            "until_build": read_prop(props, "pluginUntilBuild"),
        }


# --------------------------------------------------------------------------- #
# JetBrains APIs
# --------------------------------------------------------------------------- #


def _http_get(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "zigbrains-platform-check"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def parse_releases(payload: dict, code: str) -> dict:
    """Pick the newest entry of `code` from a /products/releases response."""
    entries = payload.get(code) or []
    if not entries:
        raise SystemExit(f"error: JetBrains API returned no releases for {code}")
    newest = max(entries, key=lambda e: version_key(e.get("build", "0")))
    return {
        "version": newest["version"],
        "build": newest["build"],
        "type": newest.get("type", ""),
        "date": newest.get("date", ""),
    }


def fetch_latest(channel: str, fixture: Path | None = None) -> dict:
    if fixture is not None:
        payload = json.loads(fixture.read_text(encoding="utf-8"))
    else:
        query = urllib.parse.urlencode(
            {"code": f"{CLION_CODE},{IDEA_CODE}", "type": channel, "latest": "true"}
        )
        payload = json.loads(_http_get(f"{RELEASES_API}?{query}"))
    return {"clion": parse_releases(payload, CLION_CODE), "idea": parse_releases(payload, IDEA_CODE)}


def parse_plugin_list(xml_bytes: bytes, plugin_id: str) -> str | None:
    root = ET.fromstring(xml_bytes)
    versions = []
    for plugin in root.iter("idea-plugin"):
        pid = plugin.findtext("id")
        ver = plugin.findtext("version")
        if pid == plugin_id and ver:
            versions.append(ver.strip())
    return max(versions, key=version_key) if versions else None


def resolve_native_debug(idea_build: str, fixture: Path | None = None) -> tuple[str, str]:
    """
    Find the com.intellij.nativeDebug version compatible with the given IDEA build.

    Returns (version, source) where source is "marketplace" or "fallback".
    The plugin is published with the IDE build number as its version, so the
    IDEA build number itself is the fallback when the marketplace is unreachable.
    """
    try:
        if fixture is not None:
            data = fixture.read_bytes()
        else:
            query = urllib.parse.urlencode({"pluginId": NATIVE_DEBUG_PLUGIN_ID, "build": f"IU-{idea_build}"})
            data = _http_get(f"{PLUGIN_LIST_API}?{query}")
        found = parse_plugin_list(data, NATIVE_DEBUG_PLUGIN_ID)
        if found:
            return found, "marketplace"
    except (urllib.error.URLError, ET.ParseError, OSError) as e:
        print(f"warning: nativeDebug lookup failed ({e}); falling back to IDEA build", file=sys.stderr)
    return idea_build, "fallback"


# --------------------------------------------------------------------------- #
# Output
# --------------------------------------------------------------------------- #


def emit(result: dict, prefix: str = "") -> None:
    print(json.dumps(result, indent=2))
    out = os.environ.get("GITHUB_OUTPUT")
    if not out:
        return
    with open(out, "a", encoding="utf-8") as f:
        for key, value in _flatten(result, prefix):
            if isinstance(value, bool):
                value = str(value).lower()
            f.write(f"{key}={value}\n")


def _flatten(d: dict, prefix: str):
    for k, v in d.items():
        name = f"{prefix}{k}"
        if isinstance(v, dict):
            yield from _flatten(v, f"{name}_")
        elif isinstance(v, list):
            yield name, " ".join(map(str, v))
        else:
            yield name, "" if v is None else v


# --------------------------------------------------------------------------- #
# Commands
# --------------------------------------------------------------------------- #


def cmd_current(args) -> int:
    emit(Repo(args.root).current())
    return 0


def cmd_latest(args) -> int:
    emit(fetch_latest(args.channel, args.releases_fixture))
    return 0


def cmd_check(args) -> int:
    current = Repo(args.root).current()
    latest = fetch_latest(args.channel, args.releases_fixture)

    def newer(product: str) -> bool:
        return version_key(latest[product]["version"]) > version_key(current[product])

    outdated = [p for p in ("clion", "idea") if newer(p)]
    result = {
        "update_needed": bool(outdated),
        "outdated": outdated,
        "current": current,
        "latest": latest,
        "new_branch": branch_of(latest["clion"]["build"]) != current["since_build"],
    }
    emit(result)
    return 0


def cmd_bump(args) -> int:
    repo = Repo(args.root)
    before = repo.current()

    latest = None
    if "latest" in (args.clion, args.idea):
        latest = fetch_latest(args.channel, args.releases_fixture)
    field = "build" if args.prefer_build else "version"
    clion = latest["clion"][field] if args.clion == "latest" else args.clion
    idea = latest["idea"][field] if args.idea == "latest" else args.idea
    idea_build = args.idea_build or (latest["idea"]["build"] if latest and args.idea == "latest" else None)

    if args.native_debug in ("", "auto"):
        if idea_build is None:
            if re.fullmatch(r"\d{3}\.\d+(\.\d+)*", idea):
                idea_build = idea  # --idea was given as a build number
            else:
                idea_build = _lookup_build(idea, args)
        native_debug, nd_source = resolve_native_debug(idea_build, args.plugins_fixture)
    elif args.native_debug == "keep":
        native_debug, nd_source = before["native_debug"], "kept"
    else:
        native_debug, nd_source = args.native_debug, "input"

    if args.since_build in ("", "auto"):
        since_build = branch_of(idea)
    elif args.since_build == "keep":
        since_build = before["since_build"]
    else:
        since_build = args.since_build
    if not re.fullmatch(r"\d{3}(\.\d+)*", since_build):
        raise SystemExit(f"error: invalid sinceBuild '{since_build}'")

    toml = repo.toml_path.read_text(encoding="utf-8")
    toml = replace_toml_value(toml, TOML_KEYS["clion"], clion)
    toml = replace_toml_value(toml, TOML_KEYS["idea"], idea)
    toml = replace_toml_value(toml, TOML_KEYS["native_debug"], native_debug)
    props = repo.props_path.read_text(encoding="utf-8")
    props = replace_prop(props, "pluginSinceBuild", since_build)

    after = {**before, "clion": clion, "idea": idea, "native_debug": native_debug, "since_build": since_build}
    changed = after != before
    if changed and not args.dry_run:
        repo.toml_path.write_text(toml, encoding="utf-8")
        repo.props_path.write_text(props, encoding="utf-8")

    emit({
        "changed": changed,
        "dry_run": args.dry_run,
        "native_debug_source": nd_source,
        "branch_changed": after["since_build"] != before["since_build"],
        "before": before,
        "after": after,
    })
    return 0


def _lookup_build(version: str, args) -> str:
    """Resolve an IDEA marketing version (e.g. 2026.2.0.1) to its build number via the releases API."""
    if args.releases_fixture is not None:
        payload = json.loads(args.releases_fixture.read_text(encoding="utf-8"))
    else:
        query = urllib.parse.urlencode({"code": IDEA_CODE, "type": "release,eap,rc"})
        try:
            payload = json.loads(_http_get(f"{RELEASES_API}?{query}"))
        except (urllib.error.URLError, OSError) as e:
            raise SystemExit(f"error: cannot resolve IDEA {version} to a build number ({e}); pass --idea-build")
    for entry in payload.get(IDEA_CODE) or []:
        if entry.get("version") == version:
            return entry["build"]
    raise SystemExit(f"error: IDEA {version} not found in JetBrains releases; pass --idea-build or --native-debug")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--root", type=Path, default=Path.cwd(), help="repository root (default: cwd)")
    p.add_argument("--channel", default="release", choices=["release", "eap", "rc"],
                   help="JetBrains release channel to query (default: release)")
    p.add_argument("--releases-fixture", type=Path, help=argparse.SUPPRESS)
    p.add_argument("--plugins-fixture", type=Path, help=argparse.SUPPRESS)
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("current", help="print currently targeted versions").set_defaults(func=cmd_current)
    sub.add_parser("latest", help="print latest CLion / IDEA").set_defaults(func=cmd_latest)
    sub.add_parser("check", help="compare latest with current").set_defaults(func=cmd_check)

    b = sub.add_parser("bump", help="update libs.versions.toml and gradle.properties")
    b.add_argument("--clion", default="latest", help="CLion version, or 'latest' (default)")
    b.add_argument("--idea", default="latest", help="IntelliJ IDEA version, or 'latest' (default)")
    b.add_argument("--idea-build", default=None, help="IDEA build number, used to resolve nativeDebug")
    b.add_argument("--native-debug", default="auto",
                   help="com.intellij.nativeDebug version, 'auto' (marketplace lookup, default) or 'keep'")
    b.add_argument("--since-build", default="auto",
                   help="pluginSinceBuild, 'auto' (branch of the new platform, default) or 'keep'")
    b.add_argument("--prefer-build", action="store_true",
                   help="with 'latest', write build numbers instead of marketing versions (useful for EAP)")
    b.add_argument("--dry-run", action="store_true", help="do not write files")
    b.set_defaults(func=cmd_bump)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
