package core.model

data class Definition(
    val name: String,
    val term: Term,
    val nameSpan: TextSpan?,
)

data class ParsedDocument(
    val sourceText: String,
    val definitions: List<Definition>,
    val diagnostics: List<Diagnostic> = emptyList(),
)
