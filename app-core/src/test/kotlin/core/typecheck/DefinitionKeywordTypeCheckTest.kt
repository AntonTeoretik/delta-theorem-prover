package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class DefinitionKeywordTypeCheckTest {
    @Test
    fun supportsAxiomRecursorNewtypeAndDefKeywords() {
        val source = """
            newtype ℕ;
            newtype = : {A : Type} → A → A → Type;
            newtype Eq {A : Type}, _ : A, _ : A;
            axiom zero : ℕ;
            axiom succ : ℕ → ℕ;
            recursor natRec : (P : ℕ → Type) → P(zero) → ((n : ℕ) → P(n) → P(succ(n))) → (n : ℕ) → P(n);

            def idNat (x : ℕ) : ℕ := x;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun rejectsAxiomBody() {
        val source = """
            newtype ℕ;
            axiom bad : ℕ := bad;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.any { it.message.contains("must not have body") }, "Expected body-forbidden diagnostic, got: $diagnostics")
    }

    @Test
    fun rejectsNewtypeWithNonTypeType() {
        val source = """
            newtype bad : ℕ;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.any { it.message.contains("must target Type") }, "Expected newtype Type-target diagnostic, got: $diagnostics")
    }

    @Test
    fun rejectsFunWithoutBody() {
        val source = """
            newtype ℕ;
            fun bad : ℕ;
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.any { it.message.contains("must have body") }, "Expected missing-body diagnostic, got: $diagnostics")
    }
}
