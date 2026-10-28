import sqlite3
from pathlib import Path


DB = Path("pick/autopcr-android/app/src/main/python/cache/db/202604021043.db")
TABLES = [
    "unit_attack_pattern",
    "unit_rarity",
    "unit_promotion_status",
    "unit_promotion",
    "equipment_data",
    "unit_unique_equip",
    "unit_unique_equipment",
    "experience_unit",
    "unit_status_coefficient",
    "exceed_level_unit",
    "unit_ex_equipment_slot",
    "ex_equipment_data",
    "labyrinth_npc_unit_data",
    "labyrinth_npc_unit_enhance_data",
]


def main():
    connection = sqlite3.connect(DB)
    try:
        existing = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table in TABLES:
            if table not in existing:
                continue
            print(f"\n[{table}]")
            for row in connection.execute(f"PRAGMA table_info({table})"):
                print(row)
            print("sample:")
            for row in connection.execute(f"SELECT * FROM {table} LIMIT 3"):
                print(row)
    finally:
        connection.close()


if __name__ == "__main__":
    main()
