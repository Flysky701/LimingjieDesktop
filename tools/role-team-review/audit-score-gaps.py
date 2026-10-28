"""List large combat-model vs real-user-score gaps for manual model auditing.

User scores are NOT training labels.  This report only finds suspicious disagreements so a
human can determine whether the cause is a parser/model bug, scenario dependence, relative
competition, or acquisition-value bias in the guide score.
"""

from __future__ import annotations

import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
DATA_PATH = HERE / "data.js"
PREFIX = "window.LABYRINTH_REVIEW_DATA = "


def load_data() -> dict:
    text = DATA_PATH.read_text(encoding="utf-8")
    if not text.startswith(PREFIX):
        raise RuntimeError("unexpected data.js prefix")
    return json.loads(text[len(PREFIX):].rstrip().removesuffix(";"))


def modeled(role: dict) -> bool:
    return str(role.get("combatModel", {}).get("modelStatus", "")).startswith("database-v")


def scenario_peak(role: dict, targets: int) -> tuple[str, float]:
    suffix = "1Target" if targets <= 1 else "2Target" if targets == 2 else "3Target"
    fields = {
        f"physicalDamage{suffix}": role.get(f"physicalDamage{suffix}", role.get("physicalDamage")),
        f"magicDamage{suffix}": role.get(f"magicDamage{suffix}", role.get("magicDamage")),
        "physicalSupport": role.get("physicalSupport"),
        "magicSupport": role.get("magicSupport"),
        "universalSupport": role.get("universalSupport"),
        "physicalDefenseDown": role.get("physicalDefenseDown"),
        "magicDefenseDown": role.get("magicDefenseDown"),
    }
    key, value = max(fields.items(), key=lambda item: float(item[1] or 0))
    return key, float(value or 0)


def rows_for_targets(roles: list[dict], targets: int) -> list[tuple]:
    rows = []
    for role in roles:
        user_score = role.get("referenceScore")
        if user_score is None or not modeled(role):
            continue
        metric, model_score = scenario_peak(role, targets)
        impact = role.get("combatModel", {}).get("supportImpact", {})
        system = "PHYSICAL" if float(role.get(f"physicalDamage{targets}Target") or 0) >= float(role.get(f"magicDamage{targets}Target") or 0) else "MAGIC"
        team_uplift = float(impact.get("physicalTeamUplift" if system == "PHYSICAL" else "magicTeamUplift") or 0)
        rows.append((model_score - float(user_score), role["id"], role["name"], float(user_score), metric, model_score, team_uplift))
    return sorted(rows, reverse=True)


def main() -> None:
    payload = load_data()
    roles = payload["characters"]
    for targets in (1, 2, 3):
        print(f"\n=== {targets if targets < 3 else '3+'} TARGET: MODEL HIGH / USER LOW ===")
        for gap, role_id, name, user, metric, model, uplift in rows_for_targets(roles, targets)[:20]:
            print(
                f"{gap:6.1f}  {role_id:>4}  {name:<20} user={user:5.1f} "
                f"model={model:5.1f} [{metric}] team_uplift={uplift:+.1%}"
            )

    print("\n=== LARGE NEGATIVE TEAM EFFECTS ===")
    penalties = []
    for role in roles:
        if not modeled(role):
            continue
        impact = role.get("combatModel", {}).get("supportImpact", {})
        physical = float(impact.get("physicalTeamUplift") or 0)
        magic = float(impact.get("magicTeamUplift") or 0)
        if min(physical, magic) < -0.05:
            penalties.append((min(physical, magic), role["id"], role["name"], physical, magic))
    for _, role_id, name, physical, magic in sorted(penalties):
        print(f"{role_id:>4}  {name:<20} physical={physical:+.1%} magic={magic:+.1%}")


if __name__ == "__main__":
    main()
