package core.model

data class Diagnostic(
    val message: String,
    val line: Int,
    val column: Int,
)
