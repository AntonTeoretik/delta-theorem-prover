package core.model

data class LineVisual(
    val lineNumber: Int,
    val length: Int,
    val preview: String,
)

data class VisualizationData(
    val sourceText: String,
    val lineCount: Int,
    val nonEmptyLineCount: Int,
    val totalCharacters: Int,
    val lines: List<LineVisual>,
)
