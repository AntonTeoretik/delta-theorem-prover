package core.eval

import core.model.ParsedDocument
import core.model.Term
import core.model.TermEdge
import core.model.TermNode
import core.model.TermNodeType
import core.model.VisualizationData

class SimpleVisualizationEvaluator : VisualizationEvaluator {
    override fun evaluate(document: ParsedDocument): VisualizationData {
        val term = document.term
            ?: return VisualizationData(
                sourceText = document.sourceText,
                diagnostics = document.diagnostics,
                freeVariableNames = emptyList(),
                nodes = emptyList(),
                blueEdges = emptyList(),
                greenEdges = emptyList(),
            )

        val builder = TermGraphBuilder()
        val graph = builder.build(term)

        return VisualizationData(
            sourceText = document.sourceText,
            diagnostics = document.diagnostics,
            freeVariableNames = graph.freeVariableNames,
            nodes = graph.nodes,
            blueEdges = graph.blueEdges,
            greenEdges = graph.greenEdges,
        )
    }
}

private class TermGraphBuilder {
    private val nodes = mutableListOf<MutableNode>()
    private val blueEdges = mutableListOf<TermEdge>()
    private val greenEdges = mutableListOf<TermEdge>()
    private val freeVariablePortByName = LinkedHashMap<String, Int>()

    private var nextNodeId = 0
    private var nextBlueEdgeId = 0
    private var nextGreenEdgeId = 0
    private lateinit var rootNodeId: String

    fun build(term: Term): GraphResult {
        val rootNode = addNode(
            type = TermNodeType.ROOT,
            label = "ROOT",
            width = 120.0,
            height = 42.0,
            blueInputCount = 0,
            blueOutputCount = 1,
            greenInputCount = 0,
            greenOutputCount = 0,
        )
        rootNodeId = rootNode.id

        val termRoot = addTerm(term, emptyMap())
        addBlueEdge(rootNode.id, termRoot, fromPort = 0, toPort = 0)

        val freeNames = freeVariablePortByName.keys.toList()
        rootNode.greenOutputCount = freeNames.size
        rootNode.width = maxOf(120.0, 92.0 + freeNames.size * 26.0)

        layout(rootNode.id)

        return GraphResult(
            nodes = nodes.map { it.toImmutable() },
            blueEdges = blueEdges.toList(),
            greenEdges = greenEdges.toList(),
            freeVariableNames = freeNames,
        )
    }

    private fun addTerm(term: Term, scope: Map<String, BinderRef>): String {
        return when (term) {
            is Term.Application -> {
                val node = addNode(
                    type = TermNodeType.APP,
                    label = "",
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 2,
                    greenInputCount = 0,
                    greenOutputCount = 0,
                )
                val fn = addTerm(term.function, scope)
                val arg = addTerm(term.argument, scope)
                addBlueEdge(node.id, fn, fromPort = 0, toPort = 0)
                addBlueEdge(node.id, arg, fromPort = 1, toPort = 0)
                node.id
            }

            is Term.Lambda -> {
                val node = addNode(
                    type = TermNodeType.LAMBDA,
                    label = "",
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 1,
                    greenInputCount = 0,
                    greenOutputCount = 1,
                )

                val extendedScope = scope.toMutableMap()
                extendedScope[term.parameter] = BinderRef(nodeId = node.id, port = 0)

                val body = addTerm(term.body, extendedScope)
                addBlueEdge(node.id, body, fromPort = 0, toPort = 0)
                node.id
            }

            is Term.Constant -> {
                addNode(
                    type = TermNodeType.CONST,
                    label = term.name,
                    width = maxOf(48.0, 22.0 + term.name.length * 9.0),
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 0,
                    greenInputCount = 0,
                    greenOutputCount = 0,
                ).id
            }

            is Term.Variable -> {
                val node = addNode(
                    type = TermNodeType.VAR,
                    label = term.name,
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 0,
                    greenInputCount = 1,
                    greenOutputCount = 0,
                )

                val binder = scope[term.name]
                if (binder != null) {
                    addGreenEdge(binder.nodeId, node.id, fromPort = binder.port, toPort = 0)
                } else {
                    val rootPort = freeVariablePortByName.getOrPut(term.name) { freeVariablePortByName.size }
                    addGreenEdge(rootNodeId, node.id, fromPort = rootPort, toPort = 0)
                }
                node.id
            }
        }
    }

    private fun addNode(
        type: TermNodeType,
        label: String,
        width: Double,
        height: Double,
        blueInputCount: Int,
        blueOutputCount: Int,
        greenInputCount: Int,
        greenOutputCount: Int,
    ): MutableNode {
        val node = MutableNode(
            id = "n${nextNodeId++}",
            type = type,
            label = label,
            x = 0.0,
            y = 0.0,
            width = width,
            height = height,
            blueInputCount = blueInputCount,
            blueOutputCount = blueOutputCount,
            greenInputCount = greenInputCount,
            greenOutputCount = greenOutputCount,
        )
        nodes += node
        return node
    }

    private fun addBlueEdge(fromNodeId: String, toNodeId: String, fromPort: Int, toPort: Int) {
        blueEdges += TermEdge(
            id = "b${nextBlueEdgeId++}",
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            fromPort = fromPort,
            toPort = toPort,
        )
    }

    private fun addGreenEdge(fromNodeId: String, toNodeId: String, fromPort: Int, toPort: Int) {
        greenEdges += TermEdge(
            id = "g${nextGreenEdgeId++}",
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            fromPort = fromPort,
            toPort = toPort,
        )
    }

    private fun layout(rootId: String) {
        val children = blueEdges
            .groupBy { it.fromNodeId }
            .mapValues { (_, edges) -> edges.sortedBy { it.fromPort }.map { it.toNodeId } }

        val subtreeWidths = mutableMapOf<String, Double>()
        val nodeById = nodes.associateBy { it.id }
        val siblingGap = 36.0
        val depthGap = 120.0
        val leftMargin = 40.0
        val topMargin = 36.0

        fun measure(nodeId: String): Double {
            subtreeWidths[nodeId]?.let { return it }
            val node = nodeById.getValue(nodeId)
            val childIds = children[nodeId].orEmpty()
            val width = if (childIds.isEmpty()) {
                node.width
            } else {
                val total = childIds.sumOf { measure(it) } + siblingGap * (childIds.size - 1)
                maxOf(node.width, total)
            }
            subtreeWidths[nodeId] = width
            return width
        }

        fun place(nodeId: String, left: Double, depth: Int) {
            val node = nodeById.getValue(nodeId)
            val subtree = subtreeWidths.getValue(nodeId)
            node.x = left + (subtree - node.width) / 2.0
            node.y = topMargin + depth * depthGap

            val childIds = children[nodeId].orEmpty()
            var childLeft = left
            for (childId in childIds) {
                place(childId, childLeft, depth + 1)
                childLeft += subtreeWidths.getValue(childId) + siblingGap
            }
        }

        measure(rootId)
        place(rootId, leftMargin, 0)
    }
}

private data class BinderRef(
    val nodeId: String,
    val port: Int,
)

private data class GraphResult(
    val nodes: List<TermNode>,
    val blueEdges: List<TermEdge>,
    val greenEdges: List<TermEdge>,
    val freeVariableNames: List<String>,
)

private data class MutableNode(
    val id: String,
    val type: TermNodeType,
    val label: String,
    var x: Double,
    var y: Double,
    var width: Double,
    var height: Double,
    var blueInputCount: Int,
    var blueOutputCount: Int,
    var greenInputCount: Int,
    var greenOutputCount: Int,
) {
    fun toImmutable(): TermNode {
        return TermNode(
            id = id,
            type = type,
            label = label,
            x = x,
            y = y,
            width = width,
            height = height,
            blueInputCount = blueInputCount,
            blueOutputCount = blueOutputCount,
            greenInputCount = greenInputCount,
            greenOutputCount = greenOutputCount,
        )
    }
}
