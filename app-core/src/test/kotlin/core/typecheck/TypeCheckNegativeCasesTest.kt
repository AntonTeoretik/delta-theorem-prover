package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class TypeCheckNegativeCasesTest {
    @Test
    fun rejectsNonLeadingImplicitArguments() {
        val source = """
            badImplicitOrder : Type → {A : Type} → A;
        """.trimIndent()

        assertHasDiagnostic(source, "implicit arguments must be leading")
    }

    @Test
    fun rejectsUnexpectedImplicitArgument() {
        val source = """
            ℕ : Type;
            zero : ℕ;
            badUnexpected : ℕ := zero{ℕ};
        """.trimIndent()

        assertHasDiagnostic(source, "unexpected implicit argument")
    }

    @Test
    fun rejectsUninferrableImplicitArgument() {
        val source = """
            ambiguous : {A : Type} → Type := λ{A : Type} => A;
            badAmbiguous : Type := ambiguous;
        """.trimIndent()

        assertHasDiagnostic(source, "cannot infer implicit argument")
    }

    @Test
    fun rejectsImplicitLambdaAgainstExplicitPi() {
        val source = """
            badLamKind : (A : Type) → A → A := λ{A : Type} => λ(x : A) => x;
        """.trimIndent()

        assertHasAnyDiagnostic(source, listOf("expected implicit function type", "Type mismatch"))
    }

    @Test
    fun rejectsExplicitLambdaAgainstImplicitPi() {
        val source = """
            badLamKind2 : {A : Type} → A → A := λ(A : Type) => λ(x : A) => x;
        """.trimIndent()

        assertHasAnyDiagnostic(source, listOf("expected explicit function type", "Type mismatch", "Lambda binder type mismatch"))
    }

    @Test
    fun rejectsWrongTypeForExplicitImplicitArgument() {
        val source = """
            Id : {A : Type} → A → A → Type;
            ℕ : Type;
            zero : ℕ;
            badImplicitType : Type := Id{zero}(zero, zero);
        """.trimIndent()

        assertHasAnyDiagnostic(source, listOf("type mismatch for implicit argument", "Type mismatch"))
    }

    @Test
    fun rejectsMismatchedLambdaBinderType() {
        val source = """
            ℕ : Type;
            zero : ℕ;
            badBinder : (x : ℕ) → ℕ := λ(x : Type) => zero;
        """.trimIndent()

        assertHasDiagnostic(source, "Lambda binder type mismatch")
    }

    @Test
    fun rejectsRewriteRuleForNonAxiomaticConstant() {
        val source = """
            ℕ : Type;
            idNat : ℕ → ℕ := λ(n : ℕ) => n;
            rule bad.rule: idNat(n) ↦ n;
        """.trimIndent()

        assertHasDiagnostic(source, "is not axiomatic")
    }

    @Test
    fun rejectsRewriteRuleWithRhsVariablesNotInLhs() {
        val source = """
            ℕ : Type;
            F : ℕ → ℕ;
            rule bad.closed: F(n) ↦ m;
        """.trimIndent()

        assertHasAnyDiagnostic(source, listOf("is not closed", "Unknown variable or constant 'm'"))
    }

    @Test
    fun rejectsRewriteRuleWithNonConstantLhsHead() {
        val source = """
            ℕ : Type;
            zero : ℕ;
            rule bad.lhs: (λ(x : ℕ) => x)(zero) ↦ zero;
        """.trimIndent()

        assertHasDiagnostic(source, "LHS must be an application of a constant")
    }

    private fun assertHasDiagnostic(source: String, expectedSubstring: String) {
        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(
            diagnostics.any { it.message.contains(expectedSubstring) },
            "Expected diagnostic containing '$expectedSubstring', got: $diagnostics",
        )
    }

    private fun assertHasAnyDiagnostic(source: String, expectedSubstrings: List<String>) {
        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(
            diagnostics.any { diag -> expectedSubstrings.any { expected -> diag.message.contains(expected) } },
            "Expected one of diagnostics $expectedSubstrings, got: $diagnostics",
        )
    }
}
