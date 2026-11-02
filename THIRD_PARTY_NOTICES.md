# Third-Party Notices

Project author: **wbero**. QQ discussion group: **1065226139**.

The root CC BY-NC-SA 4.0 license applies to this project's own contributions,
not to third-party works under different terms.

## AutoPCR

The upstream AutoPCR project (Python) is a behavioral, protocol, and
Labyrinth opening-reroll reference for the native Kotlin implementation
(Python runtime and source files are not bundled).

Upstream project: <https://github.com/cc004/autopcr>

Upstream license: [Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International](https://github.com/cc004/autopcr/blob/main/LICENSE).

## 花阳亲的米团子

The Labyrinth opening-reroll approach also references 花阳亲的米团子 (Go, CC BY-NC-SA 4.0),
as credited by the project author. The local reference checkout is excluded from this Git repository.

## Community character scores and strategy data

Player ratings are aggregated from scores submitted by members of the
discussion group. Public attribution of individual contributors requires their agreement;
raw submissions and personal information should not be uploaded.

The imported Labyrinth workbook and derived relic/event data have separate
source and redistribution terms. Keep their provenance and confirm
permission before redistribution.

## LandosolRoster character names

The character-name catalog is derived from the `CHARA_NAME` and
`UnavailableChara` tables in Ice9Coffee/LandosolRoster:
<https://github.com/Ice9Coffee/LandosolRoster>

Repository license: **GNU General Public License v3.0 (GPL-3.0)**.
A deterministic offline importer converts only IDs, names, aliases, and availability into
the app's `CharacterResource` JSON schema. Keep this notice and the GPL-3.0
license text with any release that ships generated catalog data. Confirm applicable
distribution obligations or replace the derived catalog before public redistribution.

## priconne-database

The Chinese-service unit and skill database is used as an ID/skill
schema reference during resource validation; the SQLite database itself is
not bundled in the APK:
<https://github.com/SonderXiaoming/priconne-database>

## redive.estertion.win unit images

Character icon files are cached from <https://redive.estertion.win/icon/unit/>.
Princess Connect! Re:Dive game imagery rights remain with the original rights holders.

## Kokkoro Clan Battle Assistant

Project: <https://github.com/wbero/kokkoro-clan-battle-assistant>

Owned by this project's owner; its Kotlin code, visual resources, tests, and axis formats are shared or migrated into this application.

## Current CN database delivery

Database releases are supplied through <https://github.com/wbero/autopcr-db-builder>.
The app downloads and validates the database at runtime; game data ownership remains with the original rights holders.

## Android and Kotlin libraries

AndroidX, Kotlin, Gradle, OkHttp, kotlinx.serialization, and other build/runtime dependencies are subject to their respective licenses.
The game protocol MessagePack and AES-CBC layer is a native Kotlin implementation in this repository.

## Geetest

When Bilibili requests secondary verification, the app loads the official web component for the user to complete verification manually.

## Windows desktop distribution

The resolved Windows runtime dependency inventory is in
`licenses/DEPENDENCIES.md` in the portable archive (`desktop/licenses/` in source).
It lists the Maven coordinates, published POM license declarations, and
embedded notices for Kotlin, Compose Desktop/AndroidX, coroutines,
serialization, Room/SQLite, OkHttp/Okio, apksig, ONNX Runtime, JNA, Skiko, and
JSON-java. JNA is used under its Apache-2.0 option with its dual-license notice retained.
ONNX Runtime's `ThirdPartyNotices` is retained alongside its MIT license.
The bundled JRE retains its `runtime/legal` directory.

The Windows portable ZIP strips unused non-Windows native libraries and debug symbols
from the ONNX Runtime, bundled SQLite, and JNA JARs while retaining all Java classes,
Windows x64 libraries, and license/NOTICE files.

MaaTouch v1.1.0 is distributed unmodified from
<https://github.com/MaaAssistantArknights/MaaTouch/releases/tag/v1.1.0> under
Apache-2.0 (`licenses/MaaTouch/`). OCR model provenance and licenses are in `licenses/OCR/`.
ADB is not bundled; the device page can download official Android Platform Tools on user request.

The application icon is project artwork drawn from `desktop/app/src/main/resources/desktop/app.svg`.
The desktop UI uses 20 unmodified Material Symbols Rounded SVG resources from
[google/material-design-icons](https://github.com/google/material-design-icons)
under Apache-2.0 (`licenses/material-symbols/LICENSE.txt`).
The Light Modern and Dark Modern color mappings follow the default themes in
[microsoft/vscode](https://github.com/microsoft/vscode/tree/main/extensions/theme-defaults/themes)
under MIT (`licenses/vscode/LICENSE.txt`).
License text provenance is recorded in `licenses/SOURCES.md`.
