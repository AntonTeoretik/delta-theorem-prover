package core.model

enum class TermNodeType {
    ROOT,
    APP,
    TYPE,
    CONST,
    VAR,
    LAMBDA,
    PI,
    META,
}

data class TermNode(
    val id: String,
    val type: TermNodeType,
    val label: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val blueInputCount: Int,
    val blueOutputCount: Int,
    val greenInputCount: Int,
    val greenOutputCount: Int,
)

data class TypeHint(
    val id: String,
    val span: TextSpan,
    val type: String,
)

data class TypeCheckTrace(
    val title: String,
    val line: Int,
    val steps: List<String>,
)

data class EvaluationStep(
    val reason: String,
    val from: String,
    val to: String,
)

data class EvaluationTrace(
    val title: String,
    val line: Int,
    val steps: List<EvaluationStep>,
)

data class DefinitionStatus(
    val line: Int,
    val markerOffset: Int,
    val isOk: Boolean,
    val messages: List<String>,
)

data class TermEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val fromPort: Int,
    val toPort: Int,
)

data class VisualizationData(
    val sourceText: String,
    val diagnostics: List<Diagnostic>,
    val textHighlights: List<TextHighlight>,
    val typeHints: List<TypeHint>,
    val activeTypeCheckTrace: TypeCheckTrace?,
    val activeEvaluationTrace: EvaluationTrace?,
    val definitionStatuses: List<DefinitionStatus>,
    val symbolReplacements: Map<String, String>,
    val infixDeclarations: List<InfixDeclaration>,
    val definitionNames: List<String>,
    val selectedDefinitionName: String?,
    val freeVariableNames: List<String>,
    val nodes: List<TermNode>,
    val nodeTypeHints: Map<String, String>,
    val blueEdges: List<TermEdge>,
    val greenEdges: List<TermEdge>,
)
