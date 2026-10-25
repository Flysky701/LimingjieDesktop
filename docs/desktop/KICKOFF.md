# 总指挥启动提示词（整段作为第一条消息发送）

你是「黎明界助手 Windows 桌面版移植」的总指挥，同时也是主实现者。

**目标**：把 `wbero/limingjie-assistant`（Kotlin + Jetpack Compose 的 Android 助手，基线 commit `b809b196`，版本 1.0.18）移植为一款类似 MAA 的 Windows 桌面 EXE。
核心原则：**业务能力和行为保持一致，界面按桌面使用方式重新设计。** 允许调整、美化功能页面，不能只加桌面外壳后照搬手机布局。助手页面、逻辑和数据管理均在电脑上，通过 ADB 连接模拟器完成截图、触控和相关任务。模拟器只运行游戏，无需安卓版助手。
功能覆盖上游全部模块：账号与登录、黎明界刷开局、画面自动化、队伍编组、策略设置、个人角色评分、任务控制、诊断面板与日志包、数据库更新。会战助手在上游未接入 UI，桌面版同样不接入（代码照常编译和测试）。

## 一、先读，读完再动手

1. `AGENTS.md`（优先级最高）
2. `docs/desktop/PLAN.md`
3. `docs/desktop/specs/` 下全部 SPEC
4. `docs/desktop/EDGE_CASES.md`、`docs/desktop/ACCEPTANCE.md`、`docs/desktop/WORKSTREAMS.md`
   以及 `docs/desktop/registers/` 下已预填的登记表（DECISIONS 中 D-28…D-36 为原方案取舍，D-37 为用户授权的桌面界面修订；FALLBACKS 为允许的回退）
5. 上游 `README.md`、`参考来源与洁净实现边界.md`、`THIRD_PARTY_NOTICES.md`，以及 `android/docs/` 中与当前任务相关的文档
6. 上游 `android/app/src/main/java/com/landosol/toolbox/LandosolToolboxApplication.kt`：装配根，是桌面装配的行为基准

## 二、工作方式

- **自主推进**，不等待确认。方案没覆盖到的问题，按「上游代码事实 > 本方案 > 通用最佳实践」决策，写进 `registers/DECISIONS.md`，然后继续。
- 只在 `AGENTS.md §9` 列出的情况下向用户提问。
- **先做 M0**（环境探测 + 基线测量），结果写入 `registers/PROGRESS.md`，内容包括：
  - 操作系统、是否 Windows、JDK 版本
  - Maven Central / Google Maven / GitHub 是否可达
  - 是否有 adb、已连接哪些设备；是否有 Android SDK
  - 重新测量 PLAN.md §2 的数字，有偏差就更新 PLAN.md
  据此确定离线验收和实机验收（L1/L2/L3）各自能做到哪一级。
- **先展示主要页面原型**：附简短功能清单，再实现桌面页面。界面验收保持简单，不新增 Gate、不逐字段列证据、不做全页面截图矩阵；已有检查可复用。授权内设计直接推进，实际反馈如实记录，后端可并行。

- **M1 必须由你本人完成**：共享源码编译骨架、垫片、同名替身、上游纯搬移补丁，以及冻结 PLAN.md §6 列出的端口契约。契约冻结后，再按 `WORKSTREAMS.md` 并行派发子代理或多开会话。
- 每个里程碑结束时，执行 `ACCEPTANCE.md` 中对应的 Gate；依赖它的阶段须在通过后开始，并更新 PROGRESS。原型与 M1 可并行，独立后端工作不因界面原型等待而停下。

## 三、派发子代理

- 使用 `WORKSTREAMS.md` 中的模板。每个子代理一个 git worktree 和分支 `desktop/ws-<id>`，只改自己拥有的路径。
- 子代理交付三样东西：代码、测试、报告（模板在 WORKSTREAMS.md）。你审查后按合并顺序合入 `desktop/main`。
- 审查重点：
  - 是否违反 AGENTS.md §3 反防御性规则
  - 是否改了不属于自己的路径
  - 是否新增了未登记的回退、重试或配置项
  - 测试是否真实覆盖了对应的 EDGE_CASES 条目
  - 是否复制了应共享的业务实现；呈现重设计是否遗漏字段、确认、状态或动作

## 四、交付物（全部完成才算结束）

1. `desktop/` 下可构建的 Gradle 工程。打包任务（名称以 SPEC-60 为准）产出便携 zip，解压后双击 `LimingjieAssistant.exe` 即可启动。
2. 上游 Android 工程仍能构建，单测通过（本地没有 Android SDK 时由 CI 验证）。
3. `.github/workflows/desktop-ci.yml`：在 windows-latest 上完成构建、测试、打包，运行 `--self-test`，并上传产物。原有 `android-ci.yml` 保持不变并继续通过。
4. `docs/desktop/registers/` 下的全部登记表填写完整；面向用户的 `README-desktop.md`（安装、模拟器设置、首次使用、常见问题）。
5. 主要页面原型、简短设计说明和功能清单；少量代表截图与典型操作结论可复用已有记录，不另建一套验收材料。
6. 按 `ACCEPTANCE.md §7` 模板写的最终报告 `docs/desktop/FINAL_REPORT.md`；所有未验证的实机项汇总到 `docs/desktop/USER_CHECKLIST.md`（ACCEPTANCE §6.5）。

现在开始执行 M0。
