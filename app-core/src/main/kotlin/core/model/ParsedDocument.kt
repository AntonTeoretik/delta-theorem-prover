package core.model

data class ParsedDocument(
    val sourceText: String,
    val term: Term?,
    val diagnostics: List<Diagnostic> = emptyList(),
)
