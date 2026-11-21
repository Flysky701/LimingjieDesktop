# 迷宫事件内核

每个识别会话持有一个 Kotlin/JVM `KernelRuntime`。Android 与桌面端共用目录、规则、估值和门禁。识别器提供当前帧的文字、稳定次数、按钮位置和亮蓝置信度。平衡模式的规划线程只计算推荐，控制线程在规划完成后的新详细帧上重绑按钮，再发布动作许可。原生派发仍持有原有的原子版本和帧时间戳。

## 因果记录

同一 runtime 的 `eventSeq` 跨会话递增。每次启动产生一个 trace，`causeEventSeq` 指向直接原因，帧号和场景代随原因传递。通常可以从动作回执沿父指针找到派发、许可、干预结果、建议、规则、事件识别和原始帧。动作执行器返回成功只表示已执行输入。如果页面持续不变，控制器会另记 `PAGE_UNCHANGED`，将选项加入本次访问的拒选集合，再请求规划。

日志默认保留最近 2,000 条。父事件已淘汰时，`causalChain` 返回仍在缓冲区内的链段。调试面板的 `GET /api/kernel/events` 提供同一快照的统计与记录，`?format=ndjson` 逐行导出。已绑定会话的 `/logs.zip` 包含 `kernel/events.ndjson`。每一行都可解码为带具体载荷类型的事件。

## 扩展国服事件

用 `KernelEventExtensionPackCodec.decode` 读取独立 JSON 文档，调用会话的 `updateEventExtensionPack` 整包替换。别名从 OCR 标题映射到事件 ID，选项文字和分数差仍按既有阈值判定。已有事件可仅补充标签，或用选项 ID 补充效果原语。以下示例在第二区域识别庆典并且赠礼按钮可用时选取赠礼。

```json
{
  "overlay": {
    "events": [{
      "id": "cn-festival", "name": "星光庆典即将开幕", "area": 2,
      "familyId": "festival", "tags": ["庆典"],
      "choices": [{"id": "gift", "slot": 1, "name": "领取庆典礼物", "description": "阿尔法金币×800"}]
    }],
    "aliases": {"全新的星光活动": "cn-festival"}
  },
  "rules": [{
    "id": "festival-gift", "priority": 120,
    "condition": {"type": "AllOf", "conditions": [
      {"type": "EventFamilyIn", "ids": ["festival"]},
      {"type": "AreaIn", "areas": [2]},
      {"type": "ChoiceEnabled", "choiceId": "gift"}
    ]},
    "action": {"type": "SelectByChoiceId", "choiceId": "gift"}
  }]
}
```

规则按优先级降序、ID 升序取首个命中项。石板和公会固定规则的优先级为 100，已有策略分享码的有效自定义选择编译为优先级 50 的规则。目录已有选项沿用原有效用模型，新选项把收益原语交给同一套角色、遗物及金币估值函数。余额未知时，余额区间条件不命中。

未知事件默认记录并等待。选择 `SAFE_PARSE_OR_WAIT` 或 `FIRST_FREE_ENABLED` 后，识别器用已校准的每个选项文字区域连同其按钮读取文案，每个详细帧最多追加一次异步 OCR。只有当前选项图像指纹和裁剪区域同时匹配，结果才可用于该槽位。`SAFE_PARSE_OR_WAIT` 还要求整段文字能解释为免费正收益。缺少逐槽文字时，只有显式采用 `FIRST_ENABLED_SLOT` 才可按槽位建议。任何策略均需通过当前帧的稳定、亮蓝、唯一映射和拒选检查。

## 模块扩展

`registerKernelHook` 和 `unregisterKernelHook` 在控制线程更新钩子并撤销现有许可。钩子先按优先级再按 ID 执行。派生事件和干预原因保留父指针。首个否决或覆盖决定本次建议，覆盖目标再次通过按钮门禁。钩子应在事件回调内完成短同步计算。

下一阶段可将地图、战斗、商店、奖励和结算分别注册为领域模块。迁移单位是一个可离线回放的观察、决策、许可和回执闭环。模块沿用各自的场景身份、前台检查和动作执行器，由会话继续管理帧与线程生命周期。
