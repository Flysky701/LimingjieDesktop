# 桌面模块契约

2026-10-25 M1 离线验证完成，接口冻结。下列源码是签名权威来源；需要调整时由主实现者登记并通知受影响模块。

- `shared/.../desktop/shared/DesktopBindings.kt`：每个应用实例一次安装，退出后按对象身份 release；OCR 回调由 shared 切到 Swing Main；凭据保护不提供明文回退；设备画像缺失阻止登录。
- `device/.../DeviceContracts.kt`：进程执行、连接状态、截图、触控、设备信息与节点树。普通命令默认 10 秒，发现连接 3 秒，截图 10 秒，APK 拉取 60 秒；stdout/stderr 并发排空，普通命令最多 4 并发，截图独立通道。动作前实时前台查询；失败返回明确结果。断线停止任务，重连只恢复连接。
- `ocr/.../OcrEngine.kt`：单例引擎异步提交，提交后不再访问调用方 Bitmap；null 表示失败，空串表示没有识别到文字。启动后预热；关闭后释放模型与线程。
- `shared/.../desktop/shared/AtomicFiles.kt` 与 `DesktopContext.kt`：同步原子配置写、每个 name 同一 prefs 实例；新增同目录 move(source,target) 供大型数据库原子替换，失败不静默换目录或回退到非原子覆盖。

依赖方向遵守 PLAN：device、ocr、platform 不引用 shared。platform 的 DPAPI facade 由 app 包装为 shared 的 SecretProtector；device 的纯 JVM 画像由 app 映射为 shared 的持久化 DTO。
