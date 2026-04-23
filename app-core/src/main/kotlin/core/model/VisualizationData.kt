package core.model

enum class TermNodeType {
    ROOT,
    APP,
    TYPE,
    CONST,
    VAR,
    LAMBDA,
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
    val symbolReplacements: Map<String, String>,
    val infixDeclarations: List<InfixDeclaration>,
    val definitionNames: List<String>,
    val selectedDefinitionName: String?,
    val freeVariableNames: List<String>,
    val nodes: List<TermNode>,
    val blueEdges: List<TermEdge>,
    val greenEdges: List<TermEdge>,
)
