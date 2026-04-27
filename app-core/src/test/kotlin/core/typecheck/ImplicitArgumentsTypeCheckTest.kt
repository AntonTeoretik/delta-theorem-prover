package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class ImplicitArgumentsTypeCheckTest {
    @Test
    fun infersImplicitArgumentsForIdAndRefl() {
        val source = """
            Id : {A : Type} → A → A → Type;
            refl : {A : Type} → (x : A) → Id(x, x);

            ℕ : Type;
            zero : ℕ;

            test : Id(zero, zero) := refl(zero);
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun supportsAutomaticImplicitLambdaInsertion() {
        val source = """
            ℕ : Type;
            zero : ℕ;

            id2 : {A : Type} → A → A :=
              λ(x : A) => x;

            testId : ℕ := id2(zero);
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun reportsImplicitArgumentsMustBeLeading() {
        val source = """
            badImplicitOrder : Type → {A : Type} → A;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.any { it.message.contains("implicit arguments must be leading") }, "Expected leading-implicit diagnostic, got: $diagnostics")
    }

    @Test
    fun reportsUnexpectedImplicitArgument() {
        val source = """
            ℕ : Type;
            zero : ℕ;
            badUnexpected : ℕ := zero{ℕ};
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.any { it.message.contains("unexpected implicit argument") }, "Expected unexpected-implicit diagnostic, got: $diagnostics")
    }

    @Test
    fun reportsCannotInferImplicitArgumentWhenAmbiguous() {
        val source = """
            ambiguous : {A : Type} → Type := λ{A : Type} => A;
            badAmbiguous : Type := ambiguous;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.any { it.message.contains("cannot infer implicit argument") }, "Expected cannot-infer-implicit diagnostic, got: $diagnostics")
    }

    @Test
    fun acceptsJTypeWithImplicitIdArgument() {
        val source = """
            Id : {A : Type} → A → A → Type;
            refl : {A : Type} → (x : A) → Id{A}(x, x);

            J :
              (A : Type) →
              (x : A) →
              (P : (y : A) → Id(x, y) → Type) →
              P(x, refl(x)) →
              (y : A) →
              (p : Id(x, y)) →
              P(y, p);
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics for J snippet, got: $diagnostics")
    }

    @Test
    fun acceptsJTypeAndRewriteRuleWithMixedImplicitStyle() {
        val source = """
            Id : {A : Type} → A → A → Type;
            refl : {A : Type} → (x : A) → Id{A}(x, x);

            J :
              (A : Type) →
              (x : A) →
              (P : (y : A) → Id(x, y) → Type) →
              P(x, refl(x)) →
              (y : A) →
              (p : Id(x, y)) →
              P(y, p);

            rule J.refl:
              J(A, x, P, pr, x, refl{A}(x)) ↦ pr;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics for J + rule snippet, got: $diagnostics")
    }

    @Test
    fun acceptsCongDefinitionWithImplicitIdInMotive() {
        val source = """
            Id : {A : Type} → A → A → Type;
            refl : {A : Type} → (x : A) → Id{A}(x, x);

            J :
              (A : Type) →
              (x : A) →
              (P : (y : A) → Id(x, y) → Type) →
              P(x, refl(x)) →
              (y : A) →
              (p : Id(x, y)) →
              P(y, p);

            cong : ∀(A : Type), (B : Type), (f : A → B), (x : A), (y : A) => Id(x, y) → Id(f(x), f(y))
              := λ(A : Type), (B : Type), (f : A → B), (x : A), (y : A), (p : Id(x, y)) =>
                J(A, x, λ(y : A) => λ(p : Id(x, y)) => Id(f(x), f(y)),
                  refl{B}(f(x)),
                  y, p
                );
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics for cong snippet, got: $diagnostics")
    }

    @Test
    fun infersTypeForUnannotatedFunctionBinderInCongStyleLambda() {
        val source = """
            Id : {A : Type} → A → A → Type;
            refl : {A : Type} → (x : A) → Id{A}(x, x);

            J :
              (A : Type) →
              (x : A) →
              (P : (y : A) → Id(x, y) → Type) →
              P(x, refl(x)) →
              (y : A) →
              (p : Id(x, y)) →
              P(y, p);

            cong : ∀(A : Type), (B : Type), (f : A → B), (x : A), (y : A) => Id(x, y) → Id(f(x), f(y))
              := λ A, B, f, (x : A), (y : A), (p : Id(x, y)) =>
                J(A, x, λ(y : A), (p : Id(x, y)) => Id(f(x), f(y)),
                  refl(f(x)),
                  y, p
                );
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics for unannotated f binder, got: $diagnostics")
    }

    @Test
    fun supportsTelescopeStyleDefinitionSugar() {
        val source = """
            id {A : Type}, x : A : A := x;

            ℕ : Type;
            zero : ℕ;
            test : ℕ := id(zero);
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics for telescope sugar, got: $diagnostics")
    }
}
