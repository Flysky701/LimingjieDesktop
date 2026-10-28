"""Database-driven combat value model for the Dawn Realm role review page.

The model intentionally keeps raw combat quantities separate from 0-100 display scores.
It uses the readable CN master snapshot because the current public CN master is obfuscated
and does not expose every table required for panel reconstruction through the existing
semantic resolver yet.  Facts from a newer master can still override atk_type/position in
generate-data.py.

V2 covers:
- Dawn Realm NPC enhancement template (level/rarity/rank/equipment/UE)
- panel reconstruction compatible with AutoPCR's calc_unit_attribute ordering
- unit_attack_pattern opener/loop parsing
- normal attack + main-skill + UB damage formula proxies
- attack/critical buffs, defense down, action speed, TP recovery
- healing, regeneration, shields and control coverage
- action_type=28 conditional-tier discounting so mutually exclusive stack/HP branches are
  not all treated as permanently active at once
- ally-side attack debuffs as negative team contribution
- 1/2/3-target output scenarios

Conditional tiers are still an approximation until every character-specific state machine
is implemented. Transformation modes, summons and other uncommon action types remain
discounted or unsupported rather than being presented as exact simulation.
"""

from __future__ import annotations

import math
import sqlite3
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_CEILING, ROUND_HALF_UP
from pathlib import Path
from statistics import median
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DB = ROOT / "pick/autopcr-android/app/src/main/python/cache/db/202604021043.db"

# TP is capped at 1000.  V1 uses a reference 18 s UB interval only to convert explicit TP
# support into a comparable throughput proxy.  The raw tpPerSecond is always retained.
REFERENCE_UB_INTERVAL_SECONDS = 18.0
REFERENCE_BASE_TP_PER_SECOND = 1000.0 / REFERENCE_UB_INTERVAL_SECONDS
REFERENCE_UB_DAMAGE_SHARE = 0.35
MODEL_BATTLE_SECONDS = 90.0

# Community documentation gives ~5% critical chance per 100 critical at equal levels.
# A normal critical doubles damage, therefore +100 crit ~= +5% expected damage before cap.
CRIT_STAT_TO_EXPECTED_DAMAGE = 0.0005

# Equipment enhance tables stop at old ranks; rank >= 10 uses the same five enhance levels.
FALLBACK_EQUIPMENT_ENHANCE_LEVEL = 5

STAT_COLUMNS = {
    "hp": "hp",
    "atk": "atk",
    "magic_str": "magic_str",
    "def": "def",
    "magic_def": "magic_def",
    "physical_critical": "physical_critical",
    "magic_critical": "magic_critical",
    "wave_hp_recovery": "wave_hp_recovery",
    "wave_energy_recovery": "wave_energy_recovery",
    "dodge": "dodge",
    "physical_penetrate": "physical_penetrate",
    "magic_penetrate": "magic_penetrate",
    "life_steal": "life_steal",
    "hp_recovery_rate": "hp_recovery_rate",
    "energy_recovery_rate": "energy_recovery_rate",
    "energy_reduce_rate": "energy_reduce_rate",
    "accuracy": "accuracy",
}


def _d(value: object) -> Decimal:
    return Decimal(str(value or 0))


def _round_half_up(value: Decimal) -> Decimal:
    return value.quantize(Decimal(1), rounding=ROUND_HALF_UP)


def _ceil(value: Decimal) -> Decimal:
    return value.quantize(Decimal(1), rounding=ROUND_CEILING)


@dataclass
class Attributes:
    values: dict[str, Decimal] = field(default_factory=lambda: {name: Decimal(0) for name in STAT_COLUMNS})

    @classmethod
    def from_row(cls, row: sqlite3.Row | None, suffix: str = "", prefix: str = "") -> "Attributes":
        result = cls()
        if row is None:
            return result
        keys = set(row.keys())
        for name, column in STAT_COLUMNS.items():
            target = f"{prefix}{column}{suffix}"
            if target in keys:
                result.values[name] = _d(row[target])
        return result

    def copy(self) -> "Attributes":
        return Attributes(dict(self.values))

    def add(self, other: "Attributes") -> "Attributes":
        for key in self.values:
            self.values[key] += other.values[key]
        return self

    def scaled(self, value: float | Decimal) -> "Attributes":
        scale = value if isinstance(value, Decimal) else Decimal(str(value))
        return Attributes({key: amount * scale for key, amount in self.values.items()})

    def rounded(self) -> "Attributes":
        return Attributes({key: _round_half_up(value) for key, value in self.values.items()})

    def ceiled(self) -> "Attributes":
        return Attributes({key: _ceil(value) for key, value in self.values.items()})

    def number(self, key: str) -> float:
        return float(self.values.get(key, Decimal(0)))

    def as_json(self) -> dict[str, int | float]:
        output = {}
        for key, value in self.values.items():
            rounded = _round_half_up(value)
            output[key] = int(rounded)
        return output


@dataclass(frozen=True)
class DawnBuild:
    enhance_id: int
    level: int
    rarity: int
    rank: int
    equipment_slots: tuple[bool, ...]
    unique_level_1: int
    unique_level_2: int
    ex_equipment_ids: tuple[int, ...]
    ex_equipment_levels: tuple[int, ...]


def _fetch_one(connection: sqlite3.Connection, sql: str, args: tuple = ()) -> sqlite3.Row | None:
    return connection.execute(sql, args).fetchone()


def _table_exists(connection: sqlite3.Connection, table: str) -> bool:
    return connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone() is not None


class CombatModel:
    def __init__(self, database_path: Path = DEFAULT_DB):
        self.database_path = Path(database_path)
        self.connection = sqlite3.connect(self.database_path)
        self.connection.row_factory = sqlite3.Row
        self._equipment_max_enhance = self._load_equipment_max_enhance()

    def close(self) -> None:
        self.connection.close()

    def _load_equipment_max_enhance(self) -> dict[int, int]:
        if not _table_exists(self.connection, "equipment_enhance_data"):
            return {}
        return {
            int(row[0]): int(row[1])
            for row in self.connection.execute(
                "SELECT promotion_level, MAX(equipment_enhance_level) "
                "FROM equipment_enhance_data GROUP BY promotion_level"
            )
        }

    def dawn_build(self, unit_id: int) -> DawnBuild | None:
        npc = _fetch_one(
            self.connection,
            "SELECT unit_id, enhance_id FROM labyrinth_npc_unit_data WHERE unit_id=?",
            (unit_id,),
        )
        if npc is None:
            return None
        row = _fetch_one(
            self.connection,
            "SELECT * FROM labyrinth_npc_unit_enhance_data WHERE enhance_id=?",
            (int(npc["enhance_id"]),),
        )
        if row is None:
            return None
        return DawnBuild(
            enhance_id=int(row["enhance_id"]),
            level=int(row["unit_level"]),
            rarity=int(row["rarity"]),
            rank=int(row["promotion_level"]),
            equipment_slots=tuple(bool(row[f"equipment_slot_{index}"]) for index in range(1, 7)),
            unique_level_1=int(row["unique_equipment_level_1"]),
            unique_level_2=int(row["unique_equipment_level_2"]),
            ex_equipment_ids=tuple(int(row[f"ex_equip_slot_{index}"]) for index in range(1, 4)),
            ex_equipment_levels=tuple(int(row[f"ex_equip_enhance_level_{index}"]) for index in range(1, 4)),
        )

    def _equipment_enhance_level(self, rank: int) -> int:
        if rank in self._equipment_max_enhance:
            return self._equipment_max_enhance[rank]
        lower = [value for key, value in self._equipment_max_enhance.items() if key <= rank]
        return max(lower, default=FALLBACK_EQUIPMENT_ENHANCE_LEVEL)

    def panel(self, unit_id: int, build: DawnBuild) -> Attributes | None:
        rarity = _fetch_one(
            self.connection,
            "SELECT * FROM unit_rarity WHERE unit_id=? AND rarity=?",
            (unit_id, build.rarity),
        )
        if rarity is None:
            return None

        # Same ordering used by AutoPCR calc_unit_attribute().
        base = Attributes.from_row(rarity)
        base.add(Attributes.from_row(rarity, suffix="_growth").scaled(build.level + build.rank))
        base = base.rounded()

        promotion = _fetch_one(
            self.connection,
            "SELECT * FROM unit_promotion_status WHERE unit_id=? AND promotion_level=?",
            (unit_id, build.rank),
        )
        if promotion is not None:
            base.add(Attributes.from_row(promotion).rounded())

        if _table_exists(self.connection, "promotion_bonus"):
            bonus = _fetch_one(
                self.connection,
                "SELECT * FROM promotion_bonus WHERE unit_id=? AND promotion_level=?",
                (unit_id, build.rank),
            )
            if bonus is not None:
                base.add(Attributes.from_row(bonus).rounded())

        promotion_equip = _fetch_one(
            self.connection,
            "SELECT * FROM unit_promotion WHERE unit_id=? AND promotion_level=?",
            (unit_id, build.rank),
        )
        if promotion_equip is not None:
            enhance_level = self._equipment_enhance_level(build.rank)
            for index, equipped in enumerate(build.equipment_slots, start=1):
                if not equipped:
                    continue
                equipment_id = int(promotion_equip[f"equip_slot_{index}"])
                equipment = _fetch_one(
                    self.connection,
                    "SELECT * FROM equipment_data WHERE equipment_id=?",
                    (equipment_id,),
                )
                if equipment is None:
                    continue
                piece = Attributes.from_row(equipment)
                rate = _fetch_one(
                    self.connection,
                    "SELECT * FROM equipment_enhance_rate WHERE equipment_id=?",
                    (equipment_id,),
                )
                if rate is not None:
                    piece.add(Attributes.from_row(rate).scaled(enhance_level))
                base.add(piece.ceiled())

        # Dawn Realm template currently supplies UE1 only.  UE2 is kept in the schema for
        # future data but is not guessed if no mapping exists.
        if build.unique_level_1 > 0:
            unique = _fetch_one(
                self.connection,
                "SELECT equip_id FROM unit_unique_equipment WHERE unit_id=? AND equip_slot=1",
                (unit_id,),
            )
            if unique is None and _table_exists(self.connection, "unit_unique_equip"):
                unique = _fetch_one(
                    self.connection,
                    "SELECT equip_id FROM unit_unique_equip WHERE unit_id=? AND equip_slot=1",
                    (unit_id,),
                )
            if unique is not None:
                equipment_id = int(unique["equip_id"])
                unique_data = _fetch_one(
                    self.connection,
                    "SELECT * FROM unique_equipment_data WHERE equipment_id=?",
                    (equipment_id,),
                )
                if unique_data is not None:
                    unique_attr = Attributes.from_row(unique_data)
                    for rate in self.connection.execute(
                        "SELECT * FROM unique_equip_enhance_rate WHERE equipment_id=? ORDER BY min_lv",
                        (equipment_id,),
                    ):
                        minimum = int(rate["min_lv"])
                        maximum = int(rate["max_lv"])
                        if build.unique_level_1 < minimum:
                            continue
                        count = (
                            build.unique_level_1 - minimum + 1
                            if maximum == -1
                            else min(maximum, build.unique_level_1) - minimum + 1
                        )
                        if count > 0:
                            unique_attr.add(Attributes.from_row(rate).scaled(count))
                    base.add(unique_attr.ceiled())

        # Bond/story stats are intentionally excluded: Dawn Realm NPC templates are not
        # player-owned units and the master template does not specify bond stories.
        return base

    def _selected_skills(self, unit_id: int, build: DawnBuild) -> dict[str, int]:
        row = _fetch_one(self.connection, "SELECT * FROM unit_skill_data WHERE unit_id=?", (unit_id,))
        if row is None:
            return {}

        def value(name: str) -> int:
            return int(row[name]) if name in row.keys() and row[name] else 0

        result: dict[str, int] = {}
        ub = value("union_burst_evolution") if build.rarity >= 6 else 0
        result["ub"] = ub or value("union_burst")
        for index in range(1, 11):
            normal = value(f"main_skill_{index}")
            evolution = value(f"main_skill_evolution_{index}")
            use_evolution = (index == 1 and build.unique_level_1 > 0) or (index == 2 and build.unique_level_2 > 0)
            selected = evolution if use_evolution and evolution else normal
            if selected:
                result[f"main{index}"] = selected
        # Attack patterns use 2001/2002/... for SP skills.  These frequently initialise
        # stack mechanics (e.g. 剑之刻印 / 水月), so silently dropping them makes conditional
        # characters impossible to audit correctly.
        for index in range(1, 6):
            normal = value(f"sp_skill_{index}")
            evolution = value(f"sp_skill_evolution_{index}")
            selected = evolution if build.unique_level_1 > 0 and evolution else normal
            if selected:
                result[f"sp{index}"] = selected
        ex = value("ex_skill_evolution_1") if build.rarity >= 5 else 0
        result["ex1"] = ex or value("ex_skill_1")
        return {key: skill_id for key, skill_id in result.items() if skill_id}

    def _pattern(self, unit_id: int) -> dict | None:
        row = _fetch_one(
            self.connection,
            "SELECT * FROM unit_attack_pattern WHERE unit_id=? ORDER BY pattern_id LIMIT 1",
            (unit_id,),
        )
        if row is None:
            return None
        actions = []
        for index in range(1, 21):
            value = int(row[f"atk_pattern_{index}"])
            if value:
                actions.append(value)
        if not actions:
            return None
        loop_start = max(1, int(row["loop_start"]))
        loop_end = min(len(actions), int(row["loop_end"]))
        if loop_end < loop_start:
            loop_start, loop_end = 1, len(actions)
        return {
            "actions": actions,
            "opener": actions[: loop_start - 1],
            "loop": actions[loop_start - 1 : loop_end],
            "loopStart": loop_start,
            "loopEnd": loop_end,
            "patternId": int(row["pattern_id"]),
        }

    def _skill_row(self, skill_id: int) -> sqlite3.Row | None:
        return _fetch_one(self.connection, "SELECT * FROM skill_data WHERE skill_id=?", (skill_id,))

    @staticmethod
    def _conditional_uptime(threshold: float) -> float:
        """Conservative V2 estimate for a gated tier before a full per-character state machine.

        The old model counted every action behind an action_type=28 gate at 100%, which
        massively over-counted mutually exclusive <33 / >=33 / >=66 / =99 tiers.  This
        bounded monotonic estimate deliberately leaves probability mass for the default tier.
        Raw threshold/weight metadata is preserved so these approximations can be replaced by
        exact stack simulation character-by-character without changing the public schema.
        """
        if threshold <= 0:
            return 0.35
        return max(0.10, min(0.45, 0.45 - threshold * 0.003))

    def _skill_actions(self, skill_id: int) -> list[tuple[sqlite3.Row, float, dict | None]]:
        skill = self._skill_row(skill_id)
        if skill is None:
            return []
        rows: list[sqlite3.Row] = []
        for index in range(1, 11):
            action_id = int(skill[f"action_{index}"])
            if not action_id:
                continue
            action = _fetch_one(self.connection, "SELECT * FROM skill_action WHERE action_id=?", (action_id,))
            if action is not None:
                rows.append(action)

        weights = {int(row["action_id"]): 1.0 for row in rows}
        for row in rows:
            action_type = int(row["action_type"])
            if action_type == 53:  # condition branch; true/false branches are both stored
                for key in ("action_detail_2", "action_detail_3"):
                    branch = int(row[key])
                    if branch in weights:
                        weights[branch] = min(weights[branch], 0.5)
            elif action_type == 42:  # counter/triggered branch
                branch = int(row["action_detail_2"])
                if branch in weights:
                    weights[branch] = min(weights[branch], 0.35)
        # action_type=28 is a condition gate.  In master data a gate is followed by one or
        # more actions belonging to that tier until the next gate.  V1 incorrectly summed
        # every tier.  V2 carries the active condition forward and applies a conservative,
        # mutually-exclusive-tier approximation instead.
        entries: list[tuple[sqlite3.Row, float, dict | None]] = []
        active_condition: dict | None = None
        for row in rows:
            action_type = int(row["action_type"])
            if action_type == 28:
                threshold = abs(float(row["action_value_3"] or 0.0))
                active_condition = {
                    "stateId": int(row["action_detail_1"]),
                    "threshold": threshold,
                    "estimatedUptime": self._conditional_uptime(threshold),
                }
                entries.append((row, weights[int(row["action_id"])], active_condition))
                continue
            weight = weights[int(row["action_id"])]
            if active_condition is not None:
                weight *= float(active_condition["estimatedUptime"])
            entries.append((row, weight, active_condition))
        return entries

    def _token_skill(self, token: int, skills: dict[str, int]) -> int | None:
        if token == 1:
            return None
        if 1001 <= token < 2000:
            return skills.get(f"main{token - 1000}")
        if 2001 <= token < 3000:
            return skills.get(f"sp{token - 2000}")
        return None

    def _action_duration(self, token: int, skills: dict[str, int], normal_cast: float) -> float:
        if token == 1:
            return max(0.35, normal_cast)
        skill_id = self._token_skill(token, skills)
        skill = self._skill_row(skill_id) if skill_id else None
        cast = float(skill["skill_cast_time"]) if skill is not None else 0.0
        # Some skills report zero cast time even though their animation still occupies a turn.
        return max(0.35, cast if cast > 0 else normal_cast * 0.85)

    @staticmethod
    def _damage_system(action: sqlite3.Row) -> str | None:
        description = str(action["description"] or "")
        detail = int(action["action_detail_1"])
        if "魔法伤害" in description or detail == 2:
            return "MAGIC"
        if "物理伤害" in description or detail == 1:
            return "PHYSICAL"
        return None

    @staticmethod
    def _team_target_factor(action: sqlite3.Row) -> float:
        if int(action["target_assignment"]) != 2:
            return 0.0
        description = str(action["description"] or "")
        target_type = int(action["target_type"])
        action_type = int(action["action_type"])
        excludes_self = "除自身" in description or "自身以外" in description
        if action_type == 38:
            # Area/field actions use target_type=7 for the field anchor; this is not a self-only
            # buff.  Exact member inclusion needs team positions, so V1 uses a conservative
            # multi-target factor instead of claiming full-team coverage.
            return 0.65
        if not excludes_self and (target_type == 7 or "自身" in description or "自己" in description):
            return 0.0
        target_count = int(action["target_count"])
        target_area = int(action["target_area"])
        target_range = int(action["target_range"])
        if target_count >= 5 or target_count == 99 or target_area == 3 or "全体" in description:
            # “除自身以外全体” affects four of five standard team slots.  Until the full
            # five-member simulator applies effects per recipient, use 4/5 as the aggregate
            # team-share proxy instead of pretending the caster is debuffed as well.
            return 0.8 if excludes_self else 1.0
        if target_count == 1:
            return 0.25
        if target_area == 2 or target_range > 0:
            return 0.65
        return 0.5

    @staticmethod
    def _enemy_target_factor(action: sqlite3.Row) -> float:
        """Boss-oriented target factor for enemy debuffs.

        A single-target boss debuff and an all-enemy debuff both fully affect the primary
        target.  Range/position edge cases remain a V1 limitation and are documented.
        """
        return 1.0 if int(action["target_assignment"]) == 1 else 0.0

    @staticmethod
    def _is_self(action: sqlite3.Row) -> bool:
        description = str(action["description"] or "")
        if int(action["action_type"]) == 38:
            return False
        if "除自身" in description or "自身以外" in description:
            return False
        return int(action["target_type"]) == 7 or "自身" in description or "自己" in description

    @staticmethod
    def _linear_skill_value(action: sqlite3.Row, skill_level: int) -> float:
        return float(action["action_value_2"]) + float(action["action_value_3"]) * skill_level

    @staticmethod
    def _damage_value(action: sqlite3.Row, skill_level: int, attack: float) -> float:
        return (
            float(action["action_value_1"])
            + float(action["action_value_2"]) * skill_level
            + float(action["action_value_3"]) * attack
        )

    @staticmethod
    def _heal_value(action: sqlite3.Row, skill_level: int, magic_attack: float) -> float:
        return (
            float(action["action_value_2"])
            + float(action["action_value_3"]) * skill_level
            + float(action["action_value_4"]) * magic_attack
        )

    @staticmethod
    def _duration(action: sqlite3.Row) -> float:
        action_type = int(action["action_type"])
        if action_type in (10, 90):
            return max(0.0, float(action["action_value_4"]))
        if action_type in (6, 8, 38, 72):
            return max(0.0, float(action["action_value_3"]))
        if action_type == 48:
            return max(0.0, float(action["action_value_5"]))
        return 0.0

    def _status_amount(self, action: sqlite3.Row, skill_level: int) -> float:
        action_type = int(action["action_type"])
        if action_type == 38:
            # Field actions store base/per-level in value1/value2 and duration in value3.
            return float(action["action_value_1"]) + float(action["action_value_2"]) * skill_level
        if action_type in (10, 90):
            return self._linear_skill_value(action, skill_level)
        return 0.0

    def _effect_summary(
        self,
        action: sqlite3.Row,
        skill_level: int,
        physical_attack: float,
        magic_attack: float,
        skill_description: str = "",
    ) -> tuple[str, float, float] | None:
        action_type = int(action["action_type"])
        detail = int(action["action_detail_1"])
        duration = self._duration(action)
        if action_type == 1:
            system = self._damage_system(action)
            attack = physical_attack if system == "PHYSICAL" else magic_attack
            if system:
                return f"damage:{system}", self._damage_value(action, skill_level, attack), 0.0
        # action_type=3 is the game's target-movement action. Keep it strictly enemy-side so self
        # movement such as Lima's opening rush (action_type=44) can never be mistaken for an
        # encounter push/pull tool. Skill semantics take precedence over value_1 sign: some skills
        # (notably Summer Misogi) encode a positive movement parameter while their authoritative
        # description explicitly says the farthest enemy is dragged to the caster.
        if action_type == 3 and int(action["target_assignment"]) == 1:
            distance = float(action["action_value_1"])
            if any(term in skill_description for term in ("拉近", "拉拽", "拉扯", "拉至", "拉到")):
                return "enemyPull", abs(distance), 0.0
            if any(term in skill_description for term in ("击飞", "吹飞")):
                return "enemyPush", abs(distance), 0.0
            if distance > 0.0:
                return "enemyPush", distance, 0.0
            if distance < 0.0:
                return "enemyPull", abs(distance), 0.0
        if action_type in (10, 38, 90):
            amount = self._status_amount(action, skill_level)
            mapping = {
                10: "physicalAttackUp", 11: "physicalAttackDown",
                20: "physicalDefenseUp", 21: "physicalDefenseDown",
                30: "magicAttackUp", 31: "magicAttackDown",
                40: "magicDefenseUp", 41: "magicDefenseDown",
                60: "physicalCriticalUp", 61: "physicalCriticalDown",
                70: "magicCriticalUp", 71: "magicCriticalDown",
                80: "tpGainUp", 81: "tpGainDown",
                100: "physicalCriticalDamageUp", 101: "magicCriticalDamageUp",
                102: "physicalDamageUp", 103: "magicDamageUp",
            }
            effect = mapping.get(detail)
            if effect:
                description = str(action["description"] or "")
                # mode=2 + “初始值的X%” is a percentage-of-base-stat modifier, not a flat
                # X-point modifier.  Summer Anna's -90% ally MATK is the canonical example.
                if (
                    action_type == 10
                    and float(action["action_value_1"]) == 2.0
                    and "初始值" in description
                    and effect in {
                        "physicalAttackDown", "magicAttackDown",
                        "physicalCriticalDown", "magicCriticalDown",
                    }
                ):
                    return f"{effect}Rate", max(0.0, float(action["action_value_2"]) / 100.0), duration
                return effect, amount, duration
        if action_type == 8:
            if detail == 2:
                return "actionSpeedUp", max(0.0, float(action["action_value_1"]) - 1.0), duration
            if detail == 7:
                return "control", duration, duration
        if action_type == 16:
            amount = float(action["action_value_1"]) + float(action["action_value_2"]) * skill_level
            return "tpRecovery", amount, 0.0
        if action_type == 4:
            return "heal", self._heal_value(action, skill_level, magic_attack), 0.0
        if action_type == 48:
            amount = (
                float(action["action_value_1"])
                + float(action["action_value_2"]) * skill_level
                + float(action["action_value_3"]) * magic_attack
            )
            # Detail 2 is TP regen on some skills; otherwise treat as HP regen.
            effect = "tpRegen" if int(action["action_detail_2"]) == 2 else "regeneration"
            return effect, amount, duration
        if action_type == 6:
            amount = float(action["action_value_1"]) + float(action["action_value_2"]) * skill_level
            return "shield", amount, duration
        if action_type == 72:
            detail_map = {1: "physicalDamageReduction", 2: "magicDamageReduction"}
            effect = detail_map.get(detail, "damageReduction")
            return effect, float(action["action_value_1"]) / 100.0, duration
        return None

    def analyze_unit(self, unit_id: int) -> dict | None:
        build = self.dawn_build(unit_id)
        if build is None:
            return None
        panel = self.panel(unit_id, build)
        skills = self._selected_skills(unit_id, build)
        pattern = self._pattern(unit_id)
        unit = _fetch_one(
            self.connection,
            "SELECT unit_name, atk_type, normal_atk_cast_time, search_area_width FROM unit_data WHERE unit_id=?",
            (unit_id,),
        )
        if panel is None or pattern is None or unit is None:
            return None

        skill_level = build.level
        normal_cast = float(unit["normal_atk_cast_time"] or 0.0)
        opener = list(pattern["opener"])
        loop = list(pattern["loop"])
        opener_duration = sum(self._action_duration(token, skills, normal_cast) for token in opener)
        cycle_duration = sum(self._action_duration(token, skills, normal_cast) for token in loop)
        if cycle_duration <= 0:
            cycle_duration = max(1.0, len(loop) * 2.0)

        # Complete 90 s model: opener occurs once, then the configured loop repeats.
        remaining = max(0.0, MODEL_BATTLE_SECONDS - opener_duration)
        loop_repetitions = remaining / cycle_duration
        normal_event_count = opener.count(1) + loop.count(1) * loop_repetitions
        normal_events_per_second = normal_event_count / MODEL_BATTLE_SECONDS
        skill_event_counts: dict[int, float] = {}
        for token in set(opener + loop):
            skill_id = self._token_skill(token, skills)
            if skill_id:
                skill_event_counts[skill_id] = opener.count(token) + loop.count(token) * loop_repetitions
        skill_events_per_second = {
            skill_id: count / MODEL_BATTLE_SECONDS
            for skill_id, count in skill_event_counts.items()
        }

        raw_effects: list[dict] = []

        def add_skill(skill_id: int, events_per_second: float, source: str, ub: bool = False) -> None:
            if not skill_id or events_per_second <= 0:
                return
            skill = self._skill_row(skill_id)
            if skill is None:
                return
            for action, branch_weight, condition in self._skill_actions(skill_id):
                summary = self._effect_summary(
                    action,
                    skill_level,
                    panel.number("atk"),
                    panel.number("magic_str"),
                    str(skill["description"] or ""),
                )
                if summary is None:
                    continue
                effect, amount, duration = summary
                coverage = min(1.0, duration * events_per_second) if duration > 0 else 0.0
                raw_effects.append({
                    "skillId": skill_id,
                    "skillName": str(skill["name"] or skill_id),
                    "source": source,
                    "effect": effect,
                    "amount": amount,
                    "duration": duration,
                    "eventsPerSecond": events_per_second,
                    "coverage": coverage,
                    "branchWeight": branch_weight,
                    "condition": condition,
                    "teamTargetFactor": self._team_target_factor(action),
                    "enemyTargetFactor": self._enemy_target_factor(action),
                    "selfTarget": self._is_self(action) or int(action["action_type"]) == 90,
                    "targetAssignment": int(action["target_assignment"]),
                    "targetCount": int(action["target_count"]),
                    "description": str(action["description"] or ""),
                    "ub": ub,
                })

        for skill_id, events_per_second in skill_events_per_second.items():
            add_skill(skill_id, events_per_second, "loop")
        if skills.get("ub"):
            add_skill(skills["ub"], 1.0 / REFERENCE_UB_INTERVAL_SECONDS, "ub-reference", ub=True)

        # EX skill is persistent and only affects the owner.  Treat its supported status types
        # as always-on self buffs, not as team support.
        ex_self_buffs: dict[str, float] = {}
        if skills.get("ex1"):
            for action, branch_weight, _condition in self._skill_actions(skills["ex1"]):
                summary = self._effect_summary(
                    action,
                    skill_level,
                    panel.number("atk"),
                    panel.number("magic_str"),
                    str(self._skill_row(skills["ex1"])["description"] or ""),
                )
                if summary:
                    effect, amount, _ = summary
                    if effect in {"physicalAttackUp", "magicAttackUp", "physicalCriticalUp", "magicCriticalUp"}:
                        ex_self_buffs[effect] = ex_self_buffs.get(effect, 0.0) + amount * branch_weight

        # Average self buffs from the regular loop/UB are applied to the damage proxy.
        self_average: dict[str, float] = {}
        for effect in raw_effects:
            if not effect["selfTarget"]:
                continue
            if effect["effect"] not in {
                "physicalAttackUp", "physicalAttackDown", "magicAttackUp", "magicAttackDown",
                "physicalCriticalUp", "physicalCriticalDown", "magicCriticalUp", "magicCriticalDown",
                "physicalAttackDownRate", "magicAttackDownRate",
                "physicalCriticalDownRate", "magicCriticalDownRate",
                "actionSpeedUp", "tpRecovery",
            }:
                continue
            if effect["effect"] == "tpRecovery":
                contribution = effect["amount"] * effect["eventsPerSecond"] * effect["branchWeight"]
            else:
                contribution = effect["amount"] * effect["coverage"] * effect["branchWeight"]
            self_average[effect["effect"]] = self_average.get(effect["effect"], 0.0) + contribution

        physical_attack_rate = min(0.95, max(0.0, self_average.get("physicalAttackDownRate", 0.0)))
        magic_attack_rate = min(0.95, max(0.0, self_average.get("magicAttackDownRate", 0.0)))
        physical_crit_rate = min(0.95, max(0.0, self_average.get("physicalCriticalDownRate", 0.0)))
        magic_crit_rate = min(0.95, max(0.0, self_average.get("magicCriticalDownRate", 0.0)))
        effective_atk = max(
            0.0,
            (panel.number("atk") + ex_self_buffs.get("physicalAttackUp", 0.0)
            + self_average.get("physicalAttackUp", 0.0) - self_average.get("physicalAttackDown", 0.0))
            * (1.0 - physical_attack_rate),
        )
        effective_magic = max(
            0.0,
            (panel.number("magic_str") + ex_self_buffs.get("magicAttackUp", 0.0)
            + self_average.get("magicAttackUp", 0.0) - self_average.get("magicAttackDown", 0.0))
            * (1.0 - magic_attack_rate),
        )
        physical_crit = max(
            0.0,
            (panel.number("physical_critical") + ex_self_buffs.get("physicalCriticalUp", 0.0)
            + self_average.get("physicalCriticalUp", 0.0) - self_average.get("physicalCriticalDown", 0.0))
            * (1.0 - physical_crit_rate),
        )
        magic_crit = max(
            0.0,
            (panel.number("magic_critical") + ex_self_buffs.get("magicCriticalUp", 0.0)
            + self_average.get("magicCriticalUp", 0.0) - self_average.get("magicCriticalDown", 0.0))
            * (1.0 - magic_crit_rate),
        )
        physical_crit_multiplier = 1.0 + min(1.0, physical_crit * CRIT_STAT_TO_EXPECTED_DAMAGE)
        magic_crit_multiplier = 1.0 + min(1.0, magic_crit * CRIT_STAT_TO_EXPECTED_DAMAGE)

        self_speed = max(0.0, self_average.get("actionSpeedUp", 0.0))
        regular_speed_multiplier = 1.0 + min(1.0, self_speed)

        physical_dps = 0.0
        magic_dps = 0.0
        physical_single_dps = 0.0
        physical_aoe_per_target_dps = 0.0
        magic_single_dps = 0.0
        magic_aoe_per_target_dps = 0.0
        if int(unit["atk_type"]) == 1:
            normal = effective_atk * normal_events_per_second * regular_speed_multiplier * physical_crit_multiplier
            physical_dps += normal
            physical_single_dps += normal
        elif int(unit["atk_type"]) == 2:
            normal = effective_magic * normal_events_per_second * regular_speed_multiplier * magic_crit_multiplier
            magic_dps += normal
            magic_single_dps += normal

        # Re-evaluate damage formulas with the owner's average buffed attack values.
        for effect in raw_effects:
            if not effect["effect"].startswith("damage:"):
                continue
            # The raw effect amount used base attack. Scale the attack-dependent part by the
            # ratio of effective/base attack as a bounded approximation instead of pretending
            # to know every conditional formula variant.
            system = effect["effect"].split(":", 1)[1]
            if system == "PHYSICAL":
                base_attack = max(1.0, panel.number("atk"))
                amount = effect["amount"] * (0.6 + 0.4 * effective_atk / base_attack)
                contribution = amount * effect["eventsPerSecond"] * effect["branchWeight"]
                if effect["source"] == "loop":
                    contribution *= regular_speed_multiplier
                contribution *= physical_crit_multiplier
                physical_dps += contribution
                if effect["targetCount"] == 1:
                    physical_single_dps += contribution
                else:
                    physical_aoe_per_target_dps += contribution
            else:
                base_attack = max(1.0, panel.number("magic_str"))
                amount = effect["amount"] * (0.6 + 0.4 * effective_magic / base_attack)
                contribution = amount * effect["eventsPerSecond"] * effect["branchWeight"]
                if effect["source"] == "loop":
                    contribution *= regular_speed_multiplier
                contribution *= magic_crit_multiplier
                magic_dps += contribution
                if effect["targetCount"] == 1:
                    magic_single_dps += contribution
                else:
                    magic_aoe_per_target_dps += contribution

        # Scenario output keeps single-target and range damage separate.  A range attack still
        # hits the primary target once; additional targets add extra copies of its per-target
        # contribution.  This prevents a multi-target specialist from receiving its 3-target
        # ceiling in a single-boss context.
        physical_one_target = physical_single_dps + physical_aoe_per_target_dps
        physical_two_target = physical_single_dps + physical_aoe_per_target_dps * 2.0
        physical_three_target = physical_single_dps + physical_aoe_per_target_dps * 3.0
        magic_one_target = magic_single_dps + magic_aoe_per_target_dps
        magic_two_target = magic_single_dps + magic_aoe_per_target_dps * 2.0
        magic_three_target = magic_single_dps + magic_aoe_per_target_dps * 3.0
        one_target_dps = physical_one_target + magic_one_target
        two_target_dps = physical_two_target + magic_two_target
        three_target_dps = physical_three_target + magic_three_target
        pure_aoe_three_target = (physical_aoe_per_target_dps + magic_aoe_per_target_dps) * 3.0

        support = {
            "physicalAttackAdd": 0.0,
            "magicAttackAdd": 0.0,
            "allyPhysicalAttackDown": 0.0,
            "allyMagicAttackDown": 0.0,
            "allyPhysicalAttackRateDown": 0.0,
            "allyMagicAttackRateDown": 0.0,
            "allyPhysicalCriticalDown": 0.0,
            "allyMagicCriticalDown": 0.0,
            "allyPhysicalCriticalRateDown": 0.0,
            "allyMagicCriticalRateDown": 0.0,
            "physicalCriticalAdd": 0.0,
            "magicCriticalAdd": 0.0,
            "physicalDamageUp": 0.0,
            "magicDamageUp": 0.0,
            "actionSpeedThroughput": 0.0,
            "tpPerSecond": 0.0,
            "physicalDefenseDown": 0.0,
            "magicDefenseDown": 0.0,
            "healPerSecond": 0.0,
            "regenPerSecond": 0.0,
            "shieldAverage": 0.0,
            "controlCoverage": 0.0,
            "physicalDamageReduction": 0.0,
            "magicDamageReduction": 0.0,
            "enemyPhysicalAttackDown": 0.0,
            "enemyMagicAttackDown": 0.0,
            # Encounter-only positioning facts.  These are deliberately not folded into generic
            # team support/uplift: whether displacement is valuable depends on the enemy layout.
            "enemyPushDistance": 0.0,
            "enemyPullDistance": 0.0,
        }
        detail_rows = []
        for effect in raw_effects:
            key = effect["effect"]
            amount = effect["amount"]
            coverage = effect["coverage"]
            events = effect["eventsPerSecond"]
            team_factor = effect["teamTargetFactor"] * effect["branchWeight"]
            enemy_factor = effect["enemyTargetFactor"] * effect["branchWeight"]
            if key in {"physicalDefenseDown", "magicDefenseDown", "enemyPush", "enemyPull"}:
                factor = enemy_factor
            elif key in {
                "physicalAttackDown", "magicAttackDown", "physicalCriticalDown", "magicCriticalDown",
                "physicalAttackDownRate", "magicAttackDownRate",
                "physicalCriticalDownRate", "magicCriticalDownRate",
            }:
                factor = team_factor if effect["targetAssignment"] == 2 else enemy_factor
            else:
                factor = team_factor
            if factor <= 0:
                continue
            if key == "physicalAttackUp":
                support["physicalAttackAdd"] += amount * coverage * factor
            elif key == "magicAttackUp":
                support["magicAttackAdd"] += amount * coverage * factor
            elif key == "physicalCriticalUp":
                support["physicalCriticalAdd"] += amount * coverage * factor
            elif key == "magicCriticalUp":
                support["magicCriticalAdd"] += amount * coverage * factor
            elif key == "physicalAttackDown":
                if effect["targetAssignment"] == 2:
                    support["allyPhysicalAttackDown"] += amount * coverage * factor
                else:
                    support["enemyPhysicalAttackDown"] += amount * coverage * factor
            elif key == "magicAttackDown":
                if effect["targetAssignment"] == 2:
                    support["allyMagicAttackDown"] += amount * coverage * factor
                else:
                    support["enemyMagicAttackDown"] += amount * coverage * factor
            elif key == "physicalCriticalDown" and effect["targetAssignment"] == 2:
                support["allyPhysicalCriticalDown"] += amount * coverage * factor
            elif key == "magicCriticalDown" and effect["targetAssignment"] == 2:
                support["allyMagicCriticalDown"] += amount * coverage * factor
            elif key == "physicalAttackDownRate" and effect["targetAssignment"] == 2:
                support["allyPhysicalAttackRateDown"] += amount * coverage * factor
            elif key == "magicAttackDownRate" and effect["targetAssignment"] == 2:
                support["allyMagicAttackRateDown"] += amount * coverage * factor
            elif key == "physicalCriticalDownRate" and effect["targetAssignment"] == 2:
                support["allyPhysicalCriticalRateDown"] += amount * coverage * factor
            elif key == "magicCriticalDownRate" and effect["targetAssignment"] == 2:
                support["allyMagicCriticalRateDown"] += amount * coverage * factor
            elif key == "physicalDamageUp":
                support["physicalDamageUp"] += amount * coverage * factor / 100.0
            elif key == "magicDamageUp":
                support["magicDamageUp"] += amount * coverage * factor / 100.0
            elif key == "actionSpeedUp":
                support["actionSpeedThroughput"] += amount * coverage * factor
            elif key == "tpRecovery":
                support["tpPerSecond"] += amount * events * factor
            elif key == "tpRegen":
                support["tpPerSecond"] += amount * max(0.0, effect["duration"]) * events * factor
            elif key == "physicalDefenseDown":
                support["physicalDefenseDown"] += amount * coverage * factor
            elif key == "magicDefenseDown":
                support["magicDefenseDown"] += amount * coverage * factor
            elif key == "heal":
                support["healPerSecond"] += amount * events * factor
            elif key == "regeneration":
                support["regenPerSecond"] += amount * max(1.0, effect["duration"]) * events * factor
            elif key == "shield":
                support["shieldAverage"] += amount * coverage * factor
            elif key == "control":
                support["controlCoverage"] += min(1.0, coverage * factor)
            elif key == "physicalDamageReduction":
                support["physicalDamageReduction"] += amount * coverage * factor
            elif key == "magicDamageReduction":
                support["magicDamageReduction"] += amount * coverage * factor
            elif key == "enemyPush":
                # Presence matters more than repeated casts for the EX positioning use-case.  Keep
                # the strongest observed enemy displacement as an auditable raw distance instead
                # of inflating it by rotation frequency.
                support["enemyPushDistance"] = max(support["enemyPushDistance"], amount)
            elif key == "enemyPull":
                support["enemyPullDistance"] = max(support["enemyPullDistance"], amount)
            if key not in {"damage:PHYSICAL", "damage:MAGIC"}:
                detail_rows.append({
                    "skill": effect["skillName"],
                    "effect": key,
                    "amount": round(amount, 3),
                    "coverage": round(coverage, 3),
                    "targetFactor": round(effect["teamTargetFactor"], 3),
                    "condition": effect.get("condition"),
                    "source": effect["source"],
                })

        return {
            "unitId": unit_id,
            "name": str(unit["unit_name"]),
            "atkType": int(unit["atk_type"]),
            "position": int(unit["search_area_width"]),
            "build": {
                "enhanceId": build.enhance_id,
                "level": build.level,
                "rarity": build.rarity,
                "rank": build.rank,
                "equipmentSlots": list(build.equipment_slots),
                "uniqueLevel1": build.unique_level_1,
                "uniqueLevel2": build.unique_level_2,
            },
            "panel": panel.as_json(),
            "effective": {
                "atk": round(effective_atk, 1),
                "magicStr": round(effective_magic, 1),
                "physicalCritical": round(physical_crit, 1),
                "magicCritical": round(magic_crit, 1),
            },
            "rotation": {
                **pattern,
                "battleWindowSeconds": MODEL_BATTLE_SECONDS,
                "openerDuration": round(opener_duration, 3),
                "cycleDuration": round(cycle_duration, 3),
                "loopRepetitions": round(loop_repetitions, 3),
                "normalEventCount": round(normal_event_count, 3),
                "normalEventsPerSecond": round(normal_events_per_second, 5),
                "skillEventCounts": {str(key): round(value, 3) for key, value in skill_event_counts.items()},
                "skillEventsPerSecond": {str(key): round(value, 5) for key, value in skill_events_per_second.items()},
            },
            "skills": skills,
            "rawOutput": {
                "physicalDpsProxy": round(physical_one_target, 3),
                "magicDpsProxy": round(magic_one_target, 3),
                "physicalSingleComponent": round(physical_single_dps, 3),
                "physicalAoePerTargetComponent": round(physical_aoe_per_target_dps, 3),
                "magicSingleComponent": round(magic_single_dps, 3),
                "magicAoePerTargetComponent": round(magic_aoe_per_target_dps, 3),
                "oneTargetDpsProxy": round(one_target_dps, 3),
                "twoTargetDpsProxy": round(two_target_dps, 3),
                "threeTargetDpsProxy": round(three_target_dps, 3),
                "pureAoeThreeTargetProxy": round(pure_aoe_three_target, 3),
            },
            "rawSupport": {key: round(value, 5) for key, value in support.items()},
            "effectDetails": detail_rows[:24],
            "modelStatus": "database-v2",
        }


def _p95(values: Iterable[float]) -> float:
    ordered = sorted(value for value in values if value > 0 and math.isfinite(value))
    if not ordered:
        return 0.0
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * 0.95) - 1))
    return ordered[index]


def _scale(value: float, reference: float) -> float:
    if value <= 0 or reference <= 0:
        return 0.0
    return round(min(100.0, value / reference * 100.0), 1)


def analyze_roster(unit_ids: Iterable[int], database_path: Path = DEFAULT_DB) -> dict[int, dict]:
    model = CombatModel(database_path)
    try:
        analyses = {}
        for unit_id in unit_ids:
            result = model.analyze_unit(int(unit_id))
            if result is not None:
                analyses[int(unit_id)] = result

        physical_atks = [
            item["panel"]["atk"] for item in analyses.values() if item["atkType"] == 1 and item["panel"]["atk"] > 0
        ]
        magic_atks = [
            item["panel"]["magic_str"] for item in analyses.values() if item["atkType"] == 2 and item["panel"]["magic_str"] > 0
        ]
        reference_physical_atk = float(median(physical_atks)) if physical_atks else 1.0
        reference_magic_atk = float(median(magic_atks)) if magic_atks else 1.0

        for item in analyses.values():
            support = item["rawSupport"]
            physical_direct = (
                (support["physicalAttackAdd"] - support["allyPhysicalAttackDown"]) / max(1.0, reference_physical_atk)
                + (support["physicalCriticalAdd"] - support["allyPhysicalCriticalDown"]) * CRIT_STAT_TO_EXPECTED_DAMAGE
                + support["physicalDamageUp"]
                - support["allyPhysicalAttackRateDown"]
                - support["allyPhysicalCriticalRateDown"] * 0.5
            )
            magic_direct = (
                (support["magicAttackAdd"] - support["allyMagicAttackDown"]) / max(1.0, reference_magic_atk)
                + (support["magicCriticalAdd"] - support["allyMagicCriticalDown"]) * CRIT_STAT_TO_EXPECTED_DAMAGE
                + support["magicDamageUp"]
                - support["allyMagicAttackRateDown"]
                - support["allyMagicCriticalRateDown"] * 0.5
            )
            # Shared positive benefit is separated from system-specific benefit.  Ally-side
            # penalties remain signed and are applied to the relevant team rather than being
            # clamped away by the 0-100 display layer.
            common_direct = min(max(0.0, physical_direct), max(0.0, magic_direct))
            physical_specific = max(0.0, physical_direct - common_direct)
            magic_specific = max(0.0, magic_direct - common_direct)
            physical_penalty = min(0.0, physical_direct)
            magic_penalty = min(0.0, magic_direct)
            speed_uplift = max(0.0, support["actionSpeedThroughput"]) * (1.0 - REFERENCE_UB_DAMAGE_SHARE)
            tp_uplift = min(
                0.60,
                max(0.0, support["tpPerSecond"]) / REFERENCE_BASE_TP_PER_SECOND * REFERENCE_UB_DAMAGE_SHARE,
            )
            universal = common_direct + speed_uplift + tp_uplift
            item["supportImpact"] = {
                "physicalSpecificUplift": round(physical_specific, 5),
                "magicSpecificUplift": round(magic_specific, 5),
                "commonOffenseUplift": round(common_direct, 5),
                "speedThroughputUplift": round(speed_uplift, 5),
                "tpThroughputUplift": round(tp_uplift, 5),
                "universalUplift": round(universal, 5),
                "physicalTeamPenalty": round(physical_penalty, 5),
                "magicTeamPenalty": round(magic_penalty, 5),
                "physicalTeamUplift": round(physical_specific + universal + physical_penalty, 5),
                "magicTeamUplift": round(magic_specific + universal + magic_penalty, 5),
            }

        phys_output_ref = _p95(item["rawOutput"]["physicalDpsProxy"] for item in analyses.values())
        magic_output_ref = _p95(item["rawOutput"]["magicDpsProxy"] for item in analyses.values())
        phys_support_ref = _p95(item["supportImpact"]["physicalSpecificUplift"] for item in analyses.values())
        magic_support_ref = _p95(item["supportImpact"]["magicSpecificUplift"] for item in analyses.values())
        universal_ref = _p95(item["supportImpact"]["universalUplift"] for item in analyses.values())
        pdef_ref = _p95(item["rawSupport"]["physicalDefenseDown"] for item in analyses.values())
        mdef_ref = _p95(item["rawSupport"]["magicDefenseDown"] for item in analyses.values())
        heal_ref = _p95(
            item["rawSupport"]["healPerSecond"] + item["rawSupport"]["regenPerSecond"]
            for item in analyses.values()
        )
        shield_ref = _p95(item["rawSupport"]["shieldAverage"] for item in analyses.values())
        one_target_ref = _p95(item["rawOutput"]["oneTargetDpsProxy"] for item in analyses.values())
        two_target_ref = _p95(item["rawOutput"]["twoTargetDpsProxy"] for item in analyses.values())
        three_target_ref = _p95(item["rawOutput"]["threeTargetDpsProxy"] for item in analyses.values())
        aoe_ref = _p95(item["rawOutput"]["pureAoeThreeTargetProxy"] for item in analyses.values())
        phys_two_ref = _p95(
            item["rawOutput"]["physicalSingleComponent"] + item["rawOutput"]["physicalAoePerTargetComponent"] * 2
            for item in analyses.values()
        )
        phys_three_ref = _p95(
            item["rawOutput"]["physicalSingleComponent"] + item["rawOutput"]["physicalAoePerTargetComponent"] * 3
            for item in analyses.values()
        )
        magic_two_ref = _p95(
            item["rawOutput"]["magicSingleComponent"] + item["rawOutput"]["magicAoePerTargetComponent"] * 2
            for item in analyses.values()
        )
        magic_three_ref = _p95(
            item["rawOutput"]["magicSingleComponent"] + item["rawOutput"]["magicAoePerTargetComponent"] * 3
            for item in analyses.values()
        )

        for item in analyses.values():
            raw = item["rawSupport"]
            impact = item["supportImpact"]
            heal_value = raw["healPerSecond"] + raw["regenPerSecond"]
            survival_raw = heal_value + raw["shieldAverage"] * 0.08 + (
                raw["physicalDamageReduction"] + raw["magicDamageReduction"]
            ) * 1000.0
            survival_ref = max(1.0, heal_ref + shield_ref * 0.08)
            item["scores"] = {
                "physicalDamage": _scale(item["rawOutput"]["physicalDpsProxy"], phys_output_ref),
                "magicDamage": _scale(item["rawOutput"]["magicDpsProxy"], magic_output_ref),
                "physicalDamage1Target": _scale(item["rawOutput"]["physicalDpsProxy"], phys_output_ref),
                "physicalDamage2Target": _scale(
                    item["rawOutput"]["physicalSingleComponent"] + item["rawOutput"]["physicalAoePerTargetComponent"] * 2,
                    phys_two_ref,
                ),
                "physicalDamage3Target": _scale(
                    item["rawOutput"]["physicalSingleComponent"] + item["rawOutput"]["physicalAoePerTargetComponent"] * 3,
                    phys_three_ref,
                ),
                "magicDamage1Target": _scale(item["rawOutput"]["magicDpsProxy"], magic_output_ref),
                "magicDamage2Target": _scale(
                    item["rawOutput"]["magicSingleComponent"] + item["rawOutput"]["magicAoePerTargetComponent"] * 2,
                    magic_two_ref,
                ),
                "magicDamage3Target": _scale(
                    item["rawOutput"]["magicSingleComponent"] + item["rawOutput"]["magicAoePerTargetComponent"] * 3,
                    magic_three_ref,
                ),
                "physicalSupport": _scale(impact["physicalSpecificUplift"], phys_support_ref),
                "magicSupport": _scale(impact["magicSpecificUplift"], magic_support_ref),
                "universalSupport": _scale(impact["universalUplift"], universal_ref),
                "physicalDefenseDown": _scale(raw["physicalDefenseDown"], pdef_ref),
                "magicDefenseDown": _scale(raw["magicDefenseDown"], mdef_ref),
                "healing": _scale(heal_value, heal_ref),
                "survivalSupport": _scale(survival_raw, survival_ref),
                "controlSupport": round(min(100.0, raw["controlCoverage"] * 100.0), 1),
                "singleTargetDamage": _scale(item["rawOutput"]["oneTargetDpsProxy"], one_target_ref),
                "twoTargetDamage": _scale(item["rawOutput"]["twoTargetDpsProxy"], two_target_ref),
                "threeTargetDamage": _scale(item["rawOutput"]["threeTargetDpsProxy"], three_target_ref),
                "aoeDamage": _scale(item["rawOutput"]["pureAoeThreeTargetProxy"], aoe_ref),
                # Capability scores intentionally use a fixed positive confidence once a concrete
                # enemy MOVE action is present.  Raw distance remains in rawSupport for auditing;
                # this avoids treating an internal companion MOVE row as thousands of score.
                "enemyPush": 82.0 if raw["enemyPushDistance"] > 0.0 else 0.0,
                "enemyPull": 82.0 if raw["enemyPullDistance"] > 0.0 else 0.0,
            }
            item["normalization"] = {
                "referencePhysicalAttack": round(reference_physical_atk, 1),
                "referenceMagicAttack": round(reference_magic_atk, 1),
                "physicalOutputP95": round(phys_output_ref, 3),
                "magicOutputP95": round(magic_output_ref, 3),
            }
        return analyses
    finally:
        model.close()


if __name__ == "__main__":
    connection = sqlite3.connect(DEFAULT_DB)
    try:
        unit_ids = [row[0] for row in connection.execute("SELECT unit_id FROM labyrinth_npc_unit_data")]
    finally:
        connection.close()
    result = analyze_roster(unit_ids)
    print(f"modeled={len(result)}")
    for unit_id in (110301, 108901, 109101, 100101):
        if unit_id in result:
            item = result[unit_id]
            print(unit_id, item["name"], item["panel"], item["scores"], item["supportImpact"])
