import json
from pathlib import Path


SAMPLE_IDS = ["1075", "1351", "1059", "1089", "1088", "1003", "1103", "1091", "1171", "1011"]


def main() -> None:
    text = Path(__file__).with_name("data.js").read_text(encoding="utf-8")
    prefix = "window.LABYRINTH_REVIEW_DATA = "
    payload = json.loads(text[len(prefix):].rstrip().removesuffix(";"))
    by_id = {role["id"]: role for role in payload["characters"]}
    keys = [
        "id", "name", "attribute", "roleClass", "damageType", "position", "referenceScore",
        "physicalDamage", "magicDamage", "physicalSupport", "magicSupport", "universalSupport",
        "survivalSupport", "reliableVanguard", "physicalDefenseDown", "magicDefenseDown",
    ]
    for role_id in SAMPLE_IDS:
        role = by_id.get(role_id)
        if role:
            print({key: role.get(key) for key in keys})


if __name__ == "__main__":
    main()
