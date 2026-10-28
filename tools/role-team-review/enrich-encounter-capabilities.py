import json
import re
import sqlite3
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
REVIEW_DATA = HERE / "data.js"
ANDROID_DATA = ROOT / "android/app/src/main/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"
READABLE_SKILL_DB = ROOT / "pick/autopcr-android/app/src/main/python/cache/db/202604021043.db"

DOT_APPLICATION_RE = re.compile(
    r"(?:使|赋予)[^。；，\n]{0,48}(?:中毒|猛毒|毒咒|诅咒|烧伤|灼烧|绝怠灵度)"
)
SHIELD_TERMS = ("屏障", "伤害无效化", "攻击无效化", "吸收物理", "吸收魔法")
PUSH_TERMS = ("击飞", "吹飞")
PULL_TERMS = ("拉近", "拉拽", "拉扯", "拉至", "拉到")


def load_review_payload() -> dict:
    raw = REVIEW_DATA.read_text(encoding="utf-8").strip()
    prefix = "window.LABYRINTH_REVIEW_DATA = "
    if not raw.startswith(prefix):
        raise ValueError("unexpected role-team-review/data.js wrapper")
    return json.loads(raw[len(prefix):].rstrip(";"))


def positive_capabilities(skill_summary: list[str]) -> dict[str, float]:
    descriptions = [str(value) for value in skill_summary if str(value).strip()]
    text = "\n".join(descriptions)
    result: dict[str, float] = {}
    if any(
        DOT_APPLICATION_RE.search(description) and
        any(target in description for target in ("敌", "目标", "对象", "其"))
        for description in descriptions
    ):
        result["dot"] = 82.0
    if "挑衅" in text:
        result["taunt"] = 82.0
    # A self-only shield must not satisfy an encounter rule intended to protect other members.
    if any("我方" in description and any(term in description for term in SHIELD_TERMS)
           for description in descriptions):
        result["shield"] = 75.0
    # Burst is only a soft preference in the current strategy catalogue.
    if "伤害（特大）" in text or "伤害(特大)" in text:
        result["burstDamage"] = 82.0
    return result


def _wide_aoe_score(target_range: int) -> float:
    """Geometry score only; do not conflate range with damage throughput."""
    if target_range >= 1000:
        return 100.0
    if target_range >= 700:
        return 85.0
    if target_range >= 450:
        return 70.0
    if target_range >= 300:
        return 55.0
    return 0.0


def load_tactical_capabilities() -> dict[str, dict[str, float]]:
    """Extract enemy displacement and AOE geometry from the readable skill master.

    MOVE action_value_1 sign is only a fallback.  Some skills (notably 禊/未奏希（夏日）)
    use a positive internal MOVE parameter while the actual skill text explicitly says the
    farthest enemy is pulled to the caster.  Skill semantics therefore take precedence.
    """
    if not READABLE_SKILL_DB.is_file():
        return {}
    connection = sqlite3.connect(READABLE_SKILL_DB)
    connection.row_factory = sqlite3.Row
    try:
        skill_columns = [
            row[1]
            for row in connection.execute("PRAGMA table_info(unit_skill_data)")
            if row[1] != "unit_id"
        ]
        facts: dict[str, dict[str, float]] = {}
        for unit in connection.execute("SELECT unit_id FROM unit_data WHERE unit_id % 100 = 1"):
            unit_id = int(unit["unit_id"])
            skill_row = connection.execute(
                "SELECT * FROM unit_skill_data WHERE unit_id = ?", (unit_id,)
            ).fetchone()
            if not skill_row:
                continue
            skill_ids = sorted({
                int(skill_row[column])
                for column in skill_columns
                if skill_row[column]
            })
            if not skill_ids:
                continue
            placeholders = ",".join("?" for _ in skill_ids)
            skill_rows = list(connection.execute(
                f"SELECT * FROM skill_data WHERE skill_id IN ({placeholders})", skill_ids
            ))
            tactical: dict[str, float] = {}
            for skill in skill_rows:
                description = str(skill["description"] or "")
                has_pull_word = any(term in description for term in PULL_TERMS)
                has_push_word = any(term in description for term in PUSH_TERMS)
                is_backline_skill = any(term in description for term in ("最远", "最后排", "后方"))
                action_ids: list[int] = []
                for index in range(1, 11):
                    action_id = int(skill[f"action_{index}"] or 0)
                    if action_id > 0:
                        action_ids.append(action_id)
                if not action_ids:
                    continue
                action_placeholders = ",".join("?" for _ in action_ids)
                actions = list(connection.execute(
                    "SELECT action_id, action_type, target_assignment, target_count, target_range, "
                    "target_type, action_value_1, action_value_2, action_detail_1 FROM skill_action "
                    f"WHERE action_id IN ({action_placeholders})",
                    action_ids,
                ))
                for action in actions:
                    action_type = int(action["action_type"] or 0)
                    enemy_target = int(action["target_assignment"] or 0) == 1
                    if action_type == 3 and enemy_target:
                        distance = float(action["action_value_1"] or 0.0)
                        if has_pull_word:
                            tactical["enemyPull"] = 82.0
                        elif has_push_word:
                            tactical["enemyPush"] = 82.0
                        elif distance > 0.0:
                            tactical["enemyPush"] = 82.0
                        elif distance < 0.0:
                            tactical["enemyPull"] = 82.0
                    if action_type == 1 and enemy_target and int(action["target_count"] or 0) >= 2:
                        target_range = int(action["target_range"] or 0)
                        wide = _wide_aoe_score(target_range)
                        if wide > 0.0:
                            tactical["wideAoeCoverage"] = max(
                                tactical.get("wideAoeCoverage", 0.0), wide
                            )
                        if is_backline_skill and int(action["target_count"] or 0) >= 99:
                            tactical["backlineAoeCoverage"] = max(
                                tactical.get("backlineAoeCoverage", 0.0),
                                100.0 if target_range >= 100 else 80.0,
                            )
            if tactical:
                facts[str(unit_id // 100)] = tactical
        return facts
    finally:
        connection.close()


def main() -> None:
    review = load_review_payload()
    android = json.loads(ANDROID_DATA.read_text(encoding="utf-8"))
    expected_version = str(android["source"]["skillDatabaseVersion"])
    review_source = str(review.get("sources", {}).get("roleDatabase", ""))
    if expected_version not in review_source:
        raise RuntimeError(
            f"review snapshot does not match Android role source: expected {expected_version}, got {review_source}"
        )

    review_by_id = {str(character["id"]): character for character in review["characters"]}
    tactical = load_tactical_capabilities()
    counts = {
        "dot": 0,
        "taunt": 0,
        "shield": 0,
        "burstDamage": 0,
        "enemyPush": 0,
        "enemyPull": 0,
        "wideAoeCoverage": 0,
        "backlineAoeCoverage": 0,
    }
    enriched = 0
    for character in android["characters"]:
        source = review_by_id.get(str(character["characterId"]))
        if not source:
            continue
        skill_summary = source.get("skillSummary") or []
        positives = positive_capabilities(skill_summary)
        tactical_facts = tactical.get(str(character["characterId"]), {})
        positives.update(tactical_facts)
        functions = character.setdefault("functions", {})
        changed = False
        # DOT extraction is authoritative when skill text exists: remove stale false positives as
        # well as adding newly recognized wording such as 猛毒/赋予诅咒/绝怠灵度.
        if skill_summary and "dot" not in positives and "dot" in functions:
            functions.pop("dot", None)
            changed = True
        # Tactical extraction is authoritative for direction when MOVE rows are present.  Remove
        # stale sign-only classifications produced by older generators before applying the new facts.
        if "enemyPull" in tactical_facts or "enemyPush" in tactical_facts:
            for key in ("enemyPush", "enemyPull"):
                expected = tactical_facts.get(key)
                if expected is None and key in functions:
                    functions.pop(key, None)
                    changed = True
        for key, value in positives.items():
            # Geometry/movement facts come directly from the skill master and intentionally replace
            # older derived values. Other encounter facts remain fill-only.
            authoritative = key in {
                "dot", "enemyPush", "enemyPull", "wideAoeCoverage", "backlineAoeCoverage"
            }
            if authoritative or functions.get(key) is None:
                if functions.get(key) != value:
                    changed = True
                functions[key] = value
            if functions.get(key) is not None and float(functions[key]) > 0:
                counts[key] += 1
        if changed:
            enriched += 1

    ANDROID_DATA.write_text(
        json.dumps(android, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"sourceVersion": expected_version, "enrichedCharacters": enriched, **counts}, ensure_ascii=False))


if __name__ == "__main__":
    main()
