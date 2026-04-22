package core

import core.eval.SimpleVisualizationEvaluator
import core.eval.VisualizationEvaluator
import core.model.VisualizationData
import core.parser.SimpleTextParser
import core.parser.TextParser

class CorePipeline(
    private val parser: TextParser = SimpleTextParser(),
    private val evaluator: VisualizationEvaluator = SimpleVisualizationEvaluator(),
) {
    fun buildVisualization(source: String, selectedDefinitionName: String? = null, caretOffset: Int? = null): VisualizationData {
        val parsed = parser.parse(source)
        return evaluator.evaluate(parsed, selectedDefinitionName, caretOffset)
    }
}
