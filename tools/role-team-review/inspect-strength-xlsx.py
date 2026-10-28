import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"


def column_index(cell_ref: str) -> int:
    letters = re.match(r"[A-Z]+", cell_ref).group(0)
    value = 0
    for char in letters:
        value = value * 26 + ord(char) - ord("A") + 1
    return value - 1


def shared_strings(archive: zipfile.ZipFile) -> list[str]:
    path = "xl/sharedStrings.xml"
    if path not in archive.namelist():
        return []
    root = ET.fromstring(archive.read(path))
    ns = {"m": MAIN_NS}
    return [
        "".join(node.text or "" for node in item.findall(".//m:t", ns))
        for item in root.findall("m:si", ns)
    ]


def sheet_paths(archive: zipfile.ZipFile) -> list[tuple[str, str]]:
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    rels = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    rel_by_id = {
        rel.attrib["Id"]: rel.attrib["Target"]
        for rel in rels.findall(f"{{{PKG_REL_NS}}}Relationship")
    }
    output = []
    for sheet in workbook.findall(f".//{{{MAIN_NS}}}sheet"):
        rel_id = sheet.attrib[f"{{{REL_NS}}}id"]
        target = rel_by_id[rel_id].replace("\\", "/")
        if target.startswith("/"):
            target = target.lstrip("/")
        elif not target.startswith("xl/"):
            target = "xl/" + target
        output.append((sheet.attrib["name"], target))
    return output


def cell_value(cell: ET.Element, strings: list[str]) -> str:
    cell_type = cell.attrib.get("t")
    value = cell.find(f"{{{MAIN_NS}}}v")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.findall(f".//{{{MAIN_NS}}}t"))
    if value is None or value.text is None:
        return ""
    raw = value.text
    if cell_type == "s":
        try:
            return strings[int(raw)]
        except (ValueError, IndexError):
            return raw
    return raw


def read_rows(archive: zipfile.ZipFile, sheet_path: str, strings: list[str], limit: int) -> list[list[str]]:
    root = ET.fromstring(archive.read(sheet_path))
    rows = []
    for row in root.findall(f".//{{{MAIN_NS}}}sheetData/{{{MAIN_NS}}}row")[:limit]:
        values: list[str] = []
        for cell in row.findall(f"{{{MAIN_NS}}}c"):
            index = column_index(cell.attrib["r"])
            while len(values) <= index:
                values.append("")
            values[index] = cell_value(cell, strings)
        rows.append(values)
    return rows


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("攻略/公主连结角色黎明界强度表无图片.xlsx")
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 12
    with zipfile.ZipFile(path) as archive:
        strings = shared_strings(archive)
        result = {
            name: read_rows(archive, sheet_path, strings, limit)
            for name, sheet_path in sheet_paths(archive)
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
