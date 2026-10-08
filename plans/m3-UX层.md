# M3 UX 层实施计划（R3 评估文档 §五 M3）

> 2026-10-08 晨立项 · 依据：REQUIREMENTS.md R1/R6/R8c/R8d/R11
> 纪律：每步可编译可提交；严格按需求文档，不走私（M4 变速/导出不进 M3）

## M3 范围

1. **首次单一向导**（R6+R8b+R1，鼓励式）：欢迎 → 新书默认模式 → 音色试听 → 后台授权+存储说明
2. **书籍管理页**（长按书卡进入，R8c/R8d/R1）：徽标明细 + 音色切换（重渲语义）+ 模式切换 + 资产删除/重新生成；删除确认挪入管理页
3. **存储管理页**（R11）：每书尺寸+资产明细+总占用+设备可用+低空间提醒+逐书清理
4. **书架徽标**（R8c）：已缓存 N/M · AI 优化 N/M（失败章警示）
5. **缓存前预估弹窗**（R11）：预估 vs 可用空间，用户决定是否继续

## M3-a 数据层

- Book 加字段：voice("female"/"male") / mode("neural"/"system") / totalChars / totalChapters（导入时统计，旧书 0=未知）
- PlaybackStore 序列化兼容旧数据（optString/optLong 默认值）
- VoicePrefs：`cacheKeyFor(voice)` 静态映射；全局音色语义降为「新书默认」（R1）
- ChapterAudioCache：clearBook(bookId)（前缀扫删）/ clearBookVoice(bookId, voice)
- BookImporter：导入时统计 totalChars/totalChapters（Chapter.sentences 已在手，零额外扫描）
- MainActivity.confirmRemove：删书清资产（ChapterAudioCache.clearBook + AssetRegistry.clearBook，R11 验收「书删除后尺寸归零」）

## M3-b Service 接线（书级音色/模式）

- ReadAloudService：`bookVoiceKey()` = VoicePrefs.cacheKeyFor(book.voice)；isChapterCached/
  tryPlayChapterMonolithic/setBookContext 全部走书级音色
- 模式分支：`useNeural() = sherpaEngine != null && book.mode == neural`——system 模式书
  跳过门槛/章级/追上，loadAndPlay 时按书级模式 switchToSherpa/switchToSystem（M2 已有）
- RenderService：voice 从 PlaybackStore 按书读（不再用全局）
- 预估检查：loadAndPlay 门槛触发前，剩余未缓存章估算（StorageEstimator，旧书用 chapters
  现算）vs StatFs 可用；超限 → 广播事件（前台 dialog / 后台 Toast 明确文案）
- 音色切换语义（R1）：清旧音色缓存 + unmarkVoice + 更新 Book.voice；下次播放自然重渲，
  AI 优化保留（textVersion 与音色无关）

## M3-c WizardActivity（首次向导，wizardDone 标记）

- 4 步 ViewFlipper：欢迎（离线/隐私/零流量鼓励式）→ 模式说明二选（新书默认）→
  音色试听（引擎现场合成短句，模型未就绪则禁用）→ 授权+存储说明（AAC 每章约 1-2MB）
- 完成：wizardDone=true + 落默认模式/音色/授权；MainActivity onCreate 检查跳转

## M3-d BookManageActivity

- 明细：书名/格式/章数/字数/进度 + AssetRegistry.bookSummary（优化 N/M、失败 K、
  已缓存 N/M、当前音色、缓存占用）
- 操作：音色切换（女/男）→ 清旧音色缓存+落库+Toast「下次播放按新音色重渲」；
  模式切换；「删除神经缓存」（clearBookVoice 当前音色）；「删除 AI 优化」（清优化标记
  +缓存全作废，R7 语义）；「重新生成」（RenderService.start）；删除本书（复用 M3-a 清理）
- 入口：MainActivity 长按书卡（原长按删除确认移入本页）

## M3-e StorageActivity（存储管理，R11）

- 每书一行：书名 + 缓存占用（bookSummary.cacheBytes）+ 清理按钮（跳管理页删除动作）
- 头部：本 App 总占用 + 设备可用空间；可用 < 1GB → 黄色低空间提醒（非限制，拍板方案）
- 入口：设置页新条目

## M3-f 书架徽标 + 入口

- BookAdapter：进度行追加徽标（⚡缓存 N/M · ✨优化 N/M；失败章 ⚠）——数据源
  AssetRegistry.bookSummary（无资产记录 → 不显示徽标，保持老书架干净）
- MainActivity 长按 → BookManageActivity

## M3-g 验证

- 编译 + 现有仪器测试回归（真机或 CI 模拟器）
- 真机走查（用户或下会话）：向导→导书→点书（预估/门槛）→管理页切音色→存储页
- 提交推送盯 CI

## 明确不在 M3（防走私）

- 变速/AAC→M4A 导出（M4/R5/R10）
- EPUB（冻结）
- DeepSeek 优化算法本体（M1 闸门已挂管线，R7 细节属管线内已有逻辑）
