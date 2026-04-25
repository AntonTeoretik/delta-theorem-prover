package core.model

data class Definition(
    val name: String,
    val type: Term?,
    val implementation: Term?,
    val nameSpan: TextSpan?,
)

data class RewriteRule(
    val name: String,
    val lhs: Term,
    val rhs: Term,
    val keywordSpan: TextSpan,
    val nameSpan: TextSpan,
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
    val rewriteRules: List<RewriteRule> = emptyList(),
    val infixDeclarations: List<InfixDeclaration> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)
