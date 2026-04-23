package core.model

data class Definition(
    val name: String,
    val type: Term?,
    val implementation: Term?,
    val nameSpan: TextSpan?,
)

enum class InfixAssociativity {
    LEFT,
    RIGHT,
}

data class InfixDeclaration(
    val name: String,
    val precedence: Int,
    val associativity: InfixAssociativity,
    val nameSpan: TextSpan,
)

data class ParsedDocument(
    val sourceText: String,
    val definitions: List<Definition>,
    val infixDeclarations: List<InfixDeclaration> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)
