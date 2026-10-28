import sqlite3
from pathlib import Path


DB = Path("pick/autopcr-android/app/src/main/python/cache/db/202604021043.db")
TABLES = ["unit_data", "unit_role_data", "unit_skill_data", "skill_data", "skill_action"]
SAMPLE_ORIGINAL_IDS = [1010, 1012, 1037, 1103, 1089, 1088, 1075, 1351]


def main() -> None:
    connection = sqlite3.connect(DB)
    try:
        for table in TABLES:
            print(f"\n[{table}]")
            for row in connection.execute(f"PRAGMA table_info({table})"):
                print(row)
        print("\n[first unit_data rows]")
        for row in connection.execute(
            "SELECT unit_id, original_unit_id, unit_name, atk_type, search_area_width, guild_id "
            "FROM unit_data ORDER BY unit_id LIMIT 20"
        ):
            print(row)
        print("\n[sample skill descriptions]")
        for short_id in SAMPLE_ORIGINAL_IDS:
            unit_id = short_id * 100 + 1
            skill_row = connection.execute("SELECT * FROM unit_skill_data WHERE unit_id = ?", (unit_id,)).fetchone()
            if not skill_row:
                continue
            columns = [item[1] for item in connection.execute("PRAGMA table_info(unit_skill_data)")]
            skill_ids = sorted({int(value) for name, value in zip(columns, skill_row) if name != "unit_id" and value})
            descriptions = connection.execute(
                f"SELECT skill_id, name, description FROM skill_data WHERE skill_id IN ({','.join('?' for _ in skill_ids)}) ORDER BY skill_id",
                skill_ids,
            ).fetchall() if skill_ids else []
            print(short_id, descriptions[:8])
    finally:
        connection.close()


if __name__ == "__main__":
    main()
