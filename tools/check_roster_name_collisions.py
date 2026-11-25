#!/usr/bin/env python3
"""Report exact identity-name collisions in the CN character roster."""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ROSTER = ROOT / "desktop/core/src/main/resources/assets/resource-packs/cn-bilibili/characters.landosolroster.json"
ALLOWED_COLLISIONS = {
    # Two separate roster/database entities genuinely share this display name.
    "涅比亚": {"1186", "1909"},
}


def normalize(value: str) -> str:
    return value.strip().casefold()


def main() -> None:
    document = json.loads(ROSTER.read_text(encoding="utf-8"))
    owners: dict[str, list[tuple[str, str, str]]] = defaultdict(list)
    for character in document["characters"]:
        role_id = str(character.get("id", ""))
        canonical = str(character.get("name", ""))
        for kind, value in [
            ("name", canonical),
            ("officialName", str(character.get("officialName", ""))),
        ]:
            if value.strip():
                owners[normalize(value)].append((role_id, canonical, kind))

    collisions = {
        key: values
        for key, values in owners.items()
        if len({role_id for role_id, _, _ in values}) > 1
        and {role_id for role_id, _, _ in values} != ALLOWED_COLLISIONS.get(key, set())
    }
    if collisions:
        for key, values in sorted(collisions.items()):
            print(key, values)
        raise SystemExit(f"found {len(collisions)} exact name collisions")
    print("no canonical/officialName collisions")


if __name__ == "__main__":
    main()
