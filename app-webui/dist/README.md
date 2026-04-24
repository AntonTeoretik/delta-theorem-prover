# app-webui/src module map

- `main.js`: tiny entry point that starts UI bootstrap.
- `app/bootstrap.js`: wires DOM, bridge API, renderer, and all feature modules.
- `app/reportPanel.js`: diagnostics report rendering.
- `editor/state.js`: shared UI state and slash-mode state helpers.
- `editor/textUtils.js`: symbol replacement and editor text/bracket utilities.
- `editor/overlay.js`: editor highlight HTML + line number rendering.
- `editor/caretOverlay.js`: custom caret overlay positioning.
- `editor/tooltips.js`: tooltip display and editor type-hint hit testing.
- `editor/inputHandlers.js`: editor input/keyboard/mouse event handlers.
- `graph/interactions.js`: canvas zoom/pan/hover interactions.
- `app/bridge.js`, `app/definitionBar.js`, `app/graph.js`, `app/renderer.js`: existing host bridge, definition bar, graph compaction/layout, and renderer modules.

All modules stay plain JavaScript and communicate via `window.Delta*` namespaces to keep script loading robust for JCEF and `file://` usage.
