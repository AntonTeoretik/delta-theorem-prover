package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertTrue

class NewtypeRegistryTypeCheckTest {
    @Test
    fun acceptsConstructorsReturningDeclaredNewtypeHead() {
        val source = """
            newtype Vec : (A : Type) → Type {
              constructor nil : (A : Type) → Vec(A);
              constructor cons : (A : Type) → A → Vec(A) → Vec(A);
            }
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun rejectsConstructorWithWrongResultHead() {
        val source = """
            newtype T : Type {
              constructor bad : Type;
            }
        """.trimIndent()

        val diagnostics = TypeChecker(SimpleTextParser().parse(source)).checkProgram().diagnostics
        assertTrue(
            diagnostics.any { it.message.contains("must return T") },
            "Expected constructor-head diagnostic, got: $diagnostics",
        )
    }
}
