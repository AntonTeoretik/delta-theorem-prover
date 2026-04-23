# Delta Theorem Prover - MVP Skeleton

Minimal standalone desktop skeleton for future mini theorem prover.

Current end-to-end flow:

1. User edits source text in the Web editor (left pane inside JCEF).
2. `app-webui` sends source text and caret offset to host bridge.
3. `app-host` calls `app-core` pipeline (lexer -> parser -> evaluator).
4. `app-core` returns diagnostics, highlights, symbol map, and graph payload.
5. `app-host` sends payload back to `app-webui`.
6. `app-webui` updates editor highlighting and redraws the graph.

## Project structure

```text
root/
├── settings.gradle.kts
├── build.gradle.kts
├── README.md
├── app-core/
│   ├── build.gradle.kts
│   └── src/main/kotlin/core/
│       ├── model/
│       ├── parser/
│       └── eval/
├── app-host/
│   ├── build.gradle.kts
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

- `app-core`: pure Kotlin module with parser + AST + evaluator.
  - Contains term syntax, infix declarations, typed terms, Pi/Meta AST, diagnostics, highlighting spans, and graph model.
- `app-host`: Swing + JCEF desktop host.
  - Uses a single embedded JCEF surface to avoid Swing/JCEF focus conflicts.
  - Contains browser panel and bridge (`Web -> Kotlin -> Web`) for text, caret, and selected definition.
- `app-webui`: static HTML/CSS/JS renderer loaded inside JCEF.
  - Contains editor overlay/highlighting, slash symbol input mode, compact graph view, and canvas renderer.

## Data flow

Main flow (`text -> parse -> graph`):

1. User types in `textarea`.
2. `app-webui` sends `editorTextChanged:<text>`.
3. `app-host` stores latest text and runs `CorePipeline.buildVisualization(...)`.
4. `app-core` returns:
   - diagnostics,
   - text highlights,
   - symbol replacements,
   - definition list,
   - selected-definition graph (`nodes`, `blueEdges`, `greenEdges`).
5. `app-host` sends payload via `renderFromHost(...)`.
6. `app-webui` updates editor overlay and graph.

Caret flow (`cursor -> semantic highlight`):

1. `app-webui` sends `editorCaretMoved:<offset>`.
2. `app-host` re-evaluates with current caret offset.
3. `app-core` marks active references (for constants/bound vars).
4. `app-webui` refreshes highlight spans.

Definition selection flow:

1. User clicks a definition chip.
2. `app-webui` sends `selectDefinition:<name>`.
3. `app-host` rebuilds payload for that definition.
4. `app-webui` redraws graph.

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

## Syntax (current)

Program form:

```text
name [: term] [:= term];
```

- both type and implementation are optional.
- a name is treated as a constant when it is defined earlier in the program.

Term features:

- variables and application: `t(u, v)`
- lambda: `λx.t`, `λ(x:A).t`, `λx,y.t`
- typed term: `t : T` (right-associative)
- Pi sugar:
  - `A → B`
  - `(a : A) → B`
  - `∀a. B`
  - `∀(a:A),(b:B),c.T`
- infix declarations:
  - `infixl 6 +;`
  - `infixr 5 \to;`
- backtick infix use, for example:
  - `x \`f\` y`

Slash symbol input mode in editor:

- press `\` to enter special mode,
- type token (`to`, `a`, `mN`, ...),
- press space/tab to commit into a single symbol (`→`, `α`, `ℕ`, ...).

Graph visualization:

- `APP`, `TYPE(:)`, `LAMBDA`, `PI`, `META(?mN)`, `VAR`, `CONST`, `ROOT` nodes.
- blue edges = term structure.
- green edges = binder links.
- compact mode merges APP/LAMBDA/PI chains for denser layout.

## How to extend toward theorem prover

1. Add AST nodes under `app-core/src/main/kotlin/core/model`.
2. Replace `SimpleTextParser` with real parser in `core/parser`.
3. Add diagnostics propagation from parser/evaluator to host/web UI.
4. Replace line-based visualization with tree/graph payload.
5. Expand `WebUiBridge` protocol with commands/events for richer interactions.
