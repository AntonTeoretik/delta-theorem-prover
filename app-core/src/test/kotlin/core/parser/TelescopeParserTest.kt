package core.parser

import core.model.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelescopeParserTest {
    @Test
    fun parsesSingleExplicitTelescopeBinder() {
        val source = """
            A : Type;
            f (a : A) : A := a;
        """.trimIndent()

        val document = SimpleTextParser().parse(source)
        assertTrue(document.diagnostics.isEmpty(), "Unexpected diagnostics: ${document.diagnostics}")

        val definition = document.definitions.first { it.name == "f" }
        val type = definition.type as Term.Pi
        assertEquals("a", type.parameter)
        assertEquals(Term.Visibility.EXPLICIT, type.visibility)
        assertEquals("A", (type.parameterType as Term.Variable).name)
        assertEquals("A", (type.body as Term.Variable).name)

        val impl = definition.implementation as Term.Lambda
        assertEquals("a", impl.parameter)
        assertEquals(Term.Visibility.EXPLICIT, impl.visibility)
        assertEquals("A", (impl.parameterType as Term.Variable).name)
        assertEquals("a", (impl.body as Term.Variable).name)
    }

    @Test
    fun parsesMixedImplicitAndExplicitTelescopeBinders() {
        val source = """
            id {A : Type}, x : A : A := x;
        """.trimIndent()

        val document = SimpleTextParser().parse(source)
        assertTrue(document.diagnostics.isEmpty(), "Unexpected diagnostics: ${document.diagnostics}")

        val definition = document.definitions.first { it.name == "id" }
        val typeA = definition.type as Term.Pi
        assertEquals("A", typeA.parameter)
        assertEquals(Term.Visibility.IMPLICIT, typeA.visibility)

        val typeX = typeA.body as Term.Pi
        assertEquals("x", typeX.parameter)
        assertEquals(Term.Visibility.EXPLICIT, typeX.visibility)
        assertEquals("A", (typeX.parameterType as Term.Variable).name)
        assertEquals("A", (typeX.body as Term.Variable).name)

        val implA = definition.implementation as Term.Lambda
        assertEquals("A", implA.parameter)
        assertEquals(Term.Visibility.IMPLICIT, implA.visibility)
        val implX = implA.body as Term.Lambda
        assertEquals("x", implX.parameter)
        assertEquals(Term.Visibility.EXPLICIT, implX.visibility)
    }

    @Test
    fun parsesTelescopeWithoutParenthesesForExplicitBinders() {
        val source = """
            h {A : Type}, {B : Type}, f : A → B, x : A, y : A : B := f(x);
        """.trimIndent()

        val document = SimpleTextParser().parse(source)
        assertTrue(document.diagnostics.isEmpty(), "Unexpected diagnostics: ${document.diagnostics}")

        val definition = document.definitions.first { it.name == "h" }
        val piA = definition.type as Term.Pi
        val piB = piA.body as Term.Pi
        val piF = piB.body as Term.Pi
        val piX = piF.body as Term.Pi
        val piY = piX.body as Term.Pi

        assertEquals(Term.Visibility.IMPLICIT, piA.visibility)
        assertEquals(Term.Visibility.IMPLICIT, piB.visibility)
        assertEquals(Term.Visibility.EXPLICIT, piF.visibility)
        assertEquals(Term.Visibility.EXPLICIT, piX.visibility)
        assertEquals(Term.Visibility.EXPLICIT, piY.visibility)

        val lambdaA = definition.implementation as Term.Lambda
        val lambdaB = lambdaA.body as Term.Lambda
        val lambdaF = lambdaB.body as Term.Lambda
        val lambdaX = lambdaF.body as Term.Lambda
        val lambdaY = lambdaX.body as Term.Lambda

        assertEquals(Term.Visibility.IMPLICIT, lambdaA.visibility)
        assertEquals(Term.Visibility.IMPLICIT, lambdaB.visibility)
        assertEquals(Term.Visibility.EXPLICIT, lambdaF.visibility)
        assertEquals(Term.Visibility.EXPLICIT, lambdaX.visibility)
        assertEquals(Term.Visibility.EXPLICIT, lambdaY.visibility)
    }
}
