# 计划：Create — 从 GitHub 自动生成 Scoop Manifest

在 Scooper 侧边栏新增 "Create" 功能，分两期迭代。MVP 实现从 GitHub URL 自动解析项目信息、下载 Asset、计算 Hash、分析压缩包结构、生成 manifest、测试安装，并支持 Copy / Save to Bucket。后续迭代增加模板复用、AI 辅助等增强功能。

## 分期规划

### MVP（首期）
- 新增 `AppRoute.Create` 路由、侧边栏入口、快捷键
- 支持 GitHub repo / release URL 解析
- 读取 repo info + latest release assets
- 筛选 Windows ZIP / EXE / MSI / 7z 等候选 asset
- 下载所选 asset，计算 SHA-256
- 对 ZIP / tar / 7z 可解压包分析 exe 和 extract_dir
- 生成 manifest JSON（可编辑）
- 保守的 checkver/autoupdate 生成
- 测试安装（专用安全接口，确认对话框，完整日志收集，安装后自动清理）
- 基于规则的修复建议（ManifestRepairAdvisor）
- 支持 Copy / Save to Bucket
- 静态 JSON 校验

### 第二期
- 模板复用（从已有 ScoopSearch manifest 跳转 Create）
- 更完整的 checkver/autoupdate 推断（page regex / jsonpath）
- AI provider 接入（LLM 辅助修复）
- 更复杂安装器支持（NSIS / Electron installer 的 7z 解包脚本）

## 范围

### MVP 包含
- 新增 `AppRoute.Create` 路由、侧边栏入口、Ctrl+5 快捷键
- `ManifestService`：URL 解析、asset 筛选、manifest 生成、checkver/autoupdate、流程编排
- `ManifestViewModel`：Orbit MVI 状态管理
- `ManifestScreen`：5 步骤 UI（输入 URL → 确认信息 + 选择 Asset → 生成编辑 → 测试安装 → 导出）
- 测试安装：专用安全接口、确认对话框、完整日志、自动清理
- 基于规则的修复建议（ManifestRepairAdvisor）
- Copy to Clipboard / Save to Bucket

### MVP 不包含
- 模板复用（第二期）
- 复杂 checkver 模式（自定义 URL + xpath / script）（第二期）
- autoupdate.hash 中的 page regex / jsonpath 模式（第二期）
- AI provider 接入（LLM 辅助修复）（第二期）
- NSIS / Electron installer 的 7z 解包脚本自动生成（第二期）

## 关键文件

- `src/main/kotlin/scooper/ui/Route.kt` — 添加 `Create` 路由定义
- `src/main/kotlin/scooper/ui/Sidebar.kt` — 重构侧边栏 items，添加 Create 菜单项，修复键盘焦点模型
- `src/main/kotlin/scooper/ui/Main.kt` — 注册 Create 路由的 Screen 渲染和 ViewModel 生命周期
- `src/main/kotlin/scooper/ui/Shortcuts.kt` — 添加 Ctrl+5 导航快捷键
- `src/main/kotlin/scooper/service/GitHubService.kt` — 扩展：repo info、release assets、错误模型
- `src/main/kotlin/scooper/service/ManifestDownloader.kt` — 扩展：新增 `computeSha256()` 公共方法
- `src/main/kotlin/scooper/service/ManifestModels.kt` — **新建**，领域模型（ProjectInfo、CandidateAsset、ManifestConfig、ArchiveAnalysis、ManifestError）
- `src/main/kotlin/scooper/service/ArchiveAnalyzer.kt` — **新建**，压缩包/MSI 结构分析 + 安装器类型检测
- `src/main/kotlin/scooper/service/ManifestService.kt` — **新建**，URL 解析、asset 筛选、manifest 生成、checkver/autoupdate、流程编排
- `src/main/kotlin/scooper/viewmodels/ManifestViewModel.kt` — **新建**，MVI ViewModel
- `src/main/kotlin/scooper/ui/Manifest.kt` — **新建**，主界面
- `src/main/kotlin/scooper/di/Modules.kt` — 注册新依赖

## 可复用代码

- `GitHubService.fetchLatestRelease()` / `fetchReleases()`（`service/GitHubService.kt`）— 获取 Release 列表（需扩展 assets 字段）
- `GitHubService.extractRepo()`（`service/GitHubService.kt`）— 从 URL 提取 owner/repo
- `ManifestDownloader.download()`（`service/ManifestDownloader.kt`）— 下载文件逻辑可参考，但需新增 `computeSha256()` 方法
- `ScoopClient.rootDir` / `bucketsBaseDir`（`service/ScoopClient.kt`）— 获取 Scoop 文件系统路径
- `OutlinedTextField`（`ui/components/TextFields.kt`）— 统一输入框样式
- `SectionCard`（`ui/components/SectionCard.kt`）— 统一卡片样式
- `ConfirmDialog`（`ui/components/ConfirmDialog.kt`）— 对话框组件
- 侧边栏 `SidebarNavItem`（`ui/Sidebar.kt`）— 侧边栏导航项组件

### 不可直接复用，需注意

- `ScoopClient.installVersion()` — **不能用于测试安装**，会先卸载同名已安装 app。需新增专用 `testInstallManifest()` 接口。
- `ScoopClient.executeAndLog()` — 是 `private` 方法，不可外部调用。测试安装需用 `ProcessBuilder`。
- `ManifestDownloader.download()` — 只有传入 hash 参数时才计算 digest；不传 hash 时不计算。需要新增 `computeSha256(file: File): String` 公共方法。
- `TaskQueue` — 不适合承载 Create 子任务（GitHub 解析、下载分析等），会阻塞全局 Scoop CLI 队列。Manifest 流程由 `ManifestViewModel` 自有的 `CoroutineScope(Dispatchers.IO)` 管理。

## UI 原型

> 5 步流程（合并原 Step 2 和 Step 3 为 Step 2）。

### 整体布局

```
+--------+----------------------------------------------------------+
|        |  Create                                          [step] |
|        |  Generate Scoop manifest from a GitHub project           |
| SIDE   +----------------------------------------------------------+
| BAR    |                                                          |
|        |                                                          |
|  ...   |                    Main Content Area                    |
| Search |              (changes per step below)                   |
| Create |                                                          |
|  ...   |                                                          |
|        |                                                          |
|        +----------------------------------------------------------+
|        |  [Copy]  [Save to Bucket]                               |
+--------+----------------------------------------------------------+
```

### Step 进度指示器

```
  1. Parse  ──── 2. Configure ──── 3. Edit ──── 4. Test ──── 5. Export
     [●]             [○]              [○]           [○]          [○]

  已完成: ✓                                      当前步骤: ●   未到达: ○
  1. Parse  ──── 2. Configure ──── 3. Edit ──── 4. Test ──── 5. Export
     [✓]             [✓]              [✓]           [●]          [○]
```

### Step 1 — 输入 URL

```
+----------------------------------------------------------+
|  Create                                           1 / 5  |
|  Generate Scoop manifest from a GitHub project           |
+----------------------------------------------------------+
|                                                          |
|  Enter GitHub URL                                        |
|  +----------------------------------------------------+  |
|  | https://github.com/jesseduffield/lazygit           |  |
|  +----------------------------------------------------+  |
|                                                          |
|  Supported URL formats:                                  |
|    • Repository:  github.com/owner/repo                  |
|    • Releases:    github.com/owner/repo/releases         |
|    • Tagged release: github.com/.../releases/tag/v1.0    |
|                                                          |
|                                              [ Parse ]   |
|                                                          |
+----------------------------------------------------------+
```

解析中状态：

```
+----------------------------------------------------------+
|  Create                                           1 / 5  |
+----------------------------------------------------------+
|                                                          |
|  +----------------------------------------------------+  |
|  | https://github.com/jesseduffield/lazygit           |  |
|  +----------------------------------------------------+  |
|                                                          |
|            [  Parsing project info...     ]              |
|            [ ========================70%  ]              |
|                                                          |
+----------------------------------------------------------+
```

### Step 2 — 配置项目信息和 Asset（合并原 Step 2 + Step 3）

解析完成后，左侧展示项目信息（可编辑），右侧展示候选 Asset 列表。

```
+----------------------------------------------------------+
|  Create                                           2 / 5  |
|  Configure project and select assets                      |
+----------------------------------------------------------+
|                                                          |
|  +-- Project Info --+  +-- Select Assets --------------+ |
|  | Name             |  | Latest: v0.61.1 (2025-05-10) | |
|  | [ lazygit      ] |  |                                | |
|  | Description      |  | [x] lazygit_0.61.1_Windows_   | |
|  | [ A simple     ] |  |     x86_64.zip                | |
|  | [ terminal UI  ] |  |     64bit | ZIP | 4.2 MB      | |
|  | Homepage         |  |                                | |
|  | [ https://gith ] |  | [ ] lazygit_0.61.1_Windows_   | |
|  | License          |  |     32-bit.zip                | |
|  | [ MIT          ] |  |     32bit | ZIP | 3.8 MB      | |
|  | Version          |  |                                | |
|  | [ 0.61.1       ] |  | [ ] lazygit_0.61.1_Windows_   | |
|  +------------------+  |     arm64.zip                 | |
|                        |     arm64 | ZIP | 4.0 MB      | |
|                        +--------------------------------+ |
|                                                          |
|  Checksum: checksums.txt detected -> autoupdate hash     |
|                                                          |
|  +-- Download & Analyze Selected Asset --------------+   |
|  |  [ Download & Analyze ]                            |   |
|  |                                                    |   |
|  |  Result:                                           |   |
|  |  Installer type:   ZIP (portable)                  |   |
|  |  Hash (SHA-256):   6024f3094904caaf9b96...        |   |
|  |                                                    |   |
|  |  Files in archive:                                 |   |
|  |    lazygit.exe     <- main executable              |   |
|  |    LICENSE                                        |   |
|  |                                                    |   |
|  |  bin:          [ lazygit.exe           ]           |   |
|  |  extract_dir:  [ (none)                ]           |   |
|  +----------------------------------------------------+  |
|                                                          |
|                                   [ Back ]  [ Next ]     |
+----------------------------------------------------------+
```

如果检测到 InnoSetup 安装器：

```
|  +-- Download & Analyze Selected Asset --------------+   |
|  |  Result:                                           |   |
|  |  Installer type:   InnoSetup                       |   |
|  |  Hash (SHA-256):   a1b2c3d4e5f6...                |   |
|  |                                                    |   |
|  |  (i) Scoop uses innounp to extract InnoSetup      |   |
|  |      installers to $dir. Setting "innosetup":true  |   |
|  |      will tell Scoop to unpack rather than run     |   |
|  |      the installer.                                |   |
|  +----------------------------------------------------+  |
```

> 注意：InnoSetup 的 `"innosetup": true` 在 Scoop 中表示**解包**（用 innounp），不是静默安装。

如果检测到 NSIS 或无法识别的安装器：

```
|  +-- Download & Analyze Selected Asset --------------+   |
|  |  Result:                                           |   |
|  |  Installer type:   NSIS (unsupported)              |   |
|  |  Hash (SHA-256):   a1b2c3d4e5f6...                |   |
|  |                                                    |   |
|  |  ! NSIS installers require custom extract scripts  |   |
|  |    in Scoop manifests. Manual configuration needed. |   |
|  |                                                    |   |
|  |  Change type:                                      |   |
|  |  [ ] ZIP        [ ] InnoSetup  [ ] Portable EXE    |   |
|  |  [x] NSIS (manual)  [ ] MSI                        |   |
|  +----------------------------------------------------+  |
```

### Step 3 — 生成 & 编辑 Manifest

```
+----------------------------------------------------------+
|  Create                                           3 / 5  |
|  Review and edit manifest                                |
+----------------------------------------------------------+
|                                                          |
|  Generated Manifest                          [Refresh]   |
|  +----------------------------------------------------+  |
|  | {                                                   |  |
|  |     "version": "0.61.1",                            |  |
|  |     "description": "A simple terminal UI for git",  |  |
|  |     "homepage": "https://github.com/jesseduffie..", |  |
|  |     "license": "MIT",                               |  |
|  |     "architecture": {                               |  |
|  |         "64bit": {                                  |  |
|  |             "url": "https://github.com/jesseduf..", |  |
|  |             "hash": "6024f3094904caaf9b9672b80.."   |  |
|  |         }                                           |  |
|  |     },                                              |  |
|  |     "bin": "lazygit.exe",                           |  |
|  |     "checkver": "github",                           |  |
|  |     "autoupdate": {                                 |  |
|  |         "architecture": {                           |  |
|  |             "64bit": {                              |  |
|  |                 "url": "https://github.com/..$ver." |  |
|  |             }                                       |  |
|  |         },                                          |  |
|  |         "hash": {                                   |  |
|  |             "url": "$baseurl/checksums.txt"         |  |
|  |         }                                           |  |
|  |     }                                               |  |
|  | }                                                   |  |
|  +----------------------------------------------------+  |
|                                                          |
|  +--- Autoupdate Preview (detected) -----------------+  |
|  |  checkver:    "github" (auto from repo)            |  |
|  |  URL pattern: consistent across 3 releases         |  |
|  |  Hash source: checksums.txt (direct URL)           |  |
|  +----------------------------------------------------+  |
|                                                          |
|                                   [ Back ]  [ Test Install ]  |
+----------------------------------------------------------+
```

### Step 4 — 测试安装

确认对话框（点击 Test Install 后弹出）：

```
+----------------------------------------------------------+
|  Test Install                                      [ X ] |
+----------------------------------------------------------+
|                                                          |
|  You are about to test install 'lazygit' from the        |
|  generated manifest.                                     |
|                                                          |
|  This will:                                              |
|    1. Execute scoop install with the manifest file       |
|    2. Run any pre_install / post_install scripts         |
|    3. Automatically uninstall and clean up after test    |
|                                                          |
|  +-- Warning ------------------------------------------+ |
|  |  This manifest contains post_install scripts.       | |
|  |  Review them carefully before proceeding.           | |
|  +-----------------------------------------------------+ |
|                                                          |
|  Install scope:  (x) Local  ( ) Global                   |
|                                                          |
|                              [ Cancel ]  [ Install ]     |
+----------------------------------------------------------+
```

成功场景：

```
+----------------------------------------------------------+
|  Create                                           4 / 5  |
|  Test install result                                      |
+----------------------------------------------------------+
|                                                          |
|  +----------------------------------------------------+  |
|  |   ✔ Installation Successful                         |  |
|  |                                                      |  |
|  |   'lazygit' was installed and verified.             |  |
|  |   App has been uninstalled to clean up.             |  |
|  +----------------------------------------------------+  |
|                                                          |
|  Output:                                                 |
|  +----------------------------------------------------+  |
|  | Installing 'lazygit' (...)                         |  |
|  | Downloading...                                      |  |
|  | Extracting...                                       |  |
|  | Creating shim for lazygit.exe                       |  |
|  | 'lazygit' was installed successfully!               |  |
|  +----------------------------------------------------+  |
|                                                          |
|                                   [ Back ]  [ Next ]     |
+----------------------------------------------------------+
```

失败场景 + 规则修复建议：

```
+----------------------------------------------------------+
|  Create                                           4 / 5  |
|  Test install result                                      |
+----------------------------------------------------------+
|                                                          |
|  +----------------------------------------------------+  |
|  |   ✘ Installation Failed                             |  |
|  +----------------------------------------------------+  |
|                                                          |
|  Error Output:                                           |
|  +----------------------------------------------------+  |
|  | Hash check failed!                                  |  |
|  | App:         lazygit                                |  |
|  | Expected:    6024f309...                             |  |
|  | Actual:      e5f6a7b8...                             |  |
|  +----------------------------------------------------+  |
|                                                          |
|  +--- Repair Suggestion --------------------[Apply]---+  |
|  |  Hash mismatch detected.                            |  |
|  |                                                     |  |
|  |  Suggested fix:                                     |  |
|  |  - Update hash to the actual value:                 |  |
|  |    + "e5f6a7b8..." (auto-computed from download)    |  |
|  +-----------------------------------------------------+  |
|                                                          |
|                        [ Re-test ]  [ Edit Manifest ]    |
+----------------------------------------------------------+
```

### Step 5 — 导出

```
+----------------------------------------------------------+
|  Create                                           5 / 5  |
|  Export manifest                                          |
+----------------------------------------------------------+
|                                                          |
|  Manifest is ready!                                      |
|                                                          |
|  +----------------------------------------------------+  |
|  |  lazygit.json  |  12 lines  |  valid JSON  |  ✅    |  |
|  +----------------------------------------------------+  |
|                                                          |
|  [ Copy to Clipboard ]                                   |
|                                                          |
|  [ Save to Bucket ]                                      |
|                                                          |
|  +-- Save to Bucket dialog ---------------------------+  |
|  |  App name:   lazygit                                |  |
|  |  File:       lazygit.json                           |  |
|  |                                                     |  |
|  |  Select bucket:                                     |  |
|  |  ( )  extras  (C:\Users\xujl\scoop\buckets\extras) |  |
|  |  ( )  main    (C:\Users\xujl\scoop\buckets\main)   |  |
|  |                                                     |  |
|  |  Target: ...\extras\bucket\lazygit.json             |  |
|  |                                                     |  |
|  |  ! lazygit.json already exists in extras            |  |
|  |    [ Overwrite ]  [ Cancel ]                        |  |
|  |                                                     |  |
|  |  Note: Saved to a git bucket. Run `git commit`     |  |
|  |  in the bucket directory to persist changes.        |  |
|  |                          [ Cancel ]  [ Save ]       |  |
|  +-----------------------------------------------------+  |
|                                                          |
|                                   [ Back ]               |
+----------------------------------------------------------+
```

## 实施步骤（MVP）

### 1. 添加路由、侧边栏入口、快捷键
- 文件：`Route.kt`
- 变更：添加 `object Create : AppRoute()`
- 文件：`Sidebar.kt`
- 变更：
  - 重构侧边栏 items 为单一来源列表，包含 Search Online 和 Create
  - `focusRequesters` 数量从硬编码 `5` 改为 `items.size`
  - `selectedIndex` 增加 `AppRoute.ScoopSearch` 和 `AppRoute.Create` 的匹配
  - 上下键范围从 `0..4` 改为 `items.indices`
  - 在 TOOLS section 的 Search Online 下方添加 Create 菜单项
- 文件：`Shortcuts.kt`
- 变更：添加 `NavCreate("Navigate to Create", "Ctrl+5")` 快捷键，更新 `matchShortcutKey` 和 `handleWindowShortcut`
- 文件：`ShortcutsTest.kt`
- 变更：补充 Ctrl+5 → Create 的测试用例
- 验收：侧边栏显示 Create，点击和 Ctrl+5 均可导航；键盘上下导航覆盖所有菜单项包括 Search Online 和 Create

### 2. 定义 Create 领域模型和错误类型
- 文件：`service/ManifestModels.kt`（**新建**）
- 变更：定义核心数据类型：
  - `ManifestError` sealed class：`InvalidGitHubUrl`、`RepositoryNotFound`、`NoReleaseFound`、`NoWindowsAssetFound`、`RateLimited(message)`、`NetworkError(message)`、`DownloadFailed(message)`
  - `ParseResult`：URL 类型（Repo / Releases / TaggedRelease / SingleAsset）
  - `ProjectInfo`：name、description、homepage、license、latestVersion、releaseAssets
  - `CandidateAsset`：asset 信息 + 推断的架构 + 安装器类型
  - `InstallerType` enum：`Zip`、`Msi`、`InnoSetup`、`NsisNeedsManual`、`SevenZipSfx`、`PortableExe`
  - `ArchiveAnalysis`：exe 文件列表、extract_dir、bin 候选
  - `ManifestConfig`：所有 manifest 字段的用户确认值
  - `CheckverConfig` / `AutoupdateConfig`：checkver/autoupdate 生成结果
- 验收：编译通过，类型定义完整

### 3. 扩展 GitHubService
- 文件：`service/GitHubService.kt`
- 变更：
  - 新增 `GitHubReleaseAsset` 数据类（`name`、`browser_download_url`、`size`、`content_type`）
  - 修改 `GitHubRelease` 添加 `assets: List<GitHubReleaseAsset> = emptyList()` 字段
  - 新增 `GitHubRepoInfo` 数据类（`description`、`homepage`、`license`、`topics`）
  - 新增 `suspend fun fetchRepoInfo(owner, repo): Result<GitHubRepoInfo>` — 调用 `/repos/{owner}/{repo}`
  - 所有新方法内部使用 `withContext(Dispatchers.IO)` 包裹
  - 错误通过 `Result<T>` 返回，不返回 null/emptyList 代替错误
- 验收：传入 `https://github.com/cli/cli` 能获取到 repo info 和 release assets；404 repo 返回 `Result.failure`

### 4. 扩展 ManifestDownloader
- 文件：`service/ManifestDownloader.kt`
- 变更：新增公共方法 `fun computeSha256(file: File): String` — 独立于 download 流程的 SHA-256 计算
- 验收：对已知文件能正确计算 SHA-256

### 5. 实现 ManifestService（URL 解析 + asset 筛选 + manifest 生成 + checkver/autoupdate）
- 文件：`service/ManifestService.kt`（**新建**）
- 变更：参考项目现有 `ScoopService` 风格，一个类包含所有核心逻辑：
  - `parseGitHubUrl(url: String): ParseResult` — 解析 URL 类型
  - `suspend fun fetchProjectInfo(owner, repo): Result<ProjectInfo>` — 组合调用 GitHubService
  - `selectWindowsAssets(assets): List<CandidateAsset>` — 筛选 + 架构识别
  - `generateManifest(config: ManifestConfig): String` — 生成 JSON
  - `validateManifest(json: String): ValidationResult` — 静态校验
  - checkver 生成：只生成 `"github"` 或 `{ "github": "url" }`，非标准 tag 不生成
  - autoupdate URL 模板：版本号替换为 `$version`，不满足条件时不生成
  - autoupdate hash：per-asset hash 放 architecture block 内，bundle hash 放顶层
- 验收：单元测试覆盖 URL 解析、Asset 筛选、manifest 生成

### 6. 实现 ArchiveAnalyzer
- 文件：`service/ArchiveAnalyzer.kt`（**新建**）
- 变更：
  - **ZIP / tar / 7z**：解压到临时目录，分析内部结构
    - 找到所有 `.exe` → 候选 bin 列表
    - 如果 exe 在单一子目录中 → 推荐 extract_dir
    - 如果多个 exe → 返回列表让用户选择
  - **MSI**：使用 `msiexec /a file.msi /qb TARGETDIR=temp_dir`（Windows 自带的 administrative install）解压 MSI 内容，分析内部文件结构找到 exe 和 extract_dir。如果 msiexec 解压失败，fallback 为仅标记 MSI 类型并提示用户手动配置
  - **InnoSetup**：EXE 二进制签名扫描（读取 PE 资源段查找 `"Inno Setup"` 字符串）→ 标记为 InnoSetup
    - Scoop 中 `"innosetup": true` 表示用 **innounp 解包**，不是静默安装
    - 不自动生成 `pre_install` / `installer` 脚本
  - **NSIS / Electron installer**：检测到后标记为 `NsisNeedsManual`，不自动生成无效字段
    - NSIS 在 Scoop 中没有 `"nsis": true` 字段
    - 通常需要 `url: "...exe#/dl.7z"` + `pre_install: "Expand-7ZipArchive ..."` 脚本
    - 首期标记为需要手动配置
  - **7-Zip SFX**：文件头含 `7z` 魔数 → 当 ZIP 处理
  - **Portable EXE**：以上都不匹配 → 使用 URL fragment 重命名 `#/app.exe`，再生成 bin
  - 所有临时文件 try/finally 清理
- 验收：
  - 给定 InnoSetup EXE → 检测为 InnoSetup → manifest 包含 `"innosetup": true`（解包语义）
  - 给定 NSIS EXE → 标记为 `NsisNeedsManual`
  - 给定 ZIP → 正常解压分析 exe
  - 给定 MSI → 尝试 msiexec 解压分析，失败时标记为 MSI 并提示需手动配置
  - 多个 exe 的 ZIP → 返回列表不自动猜

### 7. 实现 ManifestViewModel
- 文件：`viewmodels/ManifestViewModel.kt`（**新建**）
- 变更：
  - `ManifestState`：`step`、`url`、`projectInfo`、`candidateAssets`、`selectedAssets`、`archiveAnalysis`、`manifestJson`、`validationResult`
  - `ManifestSideEffect`：`Toast(text)` 提示
  - 自有 `CoroutineScope(Dispatchers.IO + SupervisorJob())`，不使用全局 TaskQueue
  - 提供 `onUrlChange()`、`onParse()`、`onDownloadAndAnalyze()`、`onGenerate()`、`onTestInstall()`、`onApplySuggestion()`、`onCopy()`、`onSaveToBucket()` 等 intent
  - `onSaveToBucket()` 校验 app name（避免非法文件名/路径穿越）、检测目标文件是否已存在
  - 保存成功后通过 `AppsRepository.loadApps()` 或 `ScoopService.events` 刷新 app 列表
- 验收：ViewModel 状态在各步骤间正确流转

### 8. 实现测试安装和规则修复
- 文件：`service/ManifestService.kt`
- 变更：新增测试安装方法
  - `suspend fun testInstall(appName: String, manifestFile: File, onOutput: (String) -> Unit): TestInstallResult`
  - 检查 app 是否已安装，已安装时返回 `AlreadyInstalled` 阻止测试
  - 使用 `ProcessBuilder` 执行 `scoop install <manifestFile>`（不复用 `installVersion()`）
  - 收集完整 stdout + stderr 到 `outputLines: List<String>`
  - 成功后自动执行 `scoop uninstall <appName>` + `scoop cache rm <appName>` 清理
  - 默认 local 安装，不支持 global
  - try/finally 清理临时 manifest 文件
- 新增 `ManifestRepairAdvisor`：基于规则的错误分析
  - `fun analyzeError(manifest: String, errorOutput: String): RepairSuggestion?`
  - 规则：hash mismatch → 建议更新 hash；can't shim → 建议修正 bin/extract_dir；download 404 → URL 错误；failed to extract → 安装器类型错误
  - 不涉及 AI provider，纯规则匹配
- 新增 `TestInstallResult` sealed class：`Success(output)`、`Failed(output)`、`AlreadyInstalled`
- 验收：
  - 对已知项目能走通 test install + uninstall 清理
  - 已安装同名 app 时返回 `AlreadyInstalled`
  - hash mismatch 时 `analyzeError()` 返回更新 hash 的建议

### 9. 实现 ManifestScreen MVP UI
- 文件：`ui/Manifest.kt`（**新建**）
- 变更：实现 5 步骤 UI
  - **Step 1 — 输入 URL**：URL 输入框 + Parse 按钮
  - **Step 2 — 配置**：项目信息表单（可编辑） + Asset 列表（可勾选）+ 下载分析结果
  - **Step 3 — 编辑 Manifest**：JSON 文本编辑器 + Autoupdate Preview 折叠面板
  - **Step 4 — 测试安装**：确认对话框（含风险提示）+ 安装输出日志 + 成功/失败结果 + 修复建议
  - **Step 5 — 导出**：Copy / Save to Bucket
- UI 规范约束：
  - 颜色使用 `MaterialTheme.colors` 扩展属性或 `theme/Color.kt` 常量，禁止硬编码 `Color(0xFF...)`
  - 字体使用 `MaterialTheme.typography`，禁止硬编码 `fontSize = xx.sp`
  - 需要新颜色（如 success/warning 背景）时先在 `theme/Color.kt` 中扩展
  - JSON 编辑器、日志文本区域也走 `typography`
- 验收：UI 布局与项目风格一致，各步骤流转顺畅

### 10. 注册依赖和路由
- 文件：`di/Modules.kt`
- 变更：在 `system` 中注册 `ManifestService`、`ArchiveAnalyzer`，在 `viewModels` 中注册 `ManifestViewModel`
- 文件：`ui/Main.kt`
- 变更：
  - 在 `window.onCloseRequest` 中添加 `createViewModel.close()`
  - 路由分发添加 `AppRoute.Create -> ManifestScreen()`
  - `showToolbar` 添加 `AppRoute.Create -> false`
  - 添加 `ManifestSideEffect` 的 `LaunchedEffect` 收集
  - 验收：编译通过，侧边栏点击 Create 正确渲染 ManifestScreen

### 11. Save to Bucket 补充
- 在 `onSaveToBucket()` 中增加：
  - app name 校验：只允许 `[a-z0-9._-]` 字符
  - 目标路径校验：必须在 `{scoop_root}/buckets/` 下，防止路径穿越
  - 目标文件已存在时弹出确认对话框
  - 保存成功后触发 `appsRepository.loadApps()` 刷新 app 列表
  - 保存到 git bucket 时提示用户后续 commit

## 依赖关系（MVP）

```
步骤 1（路由/侧边栏/快捷键） ← 无依赖
步骤 2（领域模型） ← 无依赖
步骤 3（GitHubService 扩展） ← 无依赖
步骤 4（ManifestDownloader 扩展） ← 无依赖
步骤 5（ManifestService） ← 依赖步骤 2 + 3
步骤 6（ArchiveAnalyzer） ← 依赖步骤 2 + 4
步骤 7（ManifestViewModel） ← 依赖步骤 5 + 6
步骤 8（测试安装 + 规则修复） ← 依赖步骤 5
步骤 9（ManifestScreen UI） ← 依赖步骤 7 + 8
步骤 10（注册依赖/路由） ← 依赖步骤 1 + 7 + 9
步骤 11（Save to Bucket 补充） ← 依赖步骤 10
```

步骤 1、2、3、4 可并行。步骤 5、6 可并行（均依赖步骤 2）。步骤 7、8 可并行（均依赖步骤 5+6 / 步骤 5）。

## 风险

- **GitHub API 速率限制**：未认证请求 60 次/小时。需要在请求中检查 `X-RateLimit-Remaining`，限制不足时提示用户。可后续支持 GitHub Token 配置。
- **大文件下载耗时**：某些 Release Asset 可达数百 MB。需要提供进度反馈和取消机制，大文件时警告用户。
- **安装器类型检测不完整**：部分加壳安装器无法通过 PE 签名识别。NSIS/Electron installer 首期仅标记为需手动配置。
- **磁盘空间**：下载 Asset + 解压分析可能占用临时空间。需要 try/finally 及时清理临时文件。

## 验证方式

1. 编译验证：`unset JAVA_HOME && ./gradlew compileKotlin`
2. 单元测试：
   - URL 解析：repo URL、releases URL、tagged release URL、single asset URL、非 GitHub URL、`.git` 后缀 URL
   - Asset 筛选：`windows-amd64`、`win-x64`、`x86_64-pc-windows-msvc`、`i386`、`arm64`、排除 darwin/linux、排除 checksums.txt
   - Manifest 生成：单架构 ZIP、多架构 ZIP、portable exe with `#/app.exe`、含 extract_dir、多 exe 需用户选择
   - 快捷键：Ctrl+5 → Create、Ctrl+Shift+5 不误触
3. 功能验证（端到端）：
   - 输入 `https://github.com/jesseduffield/lazygit` → 解析成功 → 选择 64bit zip → 分析出 lazygit.exe → 生成 manifest 含 checkver + autoupdate
   - Copy → 剪贴板内容为合法 JSON
   - Save to Bucket → 保存成功 → app 列表刷新
4. 错误场景：
   - 非 GitHub URL → `InvalidGitHubUrl` 错误提示
   - 无 Release → `NoReleaseFound` 错误提示
   - 无 Windows Asset → `NoWindowsAssetFound` 错误提示

## 决策记录

- AI 辅助修复首期仅用规则引擎，命名为 `ManifestRepairAdvisor`
- 安装器类型：ZIP/7z/tar 自动分析，MSI 通过 `msiexec /a` 解压分析结构（失败时 fallback 提示手动配置），InnoSetup 生成 `"innosetup": true`（解包语义），NSIS 标记为需手动配置
- 测试安装使用专用安全接口，不复用 `installVersion()`，含确认对话框和已安装 app 保护
- `ManifestDownloader` 新增 `computeSha256()` 公共方法，不改造已有 download 逻辑
- Create 子任务使用 ViewModel 自有 CoroutineScope，不进入全局 TaskQueue
- GitHubService 扩展方法使用 `Result<T>` 返回错误，使用 `withContext(Dispatchers.IO)` 包裹
- checkver/autoupdate 保守生成：不强行推断复杂 regex，不满足条件时不生成 autoupdate
- UI 颜色/字体遵循项目 AGENTS.md 规范
- Sidebar 重构为统一的 items 列表，修复键盘焦点模型
- Save to Bucket 含 app name 校验、路径穿越防护、已存在文件确认、保存后刷新 app 列表
