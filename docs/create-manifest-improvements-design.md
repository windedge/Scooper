# Create Manifest 流程改进设计

## 背景

scooper × scoop-ai 的 Create 流程（对话生成 manifest → Test Install → Save to Bucket）已经可用，但在可靠性、交互、体验上仍有改进空间。本设计覆盖 P0 / P1 / P2 共 9 项改进，涉及 scooper（Compose Desktop GUI）与 scoop-ai（KMP agent 库）两侧。

## 总览

| # | 项目 | 优先级 | 侧 | 工作量 | 依赖 |
|---|------|--------|-----|--------|------|
| 1 | reasoning 空响应崩溃修复 | P0 | scoop-ai | M | 无 |
| 2 | Settings.AI 测试连接 | P0 | scooper | M | #8 先行（共用映射表） |
| 3 | 断点重试（内存 checkpoint） | P0 | both | M | scoop-ai API 与 #1 同版 0.1.1 |
| 4 | Refine 对话 | P1 | both | M-L | #4a API；UI 在 #6/#7 后 |
| 5 | AskUser 弹窗 | P1 | both | M | #5a API |
| 6 | Manifest 就地编辑 | P1 | scooper | S | 无 |
| 7 | 过程透明度 | P2 | both | S-M | #7a API |
| 8 | 错误翻译 | P2 | scooper | S | 无 |
| 9 | Save 后提示 commit | P2 | scooper | S | 无 |

## 1. reasoning 空响应崩溃修复（P0, scoop-ai）

### 现状与问题

`subgraphWithTask` 强制 `tool_choice=required`，我方 `downgradeToolChoice` 拦截器将其降级为 `auto` 后，DeepSeek reasoner 可能输出纯思考（内容放在 Koog 不认识的键里），此时响应没有 content 也没有 tool_calls，Koog 解析为 `parts=[]`。由于内建重试条件不满足（该条件要求 Text parts，且模型不支持 ToolChoice），Koog 会直接抛出 `IllegalStateException "Subgraph with task must always call tools"`。

### 方案

- 治本：新增响应改写器 `reasoningFallbackToContent`（位于 `core/.../llm/ResponseInterceptors.kt`，仿照 `normalizeMissingCreated`）。当 content 空白且无 tool_calls 时，把 `reasoning_content` / `reasoning` / `reasoningContent` 键映射为 content。注意：必须映射为 content 而不是保留 reasoning 键，否则 `parts=[Reasoning]` 时两条边都不命中仍会 throw（Koog `AIAgentEdges.kt:53-60` 要求 `MessagePart.Text`）。
- B1：`openAICompatibleCapabilities` 对 DeepSeek 模型名移除 `LLMCapability.ToolChoice`，激活 Koog 内建 "DO NOT CHAT CALL TOOLS" 重试（`AIAgentSubgraphExt.kt:747`，最多 3 次 `ASSISTANT_RESPONSE_REPEAT_MAX=3`）。
- B2：`downgradeToolChoice` 保留（Koog 序列化 toolChoice 时无 capability 门控）。
- rewriter 链：`normalizeMissingCreated(reasoningFallbackToContent(raw), 0L)`。
- 已核实 `ScoopManifestGenerator` 已有 `modelWithoutToolChoice`（双保险）；`reviewAgent` 用原始 model，这是 B1 的增量价值。

### 测试要点

TDD 方式。`ResponseInterceptorsTest` 先写失败用例（缺 content 只有 reasoning 键 → 补成 content）；`LlmProviderFactoryTest` 断言 DeepSeek 模型 capabilities 不含 ToolChoice。

## 2. Settings.AI 测试连接（P0, scooper）

### 方案

新建 `AiConnectionTester`（scooper 直连 JDK HttpClient，不用 Koog executor——Koog 会把 HTTP 错误包成 `LLMClientException` 从而丢失状态码；直连分类精确，代码 <100 行）。发送最小请求（如 "ping"，`maxTokens=1`），把结果映射成人话。

### 错误映射表

与 #8 共用 `AiErrorTranslator`：

- 401 → 检查 apiKey
- 404 → baseUrl 疑似带 /v1
- 模型 404 → model id 错
- 超时 → 网络不可达
- 429 → 限流
- 5xx → 服务端错误

### UI

Settings.AI 页加 "Test Connection" 按钮 + 结果行（成功显示模型名 + 延迟；失败显示分类原因 + 建议）。异步执行不阻塞 UI。

### 改动

- `service/AiConnectionTester.kt`（新建）
- `service/AiErrorTranslator.kt`（新建）
- `viewmodels/SettingsViewModel.kt`
- `ui/Settings.kt`

## 3. 断点重试 / 内存 checkpoint（P0, both）

### 已确认

内存即可（GUI 常驻进程，不落文件）。CLI 的文件 checkpoint 保留不动。

### scoop-ai 侧

- 捕获点选 `onPhaseOutput` 回调（策略图已有此机制，零结构改动，且保证 checkpoint nodePath 稳定）
- `ScoopManifestGenerator` 新增可选参数 `phaseOutputListener`
- `CheckpointResume` 增加 `ResumeData.fromPhaseOutputs(url, researchJson, analyzeJson, runId)` 工厂

### scooper 侧

- `ScoopAiBridge` 持 `InMemoryCheckpoint(url, runId, researchJson, analyzeJson)`
- 取消不丢弃 checkpoint（换 URL 才重置）
- `CreateState` 增加 `resumableStages` / `lastRunFailedOrCancelled`
- UI：失败时显示 "Retry from research/analyze" 按钮

### 版本兼容

内存 checkpoint 同进程同版本使用，无需 schema 版本号。

## 4. Refine 对话（P1, both）

### 方案

新增独立 `ScoopManifestGenerator.refine(manifest, instruction, context: RefineSessionContext?, maxAutoRetries=2)`，不走策略图已有 refine subgraph（避免影响 checkpoint 路径 nodePath 与既有 verify 循环）。

- 确定性守卫：version/url 等关键字段若被 LLM 意外改动，回写旧值
- `PhasePrompts` 增加 `buildUserRefinePrompt`
- `Types.kt` 增加 `RefineSessionContext`
- scooper Composer 路由：URL 为空且 note 非空且已有 manifest → 触发 refine（而非新任务）
- 成功后清空 testResult，新 manifest 卡替换旧的

## 5. AskUser 弹窗（P1, both）

### scoop-ai 侧

- `SafeAskUser` 增加 `@Volatile responseProvider: (suspend (String) -> String)?`（优先于 nonInteractive；非取消异常兜底 `DEFAULT_RESPONSE`）
- `ScoopAiConfig` 增加 `askUser` 字段，默认 null（保持 0.1.0 二进制兼容）
- `DefaultScoopAiFactory` 装配注入

### scooper 侧

- `CompletableDeferred` + `CreateState.pendingQuestion` 驱动问答卡 UI（问题 + 选项单选）
- 180s 超时自动回 `DEFAULT`
- 并发互串风险由 scooper 单任务模型（running 守卫）覆盖，KDoc 注明

## 6. Manifest 就地编辑（P1, scooper）

`ManifestPreviewCard` 增加编辑模式：只读 Text ↔ `BasicTextField`（monospace）切换；保存时 JSON 校验；合法则更新 `CreateState.manifestJson`，联动 Test Install / Save to Bucket。工作量最小，无依赖。

## 7. 过程透明度（P2, both）

- scoop-ai：core 新增 `ToolActivity(Started/Finished)` sealed interface + `onToolActivity` 回调（复用现有 `handleEvents` 机制）
- scooper：CONFLATED channel + 250ms `collectLatest` 节流，对话流渲染单行 activityLine（淡色小字，如 "下载 xx.msi 中..."、"计算 sha256..."），自动滚动

## 8. 错误翻译（P2, scooper）

`AiErrorTranslator`：`ErrorCategory` 枚举（`NETWORK` / `AUTH` / `MODEL_NOT_FOUND` / `BAD_PATH` / `RATE_LIMIT` / `SERVER` / `BAD_GATEWAY` / `EMPTY_RESPONSE` / `PARSE_ERROR` / `UNKNOWN`）+ 表驱动文案，扩展 `CreateViewModel` 已有 `sanitizeError`。与 #2 共用。表驱动便于单测。

## 9. Save 后提示 commit（P2, scooper）【已实现】

`GitHistoryService.isGitBucket` 判定：git bucket 保存成功后，`SaveResultPanel` 结果卡显示条件性 git commit 提示（命令 + Copy）。Discover 可见性与 commit 无关（保存即 upsertApp 入库）。不自动 commit。

## 版本与实施策略

- scoop-ai 升 0.1.1 发布 mavenLocal；scooper `build.gradle.kts` 改版本号（避免同版本缓存）
- 全部 scoop-ai 变更用新增可选参数，保持 0.1.0 二进制兼容

## 实施批次

1. 批次 1（scoop-ai 0.1.1）：TDD 实现 #1；顺手 #3a（`phaseOutputListener` + `fromPhaseOutputs` + mock 断言）与 #7a（`ToolActivity` + `onToolActivity`）；`:core:test` `:tools:test` 全绿 → 端到端 reasoner 网关验证（跑包 + 安装测试 + 对比官方 manifest）→ `publishToMavenLocal` 0.1.1 → scooper 升版本号编译
2. 批次 2（scooper）：#8 `AiErrorTranslator`（表驱动单测）→ #2 `AiConnectionTester` + Settings UI → #3b bridge checkpoint + Retry UI（手动：断网复现失败后 Retry from generate）→ #6 编辑态 → #9 commit 提示
3. 批次 3（scoop-ai 0.1.2 或并入 0.1.1）：#4a refine 方法 + 守卫 + mock 集成测试；#5a `SafeAskUser` responseProvider + `ScoopAiConfig.askUser`；发布
4. 批次 4（scooper）：#7b 活动行（节流 + 文案映射）→ #4b Composer 路由 + refine（手动验证 "去掉 xx bin" / "改 portable zip" 指令）→ #5b 问答卡 + 挂起桥接（手动验证触发 / 超时 / 取消）

每批结束跑完整验证流程（生成 → Test Install → Save → Discover 入库）；不自动 git commit。

## 主要权衡（记录）

- #1 改写器 vs 升级 Koog / 换 provider：改写器最小且条件严格无副作用；未改 `InterceptingHttpClient.responseRewriter` 为 List（公共签名不动，工厂内组合单函数）
- #2 直连 HTTP vs 复用 Koog executor：直连状态码分类精确，接受少量重复实现（baseUrl 归一化已复用）
- #3 `onPhaseOutput` 回调 vs 返回值 vs Flow：返回值在中途异常时拿不到已完成阶段；Flow 需新 API 面与背压处理；回调已存在零改动
- #4 独立 refine agent vs 改造策略图 refine subgraph：后者影响 checkpoint nodePath 与既有 verify 循环；守卫 + normalize + validate 三层兜底 LLM 意外改动
- #5 全局单例 `SafeAskUser` 注入 vs 工具实例化：单例并发互串风险由 scooper 单任务模型覆盖
