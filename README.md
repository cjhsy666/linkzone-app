# LinkZone Android App

LinkZone 的 Android 客户端，基于 Kotlin + Jetpack Compose。

## 功能特性

- 统一聊天界面（云端 / 本地 AI 合并）
- 本地 AI 生成（支持多提供商、流式输出、工具调用）
- 本地工具集：代码工作区、技能系统、TTS、OCR、图片生成、JS 执行、会话查询、上下文压缩、动态回复建议、会话导出等
- 标准 Agent Skills（SKILL.md 多文件包）支持
- 媒体消息、语音、Markdown 渲染

## 技术栈

- Kotlin 2.3 + Jetpack Compose
- Room（本地持久化）
- Koin（依赖注入）
- OkHttp / Retrofit（网络）
- quickjs-kt（JS 执行）
- Media3 / Coil / Markwon

## 构建

### 环境要求

- JDK 17
- Android SDK 36（compileSdk 36，targetSdk 34）
- Gradle 8.14（通过 Gradle Wrapper 自动下载）

### 命令

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（需签名配置）
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/`。

## CI

GitHub Actions 工作流 `.github/workflows/build.yml` 在每次推送 / PR 时自动构建 Debug APK 并上传为 Artifact。

## 许可证

MIT License，详见 [LICENSE](LICENSE)。
