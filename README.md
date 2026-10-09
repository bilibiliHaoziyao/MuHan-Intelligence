# 慕寒智能

<div align="center">

**由你自己掌管 API Key 的原生 Android AI 对话应用**

[![version](https://img.shields.io/badge/version-0.2.0--Fix-6D5BF6)](../../releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

</div>

---

## 这是什么

慕寒智能是一个 **BYOK（Bring Your Own Key）** 的 AI 对话客户端。它没有服务端，也不收集任何数据 —— 你填入自己的模型 API Key，应用**直连**模型服务商，对话记录与密钥全部只保存在你的手机上。

界面参考 DeepSeek 的交互习惯，并补齐了流式打字机效果、思维链折叠、Markdown 渲染、消息重新生成等体验细节。

## 功能

**对话体验**

- 流式输出，逐字渲染 + 呼吸光标，随时可中断生成
- 思维链折叠面板，回答开始后自动收起（支持 DeepSeek-R1 等推理模型）
- 完整 Markdown 渲染：标题、列表、引用、分割线、表格、行内样式
- 代码块带语言标签与一键复制
- 用户消息可编辑后重新发送，自动截断无效上下文
- 失败时给出可读的中文原因，支持单条消息重试
- 智能滚动跟随：流式时自动贴底，手动上滑即停止打扰
- **多模态输入**：支持随消息附带最多 4 张图片（视觉模型识别）或文本 / 代码文件，文件内容自动内联进上下文
- **深度思考开关**：一键开启 / 关闭推理模式（DeepSeek-R1、Claude 扩展思考、Gemini thinkingBudget 均已适配）
- **联网搜索开关**：一键开启模型的联网检索能力（OpenAI / Claude / Gemini 三种协议各自适配）
- **生图模式**：配置生图模型后，可直接在聊天里输入描述生成图片并保存到本地
- 修复浅色 / 深色模式无法即时切换的问题
- 修复模型回答时可能闪退的重大问题

**模型服务**

内置 9 家预设，一键填充即可使用：

| 服务商 | 默认模型 | 协议 |
| --- | --- | --- |
| DeepSeek（深度求索） | `deepseek-chat` | OpenAI 兼容 |
| Kimi（月之暗面） | `moonshot-v1-8k` | OpenAI 兼容 |
| 智谱 GLM | `glm-4-plus` | OpenAI 兼容 |
| 通义千问（阿里云百炼） | `qwen-plus` | OpenAI 兼容 |
| SiliconFlow 硅基流动 | `deepseek-ai/DeepSeek-V3` | OpenAI 兼容 |
| OpenAI | `gpt-4o-mini` | OpenAI |
| Anthropic Claude | `claude-3-5-sonnet-latest` | Messages API |
| Google Gemini | `gemini-2.0-flash` | Gemini API |
| Ollama（本地部署） | `qwen2.5:7b` | OpenAI 兼容 |

任意自定义端点也能接入。Base URL 支持多种写法（`api.deepseek.com`、`.../v1`、`.../v1/chat/completions`），应用会自动补全请求路径。

**安全与隐私**

- API Key 使用 **Android Keystore + EncryptedSharedPreferences** 加密落盘（AES256-GCM）
- 密钥文件已从**云备份与设备迁移**中显式排除
- 明文 HTTP 公网端点会被拦截（本地回环与内网地址除外）
- 全应用仅申请 `INTERNET` 与 `ACCESS_NETWORK_STATE` 两项权限

**新手引导**

首次启动进入向导，第一步先选择身份：**我是菜鸟**（每一步都给出最简单直白的说明与推荐配置）或 **我是迪克**（保留完整自定义项，并额外提供深度思考、联网搜索等高级默认值设置）。之后为：选择服务商 → 填写凭据 → **连接测试**。只有真实请求成功才会保存配置；向导可随时跳过，也能从设置里重新运行。

## 下载

前往 [Releases](../../releases/latest) 下载 `MuHan-Intelligence-0.2.0Fix-release.apk` 直接安装。

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `MuHan-Intelligence-0.2.0Fix-release.apk` | 约 2.4 MB | 混淆压缩后的发布版 |
| `MuHan-Intelligence-0.2.0Fix-debug.apk` | 约 19 MB | 未压缩的调试版，便于排查问题 |
| `SHA256SUMS.txt` | — | 上述文件的 SHA-256 校验值 |

核对下载完整性：

```bash
sha256sum -c SHA256SUMS.txt
```

> Release APK 使用仓库内公开的签名证书签名，仅供个人测试分发，请勿用于正式上架。

> 首次使用需要自备 API Key。没有的话推荐注册 [DeepSeek 开放平台](https://platform.deepseek.com/)，注册即送额度且价格低廉；也可以选择 Ollama 在本地跑模型。

## 界面

```
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│ ☰    新对话       ＋ │  │ ←   选择模型服务     │  │ ←   填写接入信息     │
│ ──────────────────── │  │ ──────────────────── │  │ ──────────────────── │
│                      │  │  ┌────────────────┐  │  │  服务名称            │
│  ◯ 慕寒智能          │  │  │ D  深度求索    │✓ │  │  [ 我的 DeepSeek ]   │
│    你好，我是慕寒智能 │  │  └────────────────┘  │  │                      │
│                      │  │  ┌────────────────┐  │  │  接口地址            │
│         ┌──────────┐ │  │  │ K  Kimi        │  │  │  [ api.deepseek… ]   │
│         │ 帮我写…  │ │  │  └────────────────┘  │  │                      │
│         └──────────┘ │  │  ┌────────────────┐  │  │  API Key             │
│                      │  │  │ G  智谱 GLM    │  │  │  [ sk-•••••••• ] 👁  │
│  ◯ 慕寒智能          │  │  └────────────────┘  │  │                      │
│    ▼ 深度思考过程    │  │  ┌────────────────┐  │  │  [    下一步   ]     │
│    好的，以下是…     │  │  │ S  SiliconFlow │  │  │                      │
│ ──────────────────── │  │  └────────────────┘  │  └──────────────────────┘
│ [ 给慕寒智能发送… ]↑ │  │ ⊕  自定义接入        │
└──────────────────────┘  │ [     下一步     ]   │
                          └──────────────────────┘
```

## 构建

**环境要求**：JDK 17+、Android SDK Platform 35 + Build-Tools 35.0.0

```bash
git clone https://github.com/bilibiliHaoziyao/MuHan-Intelligence.git
cd MuHan-Intelligence

./gradlew assembleDebug        # Debug 构建
./gradlew assembleRelease      # Release 构建
./gradlew testDebugUnitTest    # 单元测试
```

产物位于 `app/build/outputs/apk/`。

### 签名配置

`app/build.gradle.kts` 按以下顺序解析签名信息：

1. 仓库根目录的 `keystore.properties`
2. 环境变量 `RELEASE_KEYSTORE_PATH` / `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`

两者都不存在时，Release 变体会回退到 debug 签名，保证本地仍能构建出可安装的包。

```properties
# keystore.properties（仓库根目录）
storeFile=../keystore/muhan-release.jks
storePassword=你的口令
keyAlias=muhan
keyPassword=你的口令
```

> **关于 `storeFile` 的路径基准**
>
> `storeFile` 是相对 `:app` 模块目录解析的，而不是仓库根目录。所以位于仓库根
> 的密钥库要写成 `../keystore/muhan-release.jks`。
> 写成 `keystore/muhan-release.jks` 也能正常工作——构建脚本会先按模块相对解析，
> 找不到时再按仓库根相对解析，两种风格都支持。

签名证书指纹（用于核对 APK 是否由本仓库构建）：

| 算法 | 指纹 |
| --- | --- |
| SHA-256 | `46:29:08:F0:00:C0:E7:EE:08:A0:A7:9B:54:B7:BA:3A:F4:69:82:F4:D4:1E:F0:B6:4B:3F:81:03:EE:BF:E6:2A` |
| SHA-1 | `0A:23:CF:9F:62:91:A3:C2:8C:4F:B0:CB:A4:42:8C:F1:49:68:16:BE` |

> ### ⚠️ 关于密钥库
>
> 本仓库为了满足 CI 自动构建的约定，**把密钥库 `keystore/muhan-release.jks` 和口令一起提交进了仓库**。
> 这在任何意义上都不安全，只适用于「个人项目、分发给朋友试用」的场景。
> 正式发布请改用 GitHub Secrets（工作流已支持，见下方"CI 说明"）并删除仓库内的密钥库文件。

## CI 说明

`.github/workflows/build-release.yml` 会在以下时机自动构建：

- 推送到 `main` / `master` 分支 → 构建并上传 Artifact
- 打 `v*` 标签 → 构建并**自动创建 GitHub Release**，附带 APK 与 SHA256 校验值
- 手动触发 → 可选是否创建 Release

完整流程：准备签名 → **校验签名配置可解析** → 单元测试 → 构建 Release/Debug APK
→ `apksigner verify` 校验签名 → 整理产物并生成 `SHA256SUMS.txt` → 上传 Artifact
→ 打标签时创建 Release。

工作流优先使用 Secrets 中的密钥库；若未配置，则回退到仓库内的 `keystore/muhan-release.jks`。要切换到 Secrets 方案，请配置：

| Secret | 说明 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 密钥库文件的 base64 编码 |
| `RELEASE_KEYSTORE_PASSWORD` | 密钥库口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

```bash
# 生成 base64
base64 -w 0 keystore/muhan-release.jks
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| UI | Jetpack Compose + Material 3（支持动态取色） |
| 架构 | MVVM + Repository，单向数据流（`StateFlow`） |
| 依赖注入 | Hilt |
| 本地存储 | Room（对话/消息）+ DataStore（偏好）+ EncryptedSharedPreferences（密钥） |
| 网络 | OkHttp + Retrofit + kotlinx.serialization |
| 流式解析 | 自研 SSE 解析器，处理跨分片与三种协议差异 |
| 最低 / 目标版本 | Android 8.0（API 26）/ Android 15（API 35） |

### 目录结构

```
app/src/main/java/com/muhan/intelligence/
├── data/
│   ├── local/          Room 实体与 DAO、数据库、加密存储、偏好设置
│   ├── remote/         SSE 解析、端点解析、请求构建、错误映射、服务商预设
│   └── repository/     对话仓库、服务商仓库、设置仓库
├── di/                 Hilt 模块
├── domain/model/       领域模型
├── ui/
│   ├── components/     Markdown 渲染、消息气泡、输入栏、历史抽屉
│   ├── navigation/     路由与导航图
│   ├── screens/        引导、对话、设置
│   └── theme/          配色、字体、形状
└── util/               工具类
```

## 已知限制

- 会话内的消息搜索尚未实现（历史列表支持按标题搜索）
- 非文本类二进制附件不会发送给模型（仅提示不支持）
- 表格以等宽文本渲染，未做真正的表格布局
- 关闭流式输出后走非流式路径，但界面仍按流式状态机渲染

## 贡献

欢迎提交 Issue 与 PR。报告 Bug 请附上机型、Android 版本与复现步骤；提交代码前请确保 `./gradlew testDebugUnitTest` 与 `./gradlew assembleDebug` 均通过。

## 协议

[MIT License](LICENSE)

> 慕寒智能与 DeepSeek、Kimi、智谱、OpenAI、Anthropic、Google 等模型服务商均无隶属关系，应用内出现的服务商名称仅用于指示接口兼容性。

---

<div align="center">

**如果这个项目对你有帮助，欢迎点个 Star ⭐**

Made with ❤️ by [MuHan](https://github.com/bilibiliHaoziyao)

</div>
