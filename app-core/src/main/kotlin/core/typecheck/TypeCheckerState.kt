package core.typecheck

import core.model.Definition
import core.model.Diagnostic
import core.model.RewriteRule
import core.model.Term
import core.model.TextSpan
import core.model.TypeHint
import java.util.IdentityHashMap

internal class TypeCheckEnvironment(typeUniverse: Term) {
    val globals = linkedMapOf<String, GlobalEntry>()
    val rewriteRulesByHead = linkedMapOf<String, MutableList<RegisteredRewriteRule>>()

    init {
        globals["Type"] = GlobalEntry(type = typeUniverse, implementation = null)
    }
}

internal class TypeCheckArtifacts(
    val source: String,
) {
    val diagnostics = mutableListOf<Diagnostic>()
    val inferredTypes = IdentityHashMap<Term, Term>()
    val typeHints = mutableListOf<TypeHint>()
    val typeHintKeys = linkedSetOf<String>()

    var nextTypeHintId: Int = 0
    var traceSink: MutableList<String>? = null
    var suppressedDiagnosticsDepth: Int = 0
}

internal class TypeCheckMetaState(
    var nextMetaId: Int,
) {
    val entries = linkedMapOf<Int, MetaEntry>()
}

internal data class GlobalEntry(
    val type: Term,
    val implementation: Term?,
)

internal data class MetaEntry(
    val id: Int,
    var expectedType: Term?,
    var solution: Term?,
    val allowedLocals: MutableSet<String>,
    val span: TextSpan,
    val requiresResolution: Boolean = false,
)

internal data class RegisteredRewriteRule(
    val name: String,
    val headConstant: String,
    val lhs: Term,
    val rhs: Term,
    val patternVariables: Set<String>,
)

internal data class RewriteOutcome(
    val ruleName: String,
    val term: Term,
)

internal data class ReductionOutcome(
    val term: Term,
    val reason: String,
)

internal data class AppliedArg(
    val term: Term,
    val visibility: Term.Visibility,
)

internal sealed interface TopLevelEntry {
    val startOffset: Int

    data class DefinitionEntry(
        override val startOffset: Int,
        val definition: Definition,
    ) : TopLevelEntry

    data class RuleEntry(
        override val startOffset: Int,
        val rule: RewriteRule,
    ) : TopLevelEntry
}

internal fun maxMetaIdInDocument(document: core.model.ParsedDocument): Int {
    fun maxMeta(term: Term): Int {
        return when (term) {
            is Term.Meta -> term.id
            is Term.Variable,
            is Term.Constant,
            -> -1

            is Term.Typed -> maxOf(maxMeta(term.term), maxMeta(term.type))
            is Term.Application -> maxOf(maxMeta(term.function), maxMeta(term.argument))
            is Term.Lambda -> maxOf(maxMeta(term.parameterType), maxMeta(term.body))
            is Term.Pi -> maxOf(maxMeta(term.parameterType), maxMeta(term.body))
            is Term.Case -> {
                val branchMax = term.branches.maxOfOrNull { maxMeta(it.body) } ?: -1
                maxOf(maxMeta(term.scrutinee), branchMax)
            }
        }
    }

    val values = mutableListOf<Int>()
    document.definitions.forEach { definition ->
        definition.type?.let { values += maxMeta(it) }
        definition.implementation?.let { values += maxMeta(it) }
    }
    document.rewriteRules.forEach { rule ->
        values += maxMeta(rule.lhs)
        values += maxMeta(rule.rhs)
    }
    return values.maxOrNull() ?: -1
}
