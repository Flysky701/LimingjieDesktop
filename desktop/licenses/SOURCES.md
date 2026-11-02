# Distribution license sources

- Maven dependency metadata and embedded texts: the exact resolved runtime JAR/POM artifacts listed in `DEPENDENCIES.md`; copied without modifying legal text.
- Apache-2.0: copied from the pinned MaaTouch v1.1.0 license in `desktop/device/licenses/`.
- ONNX Runtime v1.21.0 MIT: https://raw.githubusercontent.com/microsoft/onnxruntime/v1.21.0/LICENSE
- JSON-java 20240303 public-domain declaration: https://raw.githubusercontent.com/stleary/JSON-java/20240303/LICENSE
- Skia BSD notice: https://raw.githubusercontent.com/google/skia/main/LICENSE; Skiko's published Maven artifacts declare Apache-2.0, while native Skia code retains its own terms.
- GPL-3.0 text retained for the upstream LandosolRoster-derived catalog notice: https://www.gnu.org/licenses/gpl-3.0.txt
- JNA is offered under Apache-2.0 OR LGPL-2.1-or-later; this distribution uses the Apache-2.0 option and preserves JNA's embedded dual-license notice.
- MaaTouch and OCR model-specific provenance/licenses are copied into the distribution's `licenses/MaaTouch` and `licenses/OCR` directories.
- The generated runtime keeps its JRE `legal` directory.

The application icon is project artwork drawn from `desktop/app/src/main/resources/desktop/app.svg` and exported as a PNG and multi-size ICO (16, 24, 32, 48, 64, 128, 256 pixels).
