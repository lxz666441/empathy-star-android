# 架构与职责

```text
Android accessibility events
        ↓
ScreenReader / AccessibilityChatReader
        ↓
WeChatAdapter / QqAdapter / QqGroupAdapter / NodeMessageScanner
        ↓
ScreenReadResult: messages + origin + geometry + fixed status
        ↓
analysis / knowledge points / reply suggestions / selected-message summary
        ↓
cards and overlays → guarded empty-draft fill
```

## 适配层

负责应用识别、窗口获取、页面和节点结构、消息方向、气泡/输入区位置与空草稿写入。空树、未知窗口和非聊天页是明确状态，不允许用模型猜测来冒充读取成功。

`ScreenReader`是内部接口，`ScreenReadResult`是Android节点之外的纯数据结果。扩展应用必须增加实际适配与授权注册，并分别实机验证。

## 业务层

`ChatAnalysis`、`KnowledgeTerms`、`ConversationSelection`、`ConversationSummary`和`ChatModelClient`负责提示词、结果校验、来源概念边界、显式选择、引用校验与模型调用。它们不直接读取Android节点。

独立解释页面只使用本次已取得结果。单句分析只允许所选消息及此前最多八条可见文字；多选总结只发送明确所选消息，两者授权和边界不能混用。

## 尚未完成的解耦

`WeChatService`仍承担事件、窗口生命周期、显示及业务请求编排，并含应用条件。它是安装组件名，不代表只支持微信。当前源代码尚未完成全部业务编排拆分；独立业务仓库提供后续接入基础，不宣称主App已经接入。

模型能力、逻辑测试、构建、手机安装和真实应用验收是不同证据。不能以单元测试或某个平台成功代替其他平台实测。
