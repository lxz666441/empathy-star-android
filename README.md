# 共情星 · Empathy Star Android

安卓聊天阅读与回复辅助原型：从已授权的前台聊天页读取可见文字，在对方文字旁显示分析入口，提供整句理解、建议回复、原文知识点与会话总结。

当前源码版本 **1.55 / versionCode 65**。这是开发原型，尚未完成上市验收。

## 完整流程

1. 用户分别授权应用读取与模型数据发送，配置自己的 HTTPS 模型服务。
2. 读取模块验证前台应用、聊天页、窗口和界面修订，应用适配器解析可见消息、方向与位置。
3. 合格的对方文字消息旁显示小星标；点击后才发起模型请求。
4. 一次分析返回整句解释、谨慎的沟通线索、三条候选回复及最多五个原文知识点。
5. 知识点打开独立解释页，返回保留原分析状态，不重复请求模型。
6. 建议仅能填入经过复核的空草稿，发送由用户本人完成。
7. 总结入口进入本地消息多选；本次上传另行确认，最多30条、12000字符，结果带本次原文编号引用。

## 当前能力与限制

- QQ私聊已有单设备实测记录；滚动后的星标恢复仍有明显延迟，不能称逐帧流畅。
- 微信适配代码存在，但此前测试设备上空节点问题尚未恢复，不能把QQ成功当作微信成功。
- QQ群聊有独立默认关闭授权，一期仅提供当前可见消息总结。
- 飞书及其他应用尚未适配。当前屏幕读取不代表完整历史、全部未读或后台聊天采集。
- 无障碍开关已开启不等于当前页面可读取；错误状态和诊断只报告结构与固定计数。
- 模型数据发送需单独同意，可能收费；配置中没有内置API Key。不得把默认模型描述为免费服务。

## 构建

需要 JDK 17、Android SDK Platform 36 及 Android Build Tools。仓库包含 Gradle 8.13 Wrapper。设置 `ANDROID_HOME` 或本机 `local.properties`，后者不要提交。Windows 请克隆到纯英文路径（例如 `C:\dev\empathy-star-android`），Android 构建工具会拒绝含中文的项目路径。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `./gradlew.bat`。首次构建会下载 Gradle 和依赖；Wrapper 配置包含 Gradle 发行包的 SHA-256 校验值。

PowerShell额外检查读取边界：

```powershell
./tools/Verify-ReaderBoundary.ps1
```

独立结构探针用于开发验证，不是产品依赖，不随正式版本分发。仓库不提供个人签名文件、预配置密钥或可直接上线的APK。

## 文档

- [架构与分层](docs/ARCHITECTURE.md)
- [能力与验收边界](docs/STATUS.md)
- [读取模块契约](docs/READING_MODULE.md)
- [隐私说明草案](docs/PRIVACY.md)

独立通用业务模块：[empathy-star-core](https://github.com/lxz666441/empathy-star-core)。该模块由本项目提取，主App尚未改为依赖该独立仓库。

## 许可证

项目尚未指定开源许可证。第三方依赖的许可由相应项目维护。
