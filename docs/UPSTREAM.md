# 历史来源与维护边界

`LimingjieDesktop` 是独立的 Windows 原生桌面项目，业务源码由桌面 `:core` 维护。Android 兼容、基于上游提交 SHA 的自动锁定和同步已永久退役，上游资料仅用于追溯历史。模块分工和验证入口见 [开发文档](DEVELOPMENT.md)。

## 历史来源与业务基线

- 最初的业务代码源于 `wbero/limingjie-assistant`（基线 `b809b196988b019571caf5679ccf8d7ebb7f072d`）。历史上合入 `limingjie-assistant-copy`（`143d2b2115eeb2410cc0baf63334cb193b1fdd6d`）及 [SatenRuiko10032/limingjie-assistant](https://github.com/SatenRuiko10032/limingjie-assistant) 的 PR #29，包括 3/4/5 区商店重构、三档前排生存模式、EX/遗物模板、事件复用与 1088 恢复。
- 桌面业务保留十公会、开局候选池与赠送角色、自定义事件和路线及预览、选人确认、遗物唯一持有与层数、前排生存门槛及普战跳编队开关。现有运行时包括结算宝箱推进衔接、当前帧地图点击证据、已识别路线清空未知计时、入口慢加载 90s 分阶段预算与 `READY_FOR_NEXT_ROUND` 透传、HUD 进展感知战斗等待上限（5–14 分钟）、单队 1–5 人统一重试及失败评估线程卸载。实机验证待办见 [已知限制与待办](KNOWN_LIMITATIONS.md#待修复与验证问题)。
- 桌面保持横屏参考映射（支持 1080p 与 720p 开放测试）、独立分数 OCR 隔离、直接像素视图、预编译角色样本与 300 ms 默认节拍。策略库保留原子保存与撤销，旧桌面分享码与 `LABY1.` 双向兼容。战斗估值由 `:app` 装配，`:core` 通过纯 Kotlin 接口解耦模型与 Path B 规划。演进里程碑见 [统一路线图](ROADMAP.md)。

## 桌面维护边界

- 保留多 JVM 设备隔离、动作前分辨率与实时前台校验、同账号/UID 租约、Windows DPAPI 与日志脱敏、原子存储和损坏备份。数据库变更须核对受影响的版本、迁移 SQL、schema identity、设备画像和 UI 入口。
- 参考实现遵守洁净实现与授权边界，保留已有署名和许可。`D:\WorkSpace\LimingjiePC` 保持只读参考，本机副本、原始素材及凭据不得进入仓库。参见 [项目执行约定](../AGENTS.md#参考来源与洁净实现)和 [第三方声明](../THIRD_PARTY_NOTICES.md)。
- 历史记录见 [历史修改台账](archive/desktop/registers/UPSTREAM_PATCHES.md)。
