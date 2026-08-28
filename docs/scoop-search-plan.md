# Scoop Online Search 开发计划

为 Scooper 增加在线搜索 https://scoop.sh 安装包的功能。用户可以在不添加本地 bucket 的情况下，直接搜索 Scoop 生态中所有公开的包，查看包的名称、描述、版本、来源 bucket、星标数等信息，并可通过链接打开包主页或 GitHub 仓库。

## 背景

### 搜索 API 分析

scoop.sh 网站前端使用 Azure Cognitive Search 作为搜索引擎，API 端点与密钥均公开在前端 JS 中：

- **API URL**: `https://scoopsearch.search.windows.net/indexes/apps/docs/search?api-version=2020-06-30`
- **API Key**: `DC6D2BBE65FC7313F2C52BBD2B0286ED`（公开在前端 `index.js` 中，所有用户可见）
- **请求方式**: POST + JSON body
- **项目许可证**: MIT License（ScoopInstaller/scoopinstaller.github.io）

典型请求体结构：
```json
{
  "count": true,
  "search": "firefox",
  "searchMode": "all",
  "filter": "Metadata/OfficialRepositoryNumber eq 1 and Metadata/DuplicateOf eq null",
  "orderby": "search.score() desc, Metadata/OfficialRepositoryNumber desc, NameSortable asc",
  "skip": 0,
  "top": 20,
  "select": "Id,Name,Description,Homepage,License,Version,Metadata/Repository,Metadata/RepositoryStars,Metadata/Committed"
}
```

典型响应结构：
```json
{
  "@odata.context": "...",
  "@odata.count": 30,
  "value": [
    {
      "@search.score": 17129.121,
      "Name": "firefox",
      "Description": "Popular open source web browser.",
      "Homepage": "https://www.firefox.com/",
      "License": "MPL-2.0",
      "Version": "150.0",
      "Metadata": {
        "Repository": "https://github.com/ScoopInstaller/Extras",
        "RepositoryStars": 2062,
        "Committed": "2026-04-21T12:43:39Z"
      }
    }
  ]
}
```

### 注意事项

1. API Key 公开但属于第三方服务密钥，应集中管理在 `ScoopSearchService` 中，方便未来更换。
2. Azure Search 可能有速率限制，需要在客户端做 debounce（参考网站实现 250ms debounce）。
3. API Key 理论上可能被 ScoopInstaller 团队更换。如果出现 403，需更新 key。

---

## 范围

### 包含（In）

- 搜索服务层：调用 Azure Search API，支持分页
- ViewModel 层：状态管理、输入防抖、分页加载
- UI 层：搜索输入框 + 结果列表 + 无限滚动
- 路由与导航：新增 `ScoopSearch` 路由，侧边栏 TOOLS 区添加入口
- DI 注册

### 不包含（Out）

- 搜索结果直接安装（远程包可能不在本地 bucket 中，需要先 add bucket）
- 离线搜索 / 搜索历史
- 搜索结果排序切换（默认 Best match）
- 高级筛选（如只看 Official bucket 等）

---

## 实现步骤

### Step 1: 创建数据模型

**文件**: `src/main/kotlin/scooper/service/ScoopSearchService.kt`（新建）

定义 API 响应的数据类，使用 `kotlinx.serialization`：

```kotlin
@Serializable
data class ScoopSearchResult(
    val count: Int = 0,           // "@odata.count"
    val value: List<ScoopSearchApp> = emptyList(),
)

@Serializable
data class ScoopSearchApp(
    val Name: String = "",
    val Description: String = "",
    val Homepage: String = "",
    val License: String = "",
    val Version: String = "",
    val Metadata: ScoopSearchMetadata = ScoopSearchMetadata(),
)

@Serializable
data class ScoopSearchMetadata(
    val Repository: String = "",       // bucket 的 GitHub URL
    val RepositoryStars: Int = 0,
    val Committed: String = "",        // ISO 8601 时间戳
)
```

注意：Azure Search 返回的 JSON 字段是大写开头的（如 `Name`、`Description`），数据类保持一致。

### Step 2: 创建搜索服务

**文件**: `src/main/kotlin/scooper/service/ScoopSearchService.kt`（同上）

- 使用 `java.net.http.HttpClient`（项目 build.gradle 中已包含 `java.net.http` 模块）发起 POST 请求
- 使用 `kotlinx.serialization.json.Json` 解析响应（`ignoreUnknownKeys = true`，忽略 `@odata.context`、`@search.score` 等不用的字段）
- `search(query: String, page: Int, pageSize: Int)` 方法，返回 `ScoopSearchResult`
- API Key 和 URL 集中定义在 `companion object` 中
- 请求体参考网站 SearchProcessor.tsx 的格式构建

### Step 3: 创建 ViewModel

**文件**: `src/main/kotlin/scooper/viewmodels/ScoopSearchViewModel.kt`（新建）

遵循项目已有的 Orbit MVI 模式（参考 `CleanupViewModel`）：

- **State**: `ScoopSearchState`
  - `query: String` — 当前搜索文本
  - `results: List<ScoopSearchApp>` — 已加载的结果列表
  - `totalCount: Int` — 总匹配数量（用于判断是否还有更多）
  - `currentPage: Int` — 当前页码（0-based）
  - `pageSize: Int` — 每页数量，默认 20（与网站一致）
  - `searching: Boolean` — 是否正在加载
  - `initialLoaded: Boolean` — 是否已执行过搜索（区分"未搜索"和"搜索无结果"）

- **SideEffect**: `ScoopSearchSideEffect.Toast(text)`

- **方法**:
  - `onQueryChanged(query)` — 更新 query 并触发 debounce 搜索（400ms，参考网站）
  - `loadMore()` — 加载下一页，追加到 results 列表
  - `refresh()` — 重新从第 0 页搜索

- **debounce 实现**: 使用 `MutableStateFlow` + `debounce(400)` + `distinctUntilChanged`，在 `init` 块中启动协程收集

### Step 4: 注册 SideEffect

**文件**: `src/main/kotlin/scooper/viewmodels/SideEffect.kt`（修改）

添加 `ScoopSearchSideEffect` sealed class：

```kotlin
sealed class ScoopSearchSideEffect {
    data class Toast(val text: String) : ScoopSearchSideEffect()
}
```

### Step 5: 添加路由

**文件**: `src/main/kotlin/scooper/ui/Route.kt`（修改）

在 `AppRoute` 中添加：

```kotlin
object ScoopSearch : AppRoute()
```

### Step 6: 修改侧边栏导航

**文件**: `src/main/kotlin/scooper/ui/Sidebar.kt`（修改）

在 TOOLS 区段的 Cleanup 导航项下方，添加 "Search Online" 导航项：

```kotlin
// 在 Cleanup 导航项的 Spacer(Modifier.height(16.dp)) 之前添加
SidebarNavItem(
    label = "Search Online",
    selected = currentRoute is AppRoute.ScoopSearch,
    onClick = { navigator.popupAllAndPush(AppRoute.ScoopSearch) },
) {
    Icon(painterResource("search_for.svg"), "Search Online", modifier = Modifier.size(18.dp))
}
```

已有图标资源 `search_for.svg` 可直接使用。

### Step 7: 创建搜索页面 UI

**文件**: `src/main/kotlin/scooper/ui/ScoopSearch.kt`（新建）

页面布局参考 `CleanupScreen` 的居中内容区 + 卡片容器模式。

#### 整体结构

```
┌──────────────────────────────────────────────┐
│  Search Online (标题)                         │
│  Search all Scoop packages from scoop.sh      │
│                                               │
│  ┌─ 搜索输入框 ────────────────────────────┐  │
│  │ 🔍 Search packages...            [清除]  │  │
│  └────────────────────────────────────────┘  │
│                                               │
│  ┌─ 搜索状态 ──────────────────────────────┐  │
│  │ Found 30 results for "firefox"          │  │
│  └────────────────────────────────────────┘  │
│                                               │
│  ┌─ 结果卡片 1 ────────────────────────────┐  │
│  │ firefox  v150.0  ⭐ 2062                │  │
│  │ Popular open source web browser.         │  │
│  │ 🏠 firefox.com  📄 MPL-2.0  🪣 Extras   │  │
│  └────────────────────────────────────────┘  │
│  ┌─ 结果卡片 2 ────────────────────────────┐  │
│  │ ...                                     │  │
│  └────────────────────────────────────────┘  │
│                                               │
│  ─── Loading more... ───                      │
└──────────────────────────────────────────────┘
```

#### 搜索栏

参考现有 `SearchBar` 的输入框样式：
- 高度 40dp，圆角 8dp
- 左侧搜索图标（`Icons.Filled.Search`）
- placeholder: "Search packages..."
- 右侧清除按钮（非空时显示）
- 输入变化时调用 `viewModel.onQueryChanged()`

#### 结果列表

- 使用 `LazyColumn` + `VerticalScrollbar`
- 通过 `OnBottomReached` 组件实现无限滚动加载（参考 `AppList` 中的用法）
- 每个结果项是一个 `SearchResultCard`

#### SearchResultCard

每行显示：
1. **首行**：包名（粗体，可点击打开 Homepage） + 版本号 + 星标数（如 `⭐ 2062`）
2. **描述行**：Description，单行省略
3. **元信息行**：License + bucket 名称（从 Repository URL 提取最后一段，如 `Extras`）+ 更新日期

样式参考 `AppCard`：
- 悬停时背景变色（`colors.backgroundHover`）
- 底部分隔线（`Divider`）
- 链接使用 `safeBrowse()` 打开

#### 空状态与加载状态

- **未搜索**：居中显示提示文字 "Enter a keyword to search packages on scoop.sh"
- **搜索中**：居中显示 `CircularProgressIndicator`
- **无结果**：居中显示 "No results found for \"xxx\""
- **加载更多**：列表底部显示小型加载指示器

### Step 8: 集成到 Main.kt

**文件**: `src/main/kotlin/scooper/ui/Main.kt`（修改）

#### 8a. 注入 ViewModel

在 `Window` 块中添加：

```kotlin
val scoopSearchViewModel: ScoopSearchViewModel = koinInject()
```

#### 8b. 收集 SideEffect

添加 LaunchedEffect 收集 `ScoopSearchSideEffect.Toast`，展示 Snackbar。

#### 8c. 修改路由渲染

在 `showToolbar` 判断中排除 `AppRoute.ScoopSearch`（该页面自带搜索栏）：

```kotlin
val showToolbar = when (currentRoute.value) {
    is AppRoute.Settings -> false
    AppRoute.Output -> false
    AppRoute.Cleanup -> false
    AppRoute.ScoopSearch -> false    // 新增
    else -> true
}
```

在 `routeKey` 分支中添加：

```kotlin
AppRoute.ScoopSearch -> "scoopSearch"
```

在 `when` 路由渲染中添加：

```kotlin
AppRoute.ScoopSearch -> ScoopSearchScreen()
```

### Step 9: 注册 DI

**文件**: `src/main/kotlin/scooper/di/Modules.kt`（修改）

#### system module

添加：

```kotlin
single { ScoopSearchService() }
```

#### viewModels module

添加：

```kotlin
single { ScoopSearchViewModel(get()) }
```

### Step 10: 编译验证

```bash
unset JAVA_HOME && ./gradlew compileKotlin
```

验证所有文件编译通过，无类型错误和未解析引用。

---

## 涉及文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `service/ScoopSearchService.kt` | 新建 | API 调用 + 数据模型 |
| `viewmodels/ScoopSearchViewModel.kt` | 新建 | 状态管理 |
| `viewmodels/SideEffect.kt` | 修改 | 添加 `ScoopSearchSideEffect` |
| `ui/Route.kt` | 修改 | 添加 `ScoopSearch` 路由 |
| `ui/Sidebar.kt` | 修改 | TOOLS 区添加导航项 |
| `ui/ScoopSearch.kt` | 新建 | 搜索页面 UI |
| `ui/Main.kt` | 修改 | 路由分发 + VM 注入 + SideEffect |
| `di/Modules.kt` | 修改 | DI 注册 |

## 风险与应对

| 风险 | 应对 |
|------|------|
| API Key 被更换导致 403 | Key 集中在 `companion object`，可快速更新；服务层捕获异常并返回空结果 |
| Azure Search 速率限制 | 客户端 400ms debounce + 每次只请求 20 条 |
| 网络不可用 | 捕获异常，显示错误提示，不崩溃 |
