# desktop.8 候选包交付记录

日期：2026-10-27。版本：`1.0.18+desktop.8`。状态：本地测试候选，未发布。

## 基线与产物

- 分支：`codex/baseline-desktop-8`。
- 构建源码提交：`f9dae1dc5e928d5e2c8e920dd4750928c2adbec8`。本记录在构建后补入，不改变程序内容。
- 保留点：本地标签 `baseline-desktop8-20261027`，包含源码与本交付记录；不是正式发布标签。
- ZIP：`dist/desktop.8-candidate/LimingjieAssistant-desktop-1.0.18+desktop.8-win-x64.zip`。
- 文件大小：161,880,943 字节，约 154.4 MiB。
- SHA-256：`3a4b0c6a160c75bcee6f090e808d3ff027ca40f56381fdd09e65d0096be3e06c`，同目录附 `.sha256`。
- 已解压程序：`dist/desktop.8-candidate/LimingjieAssistant/LimingjieAssistant.exe`。
- 包含：Java 21、Windows x64 运行库、识别资源、使用指南、基础版说明、用户验收清单及许可。

该目录由本轮新建，没有覆盖旧程序或复制原账号数据。自检使用 `.tools/desktop8-self-test-data`，后台进程检查使用临时目录与模拟凭据；用户测试目录未写入这些数据。

## 本轮实际验证

| 检查 | 结果 |
|---|---|
| `:app:packagePortableZip --offline --no-daemon --max-workers=2` | 54 秒成功，包含程序编译、应用镜像、现有许可证检查、原生库裁剪和校验文件生成。 |
| ZIP 身份与版本 | SHA-256 与生成文件一致；从该 ZIP 解压后执行自检，报告版本为 `1.0.18+desktop.8`。 |
| 解压后运行环境自检 | 10 项全部通过，0 跳过；覆盖字体、资源、OCR 样图、SQLite、Room 迁移、DPAPI、JNA、MaaTouch 资源及 TLS 握手。 |
| `SharedPackagedRuntimeTest` | 1 项通过，0 失败、0 错误、0 跳过。实际启动该 ZIP 中的后台进程，核对共享数据、配置保存、实例隔离与退出释放。未发现设备或登录游戏。 |

运行环境 OCR 样图通过只证明随包模型可用，不替代 OCR 数据集准确率评估。TLS 检查只进行握手，没有账号或游戏接口请求。

本地原始记录：`.tools/desktop8-package.log`、`.tools/desktop8-self-test.json`、`.tools/desktop8-packaged-runtime.log`，以及 `desktop/app/build/test-results/test/TEST-com.landosol.toolbox.desktop.app.packaging.SharedPackagedRuntimeTest.xml`。

## 复用结果与待用户测试

本轮复用五项公共优化、设备发现与清理、坐标修正的既有定向结果，未重复全量测试。变更范围见 [基础版说明](BASELINE-DESKTOP8.md)。未运行 Android 全量构建或远端 CI，没有调整门禁。

本候选包的界面实际操作、真实登录与完整游戏循环由用户测试；优先项目见 [用户验收清单](../USER_CHECKLIST.md) 顶部的 desktop.8 单开部分。未量化 CPU、内存或整局耗时收益。现有 OCR 归档为 14/15，准确率及样本覆盖尚未达到项目目标，发布前需如实处理对应 CI 门禁状态，不能由本轮自检结果代替。

未推送源码、未推送正式发布标签、未上传 GitHub Release。测试完成后再决定修正或发布；内部拆解路线和旧版程序继续独立保留。
