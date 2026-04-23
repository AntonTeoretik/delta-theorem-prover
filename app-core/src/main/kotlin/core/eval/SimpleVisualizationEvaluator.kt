package core.eval

import core.model.ParsedDocument
import core.model.SymbolDisplay
import core.model.Term
import core.model.TermEdge
import core.model.TextHighlight
import core.model.TextHighlightKind
import core.model.TextSpan
import core.model.TermNode
import core.model.TermNodeType
import core.model.VisualizationData
import core.typecheck.TypeChecker

class SimpleVisualizationEvaluator : VisualizationEvaluator {
    override fun evaluate(document: ParsedDocument, selectedDefinitionName: String?, caretOffset: Int?): VisualizationData {
        val typeCheck = TypeChecker(document).checkProgram()
        val allDiagnostics = document.diagnostics + typeCheck.diagnostics
        val definitionNames = document.definitions.map { it.name }
        val textHighlights = buildTextHighlights(document, caretOffset)
        val selected = document.definitions.firstOrNull { it.name == selectedDefinitionName }
            ?: document.definitions.firstOrNull()
            ?: return VisualizationData(
                sourceText = document.sourceText,
                diagnostics = allDiagnostics,
                textHighlights = textHighlights,
                typeHints = typeCheck.typeHints,
                symbolReplacements = SymbolDisplay.symbolReplacements,
                infixDeclarations = document.infixDeclarations,
                definitionNames = definitionNames,
                selectedDefinitionName = null,
                freeVariableNames = emptyList(),
                nodes = emptyList(),
                nodeTypeHints = emptyMap(),
                blueEdges = emptyList(),
                greenEdges = emptyList(),
            )

        val selectedIndex = document.definitions.indexOf(selected).coerceAtLeast(0)
        val knownConstantsBeforeSelected = document.definitions
            .take(selectedIndex)
            .map { it.name }
            .toSet()

        val selectedTerm = selected.implementation ?: selected.type
        if (selectedTerm == null) {
            return VisualizationData(
                sourceText = document.sourceText,
                diagnostics = allDiagnostics,
                textHighlights = textHighlights,
                typeHints = typeCheck.typeHints,
                symbolReplacements = SymbolDisplay.symbolReplacements,
                infixDeclarations = document.infixDeclarations,
                definitionNames = definitionNames,
                selectedDefinitionName = selected.name,
                freeVariableNames = emptyList(),
                nodes = emptyList(),
                nodeTypeHints = emptyMap(),
                blueEdges = emptyList(),
                greenEdges = emptyList(),
            )
        }

        val builder = TermGraphBuilder(
            knownConstants = knownConstantsBeforeSelected,
            inferredTypes = typeCheck.inferredTypes,
        )
        val graph = builder.build(selectedTerm)

        return VisualizationData(
            sourceText = document.sourceText,
            diagnostics = allDiagnostics,
            textHighlights = textHighlights,
            typeHints = typeCheck.typeHints,
            symbolReplacements = SymbolDisplay.symbolReplacements,
            infixDeclarations = document.infixDeclarations,
            definitionNames = definitionNames,
            selectedDefinitionName = selected.name,
            freeVariableNames = graph.freeVariableNames,
            nodes = graph.nodes,
            nodeTypeHints = graph.nodeTypeHints,
            blueEdges = graph.blueEdges,
            greenEdges = graph.greenEdges,
        )
    }

    private fun buildTextHighlights(document: ParsedDocument, caretOffset: Int?): List<TextHighlight> {
        val collector = SymbolCollector()
        val knownConstants = linkedSetOf<String>()
        document.definitions.forEach { definition ->
            definition.type?.let { collector.collect(it, linkedMapOf(), knownConstants) }
            definition.implementation?.let { collector.collect(it, linkedMapOf(), knownConstants) }
            knownConstants += definition.name
        }

        val highlights = mutableListOf<TextHighlight>()
        collector.constantSpans.forEach { (_, spans) ->
            spans.forEach { span ->
                highlights += TextHighlight(span, TextHighlightKind.CONSTANT)
            }
        }
        document.definitions.forEach { definition ->
            definition.nameSpan?.let { span ->
                highlights += TextHighlight(span, TextHighlightKind.CONSTANT)
            }
        }
        collector.freeVariableSpans.forEach { span ->
            highlights += TextHighlight(span, TextHighlightKind.FREE_VARIABLE)
        }
        collector.typeUniverseSpans.forEach { span ->
            highlights += TextHighlight(span, TextHighlightKind.TYPE_UNIVERSE)
        }
        collector.boundVariableSpans.forEach { span ->
            highlights += TextHighlight(span, TextHighlightKind.BOUND_VARIABLE)
        }

        val activeConstant = resolveActiveConstant(document, collector.constantSpans, caretOffset)
        if (activeConstant != null) {
            highlights += TextHighlight(activeConstant.usageSpan, TextHighlightKind.ACTIVE_CONSTANT_USAGE)
            highlights += TextHighlight(activeConstant.definitionSpan, TextHighlightKind.ACTIVE_CONSTANT_DEFINITION)
        }

        val activeBound = resolveActiveBoundLink(collector, caretOffset)
        if (activeBound != null) {
            highlights += TextHighlight(activeBound.definitionSpan, TextHighlightKind.ACTIVE_BOUND_DEFINITION)
            activeBound.usageSpans.forEach { span ->
                highlights += TextHighlight(span, TextHighlightKind.ACTIVE_BOUND_USAGE)
            }
        }

        return highlights
    }

    private fun resolveActiveConstant(
        document: ParsedDocument,
        constantSpans: Map<String, List<TextSpan>>,
        caretOffset: Int?,
    ): ActiveConstantLink? {
        if (caretOffset == null) {
            return null
        }

        val hit = constantSpans
            .flatMap { (name, spans) -> spans.map { name to it } }
            .firstOrNull { (_, span) -> caretOffset >= span.startOffset && caretOffset < span.endOffset }
            ?: return null

        val activeName = hit.first
        val usageSpan = hit.second

        val definition = document.definitions
            .lastOrNull { def ->
                val definitionSpan = def.nameSpan
                definitionSpan != null && def.name == activeName && definitionSpan.startOffset < usageSpan.startOffset
            }
            ?: return null

        return ActiveConstantLink(
            usageSpan = usageSpan,
            definitionSpan = definition.nameSpan ?: return null,
        )
    }

    private fun resolveActiveBoundLink(
        collector: SymbolCollector,
        caretOffset: Int?,
    ): ActiveBoundLink? {
        if (caretOffset == null) {
            return null
        }

        val declarationHit = collector.bindersById.values
            .firstOrNull { caretOffset in it.declarationSpan.startOffset until it.declarationSpan.endOffset }
        if (declarationHit != null) {
            return ActiveBoundLink(
                definitionSpan = declarationHit.declarationSpan,
                usageSpans = declarationHit.useSpans.toList(),
            )
        }

        val usageHit = collector.bindersById.values
            .asSequence()
            .flatMap { binder -> binder.useSpans.asSequence().map { span -> binder to span } }
            .firstOrNull { (_, span) -> caretOffset in span.startOffset until span.endOffset }
            ?: return null

        return ActiveBoundLink(
            definitionSpan = usageHit.first.declarationSpan,
            usageSpans = listOf(usageHit.second),
        )
    }
}

private class SymbolCollector {
    val constantSpans: MutableMap<String, MutableList<TextSpan>> = linkedMapOf()
    val freeVariableSpans: MutableList<TextSpan> = mutableListOf()
    val typeUniverseSpans: MutableList<TextSpan> = mutableListOf()
    val boundVariableSpans: MutableList<TextSpan> = mutableListOf()
    val bindersById: MutableMap<Int, BinderInfo> = linkedMapOf()

    private var nextBinderId: Int = 0

    fun collect(
        term: Term,
        boundStacks: MutableMap<String, MutableList<Int>>,
        knownConstants: Set<String>,
    ) {
        when (term) {
            is Term.Application -> {
                collect(term.function, boundStacks, knownConstants)
                collect(term.argument, boundStacks, knownConstants)
            }

            is Term.Lambda -> {
                val binderId = nextBinderId++
                val binderInfo = BinderInfo(
                    id = binderId,
                    declarationSpan = term.parameterSpan,
                    useSpans = mutableListOf(),
                )
                bindersById[binderId] = binderInfo
                boundVariableSpans += term.parameterSpan

                val stack = boundStacks.getOrPut(term.parameter) { mutableListOf() }
                stack.add(binderId)

                collect(term.parameterType, boundStacks, knownConstants)

                collect(term.body, boundStacks, knownConstants)

                if (stack.isNotEmpty()) {
                    stack.removeAt(stack.lastIndex)
                }
                if (stack.isEmpty()) {
                    boundStacks.remove(term.parameter)
                }
            }

            is Term.Pi -> {
                val binderId = nextBinderId++
                val binderInfo = BinderInfo(
                    id = binderId,
                    declarationSpan = term.parameterSpan,
                    useSpans = mutableListOf(),
                )
                bindersById[binderId] = binderInfo
                boundVariableSpans += term.parameterSpan

                collect(term.parameterType, boundStacks, knownConstants)

                val stack = boundStacks.getOrPut(term.parameter) { mutableListOf() }
                stack.add(binderId)

                collect(term.body, boundStacks, knownConstants)

                if (stack.isNotEmpty()) {
                    stack.removeAt(stack.lastIndex)
                }
                if (stack.isEmpty()) {
                    boundStacks.remove(term.parameter)
                }
            }

            is Term.Meta -> Unit

            is Term.Constant -> {
                constantSpans.getOrPut(term.name) { mutableListOf() }.add(term.span)
            }

            is Term.Variable -> {
                val stack = boundStacks[term.name]
                if (!stack.isNullOrEmpty()) {
                    boundVariableSpans += term.span
                    val binderId = stack.lastOrNull()
                    if (binderId != null) {
                        bindersById[binderId]?.useSpans?.add(term.span)
                    }
                } else if (term.name == "Type") {
                    typeUniverseSpans += term.span
                } else if (term.name in knownConstants) {
                    constantSpans.getOrPut(term.name) { mutableListOf() }.add(term.span)
                } else {
                    freeVariableSpans += term.span
                }
            }

            is Term.Typed -> {
                collect(term.term, boundStacks, knownConstants)
                collect(term.type, boundStacks, knownConstants)
            }
        }
    }
}

private data class ActiveConstantLink(
    val usageSpan: TextSpan,
    val definitionSpan: TextSpan,
)

private data class ActiveBoundLink(
    val definitionSpan: TextSpan,
    val usageSpans: List<TextSpan>,
)

private data class BinderInfo(
    val id: Int,
    val declarationSpan: TextSpan,
    val useSpans: MutableList<TextSpan>,
)

private class TermGraphBuilder(
    private val knownConstants: Set<String>,
    private val inferredTypes: Map<Term, Term>,
) {
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
            nodeTypeHints = nodes
                .mapNotNull { node ->
                    val sourceTerm = node.sourceTerm ?: return@mapNotNull null
                    val inferredType = inferredTypes[sourceTerm] ?: return@mapNotNull null
                    node.id to TypeChecker.prettyTerm(inferredType)
                }
                .toMap(),
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
                    sourceTerm = term,
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
                    label = term.parameter,
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 2,
                    greenInputCount = 0,
                    greenOutputCount = 1,
                    sourceTerm = term,
                )

                val parameterType = addTerm(term.parameterType, scope)
                addBlueEdge(node.id, parameterType, fromPort = 0, toPort = 0)

                val binderRef = BinderRef(nodeId = node.id, port = 0)

                val extendedScope = scope.toMutableMap()
                extendedScope[term.parameter] = binderRef

                val body = addTerm(term.body, extendedScope)
                addBlueEdge(node.id, body, fromPort = 1, toPort = 0)

                if (binderRef.usageCount == 0) {
                    node.greenOutputCount = 0
                    node.width = 48.0
                } else {
                    node.width = maxOf(48.0, 22.0 + term.parameter.length * 9.0)
                }
                node.id
            }

            is Term.Pi -> {
                val node = addNode(
                    type = TermNodeType.PI,
                    label = term.parameter,
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 2,
                    greenInputCount = 0,
                    greenOutputCount = 1,
                    sourceTerm = term,
                )

                val parameterType = addTerm(term.parameterType, scope)
                addBlueEdge(node.id, parameterType, fromPort = 0, toPort = 0)

                val binderRef = BinderRef(nodeId = node.id, port = 0)
                val extendedScope = scope.toMutableMap()
                extendedScope[term.parameter] = binderRef

                val body = addTerm(term.body, extendedScope)
                addBlueEdge(node.id, body, fromPort = 1, toPort = 0)

                if (binderRef.usageCount == 0) {
                    node.greenOutputCount = 0
                    node.width = 48.0
                } else {
                    node.width = maxOf(48.0, 22.0 + term.parameter.length * 9.0)
                }
                node.id
            }

            is Term.Typed -> {
                val node = addNode(
                    type = TermNodeType.TYPE,
                    label = ":",
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 2,
                    greenInputCount = 0,
                    greenOutputCount = 0,
                    sourceTerm = term,
                )
                val expression = addTerm(term.term, scope)
                val type = addTerm(term.type, scope)
                addBlueEdge(node.id, expression, fromPort = 0, toPort = 0)
                addBlueEdge(node.id, type, fromPort = 1, toPort = 0)
                node.id
            }

            is Term.Constant -> {
                val label = SymbolDisplay.displayName(term.name)
                addNode(
                    type = TermNodeType.CONST,
                    label = label,
                    width = maxOf(48.0, 22.0 + label.length * 9.0),
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 0,
                    greenInputCount = 0,
                    greenOutputCount = 0,
                    sourceTerm = term,
                ).id
            }

            is Term.Variable -> {
                if (term.name in knownConstants) {
                    val label = SymbolDisplay.displayName(term.name)
                    return addNode(
                        type = TermNodeType.CONST,
                        label = label,
                        width = maxOf(48.0, 22.0 + label.length * 9.0),
                        height = 48.0,
                        blueInputCount = 1,
                        blueOutputCount = 0,
                        greenInputCount = 0,
                        greenOutputCount = 0,
                        sourceTerm = term,
                    ).id
                }

                val node = addNode(
                    type = TermNodeType.VAR,
                    label = term.name,
                    width = 48.0,
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 0,
                    greenInputCount = 1,
                    greenOutputCount = 0,
                    sourceTerm = term,
                )

                val binder = scope[term.name]
                if (binder != null) {
                    binder.usageCount += 1
                    addGreenEdge(binder.nodeId, node.id, fromPort = binder.port, toPort = 0)
                } else {
                    val rootPort = freeVariablePortByName.getOrPut(term.name) { freeVariablePortByName.size }
                    addGreenEdge(rootNodeId, node.id, fromPort = rootPort, toPort = 0)
                }
                node.id
            }

            is Term.Meta -> {
                val label = "?m${term.id}"
                addNode(
                    type = TermNodeType.META,
                    label = label,
                    width = maxOf(48.0, 22.0 + label.length * 9.0),
                    height = 48.0,
                    blueInputCount = 1,
                    blueOutputCount = 0,
                    greenInputCount = 0,
                    greenOutputCount = 0,
                    sourceTerm = term,
                ).id
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
        sourceTerm: Term? = null,
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
            sourceTerm = sourceTerm,
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
        val depthGap = 92.0
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
    var usageCount: Int = 0,
)

private data class GraphResult(
    val nodes: List<TermNode>,
    val nodeTypeHints: Map<String, String>,
    val blueEdges: List<TermEdge>,
    val greenEdges: List<TermEdge>,
    val freeVariableNames: List<String>,
)

private data class MutableNode(
    val id: String,
    val type: TermNodeType,
    var label: String,
    var x: Double,
    var y: Double,
    var width: Double,
    var height: Double,
    var blueInputCount: Int,
    var blueOutputCount: Int,
    var greenInputCount: Int,
    var greenOutputCount: Int,
    val sourceTerm: Term?,
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
