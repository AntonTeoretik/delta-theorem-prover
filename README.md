# This is AI generated project, just to test its ability to do something fun.
Everything below is generated and might be not up-to-date

# Delta Theorem Prover

A local desktop theorem-prover playground with a Kotlin core, Swing/JCEF host, and web UI.

The project already supports dependent type-checking, implicit arguments, rewrite rules,
`newtype`/`inductive` generation, and elaboration of `case ... of` into recursors.

## What works today

- Dependent core terms: `Type`, `Pi`, `Lambda`, application, typed terms, metas.
- Definitions and declarations: `def`, `fun`, `lemma`, `theorem`, `axiom`, `recursor`.
- Infix declarations and Unicode-friendly syntax.
- Rewrite rules with validation and evaluation support.
- `newtype` blocks with generated registry metadata.
- `inductive` blocks (including parameterized non-indexed inductives) with generated:
  - constructors,
  - recursor (`T.rec`),
  - computation rules (`T.rec.<ctor>`).
- `case ... of` elaboration (check-only) with constructor-coverage checks and recursor lowering.
- UI diagnostics, definition status markers, highlighting, and term graph rendering.

## Current known limitations

- `case` is check-only: type inference for raw `case` expressions is intentionally rejected.
- Pattern syntax is first-order only: `c` or `c(x, y, ...)`.
- No nested patterns or wildcard semantics beyond identifier-level `_` handling.
- Indexed inductive families are not fully supported (`Vec(A, n)`, `Id`-style indexed elimination).
- Some advanced dependent `case` scenarios are still under active refinement.

## Repository layout

```text
.
├── app-core/      # Kotlin parser, model, typechecker, reduction, tests
├── app-host/      # Desktop host (Swing + JCEF bridge)
├── app-webui/     # HTML/CSS/JS editor + report/graph UI
├── scripts/       # convenience scripts for build/run
├── build.gradle.kts
└── settings.gradle.kts
```

## Build and run

Requirements:
- JDK 17+
- Node.js 18+

### Run tests

```bash
./gradlew :app-core:test
```

### Build host app

```bash
./gradlew :app-host:build
```

### Build web UI

```bash
./scripts/build-webui.sh
```

Windows:

```bat
scripts\build-webui.bat
```

### Run desktop app

```bash
./scripts/run-host.sh
```

Windows:

```bat
scripts\run-host.bat
```

### Full local flow

```bash
./scripts/run-all.sh
```

Windows:

```bat
scripts\run-all.bat
```

## Language overview

### Top-level forms

```text
name : Type;
name := term;
name : Type := term;
```

Keyworded forms are also supported:

```text
def f : T := ...;
lemma l : T := ...;
theorem t : T := ...;
axiom A : Type;
```

### Core term syntax

- Lambda: `λ(x : A) => body` or `λx, y => body`
- Pi: `(x : A) → B`, `A → B`, `∀(x : A) => B`
- Application: `f(x, y)` and implicit application `f{A}(x)`
- Type annotation: `t : T`
- Unicode symbols are accepted (e.g. `ℕ`, `→`, `∀`).

### Inductive and newtype examples

```text
inductive ℕ : Type {
  zero : ℕ;
  succ : ℕ → ℕ;
}

newtype = : {A : Type} → A → A → Type {
  constructor refl : {A : Type} → (x : A) → x = x;
}
```

Parameterized non-indexed inductives are supported, e.g. existential:

```text
inductive ∃ : {A : Type} → (B : A → Type) → Type {
  make : {A : Type} → {B : A → Type} → (a : A) → (b : B(a)) → ∃{A}(B);
}
```

This created recurser, and computation rules 

### Case expressions

```text
case e of {
  ctor1(x) => b1;
  ctor2(y, z) => b2;
};
```

`case` elaborates to recursor applications (`T.rec(...)`) and is validated against the expected type.

## Developer notes

- Core logic lives under `app-core/src/main/kotlin/core/typecheck/`.
- Parser logic is under `app-core/src/main/kotlin/core/parser/`.
- Model/AST lives under `app-core/src/main/kotlin/core/model/`.
- Tests are under `app-core/src/test/kotlin/core/`.

If you are changing typechecker behavior, add/adjust focused regression tests first,
then run `./gradlew :app-core:test`.
