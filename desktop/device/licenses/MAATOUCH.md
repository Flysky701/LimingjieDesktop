# MaaTouch

- Upstream: https://github.com/MaaAssistantArknights/MaaTouch
- Version: v1.1.0
- Asset: https://github.com/MaaAssistantArknights/MaaTouch/releases/download/v1.1.0/maatouch
- SHA-256: `4ea8590cd0349ce900f39ab16ef3751dad2356286b465b4293f80f9858c995d0`
- License: Apache-2.0, reproduced in `MaaTouch-LICENSE.txt` from the v1.1.0 tag.
- Asset is packaged unmodified at `/device/maatouch`; build and runtime both check its pinned checksum.
- The device layer pushes it only when the user connects in MaaTouch mode. ADB is not bundled.
