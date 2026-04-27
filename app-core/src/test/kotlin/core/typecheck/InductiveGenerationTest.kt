package core.typecheck

import core.parser.SimpleTextParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InductiveGenerationTest {
    @Test
    fun generatesRecursorAndRulesForNatAndTypechecksExample() {
        val source = """
            inductive ℕ : Type {
              zero : ℕ;
              succ : ℕ → ℕ;
            }

            def + : ℕ → ℕ → ℕ := λ m, n =>
              ℕ.rec(
                λ(_ : ℕ) => ℕ,
                n,
                λ(_ : ℕ) => λ(rec : ℕ) => succ(rec),
                m
              );

            infixl 6 +;

            def one : ℕ := succ(zero);
            def two : ℕ := succ(one);
            def three : ℕ := succ(two);
            def four : ℕ := succ(three);

            infixl 3 =;

            newtype = : {A : Type} → A → A → Type {
              constructor refl : {A : Type} → (x : A) → x = x;
            }

            lemma two_plus_two_is_four : two + two = four := refl(four);
        """.trimIndent()

        val parsed = SimpleTextParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), "Parser diagnostics: ${parsed.diagnostics}")
        val registry = parsed.newtypeRegistries.firstOrNull { it.typeName == "ℕ" }
        assertTrue(registry != null, "Expected generated registry for ℕ")
        assertEquals("ℕ.rec", registry!!.recursor?.name)
        assertEquals(listOf("ℕ.rec.zero", "ℕ.rec.succ"), registry.rules.map { it.name })

        val diagnostics = TypeChecker(parsed).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }

    @Test
    fun reportsInductiveConstructorViolations() {
        val source = """
            inductive T : Type {
              badReturn : Type;
              badRec : (T → Type) → T;
            }
        """.trimIndent()

        val parsed = SimpleTextParser().parse(source)
        assertTrue(parsed.diagnostics.any { it.message.contains("constructor does not return enclosing inductive type") })
        assertTrue(parsed.diagnostics.any { it.message.contains("unsupported recursive occurrence") })
    }

    @Test
    fun reportsDuplicateConstructorAndGeneratedNameCollisions() {
        val source = """
            inductive T : Type {
              c : T;
            }

            rule T.rec.c: T.rec(P, case_c, c) ↦ case_c;

            inductive T : Type {
              c : T;
              c : T;
            }
        """.trimIndent()

        val parsed = SimpleTextParser().parse(source)
        assertTrue(parsed.diagnostics.any { it.message.contains("duplicate constructor name") }, "Diagnostics: ${parsed.diagnostics}")
        assertTrue(parsed.diagnostics.any { it.message.contains("generated recursor name already exists") }, "Diagnostics: ${parsed.diagnostics}")
        assertTrue(parsed.diagnostics.any { it.message.contains("generated rule name already exists") }, "Diagnostics: ${parsed.diagnostics}")
    }

    @Test
    fun supportsBinaryTreeInductiveGeneration() {
        val source = """
            inductive Tree : Type {
              leaf : Tree;
              node : Tree → Tree → Tree;
            }
        """.trimIndent()

        val parsed = SimpleTextParser().parse(source)
        assertTrue(parsed.diagnostics.isEmpty(), "Parser diagnostics: ${parsed.diagnostics}")
        val diagnostics = TypeChecker(parsed).checkProgram().diagnostics
        assertTrue(diagnostics.isEmpty(), "Expected no diagnostics, got: $diagnostics")
    }
}
