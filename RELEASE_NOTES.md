# Release Notes

## v1.2.4 (2026-08-15)

### 🐛 Bug Fixes

- **Startup crash with malformed manifests**: A scoop manifest containing an empty `shortcuts` array crashed the app at startup with a misleading "Failed to launch JVM" dialog. All manifest and config JSON parsing is now hardened: malformed arrays, wrong field types, and a corrupted scoop `config.json` are skipped or safely defaulted instead of crashing.
- **Logging restored**: ProGuard stripped slf4j's service provider, silently disabling all logging. Provider classes are now kept in the release build, so logs are visible again.

## v1.2.3 (2026-05-31)

### ✨ New Feature

- **System Tray Icon**: Optional tray icon in the taskbar. Closing the window hides it to tray instead of exiting. Double-click or right-click menu to restore. Enable in UI Settings.
- **Periodic Auto-Refresh**: Automatically refresh scoop at a configurable interval. Set the interval in UI Settings.
- **Best Match Sort**: Local search results now sort by best match relevance by default.

### 🐛 Bug Fixes

- **Link underline**: Fixed underline not covering all lines and the trailing icon in link text.

## v1.2.2 (2026-05-25)

### 🛠 Improvement

- **Improved toast notifications**: Past-tense messages with theme-based colors and success/error/info icons; no longer re-shows when switching tabs.
- **Upgrade Compose to 1.10.2**: Kotlin 2.3.10, Gradle 8.14.3. Replaced Material Icons with lightweight Lucide icons for cleaner visuals. Stripped non-Windows native libs from sqlite-jdbc to reduce distribution size.

### 🐛 Bug Fixes

- **Status consistency**: Uninstall no longer updates DB on failure; installVersion uses bucket manifest instead of temp file (legacy installs auto-repaired); orphan apps correctly indexed during reload.
- **FPS counter**: Fixed stalling after Compose 1.10+ upgrade.
- **Sidebar shadow**: Uses `dropShadow` for consistent rendering across platforms.

## v1.2.1 (2026-05-18)

### ✨ New Feature

- **Markdown Changelog**: GitHub changelog is now rendered as rich markdown instead of plain text, supporting headings, bold, italic, code, lists, links, and more. (856f731)

### 🐛 Bug Fixes

- **Preserve Text Selection on Right-Click**: Right-clicking on selected text no longer clears the selection across all text panels (console, manifest viewers, release notes). A copy link is also added to release note cards. (f9f86dc)
- **Deduplicate Apps from Multiple Buckets**: Apps appearing in multiple buckets no longer cause version overrides. Installed apps take priority, then first bucket wins. (db79869)

## v1.2.0 (2026-05-15)

### ✨ New Feature

- **Install from Search Online**: Install apps directly from search results with a one-click install button. Auto-adds the required bucket if not present locally. Includes a confirm dialog with bucket name editing and command preview. (d213393)
- **Detail side panel**: Add a detail side panel with changelog and manifest viewer for both Discover and Search Online pages. (e301184)
- **Local full-text search**: Added full-text search support for local packages, providing faster and more flexible search. (e74d55d)

### 🛠 Improvement

- **Architecture refactor**: Extract a ScoopService application layer with event-driven state sync (SharedFlow), separating workflow orchestration from CLI adapter concerns. (8ca6c44)
- **Scoop error detection**: Fix inline `ERROR` pattern detection in scoop CLI output, preventing false success reports on hash check failures. (8ca6c44)
- **Typography**: Reduce all font sizes by 1sp for a more compact layout. (ed2fa6f)
- **Bucket tags**: Remove unnecessary uppercase conversion on bucket name tags. (2a4681b)

### 🐛 Fix

- **EOFException on resource loading**: Fix crash caused by concurrent classpath resource reads (ZipFile race) by wrapping resource access with synchronization. (ac55a1e)

---

## v1.1.0 (2026-05-09)

### ✨ New Feature

- **Online package search**: Search packages indexed by rasa/scoop-directory with support for keyword, AND/OR, exact phrase, exclude, and bucket filter syntax. Sort by relevance, name, or newest. (a2f4274)
- **Ctrl+Click refresh for full reload**: Hold Ctrl while clicking refresh to force a complete re-index instead of incremental update. (9c312c1)

### 🐛 Fix

- **Version picker sorting**: Versions are now sorted numerically (e.g. 10.x appears before 9.x). (481230e)
- **Recently updated list**: All entries now show correctly in the recently updated view. (a9e7342)
- **Version history install status**: Only versions actually installed are marked as installed in the version picker. (d17fd5b)
- **Incremental manifest update**: Manifest changes after `scoop refresh` are now properly detected. (f2f10ea)
- **Scoop error detection**: ERROR output from scoop CLI is now correctly detected, preventing false success reports on install/update operations. (90a5502)

---

## v1.0.2

### Bug Fixes

- **Fix IllegalArgumentException on Apply Changes in Settings**: The form builder's `getData()` now skips constructor parameters not present in the form, letting data class defaults apply instead of passing null for non-nullable fields.
- **Fix config overwritten on window close**: `onCloseRequest` now reads the latest config from DB instead of using a stale startup snapshot (`savedConfig`), preventing all session changes (view mode, pagination mode, theme, font size, etc.) from being reverted on exit.
- **Fix missing version install callback in grid view**: The `onInstallVersion` callback was missing in the Grid view card, causing version history installs to fail silently.

### Improvements

- **Reduce pagination bar font size**: Slightly smaller font in the page navigation bar for a cleaner look.

---

## v1.0.1

### Performance

- **Incremental manifest loading**: After `scoop update`, only changed manifests are re-parsed and upserted instead of scanning all manifest files in every bucket. Uses `git diff --name-status` against the last indexed commit to detect changes. This reduces refresh time from ~10s to near-instant in most cases.

### Bug Fixes

- **Sort/query state reset on tab switch**: Sort dropdown, sort order, and search query labels no longer reset to default values when switching between "All", "Installed", and "Updates" tabs.

---

## v1.0.0

### 🚀 New Features

- Redesign UI layout with sidebar navigation, refine dark mode support across all UI components
- Add grid view and pagination mode (Waterfall / Page-based) for package list
- Add global keyboard shortcuts: Ctrl+F (search), Ctrl+R (refresh), Ctrl+1-4 (sidebar navigation), etc.
- Add font size scaling (0.8x–1.5x) with real-time preview in UI Settings
- Show download progress on refresh button during install/update
- Add version history browser and ability to install specific versions
- Add "Open" button for installed apps with shortcuts
- Add sortable order controls (field + ascending/descending) for app list
- Persist view mode, pagination mode, page size, and window position/size across restarts
- Show splash window only on first run; replace spinner with progress bar
- Make Installed badge clickable to navigate directly to Updates tab
- Add git-based timestamps for accurate createAt/updateAt fields

### ⚡ Improvements

- Optimize JVM memory usage
