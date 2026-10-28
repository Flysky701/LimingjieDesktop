import sqlite3
from pathlib import Path


DB = Path("pick/autopcr-android/app/src/main/python/cache/db/202604021043.db")


def print_unit(connection: sqlite3.Connection, unit_id: int) -> None:
    connection.row_factory = sqlite3.Row
    skill_row = connection.execute("SELECT * FROM unit_skill_data WHERE unit_id = ?", (unit_id,)).fetchone()
    if not skill_row:
        print(f"unit {unit_id}: no skill row")
        return
    skill_columns = [row[1] for row in connection.execute("PRAGMA table_info(unit_skill_data)")]
    skill_ids = sorted({int(skill_row[column]) for column in skill_columns if column != "unit_id" and skill_row[column]})
    for skill_id in skill_ids:
        skill = connection.execute("SELECT * FROM skill_data WHERE skill_id = ?", (skill_id,)).fetchone()
        if not skill:
            continue
        print(f"\nSKILL {skill_id} {skill['name']} cast={skill['skill_cast_time']}")
        print(skill["description"])
        for index in range(1, 11):
            action_id = skill[f"action_{index}"]
            if not action_id:
                continue
            action = connection.execute("SELECT * FROM skill_action WHERE action_id = ?", (action_id,)).fetchone()
            if not action:
                continue
            print(
                " action",
                index,
                "id=", action["action_id"],
                "type=", action["action_type"],
                "detail=", (action["action_detail_1"], action["action_detail_2"], action["action_detail_3"]),
                "values=", tuple(action[f"action_value_{i}"] for i in range(1, 8)),
                "target=", (
                    action["target_assignment"], action["target_type"], action["target_number"],
                    action["target_range"], action["target_area"], action["target_count"],
                ),
                "desc=", action["description"],
            )


def main() -> None:
    connection = sqlite3.connect(DB)
    try:
        print("ACTION TYPE COUNTS")
        for row in connection.execute(
            "SELECT action_type, COUNT(*) FROM skill_action GROUP BY action_type ORDER BY action_type"
        ):
            print(row)
        for unit_id in (100101, 110301, 108901, 108801, 109101):
            print("\n" + "=" * 80)
            print("UNIT", unit_id)
            print_unit(connection, unit_id)
    finally:
        connection.close()


if __name__ == "__main__":
    main()
