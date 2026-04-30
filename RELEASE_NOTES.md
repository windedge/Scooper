# Release Notes

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
