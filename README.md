# EarBook 🎧

本地小说听书 App——把你的 TXT 小说变成有声书，戴上耳机用线控听。

**仅读取本地文件，不含任何书源 / 在线内容 / 付费功能。**

[English intro below](#english)

## 功能

- 📄 导入本地 TXT（自动识别 UTF-8 / GB18030 编码，自动分章）
- 📕 导入本地 PDF（提取文字层朗读；扫描版/图片型 PDF 不支持，导入时会提示）
- 🔊 系统神经网络语音朗读（M2 将接入更多引擎）
- 🎧 耳机线控：播放 / 暂停 / 上一章 / 下一章（有线、蓝牙通用）
- 🔒 通知栏 / 锁屏媒体控制
- 📑 句子级断点续读——关掉再打开，从上一句继续
- 🌙 夜间模式（跟随系统）

## Roadmap

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | TXT / PDF 导入 + 分章 + 耳机线控 + 系统 TTS | ✅ 当前版本 |
| M2 | 微软神经网络音色（可插拔引擎）+ 音频缓存 | 🚧 |
| M4 | EPUB 导入 / 离线 TTS 兜底（sherpa-onnx）+ 旁白对话双音色 | ⏳ |
| M5 | 语速调节 / 省电优化 / 书签 | ⏳ |

## 构建

1. Android Studio（Hedgehog 以上）打开项目根目录，等待 Gradle Sync
2. 连接手机或模拟器（Android 8.0 / API 26 以上），Run

无第三方密钥、无网络依赖（M1 阶段），克隆即用。

> **前置条件**：M1 使用系统 TTS 引擎朗读。请在手机「设置 → 辅助功能（或语言和输入法）→
> 文字转语音（TTS）输出」中确认已安装并选用中文语音引擎
> （如 Google TTS 中文包、华为/小米/讯飞等厂商引擎）。

## 免责声明

- EarBook 只读取**用户自行提供的本地文件**，不提供、不抓取、不分发任何书籍内容
- 朗读功能使用设备上的系统 TTS 引擎；接入在线引擎（M2+）时请自行确认相关服务条款
- 请勿用于侵犯他人著作权的用途

## 许可

[MIT](./LICENSE)

## 致谢

以下开源项目提供了思路参考（EarBook 为独立实现）：

- [Legado / 阅读3.0](https://github.com/gedoor/legado) 及其社区分叉——朗读服务架构
- [tts-server-android](https://github.com/jing332/tts-server-android)——双音色与引擎抽象思路
- [tts-edge-java](https://github.com/WhiteMagic2014/tts-edge-java)——Edge TTS 协议参考（M2）

---

# English

EarBook is a local-file audiobook reader for Android: turn your TXT novels
into audiobooks and control playback with your headset buttons.

- Local TXT import (auto charset detection, auto chapter split)
- Neural TTS via the system speech engine
- Headset media-button control, notification/lockscreen controls
- Sentence-level resume progress

Reads **only user-provided local files** — no book sources, no online content,
no payments. MIT licensed.
