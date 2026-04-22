package core.eval

import core.model.ParsedDocument
import core.model.VisualizationData

interface VisualizationEvaluator {
    fun evaluate(document: ParsedDocument, selectedDefinitionName: String? = null): VisualizationData
}
