# SPEC-60 打包、自检与 CI（WS-G）

## 1. 发行形态（D-15）

- Compose Desktop `nativeDistributions`，执行 `createReleaseDistributable` 生成 jpackage app-image（内置 JRE 21），再由自定义任务 `packagePortableZip` 打成便携 zip。
- 产物：`desktop/app/build/dist/LimingjieAssistant-desktop-<显示版本>-win-x64.zip`。zip 内只有一个根目录 `LimingjieAssistant\`：

```text
LimingjieAssistant\
  LimingjieAssistant.exe
  app\  runtime\
  portable.flag                 # 发行包默认便携模式（D-14）
  README-desktop.md
  LICENSE                       # 上游 CC BY-NC-SA 4.0
  THIRD_PARTY_NOTICES.md        # 上游声明 + 桌面新增依赖、模型、MaaTouch
  licenses\                     # 各依赖许可证全文
```

- exe 名称、`packageName` 只用 ASCII（`LimingjieAssistant`）；窗口标题和快捷方式描述用中文。
- `packageVersion` 必须满足 jpackage 在 Windows 上的限制（最多三段数字，MAJOR / MINOR ≤ 255，BUILD ≤ 65535），直接使用上游 `versionName`（例如 `1.0.18`）。`+desktop.<n>` 只用于显示和文件名。
- `includeAllModules = true`（避免 jlink 漏掉 `jdk.crypto.ec`、`jdk.localedata`、`jdk.httpserver`、`java.naming` 等模块，R-09）。
- ProGuard / 混淆：关闭（Room、kotlinx-serialization、ONNX Runtime、JNA 都依赖反射或原生绑定）。
- JVM 参数：`-Dfile.encoding=UTF-8`、`-Dsun.stdout.encoding=UTF-8`、`-Dsun.stderr.encoding=UTF-8`、`-Djava.net.useSystemProxies=true`、`-Xmx1536m`。不添加其他调优参数，除非有测量证据并登记。
- 图标：从上游应用图标生成 `.ico`（多尺寸），来源登记到 THIRD_PARTY_NOTICES。
- 不做代码签名（没有证书）。README 写明 SmartScreen 提示的处理方式（「更多信息 → 仍要运行」）和校验 SHA-256 的方法；Release 页面同时提供 SHA-256。

## 2. 随包二进制与模型

| 项 | 来源 | 要求 |
|---|---|---|
| MaaTouch | `MaaAssistantArknights/MaaTouch` 官方 Release 资产（或在 CI 中从源码构建） | 版本、URL、SHA-256 写在 `desktop/device/MAATOUCH.md`；构建时下载并校验 SHA-256（有缓存），校验失败则构建失败；Apache-2.0，NOTICE 收入 licenses |
| OCR 模型 | 见 SPEC-20 §3 | 入库，构建时校验 SHA-256 |
| ONNX Runtime / JNA / SQLite bundled | Maven 依赖（含原生库） | 版本固定；许可证收入 licenses |
| adb | **不随包**（D-18） | 由用户环境提供或用户手动下载 |

## 3. `--self-test`

`LimingjieAssistant.exe --self-test --report <path> [--data-dir <dir>]`：不显示窗口，执行检查，把结果写成 JSON，退出码 0 表示全部通过，1 表示有失败。

| 检查 | 内容 |
|---|---|
| `resources` | 资产数量与构建时记录的清单一致；抽取一个模板用 `BitmapFactory` 垫片解码，尺寸正确 |
| `ocr` | 加载模型；对内置的样例图推理，输出等于期望文本 |
| `room` | 在临时数据目录中创建数据库、执行迁移、写入并读回一条记录 |
| `sqlite-bundled` | 原生库加载并执行 `select sqlite_version()` |
| `dpapi` | protect / unprotect 往返（非 Windows 时为 `skipped`） |
| `tls` | 对上游使用的 SDK 主机做 TLS 握手（只握手，不发业务请求）；没有网络时为 `skipped` 并写明原因，不算失败 |
| `jna` / `kernel32` | 加载成功，`SetThreadExecutionState` 符号可解析（不实际调用） |
| `maatouch` | 资源存在且 SHA-256 正确 |
| `data-dir` | 数据目录可写（CI 中使用包含中文和空格的路径） |
| `fonts` | 系统中存在可用的 CJK 字体 |

报告格式：`{ "version": …, "checks": [ { "name", "status": "passed|failed|skipped", "detail", "millis" } ] }`。

## 4. CI：`.github/workflows/desktop-ci.yml`

触发：`push` 到 `desktop/**` 分支；`pull_request`；`workflow_dispatch`。路径过滤包括 `desktop/**`、`android/app/src/**`、`android/app/build.gradle.kts`、`android/app/schemas/**`、`docs/desktop/**` 以及该工作流文件本身。

**Job 1 `jvm-test`（ubuntu-latest）**
1. checkout；Temurin JDK 21；`gradle/actions/setup-gradle`。
2. `./gradlew -p desktop check`：包含上游测试、桌面单测、`checkShimCollisions`、`checkSubstituteSignatures`、`checkUpstreamDrift`、`:ocr:evaluate` 的准确率门槛。
3. 测试后执行 `git status --porcelain --untracked-files=all -- android/`，输出非空则失败（CI 为干净检出；本地用前后快照比对，见 SPEC-00 §11）。
4. 上传测试报告。

**Job 2 `android-verify`（ubuntu-latest）**：验证上游补丁没有破坏 Android 端。JDK 17，在 `android/` 下执行与 `android-ci.yml` 完全相同的 `./gradlew lintDebug testDebugUnitTest assembleDebug`。（`android-ci.yml` 只在 main 分支和 PR 上触发，桌面分支上的推送靠这个 job 覆盖；`android-ci.yml` 本身保持不变。）

**Job 3 `windows-package`（windows-latest，依赖 Job 1）**
1. checkout **之前**执行 `git config --global core.autocrlf false` 和 `git config --global core.eol lf`，避免文本文件在 Windows 上被转成 CRLF（上游的 golden 文件和 fixture 依赖 LF）。
2. JDK 21；`desktop\gradlew.bat -p desktop check packagePortableZip`（Windows 上再跑一遍测试，包括 DPAPI 和路径相关测试）。
3. 把 zip 解压到 `$env:RUNNER_TEMP\测试 目录\`，执行 `Start-Process -Wait -PassThru` 运行 `--self-test --data-dir "$env:RUNNER_TEMP\测试 目录\data"`，检查退出码并打印报告。
4. 上传产物：zip、zip 的 SHA-256、self-test 报告、测试报告。

缓存：Gradle 缓存由 setup-gradle 处理；MaaTouch 下载缓存使用 `actions/cache`，键为其 SHA-256。

## 5. 面向用户的文档 `README-desktop.md`（M7 编写，随包分发）

内容：系统要求（Windows 10/11 x64，4 GB 内存以上）；下载与校验；解压位置建议（不要放在 Program Files）；MuMu 12 / 雷电 9 的设置（分辨率、ADB 调试开关的位置、多开时的端口）；首次使用流程（配置与任务控制在 Windows 完成，模拟器只运行游戏，无需安卓版助手）；桌面入口、任务状态、保存与取消说明；dryRun 与真实操作的区别；验证码在浏览器中完成；常见问题（找不到设备、adb 冲突、截图慢或全黑、触控无效、SmartScreen、代理与直连、PAC 不支持、`SKIKO_RENDER_API=SOFTWARE` 解决界面空白、VC++ 运行库）；诊断包导出与反馈方式；数据目录与备份；许可与风险声明（沿用上游原文）。

## 6. G5 标准

1. Windows CI 三个 job 全部通过，产物已上传。
2. 中文 + 空格路径下 self-test 通过。
3. zip 的结构、文件名和版本号符合 §1；THIRD_PARTY_NOTICES 和 licenses 完整（每个运行时依赖都有对应条目）。
4. 在干净的 Windows 环境（CI runner 之外，例如 Windows Sandbox，如果执行环境允许）双击启动能显示主窗口；做不到时把它列入用户实机清单。
