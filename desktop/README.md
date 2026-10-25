# 桌面构建

独立 Gradle 多模块工程，使用 JDK 21：

```powershell
desktop\gradlew.bat -p desktop :app:run
desktop\gradlew.bat -p desktop check
desktop\gradlew.bat -p desktop packagePortableZip
```

`app` 负责窗口和组装；`shared` 按排除/替身清单编译上游源码；`device` 负责 ADB、截图与 MaaTouch；`ocr` 负责 ONNX 推理；`platform` 负责 Windows 与本地环境。后三者不反向依赖共享业务。

不要编辑 `build/generated`。上游补丁只允许已登记的纯搬移与保留原默认行为的注入；协议和识别逻辑继续使用上游实现。更新基线时按 [上游同步流程](../docs/desktop/registers/UPSTREAM_SYNC.md) 处理漂移。

运行版自检示例：

```powershell
.\LimingjieAssistant.exe --self-test --report .\self-test.json --data-dir '.\自检 数据'
```

自检使用临时假数据，仅执行 TLS 握手，不登录账号、不操作游戏。测试与实机覆盖范围见 [实施记录](../docs/desktop/registers/PROGRESS.md)。
