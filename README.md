# Delta Theorem Prover - MVP Skeleton

Minimal standalone desktop skeleton for future mini theorem prover.

Current end-to-end flow:

1. User types text in Web editor (left pane inside JCEF).
2. `app-webui` sends source text to host bridge.
3. `app-core` builds simple visualization model from text.
4. `app-host` sends model back to web UI.
5. `app-webui` redraws canvas (right pane).

## Project structure

```text
root/
├── settings.gradle.kts
├── build.gradle.kts
├── app-core/
│   └── src/main/kotlin/core/
│       ├── model/
│       ├── parser/
│       └── eval/
├── app-host/
│   └── src/main/kotlin/app/
│       ├── Main.kt
│       ├── ui/
│       ├── editor/
│       └── bridge/
├── app-webui/
│   ├── package.json
│   ├── index.html
│   ├── src/
│   └── dist/
└── scripts/
```

## Modules

- `app-core`: pure Kotlin module with pipeline (`TextParser -> Evaluator -> VisualizationData`).
  - Future extension points: parser/AST, diagnostics, evaluator/normalizer.
- `app-host`: Swing + JCEF desktop host.
  - Uses a single embedded JCEF surface to avoid Swing/JCEF focus conflicts.
  - Contains browser panel and bridge (`Web -> Kotlin -> Web`).
  - Future extension points: file open/save, menu/actions, cursor/selection sync.
- `app-webui`: static HTML/CSS/JS renderer loaded inside JCEF.
  - Future extension points: richer canvas renderer, graph/tree renderer, syntax-aware modes.

## Requirements

- JDK 17+
- Node.js 18+ (for web UI build)

## Build web UI

From root:

```bash
./scripts/build-webui.sh
```

On Windows:

```bat
scripts\build-webui.bat
```

This copies `app-webui/index.html` and files from `app-webui/src` into `app-webui/dist`.

## Run desktop app

From root:

```bash
./scripts/run-host.sh
```

On Windows:

```bat
scripts\run-host.bat
```

Or run full flow (build web UI + run app):

```bash
./scripts/run-all.sh
```

Windows:

```bat
scripts\run-all.bat
```

## Term syntax (current MVP)

Left editor accepts a single term:

- variable: `x`
- constant: `$c`
- lambda: `\x . t`
- application: `t(a)`
- multi-arg application: `t(a, b, c)` (parsed as `(((t(a))(b))(c))`)

You can also write a program with definitions:

```text
$name1 := term;
$name2 := term2;
```

When definitions are present, the right panel shows a horizontal list of name bricks. Click any name to display its term graph.

Example:

```text
\x . ($c(y, x))
```

Visualization shows node graph with:

- blue edges for term tree structure
- green edges for binder links (`lambda` bindings and free vars from `ROOT`)

## How to extend toward theorem prover

1. Add AST nodes under `app-core/src/main/kotlin/core/model`.
2. Replace `SimpleTextParser` with real parser in `core/parser`.
3. Add diagnostics propagation from parser/evaluator to host/web UI.
4. Replace line-based visualization with tree/graph payload.
5. Expand `WebUiBridge` protocol with commands/events for richer interactions.
