# Coding Standards

Judgement-call conventions for code review, not mechanical rules; mechanical checks are left to the compiler and
tests. Every rule has an instance you can find in this repo.

## UI conventions

- Hover highlight must cover the full visual container: keep the background layer and padding separate - decide first
  which layer owns the highlight, then hang the background on that layer, instead of relying on the text's own size.
  Example: `AppCard.kt` (outer Box has onHover + background, inner Row adds the padding, highlight spans the full row);
  `Sidebar.kt` is the inverted shape (padding outside, clip + background inside, highlight is an inset rounded rect).
- Single-line text (placeholder, label, list entry) must set `maxLines = 1` + ellipsis and let layout constraints
  absorb the overflow. Example: `ac90c99 fix: keep dropdown items on a single line`, `Search.kt`, `AppCard.kt`.
- Lists are always virtualized with `LazyColumn`; never write the full `Column + forEach` composition. Example:
  `AppGrid.kt`, `ScoopSearch.kt`, `AppDetailPanel.kt`.
- Never render duplicated information twice: the summary line and the expanded detail each draw their own content;
  live activity appears only on the summary line and is not drawn again inside the bubble when collapsed. Example:
  `CreateExecution.kt` (comments splitting duties between the summary line / step block), `7799e1f` (drop the duplicate
  typing dots, dedupe the live line).
- Popup width follows its semantic anchor, not the full screen: derive panel width from the anchor or the longest
  entry, cap it with coerce, and let long entries ellipsize instead of widening. Example: the `popupPanelWidth` /
  `dropdownPanelWidth` in `ExposedDropdownMenu.kt`.

## State & seam discipline

- Single point of ownership for state: the Orbit intent is the only write path; collaborating modules never touch
  state, they only return results. Example: `DeploymentMonitor` "Owns no state: every call is a fresh read";
  `SaveCoordinator` does port + outcome only.
- Collaborating modules return outcomes, never `postSideEffect`. Toasts and state updates stay in the view model; the
  module only classifies what happened. Example: the `SaveOutcome` sealed interface in `SaveCoordinator.kt` and its
  KDoc "Toasts and CreateState updates stay with the view model".
- CancellationToken / epoch guards come in pairs: the initiator bumps the epoch, the callback carries the epoch from
  its launch and drops itself on mismatch; the double gate (offer gate + collect gate) keeps late events from a
  cancelled run out. Example: `ExecutionTimeline.kt` (offer rejects a non-current epoch + collect skips stale buffered),
  `CreateViewModel.kt` (generationEpoch / deploymentEpoch).
- Guardrails must report explicitly: scoop exiting 0 without doing the work (held app, running process) is not a
  silent success; come back with a `skippedMessage`. Example: the skippedMessage field KDoc in `CommandResult.kt`,
  the skipReasonFor extraction in `ScoopClient.kt`.

## Process conventions

- Behavior-preserving refactors are accepted by "zero changes to existing tests": module split/move commits must not
  touch assertions, only imports and grouping comments. Example: `191c059` (the extract module only adds new test
  files), `7f0ec92` (drop thin wrappers only rewrites comment text).
- Classify dead code both ways before deleting: rg for production references + rg for test references; delete only when
  production references are zero; dead code referenced by tests moves into the tests first, then is deleted. Example:
  `7f0ec92` rg-checked every relay / always-true flag / duplicate helper both ways before removal.
- KDoc explains why and trade-offs, never restates the signature. Module seams state their Trigger / Retire (when it
  is needed, when it can be dismantled and merged back). Example: `DeploymentMonitor.kt` ("Owns no state: ... the
  epoch guard and the intent serialization stay in [CreateViewModel]; this class is the read/derive seam"), the
  GitBucketPort KDoc in `SaveCoordinator.kt` ("whether a commit happens at all is decided by the save core from the
  dialog checkbox, not by the port").

## Testing naming

- Test names use backticks to describe behavior and read like a sentence: `onCancel during ask-user adds no answered
  bubble and clears askUserRequest`. Example: `CreateViewModelCancelTest.kt`.
- Each test pins exactly one behavior; a test with two assertions over two behaviors splits into two tests. Example:
  `DeploymentMonitorTest.kt` (grouped by "Not installed / unresolvable" etc., one behavior per group).
