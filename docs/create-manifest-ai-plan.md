# 计划：Create (AI) - 集成 scoop-ai 自动生成 Scoop Manifest

在 Scooper 侧边栏新增 "Create" 功能，以 **库依赖** 方式集成 `scoop-ai`（Koog 三阶段 agent），用对话式 UI 从 GitHub URL 生成 Scoop manifest，并支持测试安装 / 编辑 / 导出。

本文是 **AI 主路径** 的执行计划。规则驱动的旧计划见 `docs/create-manifest-plan.md`（保留作参考，不删除）。

---

## 1. 背景与目标

### 背景

- 旧计划 (`create-manifest-plan.md`) 设计了 **规则驱动** 的 5 步向导：URL 解析 -> Asset 选择 -> 解压分析 -> 生成编辑 -> 测试安装。
- 同期独立演进的 `scoop-ai` 项目已具备更强的端到端能力：research / analyze / generate 三阶段 LLM agent、工具链（GitHub / 下载 / hash / 解压分析 / write / verify）、以及 CLI 入口。
- 决策变更：**跳过规则 MVP，直接以 AI 生成为主路径**；Scooper 不外包 CLI，而是以库形式调用。

### 目标

| 目标 | 说明 |
|------|------|
| 一键生成 | 用户粘贴 GitHub URL，可选补充自然语言提示，生成可用 manifest |
| 库内集成 | 依赖 `scoop-ai-core` + `scoop-ai-tools`（Maven Local），不 fork CLI 进程 |
| 对话式 UX | 单屏对话 + 结果面板，而非 5 步向导 |
| 可验证 | 支持 test install（临时包名、自动清理） |
| 可配置 | Settings 独立 AI 页配置 provider / key / model |
| 可演进 | 二期支持 AskUser 弹窗与对话 refine |

### 非目标 (MVP)

- 不实现规则驱动的 ArchiveAnalyzer / ManifestRepairAdvisor 主路径
- 不实现 AskUser GUI 弹窗（MVP 走 nonInteractive 自决）
- 不实现多轮对话 refine
- 不接入 TaskQueue（Create 流程独立协程）
- 不支持 global 测试安装
- 不把 CLI 作为运行时依赖

---

## 2. 已锁定决策表

| # | 议题 | 决策 | 备注 |
|---|------|------|------|
| 1 | 依赖方式 | `publishToMavenLocal` 发布 `core` + `tools` | scooper 通过 `mavenLocal()` 引用 |
| 2 | Kotlin 版本 | 先共存：scoop-ai 2.1.x / scooper 2.3.x | 使用 `-Xskip-metadata-version-check`；后续再统一 |
| 3 | AskUser (MVP) | `nonInteractive` 自决 | agent 遇 `__ask_user__` 时自动用默认答复继续 |
| 4 | 库入口 | `DefaultScoopAiFactory` 下沉到 `:tools` | 从 CLI `CompositionRoot` 抽公共工厂，CLI 与 scooper 共用 |
| 5 | Test install 包名 | 强制 `*-scooper-test` 临时名 | 避免污染已安装正式包；测完自动卸载 |
| 6 | Provider (MVP) | `openai-compatible` + `deepseek` | 其它 provider 二期 |
| 7 | Settings | 独立 `Settings.AI` 页 | 与 General / UI / About 并列 |
| 8 | 文档 | 新建本文；保留 `create-manifest-plan.md` | 旧文档作规则方案与安全约束参考 |
| 9 | 内存 | JVM `-Xmx512m`（agent 相关进程/任务） | 控制下载+解压+LLM 峰值 |
| 10 | 二期 | AskUser 弹窗 + 对话 refine | 见第 3 节 |

### 产品形态补充决策

| 议题 | 决策 |
|------|------|
| 集成形态 | **库依赖**，不要 CLI 外包 |
| UI 形态 | **对话式 Create UI**，不是 5 步向导 |
| 主路径 | **跳过规则 MVP**，AI 生成做主路径 |
| 任务调度 | **不走 TaskQueue** |
| 测试安装 | **不用 `installVersion`**，专用安全接口 |

---

## 3. 产品形态

### 3.1 MVP

```
+--------+--------------------------------------------------------------+
|        |  Create                                                      |
| SIDE   |  Generate Scoop manifest with AI                             |
| BAR    +--------------------------------------------------------------+
|        |                                                              |
| Search |  +-- Chat -----------------------------------------------+   |
| Create |  | User: https://github.com/BurntSushi/ripgrep           |   |
|        |  |       (optional note: prefer portable zip)            |   |
|        |  |                                                       |   |
|        |  | Agent: [research] fetching repo & release...          |   |
|        |  |        [analyze] downloading windows-x64 zip...       |   |
|        |  |        [generate] writing manifest...                 |   |
|        |  |        Done. suggested name: ripgrep                  |   |
|        |  +-------------------------------------------------------+   |
|        |                                                              |
|        |  +-- Manifest Preview ------------------+  +-- Actions --+  |
|        |  | { ... editable JSON ... }            |  | [Regenerate]|  |
|        |  |                                      |  | [Test Install]| |
|        |  |                                      |  | [Copy]      |  |
|        |  +--------------------------------------+  | [Save...]   |  |
|        |                                            +-------------+  |
+--------+--------------------------------------------------------------+
```

MVP 能力清单：

1. 侧边栏 Create 入口 + 路由 + 快捷键
2. 输入 GitHub URL + 可选 user prompt
3. 调用 `DefaultScoopAiFactory` 创建 generator，跑三阶段
4. 流式/阶段进度展示（research / analyze / generate / refine）
5. 结果 JSON 可编辑
6. Test Install（临时名 `*-scooper-test`，确认框，日志，自动清理）
7. Copy / Save to Bucket
8. Settings.AI：provider、api key、base url、model、github token、nonInteractive 默认开

### 3.2 二期

| 能力 | 说明 |
|------|------|
| AskUser 弹窗 | 将 `__ask_user__` 从 nonInteractive 改为 Compose 对话框；用户可回答歧义问题（bin 选择、installer 类型等） |
| 对话 refine | 用户对已生成 manifest 追加自然语言指令（"去掉 arm64" / "加 shortcut"），触发 refine 而非全量重跑 |
| 更多 provider | openrouter / anthropic / ollama 等（复用 scoop-ai 已有 factory） |
| 模板复用 | 从 ScoopSearch 跳转 Create，带上已有 manifest 作参考 |
| 取消 / 断点续跑 | 利用 scoop-ai 的 resume / artifacts |

---

## 4. 模块边界与架构图

### 4.1 系统边界

```
+--------------------------- Scooper (Compose Desktop) --------------------------+
|                                                                                |
|  UI: CreateScreen (chat + preview)     Settings.AI                             |
|           |                                  |                                 |
|           v                                  v                                 |
|  CreateViewModel (Orbit MVI, own IO scope)   ConfigRepository (AI settings)    |
|           |                                                                    |
|           |  NOT via TaskQueue                                                 |
|           v                                                                    |
|  ScoopAiBridge / CreateService                                                 |
|           |                                                                    |
|           |  mavenLocal: com.scoopai:scoop-ai-tools / scoop-ai-core            |
|           v                                                                    |
|  +--------------------- scoop-ai (:tools + :core) --------------------------+  |
|  | DefaultScoopAiFactory                                                    |  |
|  |   -> LlmProviderFactory / ProviderConfigResolver                         |  |
|  |   -> DefaultToolRegistryFactory (GitHub, hash, archive, manifestIo, ...) |  |
|  |   -> ScoopManifestGenerator (research -> analyze -> generate -> verify)  |  |
|  |   -> SafeAskUser (MVP: nonInteractive auto default)                      |  |
|  +--------------------------------------------------------------------------+  |
|                                                                                |
|  Test install path (scooper-owned safety layer):                               |
|    CreateService.testInstall()                                                 |
|      -> rewrite name to <app>-scooper-test                                     |
|      -> ProcessBuilder scoop install <temp.json> -s                            |
|      -> scoop uninstall <app>-scooper-test                                     |
|      -> scoop cache rm <app>-scooper-test                                      |
|      -> NEVER ScoopCli.installVersion()                                        |
+--------------------------------------------------------------------------------+
```

### 4.2 职责划分

| 层 | 归属 | 职责 |
|----|------|------|
| UI / ViewModel | scooper | 对话 UI、状态、确认框、剪贴板、Save to Bucket |
| AI 配置 | scooper Settings.AI + Config | 持久化 provider/key/model；注入 factory |
| 库装配 | scoop-ai `:tools` `DefaultScoopAiFactory` | 组装 executor / tools / generator |
| Agent 编排 | scoop-ai `:core` `ScoopManifestGenerator` | 三阶段 + verify/refine |
| 工具实现 | scoop-ai `:tools` | GitHub / download / hash / archive / write |
| Test install 安全层 | **scooper** | 临时名、确认、清理；不信任 agent 侧默认 appName |
| Scoop CLI 队列 | scooper `TaskQueue` | **不参与** Create 主流程 |

### 4.3 数据流 (MVP)

```
User URL (+ optional prompt)
        |
        v
CreateViewModel.onGenerate()
        |
        v
DefaultScoopAiFactory.create(config).generate(url, userPrompt)
        |
        +--> onPhase("research"|"analyze"|"generate"|"refine") --> UI progress
        |
        v
ManifestResult { manifest, suggestedAppName, stages, tokenUsage }
        |
        v
UI: editable JSON
        |
        +--> Test Install --> temp name rewrite --> scoop install -s --> cleanup
        +--> Copy
        +--> Save to Bucket (real name, path checks)
```

---

## 5. scoop-ai 侧改动清单

> 仓库：`C:\Seafile\Works\kotlin\scoop-ai`  
> 交叉文档：`docs/scooper-integration-ai-plan.md`（与本文同步）

### 5.1 发布 `:core` + `:tools` 到 Maven Local

| 项 | 内容 |
|----|------|
| 现状 | `:core` 已有 `maven-publish`（artifact `scoop-ai-core`）；`:tools` **未** 配置 publishing |
| 改动 | 给 `:tools` 加 `maven-publish`，artifactId `scoop-ai-tools`，group `com.scoopai`，version 与 root 一致 |
| 依赖传递 | `tools` 应 `api(project(":core"))` 或在 scooper 同时声明 core+tools，避免运行时缺类 |
| 命令 | `gradlew.bat :core:publishToMavenLocal :tools:publishToMavenLocal` |
| 验收 | `~/.m2/repository/com/scoopai/scoop-ai-core/` 与 `scoop-ai-tools/` 存在对应版本 |

### 5.2 下沉 `DefaultScoopAiFactory` 到 `:tools`

| 项 | 内容 |
|----|------|
| 动机 | CLI 的 `CompositionRoot` 绑定 Clikt / EnvArgs，不适合桌面库复用 |
| 位置 | 新建 `tools/.../DefaultScoopAiFactory.kt`（或 `tools/.../factory/`） |
| 输入 | provider、apiKey、baseUrl、model、githubToken、appName、outputPath、nonInteractive、verify、onPhase、artifacts、usedAppNamesProvider 等 |
| 输出 | 配置好的 `ScoopManifestGenerator`（或更薄的 facade） |
| CLI 改造 | `CompositionRoot.createGenerator()` 委托 factory，避免双份装配逻辑 |
| 注意 | factory 不依赖 CLI 包；env 读取应可注入（Map / 显式参数），便于 scooper 从 Settings 传入 |

建议 API 草图（实现时可微调）：

```kotlin
// tools module
data class ScoopAiConfig(
    val provider: String,              // "deepseek" | "openai-compatible"
    val apiKey: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val githubToken: String? = null,
    val appName: String? = null,
    val nonInteractive: Boolean = true,
    val verify: Boolean = false,       // agent 内置 verify；scooper 另有 UI 层 test install
    val maxIterations: Int = 500,
    val keepCache: Boolean = false,
)

class DefaultScoopAiFactory {
    fun create(
        config: ScoopAiConfig,
        onPhase: ((String) -> Unit)? = null,
        artifacts: SensitiveArtifactWriter? = null,
        usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    ): ScoopManifestGenerator
}
```

### 5.3 AskUser nonInteractive 契约

| 项 | 内容 |
|----|------|
| 现状 | `SafeAskUser` 读 `SCOOP_AI_NON_INTERACTIVE` 系统属性 / 环境变量 |
| MVP | scooper 在启动 generator 前设置 `System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")` **或** factory 提供显式注入点 |
| 推荐 | factory 支持 `nonInteractive: Boolean`，内部设置 property / 替换 tool 实现，避免污染全局 |
| 二期 | 可注入自定义 AskUser tool（回调 suspend (question) -> answer），对接 Compose 弹窗 |

### 5.4 Provider 范围 (MVP)

| Provider | MVP | 说明 |
|----------|-----|------|
| deepseek | 是 | 默认之一 |
| openai-compatible | 是 | 需 baseUrl；兼容本地网关 / 代理 |
| openrouter / anthropic / ollama | 否 | 二期；代码已在 tools，UI 不暴露即可 |

### 5.5 Test install 临时名支持（可选增强）

| 项 | 内容 |
|----|------|
| 主责 | **scooper** 在 test install 前改写 manifest 的临时文件名 / app 名 |
| scoop-ai 可选 | `ProcessScoopVerifier` / `FileManifestIo` 支持 `testAppNameSuffix`，避免 agent 内置 verify 与正式名冲突 |
| MVP 最低要求 | scooper 侧强制 `*-scooper-test` 即可；agent 内置 verify 可默认关闭（`verify=false`），由 UI 按钮触发 |

### 5.6 内存与资源

| 项 | 内容 |
|----|------|
| 建议 | 文档与 CLI 默认 `-Xmx512m`（与决策一致）；scooper 主进程可保持现有 heap，但 Create 任务应避免无界缓存 |
| 缓存 | 复用 `ScoopCacheManager`；生成结束后按 `keepCache` 清理 |
| 临时文件 | 所有 temp dir / manifest 必须 try/finally 清理 |

### 5.7 Kotlin 共存

| 项 | 内容 |
|----|------|
| scoop-ai | Kotlin 2.1.21（当前） |
| scooper | Kotlin 2.3.10（当前） |
| 策略 | 两边均允许 `-Xskip-metadata-version-check`（scoop-ai 已有；scooper 消费 mavenLocal 时需同样配置） |
| 风险 | ABI / 标准库差异；以编译 + 跑通 Generate 为验收 |
| 后续 | 统一到同一 Kotlin 主版本（非 MVP 阻塞） |

### 5.8 scoop-ai 验收清单

1. `:tools` 可 `publishToMavenLocal`
2. `DefaultScoopAiFactory` 单元/集成测试：用 mock executor 或现有 mock 策略
3. CLI 仍可通过 factory 工作（回归 `GenerateCommand`）
4. nonInteractive 下 AskUser 不阻塞
5. 文档交叉引用 scooper 计划

---

## 6. scooper 侧改动清单

> 仓库：`C:\Seafile\Works\IdeaProjects\scooper`

### 6.1 Gradle 依赖

```kotlin
// repositories
mavenLocal()
mavenCentral()
// ...

// dependencies
implementation("com.scoopai:scoop-ai-tools:0.1.0") // 或当前版本
// 若 tools 未 api 传递 core：
implementation("com.scoopai:scoop-ai-core:0.1.0")

// Kotlin metadata coexistence
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}
```

注意：

- 可能需 exclude 与 scooper 冲突的日志 / 传递依赖（按编译错误收敛）
- Koog / scoop-ai 体积较大；关注打包体积与 ProGuard（release）

### 6.2 路由 / 侧边栏 / 快捷键

| 文件 | 变更 |
|------|------|
| `ui/Route.kt` | 新增 `object Create : AppRoute()` |
| `ui/Sidebar.kt` | TOOLS 区增加 Create；统一 items 列表与焦点索引 |
| `ui/Shortcuts.kt` | 如 `Ctrl+5` -> Create（与旧计划一致，可调整） |
| `ui/Main.kt` | 注册 `CreateScreen`、ViewModel 生命周期、`showToolbar` |
| `di/Modules.kt` | 注册 Create 相关依赖 |

### 6.3 Settings.AI

| 文件 | 变更 |
|------|------|
| `ui/Route.kt` | `Settings.AI("AI")` |
| `ui/Settings.kt` | `navItems` 加入 AI；`when` 分支渲染 `AiSettings()` |
| `data/Config.kt` / 持久化 | 新增 AI 配置字段（见下） |
| `viewmodels/SettingsViewModel.kt` | 读写 AI 配置 |

AI 配置字段（建议）：

```text
aiProvider: String          // "deepseek" | "openai-compatible"
aiApiKey: String            // 本地存储；注意不要写入日志
aiBaseUrl: String?          // openai-compatible 必填
aiModel: String?
aiGithubToken: String?
aiNonInteractive: Boolean   // MVP 默认 true
aiVerifyByDefault: Boolean  // 是否生成后自动 test install（默认 false）
```

安全：

- API Key 不进日志、不进 Toast 详情
- 若有 debug artifact，需脱敏（可复用 scoop-ai `SensitiveArtifactWriter` 思路）

### 6.4 Create 领域层

| 新建 | 职责 |
|------|------|
| `service/ScoopAiBridge.kt` 或 `CreateService.kt` | 把 Settings 配置映射为 `ScoopAiConfig`；调用 factory；转发 onPhase |
| `service/CreateTestInstall.kt`（可并入 CreateService） | 临时名改写、ProcessBuilder 安装/卸载、日志收集 |
| `viewmodels/CreateViewModel.kt` | Orbit MVI：chat messages、progress、manifestJson、test result、side effects |
| `ui/Create.kt`（`CreateScreen`） | 对话区 + preview + actions |

#### CreateViewModel 状态草图

```text
CreateState
  url / userPrompt
  messages: List<ChatMessage>     // user / agent / system
  phase: Idle | Running(phaseName) | Done | Failed
  stages: List<StageInfo>
  manifestJson: String
  suggestedAppName: String?
  testInstall: Idle | Confirming | Running | Success(log) | Failed(log)
  busy: Boolean
```

#### 关键约束（继承旧计划）

1. **不使用 `TaskQueue`**：Create 用 ViewModel 自有 `CoroutineScope(Dispatchers.IO + SupervisorJob())`
2. **不使用 `ScoopCli.installVersion()`**：该方法会先卸载同名已安装 app
3. **不调用 private `executeAndLog`**：测试安装直接 `ProcessBuilder("scoop", ...)`
4. **已安装保护**：若 `app-scooper-test` 意外残留，先尝试卸载再装；正式 app 名已安装 **不** 作为阻断条件（因测试用临时名）
5. **确认对话框**：Test Install 前必须确认；若 manifest 含 `pre_install` / `post_install` / `installer` 脚本，额外警告
6. **自动清理**：成功或失败后都尽量 `uninstall` + `cache rm` 临时名；临时 json 删除

#### 临时名规则

```text
suggestedAppName = "ripgrep"
testAppName      = "ripgrep-scooper-test"
temp file        = <tmp>/ripgrep-scooper-test.json
scoop install    = scoop install <tmp>/ripgrep-scooper-test.json -s
scoop uninstall  = scoop uninstall ripgrep-scooper-test
scoop cache rm   = scoop cache rm ripgrep-scooper-test
```

Save to Bucket 使用 **真实名**（`suggestedAppName` 或用户编辑名），与测试名分离。

### 6.5 UI 规范

- 颜色 / 字体遵循项目 `AGENTS.md`：用 `MaterialTheme`，禁止硬编码色值字号
- 复用 `SectionCard`、`ConfirmDialog`、`OutlinedTextField` 等组件
- 长日志区域可虚拟化或限制保留行数，避免 Compose 卡顿

### 6.6 Save to Bucket（复用旧计划约束）

- app name 仅允许 `[a-zA-Z0-9._-]`
- 目标必须在 `{scoop_root}/buckets/` 下，防路径穿越
- 已存在文件确认覆盖
- 保存后刷新 app 列表
- git bucket 提示用户自行 commit

### 6.7 scooper 验收清单

1. 编译通过（含 mavenLocal 依赖）
2. Settings.AI 可保存并在 Create 生效
3. 对 `https://github.com/BurntSushi/ripgrep`（或项目约定样例）生成 manifest
4. Test Install 使用 `*-scooper-test`，不碰正式包，结束后无残留
5. Copy / Save to Bucket 正常
6. Create 运行时不影响全局 TaskQueue 中的 install/update

---

## 7. 分阶段落地与验收标准

### Phase A - scoop-ai 库化（阻塞 scooper）

| 步骤 | 工作 | 验收 |
|------|------|------|
| A1 | `:tools` 增加 `maven-publish` | `publishToMavenLocal` 产出 jar + pom |
| A2 | 实现 `DefaultScoopAiFactory` + `ScoopAiConfig` | 单测 / CLI 委托通过 |
| A3 | nonInteractive 可配置注入 | 无控制台不挂起 |
| A4 | 文档与版本号对齐 | 两边文档交叉引用 |

**通过门禁**：scooper 能 `implementation` 到本地坐标并 `compileKotlin`。

### Phase B - scooper 壳与配置

| 步骤 | 工作 | 验收 |
|------|------|------|
| B1 | 依赖 + skip metadata check | 编译通过 |
| B2 | Route / Sidebar / Shortcut / Main | 可导航到空 Create 页 |
| B3 | Settings.AI + 持久化 | 重启后配置仍在 |
| B4 | DI 注册 | Koin 可注入 CreateViewModel |

### Phase C - 生成主路径

| 步骤 | 工作 | 验收 |
|------|------|------|
| C1 | ScoopAiBridge / CreateService | 能调用 factory.generate |
| C2 | CreateViewModel + 阶段进度 | UI 显示 research/analyze/generate |
| C3 | Manifest 预览与编辑 | JSON 可改 |
| C4 | 错误处理 | 缺 API key / 网络错误 / noAssets 有明确提示 |

**通过门禁**：至少 1 个真实仓库端到端生成成功（建议 ripgrep 或 fd）。

### Phase D - Test Install 与导出

| 步骤 | 工作 | 验收 |
|------|------|------|
| D1 | 临时名 test install 安全层 | 正式包不受影响；临时包清理干净 |
| D2 | 确认对话框 + 脚本警告 | UI 完整 |
| D3 | Copy / Save to Bucket | 校验与刷新 |

**通过门禁**：

```bat
:: 概念验证（由 UI 触发同等流程）
scoop install <temp>\app-scooper-test.json -s
scoop uninstall app-scooper-test
scoop cache rm app-scooper-test
```

### Phase E - 打磨与回归

| 步骤 | 工作 | 验收 |
|------|------|------|
| E1 | 取消生成（cancel job） | 不泄漏协程 / 临时文件 |
| E2 | 日志与 token 展示（可选） | 不泄露 key |
| E3 | 与旧 Create 计划安全条款对照 Checklist | 见第 8 节 |
| E4 | 单元测试：临时名改写、配置映射、路由快捷键 | CI 绿 |

### 二期 (Phase F+)

1. AskUser Compose 对话框 + 可注入 AskUser tool
2. 对话 refine（对当前 manifest 追加指令）
3. 更多 provider
4. 从 ScoopSearch 带 manifest 跳转
5. Kotlin 版本统一评估

---

## 8. 风险与约束

### 8.1 继承自 `create-manifest-plan.md` 的硬约束

| 约束 | 原因 | MVP 对策 |
|------|------|----------|
| 禁止用 `installVersion()` 做测试安装 | 会先卸载同名已安装 app | 专用 `testInstall` + 临时名 |
| 禁止 Create 子任务进 `TaskQueue` | 阻塞全局 Scoop CLI 队列 | ViewModel 自有 IO Scope |
| 临时文件必须清理 | 磁盘泄漏 | try/finally |
| Save to Bucket 路径校验 | 路径穿越 | buckets 根目录白名单 |
| 大文件下载 | 耗时与磁盘 | 进度 + 取消；缓存复用 scoop-ai cache |

### 8.2 新增 AI 集成风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| Kotlin 2.1 vs 2.3 元数据 | 编译失败 / 运行时异常 | skip-metadata-version-check；端到端验证 |
| Koog / 传递依赖体积 | 安装包膨胀、ProGuard 困难 | exclude 无用模块（scoop-ai 已 exclude bedrock 等）；评估 minify |
| API Key 泄露 | 安全事故 | 不写日志；Settings 本地存储；artifact 脱敏 |
| LLM 不稳定 / 超迭代 | 体验差、费用 | maxIterations 上限；阶段超时；错误信息可理解 |
| AskUser 阻塞 UI 线程 | 桌面挂起 | MVP 强制 nonInteractive |
| agent 内置 verify 与 UI test install 双重安装 | 耗时、冲突 | MVP `verify=false`，仅 UI 按钮测试 |
| 正式包名冲突 | 用户环境损坏 | **强制 `*-scooper-test`** |
| 内存峰值（下载+解压+LLM） | OOM | 文档与相关 JVM 参数 `-Xmx512m`；控制并发 |
| GitHub API 限流 | 生成失败 | Settings 支持 github token；错误提示剩余额度（若可取） |
| Maven Local 开发摩擦 | 版本不同步 | 约定 bump 流程：改 scoop-ai -> publishToMavenLocal -> 更新 scooper 版本坐标 |

### 8.3 Test Install 安全 Checklist（实现时逐项打勾）

- [ ] 安装名 = `<base>-scooper-test`（强制后缀，用户不可关）
- [ ] 临时 manifest 文件写入系统 temp，用完删除
- [ ] 使用 `scoop install <file> -s`，不走 `installVersion`
- [ ] 不进入 TaskQueue
- [ ] 默认 local，不提供 global
- [ ] 安装前确认对话框；含脚本字段时额外警告
- [ ] 无论成功失败都尝试 uninstall + cache rm
- [ ] 完整收集 stdout/stderr 供 UI 展示
- [ ] 超时（建议 5 min 级，与 ProcessScoopVerifier 一致）
- [ ] 不修改用户已安装的同名正式 app

### 8.4 开发工作流约定

```text
# 1) scoop-ai 改完并发布本地
cd C:/Seafile/Works/kotlin/scoop-ai
gradlew.bat :core:publishToMavenLocal :tools:publishToMavenLocal

# 2) scooper 编译
cd C:/Seafile/Works/IdeaProjects/scooper
gradlew.bat compileKotlin

# 3) 功能验证：Create 生成 + test install + 清理
```

不自动 git commit（除非用户明确要求）。

---

## 9. 与 `create-manifest-plan.md` 的关系

| 维度 | 旧计划 `create-manifest-plan.md` | 本计划 `create-manifest-ai-plan.md` |
|------|----------------------------------|-------------------------------------|
| 主路径 | 规则：解析 / 筛选 / 解压 / 生成 | AI：scoop-ai 三阶段 agent |
| UI | 5 步向导 | 对话式单屏 |
| 集成 | 纯 scooper 自研服务 | 库依赖 scoop-ai |
| AI | 二期才考虑 LLM 修复 | MVP 即 AI 生成 |
| 文档状态 | **保留** 作参考 | **现行执行计划** |
| 仍继承的内容 | 测试安装安全、TaskQueue 禁忌、Save to Bucket 校验、Sidebar/Route 改造要点、UI 规范 | 直接纳入第 6 / 8 节 |
| 可延后复用 | ArchiveAnalyzer、ManifestRepairAdvisor、规则 checkver | 仅当 AI 失败 fallback 时再评估（非 MVP） |

**结论**：

- 实现以 **本文** 为准。
- 旧计划不删除：安全约束与部分 UI/工程步骤仍有价值。
- 若未来需要 "无 API key 离线生成"，可再评估规则路径作 fallback，届时另开计划，不在本 MVP 范围。

---

## 10. 关键文件索引（预期）

### scoop-ai

| 路径 | 动作 |
|------|------|
| `tools/build.gradle.kts` | 加 maven-publish |
| `tools/.../DefaultScoopAiFactory.kt` | **新建** 库入口 |
| `tools/.../interaction/SafeAskUser.kt` | 可配置 nonInteractive |
| `cli/.../CompositionRoot.kt` | 委托 factory |
| `docs/scooper-integration-ai-plan.md` | 交叉文档（与本文同步） |

### scooper

| 路径 | 动作 |
|------|------|
| `build.gradle.kts` / version catalog | 依赖 scoop-ai + skip metadata |
| `ui/Route.kt` | Create + Settings.AI |
| `ui/Sidebar.kt` / `Shortcuts.kt` / `Main.kt` | 入口 |
| `ui/Settings.kt` | AI 页 |
| `ui/Create.kt` | **新建** 对话 UI |
| `viewmodels/CreateViewModel.kt` | **新建** |
| `service/CreateService.kt` (或 ScoopAiBridge) | **新建** |
| `data/Config.kt` + repository | AI 配置字段 |
| `di/Modules.kt` | 注册 |
| `docs/create-manifest-ai-plan.md` | 本文 |
| `docs/create-manifest-plan.md` | 保留不动 |

---

## 11. 成功标准（MVP Done）

1. 用户在 Settings.AI 配置 deepseek 或 openai-compatible 后，可在 Create 页仅凭 GitHub URL 生成合法 Scoop manifest JSON。
2. 阶段进度可见；失败信息可读。
3. Test Install 使用 `*-scooper-test`，确认后执行，自动清理，不影响已安装正式包与 TaskQueue。
4. Copy / Save to Bucket 可用且带路径/重名保护。
5. 不依赖 CLI 子进程跑 agent；不实现 5 步向导；不实现 AskUser 弹窗（nonInteractive 自决）。
6. 两边文档交叉引用齐全；旧规则计划仍可查阅。
