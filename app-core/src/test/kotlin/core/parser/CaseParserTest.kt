package core.parser

import core.model.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaseParserTest {
    @Test
    fun parsesCaseExpressionWithConstructorPatterns() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            def pred : ℕ → ℕ := λ(n : ℕ) => case n of {
              zero => zero;
              succ(k) => k;
            };
        """.trimIndent()

        val document = SimpleTextParser().parse(source)
        assertTrue(document.diagnostics.isEmpty(), "Unexpected diagnostics: ${document.diagnostics}")

        val pred = document.definitions.first { it.name == "pred" }
        val implementation = pred.implementation as Term.Lambda
        val caseTerm = implementation.body as Term.Case
        assertEquals("n", (caseTerm.scrutinee as Term.Variable).name)
        assertEquals(2, caseTerm.branches.size)
        assertEquals("zero", caseTerm.branches[0].constructorName)
        assertEquals("succ", caseTerm.branches[1].constructorName)
        assertEquals(listOf("k"), caseTerm.branches[1].parameters.map { it.name })
    }
}
