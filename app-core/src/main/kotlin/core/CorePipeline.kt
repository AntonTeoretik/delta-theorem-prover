package core

import core.eval.SimpleVisualizationEvaluator
import core.eval.VisualizationEvaluator
import core.model.VisualizationData
import core.parser.DeltaProjectCodec
import core.parser.SimpleTextParser
import core.parser.TextParser

class CorePipeline(
    private val parser: TextParser = SimpleTextParser(),
    private val evaluator: VisualizationEvaluator = SimpleVisualizationEvaluator(),
) {
    fun buildVisualization(source: String, selectedDefinitionName: String? = null, caretOffset: Int? = null): VisualizationData {
        val project = DeltaProjectCodec.decode(source)
        val composed = DeltaProjectCodec.composeForTypecheck(project)
        val parsed = parser.parse(composed)
        return evaluator.evaluate(parsed, selectedDefinitionName, caretOffset)
    }
}
