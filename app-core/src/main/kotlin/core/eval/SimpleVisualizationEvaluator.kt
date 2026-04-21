package core.eval

import core.model.LineVisual
import core.model.ParsedDocument
import core.model.VisualizationData

class SimpleVisualizationEvaluator : VisualizationEvaluator {
    override fun evaluate(document: ParsedDocument): VisualizationData {
        val lineVisuals = document.lines.mapIndexed { index, line ->
            LineVisual(
                lineNumber = index + 1,
                length = line.length,
                preview = line.take(32),
            )
        }

        return VisualizationData(
            sourceText = document.sourceText,
            lineCount = document.lines.size,
            nonEmptyLineCount = document.lines.count { it.isNotBlank() },
            totalCharacters = document.sourceText.length,
            lines = lineVisuals,
        )
    }
}
