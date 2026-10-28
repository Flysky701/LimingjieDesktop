import argparse
import json
import hashlib
import re
import sqlite3
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from combat_model import analyze_roster


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ASSETS = ROOT / "android/app/src/main/assets/resource-packs/cn-bilibili"
ANDROID_DECISION_PATH = ASSETS / "labyrinth-role-decision.json"
CATALOG_PATH = ASSETS / "characters.landosolroster.json"
ICONS_PATH = ASSETS / "icons.json"
TIER_SUMMARY_PATH = ROOT / "攻略/tierlist_91x91_output/summary.json"
STRENGTH_XLSX_PATH = ROOT / "攻略/公主连结角色黎明界强度表无图片.xlsx"
LEGACY_ROLE_DB_PATH = ROOT / "pick/autopcr-android/app/src/main/python/cache/db/202604021043.db"
ROLE_DB_CACHE_DIR = HERE / "cache"
ROLE_DB_PATH = ROLE_DB_CACHE_DIR / "master_cn.db"
ROLE_DB_META_PATH = ROLE_DB_CACHE_DIR / "master_cn.version.json"
VERSION_URL = "https://raw.githubusercontent.com/Expugn/priconne-database/refs/heads/master/version.json"
DATABASE_URL = "https://raw.githubusercontent.com/Expugn/priconne-database/master/master_cn.db"

MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

TIER_SCORE = {"SS": 6, "S": 5, "A": 4, "B": 3, "C": 2, "D": 1}
ATTRIBUTE_MAP = {"火": "FIRE", "水": "WATER", "风": "WIND", "光": "LIGHT", "暗": "DARK"}
USER_RATING_COLUMNS = {
    12: "浙江刘德华",
    13: "巴托鲁",
    14: "逆理之裁",
    15: "gozen",
    16: "neko",
}


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

# External/live-verified position facts used only when the current database source has no
# unit_data row.  Keep these separate from database facts so the review page never presents a
# manual/source-verified correction as raw master data.
EXTERNAL_ROLE_CALIBRATIONS = {
    "1351": {
        "atkType": 1,
        "position": 143,
        "source": "external-verified: 夏日雪菲攻略资料明确标注光/物理/前卫与索敌距离143；用户现场站位亦确认其位于智之前",
    },
}


def load_labyrinth_event_catalog() -> list[dict]:
    """Read the current readable CN Dawn Realm event catalog.

    The review UI should not hard-code the Japanese wiki summary.  The bundled readable
    master already contains the localized event title, option text, option description and
    option unlock condition, so expose those rows directly to data.js and keep the decision
    policy separate from the source facts.
    """
    if not LEGACY_ROLE_DB_PATH.is_file():
        return []
    connection = sqlite3.connect(LEGACY_ROLE_DB_PATH)
    connection.row_factory = sqlite3.Row
    try:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        if not {"labyrinth_event", "labyrinth_event_choice"}.issubset(tables):
            return []
        events = []
        for event in connection.execute(
            "SELECT event_id, event_text, choice_id_1, choice_id_2, choice_id_3 "
            "FROM labyrinth_event ORDER BY event_id"
        ):
            choices = []
            for slot in (1, 2, 3):
                choice_id = int(event[f"choice_id_{slot}"] or 0)
                if choice_id <= 0:
                    continue
                choice = connection.execute(
                    "SELECT choice_id, unit_comment, description, condition_type, condition_value "
                    "FROM labyrinth_event_choice WHERE choice_id = ?",
                    (choice_id,),
                ).fetchone()
                if not choice:
                    continue
                choices.append({
                    "slot": slot,
                    "choiceId": int(choice["choice_id"]),
                    "label": str(choice["unit_comment"] or "").replace("\\n", " ").strip(),
                    "description": str(choice["description"] or "").replace("\\n", " ").strip(),
                    "conditionType": int(choice["condition_type"] or 0),
                    "conditionValue": int(choice["condition_value"] or 0),
                })
            events.append({
                "eventId": int(event["event_id"]),
                "text": str(event["event_text"] or "").replace("\\n", " ").strip(),
                "choices": choices,
            })
        return events
    finally:
        connection.close()

# The resource catalog also contains member projection entries used by some UI/recognition
# paths.  In battle these are single combined units with their own canonical unit id.  The
# review/decision layer must expose only the canonical unit, while still accepting member ids
# and member names as aliases.
COMBINED_ROLE_GROUPS = {
    "1807": ["1183", "1184"],                    # 初音&栞
    "1808": ["1204", "1205", "1206"],          # 禊&美美&镜华
    "1809": ["1217", "1218"],                    # 秋乃&咲恋
    "1810": ["1243", "1244"],                    # 安&古蕾娅
    "1811": ["1281", "1282"],                    # 静流&璃乃
}
COMBINED_MEMBER_TO_CANONICAL = {
    member_id: canonical_id
    for canonical_id, member_ids in COMBINED_ROLE_GROUPS.items()
    for member_id in member_ids
}

UNIT_ID_ANCHORS = {
    100101, 100301, 101101, 102301, 105901, 107501,
    108801, 108901, 109101, 117101, 122501, 124201,
}
SKILL_ID_RANGE = range(1_000_000, 100_000_000)


def quote_identifier(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_]{1,128}", value):
        raise ValueError(f"unsafe sqlite identifier: {value}")
    return f'"{value}"'


def sqlite_schemas(connection: sqlite3.Connection) -> list[dict]:
    output = []
    table_names = [
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]
    for table_name in table_names:
        columns = []
        for row in connection.execute(f"PRAGMA table_info({quote_identifier(table_name)})"):
            columns.append({"name": row[1], "type": str(row[2] or "").upper(), "pk": int(row[5] or 0)})
        if columns:
            output.append({"name": table_name, "columns": columns})
    return output


def single_integer_pk(schema: dict) -> str | None:
    primary = sorted((column for column in schema["columns"] if column["pk"] > 0), key=lambda column: column["pk"])
    if len(primary) == 1 and primary[0]["type"] == "INTEGER":
        return primary[0]["name"]
    return None


def primary_key_hits(connection: sqlite3.Connection, schema: dict, values: set[int]) -> int:
    pk = single_integer_pk(schema)
    if not pk or not values:
        return 0
    table = quote_identifier(schema["name"])
    column = quote_identifier(pk)
    values = set(values)
    hits = 0
    for chunk_start in range(0, len(values), 300):
        chunk = list(values)[chunk_start:chunk_start + 300]
        placeholders = ",".join("?" for _ in chunk)
        hits += len(connection.execute(
            f"SELECT DISTINCT {column} FROM {table} WHERE {column} IN ({placeholders})",
            chunk,
        ).fetchall())
    return hits


def resolve_unit_tables(connection: sqlite3.Connection) -> tuple[dict, dict, dict]:
    schemas = sqlite_schemas(connection)

    def counts(schema: dict) -> tuple[int, int, int]:
        return (
            sum(column["type"] == "INTEGER" for column in schema["columns"]),
            sum(column["type"] == "TEXT" for column in schema["columns"]),
            sum(column["type"] == "REAL" for column in schema["columns"]),
        )

    unit_candidates = []
    for schema in schemas:
        integer_count, text_count, real_count = counts(schema)
        if (
            single_integer_pk(schema)
            and integer_count >= 10
            and 2 <= text_count <= 10
            and 15 <= len(schema["columns"]) <= 45
        ):
            hits = primary_key_hits(connection, schema, UNIT_ID_ANCHORS)
            if hits >= 8:
                unit_candidates.append((hits * 100 + integer_count + text_count * 2 + real_count, schema))
    if not unit_candidates:
        raise RuntimeError("cannot resolve obfuscated unit_data")
    unit_schema = max(unit_candidates, key=lambda item: item[0])[1]

    unit_skill_candidates = []
    for schema in schemas:
        integer_count, text_count, real_count = counts(schema)
        if single_integer_pk(schema) and integer_count >= 25 and text_count == 0 and real_count == 0:
            hits = primary_key_hits(connection, schema, UNIT_ID_ANCHORS)
            if hits >= 8:
                unit_skill_candidates.append((hits * 100 + min(integer_count, 50), schema))
    if not unit_skill_candidates:
        raise RuntimeError("cannot resolve obfuscated unit_skill_data")
    unit_skill_schema = max(unit_skill_candidates, key=lambda item: item[0])[1]

    unit_skill_pk = single_integer_pk(unit_skill_schema)
    skill_ids = set()
    integer_columns = [column["name"] for column in unit_skill_schema["columns"] if column["type"] == "INTEGER"]
    projection = ",".join(quote_identifier(column) for column in integer_columns)
    table = quote_identifier(unit_skill_schema["name"])
    pk_column = quote_identifier(unit_skill_pk)
    placeholders = ",".join("?" for _ in UNIT_ID_ANCHORS)
    for row in connection.execute(
        f"SELECT {projection} FROM {table} WHERE {pk_column} IN ({placeholders})",
        list(UNIT_ID_ANCHORS),
    ):
        for value in row:
            if isinstance(value, int) and value in SKILL_ID_RANGE:
                skill_ids.add(value)

    skill_candidates = []
    for schema in schemas:
        integer_count, text_count, _ = counts(schema)
        if (
            single_integer_pk(schema)
            and integer_count >= 10
            and text_count >= 2
            and 15 <= len(schema["columns"]) <= 50
        ):
            hits = primary_key_hits(connection, schema, skill_ids)
            skill_candidates.append((hits, schema))
    skill_candidates.sort(key=lambda item: item[0], reverse=True)
    if not skill_candidates or skill_candidates[0][0] < max(8, int(len(skill_ids) * 0.8)):
        raise RuntimeError("cannot resolve obfuscated skill_data")
    skill_schema = skill_candidates[0][1]
    return unit_schema, unit_skill_schema, skill_schema


def legacy_unit_reference() -> dict[int, tuple[int, int]]:
    if not LEGACY_ROLE_DB_PATH.is_file():
        return {}
    connection = sqlite3.connect(LEGACY_ROLE_DB_PATH)
    try:
        return {
            int(unit_id): (int(atk_type), int(position))
            for unit_id, atk_type, position in connection.execute(
                "SELECT unit_id, atk_type, search_area_width FROM unit_data"
            )
        }
    finally:
        connection.close()


def legacy_database_facts(catalog_ids: list[str]) -> dict[str, dict]:
    """Readable DB fallback for roles missing from the current public obfuscated master.

    Current-master facts always win.  This fallback is kept visibly separate so the review
    page never presents an older position/attack type as a current-master fact.
    """
    if not LEGACY_ROLE_DB_PATH.is_file():
        return {}
    connection = sqlite3.connect(LEGACY_ROLE_DB_PATH)
    connection.row_factory = sqlite3.Row
    try:
        skill_columns = [row[1] for row in connection.execute("PRAGMA table_info(unit_skill_data)") if row[1] != "unit_id"]
        output = {}
        for short_id in catalog_ids:
            unit_id = int(short_id) * 100 + 1
            unit = connection.execute(
                "SELECT unit_name, atk_type, search_area_width FROM unit_data WHERE unit_id=?",
                (unit_id,),
            ).fetchone()
            skill_row = connection.execute("SELECT * FROM unit_skill_data WHERE unit_id=?", (unit_id,)).fetchone()
            descriptions = []
            if skill_row:
                skill_ids = sorted({int(skill_row[column]) for column in skill_columns if skill_row[column]})
                if skill_ids:
                    placeholders = ",".join("?" for _ in skill_ids)
                    descriptions = [
                        row[0]
                        for row in connection.execute(
                            f"SELECT description FROM skill_data WHERE skill_id IN ({placeholders}) ORDER BY skill_id",
                            skill_ids,
                        )
                        if row[0]
                    ]
            if unit is None and not descriptions:
                continue
            output[short_id] = {
                "dbName": unit["unit_name"] if unit else None,
                "atkType": int(unit["atk_type"]) if unit else None,
                "position": int(unit["search_area_width"]) if unit else None,
                "descriptions": descriptions,
                "attackTypeSource": "legacy-master.unit_data" if unit else "skill-description-inferred",
                "positionSource": "legacy-master.unit_data" if unit else "missing",
            }
        return output
    finally:
        connection.close()


def resolve_unit_fact_columns(
    connection: sqlite3.Connection,
    unit_schema: dict,
) -> tuple[str, str]:
    """Resolve atk_type/search_area_width by matching unchanged values against the older plain schema."""
    reference = legacy_unit_reference()
    if not reference:
        raise RuntimeError("legacy unit_data reference is required to resolve hashed unit columns")
    pk = single_integer_pk(unit_schema)
    table = quote_identifier(unit_schema["name"])
    pk_column = quote_identifier(pk)
    sample_ids = list(reference.keys())[:500]
    placeholders = ",".join("?" for _ in sample_ids)
    integer_columns = [
        column["name"]
        for column in unit_schema["columns"]
        if column["type"] == "INTEGER" and column["name"] != pk
    ]
    scores = []
    for column in integer_columns:
        values = {
            int(unit_id): value
            for unit_id, value in connection.execute(
                f"SELECT {pk_column}, {quote_identifier(column)} FROM {table} WHERE {pk_column} IN ({placeholders})",
                sample_ids,
            )
        }
        atk_matches = sum(values.get(unit_id) == expected[0] for unit_id, expected in reference.items() if unit_id in values)
        pos_matches = sum(values.get(unit_id) == expected[1] for unit_id, expected in reference.items() if unit_id in values)
        scores.append((column, atk_matches, pos_matches))
    atk_column, atk_score, _ = max(scores, key=lambda item: item[1])
    pos_column, _, pos_score = max(scores, key=lambda item: item[2])
    if atk_score < 100 or pos_score < 100 or atk_column == pos_column:
        raise RuntimeError(f"cannot safely resolve unit fact columns: atk={atk_score}, pos={pos_score}")
    return atk_column, pos_column


def resolve_skill_description_column(connection: sqlite3.Connection, skill_schema: dict) -> str:
    pk = single_integer_pk(skill_schema)
    table = quote_identifier(skill_schema["name"])
    text_columns = [column["name"] for column in skill_schema["columns"] if column["type"] == "TEXT"]
    scored = []
    for column in text_columns:
        rows = connection.execute(
            f"SELECT {quote_identifier(column)} FROM {table} WHERE {quote_identifier(pk)} BETWEEN ? AND ? LIMIT 300",
            (1_000_000, 99_999_999),
        ).fetchall()
        lengths = [len(str(row[0])) for row in rows if row[0]]
        scored.append((sum(lengths) / len(lengths) if lengths else 0.0, column))
    if not scored:
        raise RuntimeError("cannot resolve skill description column")
    return max(scored)[1]


def ensure_role_database(force_update: bool = False) -> tuple[Path, dict]:
    """Use a cached current CN master DB; refresh it explicitly with --update-db."""
    ROLE_DB_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    metadata = {}
    if ROLE_DB_META_PATH.is_file():
        try:
            metadata = json.loads(ROLE_DB_META_PATH.read_text(encoding="utf-8"))
        except Exception:
            metadata = {}

    if force_update:
        with urllib.request.urlopen(VERSION_URL, timeout=30) as response:
            remote_cn = json.load(response)["CN"]
        if (
            ROLE_DB_PATH.is_file()
            and metadata.get("version") == remote_cn.get("version")
            and metadata.get("hash") == remote_cn.get("hash")
        ):
            return ROLE_DB_PATH, metadata

        temporary = ROLE_DB_CACHE_DIR / "master_cn.db.download"
        if temporary.exists():
            temporary.unlink()
        try:
            with urllib.request.urlopen(DATABASE_URL, timeout=120) as response, temporary.open("wb") as output:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
            with temporary.open("rb") as handle:
                if handle.read(16) != b"SQLite format 3\x00":
                    raise RuntimeError("downloaded master_cn.db is not SQLite")
            temporary.replace(ROLE_DB_PATH)
            metadata = {
                "version": remote_cn.get("version"),
                "hash": remote_cn.get("hash"),
                "source": DATABASE_URL,
            }
            ROLE_DB_META_PATH.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        finally:
            if temporary.exists():
                temporary.unlink()

    if ROLE_DB_PATH.is_file():
        return ROLE_DB_PATH, metadata
    if LEGACY_ROLE_DB_PATH.is_file():
        return LEGACY_ROLE_DB_PATH, {"version": "202604021043", "source": "legacy-local-cache"}
    raise FileNotFoundError("No CN master database is available; run generate-data.py --update-db")
USER_RATING_MAX = 6.0
ROLE_MULTIPLIER = {
    "攻击者": 1.00,
    "破防者": 0.82,
    "减益者": 0.68,
    "增幅者": 0.48,
    "增益者": 0.45,
    "掩护者": 0.32,
    "治疗者": 0.22,
}


def normalize(value) -> str:
    return (
        str(value or "")
        .strip()
        .replace("（", "(")
        .replace("）", ")")
        .replace("＝", "=")
        .replace("·", "=")
        .replace("・", "=")
        .replace("‧", "=")
        .replace("＆", "&")
        .replace(" ", "")
        .replace("　", "")
        .replace("\n", "")
    )


def column_index(cell_ref: str) -> int:
    letters = re.match(r"[A-Z]+", cell_ref).group(0)
    value = 0
    for char in letters:
        value = value * 26 + ord(char) - ord("A") + 1
    return value - 1


def read_strength_rows() -> dict[str, dict]:
    with zipfile.ZipFile(STRENGTH_XLSX_PATH) as archive:
        strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            strings = [
                "".join(node.text or "" for node in item.findall(f".//{{{MAIN_NS}}}t"))
                for item in root.findall(f"{{{MAIN_NS}}}si")
            ]
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        rels = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        rel_by_id = {rel.attrib["Id"]: rel.attrib["Target"] for rel in rels.findall(f"{{{PKG_REL_NS}}}Relationship")}
        first_sheet = workbook.find(f".//{{{MAIN_NS}}}sheet")
        target = rel_by_id[first_sheet.attrib[f"{{{REL_NS}}}id"]].replace("\\", "/")
        if not target.startswith("xl/"):
            target = "xl/" + target.lstrip("/")
        sheet = ET.fromstring(archive.read(target))

        rows = []
        for row in sheet.findall(f".//{{{MAIN_NS}}}sheetData/{{{MAIN_NS}}}row"):
            values = []
            for cell in row.findall(f"{{{MAIN_NS}}}c"):
                index = column_index(cell.attrib["r"])
                while len(values) <= index:
                    values.append("")
                cell_type = cell.attrib.get("t")
                value = cell.find(f"{{{MAIN_NS}}}v")
                raw = "" if value is None or value.text is None else value.text
                if cell_type == "s" and raw:
                    raw = strings[int(raw)]
                values[index] = raw
            rows.append(values)

    output = {}
    for row in rows[2:]:
        if not row or not str(row[0]).strip().isdigit():
            continue
        role_id = str(int(float(row[0])))
        japan_match = re.search(r"([1-6])", str(row[11] if len(row) > 11 else ""))
        japan_rating = int(japan_match.group(1)) if japan_match else None
        user_ratings = {}
        for column, author in USER_RATING_COLUMNS.items():
            raw = row[column] if len(row) > column else ""
            match = re.search(r"([1-6](?:\.\d+)?)", str(raw or ""))
            if match:
                user_ratings[author] = float(match.group(1))
        ratings = list(user_ratings.values())
        average = round(sum(ratings) / len(ratings), 3) if ratings else None
        user_score = round(average / USER_RATING_MAX * 100.0, 1) if average is not None else None
        output[role_id] = {
            "attribute": ATTRIBUTE_MAP.get(str(row[4]).strip()),
            "roleClass": str(row[6]).strip() or None,
            "sheetName": str(row[8]).strip(),
            "sheetAlias": str(row[10]).strip(),
            "japanReferenceRating": japan_rating,
            "userRatings": user_ratings,
            "userRatingAverage": average,
            "userRatingCount": len(ratings),
            "userScore": user_score,
        }
    return output


def load_tier_scores() -> dict[str, int]:
    summary = json.loads(TIER_SUMMARY_PATH.read_text(encoding="utf-8"))
    result = {}
    for tiers in summary.values():
        for tier, names in tiers.items():
            score = TIER_SCORE.get(tier, 0)
            for name in names:
                key = normalize(name)
                result[key] = max(result.get(key, 0), score)
    return result


def icon_map(icons: dict) -> dict[str, str]:
    grouped = {}
    for icon in icons.get("characterIcons", []):
        grouped.setdefault(str(icon["ownerId"]), []).append(icon)

    def priority(variant) -> int:
        variant = str(variant or "")
        return {"61": 400, "31": 300, "11": 200}.get(variant, int(variant) if variant.isdigit() else 0)

    result = {}
    for owner_id, values in grouped.items():
        icon = max(values, key=lambda item: priority(item.get("variant")))
        result[owner_id] = "../../android/app/src/main/assets/resource-packs/cn-bilibili/" + str(icon["file"]).replace("\\", "/")
    return result


def magnitude(text: str, keyword: str) -> int:
    if keyword not in text:
        return 0
    window_start = max(0, text.find(keyword) - 18)
    window = text[window_start:text.find(keyword) + len(keyword)]
    if "特大幅" in window:
        return 95
    if "大幅" in window:
        return 82
    if "中幅" in window:
        return 64
    if "小幅" in window:
        return 42
    return 55


def max_magnitude(text: str, keywords: list[str]) -> int:
    return max([magnitude(text, keyword) for keyword in keywords] + [0])


def contains_any(text: str, values: list[str]) -> bool:
    return any(value in text for value in values)


DOT_APPLICATION_RE = re.compile(
    r"(?:使|赋予)[^。；，\n]{0,48}(?:中毒|猛毒|毒咒|诅咒|烧伤|灼烧|绝怠灵度)"
)


def applies_enemy_dot(descriptions: list[str]) -> bool:
    """True only when a skill actively applies a DOT status to an enemy.

    Conditional text such as “当目标陷入持续伤害状态时” must not classify the role as a DOT
    applier.  This also covers client wording such as 猛毒, “赋予诅咒和束缚”, and the special
    绝怠灵度 status.
    """
    for description in descriptions:
        if DOT_APPLICATION_RE.search(description) and contains_any(
            description, ["敌", "目标", "对象", "其"]
        ):
            return True
    return False


def target_clauses(descriptions: list[str], target_words: list[str]) -> str:
    clauses = []
    for description in descriptions:
        for clause in re.split(r"[。；，]", description):
            if contains_any(clause, target_words):
                clauses.append(clause)
    return "\n".join(clauses)


def reduction_magnitude(text: str, keyword: str) -> int:
    best = 0
    for match in re.finditer(r"(?:(特大幅|大幅|中幅|小幅)[^。；，]{0,12})?降低[^。；，]{0,18}" + re.escape(keyword), text):
        phrase = match.group(0)
        if "自身" in phrase:
            continue
        if "特大幅" in phrase:
            best = max(best, 95)
        elif "大幅" in phrase:
            best = max(best, 82)
        elif "中幅" in phrase:
            best = max(best, 64)
        elif "小幅" in phrase:
            best = max(best, 42)
        else:
            best = max(best, 55)
    return best


def derive_skill_metrics(
    descriptions: list[str],
    role_class: str | None,
    reference_score: float | None,
    atk_type: int | None,
    position: int | None,
) -> dict:
    text = "\n".join(descriptions)
    ally_text = target_clauses(descriptions, ["我方", "成员", "友方"])
    enemy_text = target_clauses(descriptions, ["敌人", "敌方"])
    base_quality = reference_score or 0.0
    role_multiplier = ROLE_MULTIPLIER.get(role_class or "", 0.55)
    physical_hits = text.count("造成物理伤害")
    magic_hits = text.count("造成魔法伤害")

    physical_damage = round(min(100, base_quality * role_multiplier * (0.72 + min(physical_hits, 4) * 0.08))) if atk_type == 1 else 0
    magic_damage = round(min(100, base_quality * role_multiplier * (0.72 + min(magic_hits, 4) * 0.08))) if atk_type == 2 else 0

    physical_support = max_magnitude(ally_text, ["物理攻击力", "物理暴击", "物理攻击暴击时造成的伤害"])
    magic_support = max_magnitude(ally_text, ["魔法攻击力", "魔法暴击", "魔法攻击暴击时造成的伤害"])
    speed_support = max_magnitude(ally_text, ["行动速度"])
    tp_support = 72 if contains_any(ally_text, ["回复我方全体的技能值", "回复技能值的领域", "持续回复技能值"]) else 0
    dual_offense = physical_support > 0 and magic_support > 0
    universal_support = max(speed_support, tp_support, 78 if dual_offense else 0)

    healing = max_magnitude(ally_text, ["回复我方", "回复生命值"])
    regeneration = 68 if contains_any(ally_text, ["持续回复生命值", "回复生命值的领域"]) else 0
    shield = 75 if contains_any(ally_text, ["屏障", "伤害减轻", "攻击无效化", "吸收物理", "吸收魔法"]) else 0
    defense_buff = max(max_magnitude(ally_text, ["物理防御力"]), max_magnitude(ally_text, ["魔法防御力"]))
    survival_support = max(healing, regeneration, shield, defense_buff)
    control = 78 if contains_any(text, ["晕眩", "眩晕", "冻结", "束缚", "麻痹", "黑暗状态", "魅惑", "恐慌"]) else 0
    # Encounter mechanics use explicit skill-text facts instead of job/class guesses. Keep the
    # patterns deliberately narrow: a false positive can satisfy an EX hard requirement, while a
    # missed role merely remains a lower-priority/unknown candidate until the extractor improves.
    dot = 82 if applies_enemy_dot(descriptions) else 0
    taunt = 82 if "挑衅" in text else 0

    physical_down = reduction_magnitude(enemy_text, "物理防御力")
    magic_down = reduction_magnitude(enemy_text, "魔法防御力")
    physical_attack_reduction = reduction_magnitude(enemy_text, "物理攻击力")
    magic_attack_reduction = reduction_magnitude(enemy_text, "魔法攻击力")
    self_sustain = max_magnitude(text, ["回复自身生命值", "吸收物理攻击", "吸收魔法攻击"])
    reliable_vanguard = 0
    front_enough = position is not None and position <= 300
    if front_enough and (role_class == "掩护者" or contains_any(text, ["挑衅", "自身展开", "自身物理防御力", "自身魔法防御力"])):
        reliable_vanguard = min(100, max(58, survival_support, self_sustain))

    aoe = 82 if contains_any(text, ["敌方全体", "范围内的所有敌人", "所有敌人"]) else 0
    single = 82 if contains_any(text, ["前方一名敌人", "一名敌人"]) else 0
    burst = 82 if contains_any(text, ["伤害（特大）", "伤害(特大)"]) else 0
    boss_value = max(physical_down, magic_down, physical_attack_reduction, magic_attack_reduction, 72 if "挑衅" in text else 0)
    pure_tank = 72 if role_class == "掩护者" and max(physical_damage, magic_damage) < 40 else 0
    pure_healer = 72 if role_class == "治疗者" and max(physical_damage, magic_damage) < 35 else 0
    return {
        "physicalDamage": physical_damage,
        "magicDamage": magic_damage,
        "physicalSupport": physical_support,
        "magicSupport": magic_support,
        "universalSupport": universal_support,
        "survivalSupport": survival_support,
        "controlSupport": control,
        "reliableVanguard": reliable_vanguard,
        "selfSustain": self_sustain,
        "healing": healing,
        "regeneration": regeneration,
        "dot": dot,
        "shield": shield,
        "taunt": taunt,
        "burstDamage": burst,
        "physicalAttackReduction": physical_attack_reduction,
        "magicAttackReduction": magic_attack_reduction,
        "physicalDefenseDown": physical_down,
        "magicDefenseDown": magic_down,
        "aoeDamage": aoe,
        "singleTargetDamage": single,
        "bossMechanismValue": boss_value,
        "pureTank": pure_tank,
        "pureHealer": pure_healer,
        "skillSummary": descriptions[:6],
    }


def database_facts(catalog_ids: list[str], database_path: Path) -> dict[str, dict]:
    connection = sqlite3.connect(database_path)
    try:
        unit_schema, unit_skill_schema, skill_schema = resolve_unit_tables(connection)
        atk_column, position_column = resolve_unit_fact_columns(connection, unit_schema)
        description_column = resolve_skill_description_column(connection, skill_schema)

        unit_pk = single_integer_pk(unit_schema)
        unit_table = quote_identifier(unit_schema["name"])
        unit_pk_q = quote_identifier(unit_pk)
        atk_q = quote_identifier(atk_column)
        position_q = quote_identifier(position_column)

        unit_skill_pk = single_integer_pk(unit_skill_schema)
        unit_skill_table = quote_identifier(unit_skill_schema["name"])
        unit_skill_pk_q = quote_identifier(unit_skill_pk)
        unit_skill_columns = [
            column["name"]
            for column in unit_skill_schema["columns"]
            if column["type"] == "INTEGER" and column["name"] != unit_skill_pk
        ]
        unit_skill_projection = ",".join(quote_identifier(column) for column in unit_skill_columns)

        skill_pk = single_integer_pk(skill_schema)
        skill_table = quote_identifier(skill_schema["name"])
        skill_pk_q = quote_identifier(skill_pk)
        description_q = quote_identifier(description_column)

        output = {}
        for short_id in catalog_ids:
            unit_id = int(short_id) * 100 + 1
            unit = connection.execute(
                f"SELECT {atk_q}, {position_q} FROM {unit_table} WHERE {unit_pk_q} = ?",
                (unit_id,),
            ).fetchone()
            skill_row = connection.execute(
                f"SELECT {unit_skill_projection} FROM {unit_skill_table} WHERE {unit_skill_pk_q} = ?",
                (unit_id,),
            ).fetchone()
            descriptions = []
            if skill_row:
                skill_ids = sorted({
                    int(value)
                    for value in skill_row
                    if isinstance(value, int) and value in SKILL_ID_RANGE
                })
                if skill_ids:
                    placeholders = ",".join("?" for _ in skill_ids)
                    descriptions = [
                        row[0]
                        for row in connection.execute(
                            f"SELECT {description_q} FROM {skill_table} "
                            f"WHERE {skill_pk_q} IN ({placeholders}) ORDER BY {skill_pk_q}",
                            skill_ids,
                        )
                        if row[0]
                    ]
            inferred_atk_type = None
            if any("造成物理伤害" in description for description in descriptions):
                inferred_atk_type = 1
            if any("造成魔法伤害" in description for description in descriptions):
                inferred_atk_type = 2 if inferred_atk_type is None else inferred_atk_type
            if unit is None and not descriptions:
                continue
            output[short_id] = {
                "dbName": None,
                "atkType": int(unit[0]) if unit else inferred_atk_type,
                "position": int(unit[1]) if unit else None,
                "descriptions": descriptions,
                "attackTypeSource": "latest-master.unit_data" if unit else "skill-description-inferred",
                "positionSource": "latest-master.unit_data" if unit else "missing",
            }
        return output
    finally:
        connection.close()



def _percentile_score(value: float, population: list[float]) -> float:
    """0..100 empirical percentile; deterministic and robust to raw-stat scale differences."""
    if not population:
        return 0.0
    ordered = sorted(float(item) for item in population)
    if len(ordered) == 1:
        return 100.0
    below = sum(1 for item in ordered if item < value)
    equal = sum(1 for item in ordered if item == value)
    rank = below + max(0.0, (equal - 1) / 2.0)
    return round(rank * 100.0 / (len(ordered) - 1), 1)


def enrich_vanguard_profiles(characters: list[dict]) -> None:
    """Add battle-vanguard facts without conflating role class with actual frontline survival.

    Base durability is normalized from the Dawn Realm NPC panel.  Skill mechanisms stay in
    separate channels so an untargetable glass cannon (Grace) is not mislabeled as a high-DEF tank.
    """
    modeled_front = [
        item for item in characters
        if item.get("position") is not None
        and int(item["position"]) <= 350
        and (item.get("combatModel") or {}).get("panel")
    ]
    stat_population = {
        key: [float(item["combatModel"]["panel"].get(key) or 0.0) for item in modeled_front]
        for key in ("hp", "def", "magic_def", "dodge")
    }
    for item in characters:
        panel = (item.get("combatModel") or {}).get("panel") or {}
        descriptions = item.get("skillSummary") or []
        text = "\n".join(str(value) for value in descriptions)
        profile: dict[str, object] = {}
        if panel and stat_population["hp"]:
            hp = _percentile_score(float(panel.get("hp") or 0.0), stat_population["hp"])
            pdef = _percentile_score(float(panel.get("def") or 0.0), stat_population["def"])
            mdef = _percentile_score(float(panel.get("magic_def") or 0.0), stat_population["magic_def"])
            dodge = _percentile_score(float(panel.get("dodge") or 0.0), stat_population["dodge"])
            profile["physicalDurability"] = round(hp * 0.50 + pdef * 0.40 + dodge * 0.10, 1)
            profile["magicDurability"] = round(hp * 0.55 + mdef * 0.45, 1)
            profile["panelHp"] = int(panel.get("hp") or 0)
            profile["panelPhysicalDefense"] = int(panel.get("def") or 0)
            profile["panelMagicDefense"] = int(panel.get("magic_def") or 0)
            profile["panelDodge"] = int(panel.get("dodge") or 0)
            profile["panelLifeSteal"] = int(panel.get("life_steal") or 0)
            profile["panelHpRecoveryRate"] = int(panel.get("hp_recovery_rate") or 0)

        if "无法被敌方选为攻击目标" in text:
            profile["untargetable"] = 98.0
        if "无敌状态" in text:
            profile["invulnerability"] = 82.0
        if (
            ("每受到一次伤害" in text and "使伤害无效化" in text)
            or "受到的1次伤害无效化" in text
            or "受到的一次伤害无效化" in text
        ):
            profile["invulnerability"] = max(float(profile.get("invulnerability") or 0.0), 78.0)
        if "回避所有物理攻击" in text:
            profile["physicalEvasion"] = 92.0
        if "回避所有魔法攻击" in text:
            profile["magicEvasion"] = 92.0
        if "生命值降为0时" in text and ("不会倒下" in text or "不会死亡" in text):
            profile["revive"] = 78.0
        if "前方没有我方成员时" in text and ("解除" in text or "消失" in text):
            profile["requiresAllyInFront"] = True

        item["vanguardProfile"] = profile or None

def build_android_decision_payload(
    characters: list[dict],
    database_path: Path,
    database_meta: dict,
) -> dict:
    """Project the review data into the strict, versioned Android decision schema.

    Missing facts stay null.  Display-score fallbacks are retained only for characters whose
    combat model is unavailable; they are never promoted to modeled raw uplift.
    """
    android_characters = []
    for character in characters:
        confidence = character.get("dataConfidence") or {}
        support_available = confidence.get("support") not in {None, "missing"}
        combat_model = character.get("combatModel") or {}
        support_impact = combat_model.get("supportImpact") or {}
        model_status = str(combat_model.get("modelStatus") or "unavailable")

        def support_metric(name: str):
            return character.get(name) if support_available else None

        android_characters.append({
            "characterId": str(character["id"]),
            "displayName": character.get("name") or character.get("officialName") or str(character["id"]),
            "roleClass": character.get("roleClass"),
            "attribute": character.get("attribute"),
            "damageType": character.get("damageType"),
            "position": character.get("position"),
            "userScore": character.get("referenceScore"),
            "userRatingCount": int(character.get("userRatingCount") or 0),
            "physicalDamagePotential": support_metric("physicalDamage"),
            "magicDamagePotential": support_metric("magicDamage"),
            "physicalDamage1Target": character.get("physicalDamage1Target"),
            "physicalDamage2Target": character.get("physicalDamage2Target"),
            "physicalDamage3Target": character.get("physicalDamage3Target"),
            "magicDamage1Target": character.get("magicDamage1Target"),
            "magicDamage2Target": character.get("magicDamage2Target"),
            "magicDamage3Target": character.get("magicDamage3Target"),
            "physicalTeamUplift": (
                support_impact.get("physicalTeamUplift")
                if model_status.startswith("database-v") else None
            ),
            "magicTeamUplift": (
                support_impact.get("magicTeamUplift")
                if model_status.startswith("database-v") else None
            ),
            "modelStatus": model_status,
            "vanguardProfile": character.get("vanguardProfile"),
            "dataConfidence": {
                "attribute": confidence.get("attribute"),
                "roleClass": confidence.get("roleClass"),
                "damageType": confidence.get("damageType"),
                "position": confidence.get("position"),
                "support": confidence.get("support"),
            },
            "support": {
                "physicalOffense": support_metric("physicalSupport"),
                "magicOffense": support_metric("magicSupport"),
                "universalOffense": support_metric("universalSupport"),
                "universalSurvival": support_metric("survivalSupport"),
                "control": support_metric("controlSupport"),
            },
            "functions": {
                "reliableVanguard": support_metric("reliableVanguard"),
                "selfSustain": support_metric("selfSustain"),
                "healing": support_metric("healing"),
                "regeneration": support_metric("regeneration"),
                "dot": support_metric("dot"),
                "control": support_metric("controlSupport"),
                "shield": support_metric("shield"),
                "taunt": support_metric("taunt"),
                "burstDamage": support_metric("burstDamage"),
                "enemyPush": support_metric("enemyPush"),
                "enemyPull": support_metric("enemyPull"),
                "physicalAttackReduction": support_metric("physicalAttackReduction"),
                "magicAttackReduction": support_metric("magicAttackReduction"),
                "physicalDefenseDown": support_metric("physicalDefenseDown"),
                "magicDefenseDown": support_metric("magicDefenseDown"),
                "aoeDamage": support_metric("aoeDamage"),
                "singleTargetDamage": support_metric("singleTargetDamage"),
                "twoTargetDamage": support_metric("twoTargetDamage"),
                "threeTargetDamage": support_metric("threeTargetDamage"),
                "bossMechanismValue": support_metric("bossMechanismValue"),
                "pureTank": support_metric("pureTank"),
                "pureHealer": support_metric("pureHealer"),
            },
        })

    return {
        "schemaVersion": 2,
        "source": {
            "ratingWorkbook": str(STRENGTH_XLSX_PATH.relative_to(ROOT)).replace("\\", "/"),
            "ratingWorkbookHash": sha256_path(STRENGTH_XLSX_PATH),
            "skillDatabaseVersion": str(database_meta.get("version") or "legacy-local-cache"),
            "skillDatabaseHash": sha256_path(database_path),
            "attributeBonusSource": "runtime-config: 1/2/3/4/5 = 0/8/16/25/75 percent",
        },
        "scoring": {
            "attributeDamageBonus": {"1": 0.0, "2": 0.08, "3": 0.16, "4": 0.25, "5": 0.75},
            "playerWeight": 0.70,
            "systemDamageWeight": 0.50,
            "systemSurvivalWeight": 0.23,
            "systemFunctionWeight": 0.13,
            "systemFormationWeight": 0.04,
            "systemCohesionWeight": 0.10,
        },
        "roleChoice": {
            "candidateTeamWeights": [1.0, 0.35, 0.20],
            "requireStrictProfiles": True,
        },
        "teamPlan": {
            "oneTeamKillScore": 90.0,
            "mainTeamScore": 70.0,
            "cleanupTeamScore": 40.0,
            "mainPlusCleanupCombinedScore": 120.0,
            "stableTeamScore": 60.0,
            "fallbackTeamScore": 35.0,
            "threeTeamCombinedScore": 120.0,
        },
        "characters": android_characters,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate Dawn Realm role decision data")
    parser.add_argument(
        "--database",
        type=Path,
        help="readable SQLite database produced by autopcr-db-builder",
    )
    parser.add_argument(
        "--database-version",
        help="manifest version recorded in generated metadata (defaults to the database filename)",
    )
    parser.add_argument(
        "--update-db",
        action="store_true",
        help="refresh the legacy public database when --database is not provided",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.database is not None:
        database_path = args.database.resolve()
        if not database_path.is_file():
            raise FileNotFoundError(f"Database does not exist: {database_path}")
        database_meta = {
            "version": args.database_version or database_path.stem,
            "source": "autopcr-db-builder",
        }
    else:
        database_path, database_meta = ensure_role_database(args.update_db)
    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    icons = json.loads(ICONS_PATH.read_text(encoding="utf-8"))
    scores = load_tier_scores()
    sheet = read_strength_rows()
    catalog_by_id = {str(character["id"]): character for character in catalog["characters"]}
    canonical_catalog = [
        character
        for character in catalog["characters"]
        if str(character.get("id", "")) not in COMBINED_MEMBER_TO_CANONICAL
    ]
    catalog_ids = [str(character["id"]) for character in canonical_catalog if str(character.get("id", "")).isdigit()]
    db = database_facts(catalog_ids, database_path)
    legacy_db = legacy_database_facts(catalog_ids)
    for role_id, fallback in legacy_db.items():
        if role_id not in db:
            db[role_id] = fallback
            continue
        current = db[role_id]
        if current.get("atkType") is None and fallback.get("atkType") is not None:
            current["atkType"] = fallback["atkType"]
            current["attackTypeSource"] = fallback["attackTypeSource"]
        if current.get("position") is None and fallback.get("position") is not None:
            current["position"] = fallback["position"]
            current["positionSource"] = fallback["positionSource"]
        if not current.get("descriptions") and fallback.get("descriptions"):
            current["descriptions"] = fallback["descriptions"]

    combat = analyze_roster(
        [int(role_id) * 100 + 1 for role_id in catalog_ids],
        database_path,
    )
    icons_by_owner = icon_map(icons)

    matched_scores = 0
    sheet_matches = 0
    db_matches = 0
    characters = []
    for character in canonical_catalog:
        role_id = str(character["id"])
        merged_members = [catalog_by_id[member_id] for member_id in COMBINED_ROLE_GROUPS.get(role_id, []) if member_id in catalog_by_id]
        merged_aliases = []
        for member in merged_members:
            merged_aliases.extend([
                str(member.get("id", "")),
                member.get("name"),
                member.get("officialName"),
                *(member.get("aliases") or []),
            ])
        aliases = list(dict.fromkeys([
            *(character.get("aliases") or []),
            *(value for value in merged_aliases if value),
        ]))
        names = [character.get("name"), character.get("officialName"), *aliases]
        sheet_row = sheet.get(role_id, {})
        reference_score = sheet_row.get("userScore")
        if reference_score is not None:
            matched_scores += 1
        japanese_reference = sheet_row.get("japanReferenceRating")
        if japanese_reference is None:
            japanese_reference = max([scores.get(normalize(name), 0) for name in names] + [0]) or None
        db_row = db.get(role_id, {})
        if sheet_row:
            sheet_matches += 1
        if db_row:
            db_matches += 1
        combat_row = combat.get(int(role_id) * 100 + 1) if role_id.isdigit() else None
        external_role = EXTERNAL_ROLE_CALIBRATIONS.get(role_id)
        atk_type = db_row.get("atkType") or (combat_row.get("atkType") if combat_row else None)
        if atk_type is None and external_role:
            atk_type = external_role.get("atkType")
        damage_type = "PHYSICAL" if atk_type == 1 else "MAGIC" if atk_type == 2 else None
        metrics = derive_skill_metrics(
            db_row.get("descriptions", []),
            sheet_row.get("roleClass"),
            reference_score,
            atk_type,
            db_row.get("position"),
        )
        if combat_row:
            combat_scores = combat_row["scores"]
            metrics.update({
                "physicalDamage": combat_scores["physicalDamage"],
                "magicDamage": combat_scores["magicDamage"],
                "physicalDamage1Target": combat_scores["physicalDamage1Target"],
                "physicalDamage2Target": combat_scores["physicalDamage2Target"],
                "physicalDamage3Target": combat_scores["physicalDamage3Target"],
                "magicDamage1Target": combat_scores["magicDamage1Target"],
                "magicDamage2Target": combat_scores["magicDamage2Target"],
                "magicDamage3Target": combat_scores["magicDamage3Target"],
                "physicalSupport": combat_scores["physicalSupport"],
                "magicSupport": combat_scores["magicSupport"],
                "universalSupport": combat_scores["universalSupport"],
                "survivalSupport": combat_scores["survivalSupport"],
                "controlSupport": combat_scores["controlSupport"],
                "healing": combat_scores["healing"],
                "physicalDefenseDown": combat_scores["physicalDefenseDown"],
                "magicDefenseDown": combat_scores["magicDefenseDown"],
                "aoeDamage": combat_scores["aoeDamage"],
                "singleTargetDamage": combat_scores["singleTargetDamage"],
                "twoTargetDamage": combat_scores["twoTargetDamage"],
                "threeTargetDamage": combat_scores["threeTargetDamage"],
                "enemyPush": combat_scores["enemyPush"],
                "enemyPull": combat_scores["enemyPull"],
            })
        database_position = db_row.get("position")
        combat_position = combat_row.get("position") if combat_row else None
        resolved_position = database_position or combat_position
        external_position = external_role
        if resolved_position is None and external_position:
            resolved_position = int(external_position["position"])
        characters.append({
            "id": role_id,
            "name": character.get("name", ""),
            "officialName": character.get("officialName", ""),
            "aliases": aliases,
            "mergedMemberIds": COMBINED_ROLE_GROUPS.get(role_id, []),
            "mergedMemberNames": [member.get("name", "") for member in merged_members],
            "available": character.get("available") is not False,
            "icon": icons_by_owner.get(role_id),
            "referenceScore": reference_score,
            "scoreSource": "cn-user-average" if reference_score is not None else "unrated",
            "userRatingAverage": sheet_row.get("userRatingAverage"),
            "userRatingCount": sheet_row.get("userRatingCount", 0),
            "userRatings": sheet_row.get("userRatings") or {},
            "japanReferenceRating": japanese_reference,
            "attribute": sheet_row.get("attribute"),
            "roleClass": sheet_row.get("roleClass"),
            "damageType": damage_type,
            "position": resolved_position,
            "positionCalibrationSource": external_position.get("source") if external_position and database_position is None and combat_position is None else None,
            **metrics,
            "combatModel": combat_row or {"modelStatus": "unavailable"},
            "dataConfidence": {
                "attribute": "excel-fact" if sheet_row.get("attribute") else "missing",
                "roleClass": "excel-fact" if sheet_row.get("roleClass") else "missing",
                "damageType": (
                    "database-fact"
                    if db_row.get("attackTypeSource") == "latest-master.unit_data"
                    else "legacy-database-fact"
                    if db_row.get("attackTypeSource") == "legacy-master.unit_data" or combat_row
                    else "external-verified"
                    if external_role and external_role.get("atkType") == atk_type
                    else "skill-text-derived"
                    if damage_type
                    else "missing"
                ),
                "position": (
                    "database-fact"
                    if db_row.get("positionSource") == "latest-master.unit_data"
                    else "legacy-database-fact"
                    if db_row.get("positionSource") == "legacy-master.unit_data" or combat_position is not None
                    else "external-verified"
                    if external_position and database_position is None and combat_position is None
                    else "missing"
                ),
                "support": (
                    "database-combat-v2"
                    if combat_row
                    else "skill-text-fallback"
                    if db_row.get("descriptions")
                    else "missing"
                ),
            },
            "reviewNote": "",
        })

    enrich_vanguard_profiles(characters)

    opening_guilds = [
        {"id": 1, "name": "美食殿堂", "roleIds": ["1075", "1351", "1059"]},
        {"id": 2, "name": "破晓之星", "roleIds": ["1089", "1088", "1003", "1801", "1225"]},
        {"id": 3, "name": "咲恋救济院", "roleIds": ["1145", "1213", "1085", "1077", "1121", "1308", "1023", "1086", "1103"]},
        {"id": 4, "name": "王宫骑士团", "roleIds": ["1242", "1339", "1136", "1115", "1236", "1238"]},
        {"id": 5, "name": "拉比林斯", "roleIds": ["1091", "1171", "1011"]},
    ]
    event_catalog = load_labyrinth_event_catalog()
    database_label = f"{database_meta.get('source', 'database')}/{database_path.name}"
    payload = {
        "sources": {
            "catalog": "characters.landosolroster.json",
            "icons": "icons.json",
            "score": "攻略/公主连结角色黎明界强度表无图片.xlsx：仅浙江刘德华、巴托鲁、逆理之裁、gozen、neko五列参与最终综合评分；原始1–6分，等权平均后除以6换算为0–100",
            "japanReference": "もりりん（日服）仅作为旁路参考，不参与最终综合评分；表格缺失时可由tierlist_91x91_output/summary.json补参考档位",
            "strengthWorkbook": "攻略/公主连结角色黎明界强度表无图片.xlsx：属性、职阶、真实用户评分与日服参考",
            "roleDatabase": (
                f"{database_label}："
                f"CN master {database_meta.get('version', 'unknown')}；当前可用 atk_type、search_area_width 优先来源"
            ),
            "combatDatabase": (
                f"{database_label}："
                "可读战斗表用于黎明界强化模板、面板、行动循环与 skill_action 战斗模型 V2；"
                "数据库缺失角色/战斗表时不伪造新角色模型"
            ),
            "derived": (
                "优先使用数据库战斗模型 V2 计算输出/物辅/法辅/通辅/破防/治疗，并拆分1/2/3目标场景、友军负面效果与条件档位；"
                "无法建模角色才回退到技能文本初判，并在资料状态中明确标记"
            ),
        },
        "eventCatalog": event_catalog,
        "coverage": {
            "characters": len(characters),
            "availableCharacters": sum(1 for item in characters if item["available"]),
            "icons": sum(1 for item in characters if item["icon"]),
            "matchedScores": matched_scores,
            "unratedScores": len(characters) - matched_scores,
            "sheetMatches": sheet_matches,
            "databaseMatches": db_matches,
            "combatModeled": sum(1 for item in characters if item["dataConfidence"]["support"] == "database-combat-v2"),
            "supportFallback": sum(1 for item in characters if item["dataConfidence"]["support"] == "skill-text-fallback"),
        },
        "openingGuilds": opening_guilds,
        "combinedRoleMerges": COMBINED_MEMBER_TO_CANONICAL,
        "characters": characters,
    }
    (HERE / "data.js").write_text(
        "window.LABYRINTH_REVIEW_DATA = " + json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + ";\n",
        encoding="utf-8",
    )
    android_payload = build_android_decision_payload(characters, database_path, database_meta)
    ANDROID_DECISION_PATH.write_text(
        json.dumps(android_payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(payload["coverage"], ensure_ascii=False))


if __name__ == "__main__":
    main()
