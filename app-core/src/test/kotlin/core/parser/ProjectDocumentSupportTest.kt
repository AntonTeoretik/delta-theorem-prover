package core.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectDocumentSupportTest {
    @Test
    fun `decode and compose imports`() {
        val source = """
            --!delta-project v1
            --!file main.dlt
            import base;
            def main : Type := Nat;
            --!file base.dlt
            def Nat : Type;
        """.trimIndent()

        val project = DeltaProjectCodec.decode(source)
        val composed = DeltaProjectCodec.composeForTypecheck(project)

        assertEquals(2, project.files.size)
        assertEquals(true, composed.contains("def Nat : Type;"))
        assertEquals(true, composed.contains("def main : Type := Nat;"))
    }
}
