package core.model

data class TextSpan(
    val startOffset: Int,
    val endOffset: Int,
)

enum class TextHighlightKind {
    DEFINITION_NAME,
    RULE_KEYWORD,
    RULE_NAME,
    CONSTANT,
    TYPE_UNIVERSE,
    FREE_VARIABLE,
    BOUND_VARIABLE,
    ACTIVE_CONSTANT_USAGE,
    ACTIVE_CONSTANT_DEFINITION,
    ACTIVE_BOUND_USAGE,
    ACTIVE_BOUND_DEFINITION,
    DIAGNOSTIC,
}

data class TextHighlight(
    val span: TextSpan,
    val kind: TextHighlightKind,
)
