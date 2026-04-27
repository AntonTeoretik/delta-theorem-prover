package core.parser

import core.model.DefinitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewtypeRegistryParserTest {
    @Test
    fun parsesNewtypeRegistryBlockAndAttachesMembers() {
        val source = """
            newtype ℕ : Type {
              constructor zero : ℕ;
              constructor succ : ℕ → ℕ;
              recursor natRec : (P : ℕ → Type) → P(zero) → ((n : ℕ) → P(n) → P(succ(n))) → (n : ℕ) → P(n);
              rule natRec.zero: natRec(P, z, s, zero) ↦ z;
            }
        """.trimIndent()

        val document = SimpleTextParser().parse(source)
        assertTrue(document.diagnostics.isEmpty(), "Unexpected diagnostics: ${document.diagnostics}")
        assertEquals(1, document.newtypeRegistries.size)

        val registry = document.newtypeRegistries.single()
        assertEquals("ℕ", registry.typeName)
        assertEquals(listOf("zero", "succ"), registry.constructors.map { it.name })
        assertEquals("natRec", registry.recursor?.name)
        assertEquals(listOf("natRec.zero"), registry.rules.map { it.name })

        val byName = document.definitions.associateBy { it.name }
        assertEquals(DefinitionKind.NEWTYPE, byName.getValue("ℕ").kind)
        assertEquals(DefinitionKind.AXIOM, byName.getValue("zero").kind)
        assertEquals(DefinitionKind.AXIOM, byName.getValue("succ").kind)
        assertEquals(DefinitionKind.RECURSOR, byName.getValue("natRec").kind)
    }
}
