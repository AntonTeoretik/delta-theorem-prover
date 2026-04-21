package core.model

data class ParsedDocument(
    val sourceText: String,
    val lines: List<String>,
    val diagnostics: List<Diagnostic> = emptyList(),
)
