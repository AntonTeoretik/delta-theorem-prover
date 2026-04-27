package core.typecheck

import core.model.Diagnostic
import core.model.EvaluationTrace
import core.model.ParsedDocument
import core.model.Term
import core.model.TextSpan
import core.model.TypeCheckTrace
import core.model.TypeHint
import java.util.IdentityHashMap

data class TypeCheckResult(
    val diagnostics: List<Diagnostic>,
    val inferredTypes: Map<Term, Term>,
    val typeHints: List<TypeHint>,
    val activeTrace: TypeCheckTrace?,
    val activeEvaluationTrace: EvaluationTrace?,
)

class TypeChecker(
    internal val document: ParsedDocument,
    internal val caretOffset: Int? = null,
) {
    internal val source: String = document.sourceText

    private val environment = TypeCheckEnvironment(typeUniverseTerm())
    private val artifacts = TypeCheckArtifacts(source)
    private val metas = TypeCheckMetaState(nextMetaId = maxMetaIdInDocument(document) + 1)

    internal var nextRewritePlaceholderMetaId: Int = -1

    internal val globals: LinkedHashMap<String, GlobalEntry>
        get() = environment.globals

    internal val rewriteRulesByHead: LinkedHashMap<String, MutableList<RegisteredRewriteRule>>
        get() = environment.rewriteRulesByHead

    internal val diagnostics: MutableList<Diagnostic>
        get() = artifacts.diagnostics

    internal val inferredTypes: IdentityHashMap<Term, Term>
        get() = artifacts.inferredTypes

    internal val typeHints: MutableList<TypeHint>
        get() = artifacts.typeHints

    internal val typeHintKeys: LinkedHashSet<String>
        get() = artifacts.typeHintKeys

    internal var nextTypeHintId: Int
        get() = artifacts.nextTypeHintId
        set(value) {
            artifacts.nextTypeHintId = value
        }

    internal var traceSink: MutableList<String>?
        get() = artifacts.traceSink
        set(value) {
            artifacts.traceSink = value
        }

    internal var suppressedDiagnosticsDepth: Int
        get() = artifacts.suppressedDiagnosticsDepth
        set(value) {
            artifacts.suppressedDiagnosticsDepth = value
        }

    internal val metaContext: LinkedHashMap<Int, MetaEntry>
        get() = metas.entries

    internal var nextMetaId: Int
        get() = metas.nextMetaId
        set(value) {
            metas.nextMetaId = value
        }

    fun checkProgram(): TypeCheckResult = checkProgramInternal()

    internal fun typeUniverseTerm(): Term {
        return Term.Variable("Type", TextSpan(0, 0))
    }

    internal fun pretty(term: Term): String = prettyTerm(term)

    companion object {
        const val TRACE_STEP_LIMIT: Int = 400
        const val EVALUATION_STEP_LIMIT: Int = 80

        fun prettyTerm(term: Term): String {
            return when (term) {
                is Term.Meta -> "?m${term.id}"
                is Term.Variable -> term.name
                is Term.Constant -> term.name
                is Term.Typed -> "(${prettyTerm(term.term)} : ${prettyTerm(term.type)})"
                is Term.Application -> {
                    if (term.visibility == Term.Visibility.IMPLICIT) {
                        "(${prettyTerm(term.function)} {${prettyTerm(term.argument)}})"
                    } else {
                        "(${prettyTerm(term.function)} ${prettyTerm(term.argument)})"
                    }
                }

                is Term.Lambda -> {
                    if (term.visibility == Term.Visibility.IMPLICIT) {
                        "(λ{${term.parameter} : ${prettyTerm(term.parameterType)}} => ${prettyTerm(term.body)})"
                    } else {
                        "(λ(${term.parameter} : ${prettyTerm(term.parameterType)}) => ${prettyTerm(term.body)})"
                    }
                }

                is Term.Pi -> {
                    if (term.visibility == Term.Visibility.IMPLICIT) {
                        "({${term.parameter} : ${prettyTerm(term.parameterType)}} -> ${prettyTerm(term.body)})"
                    } else {
                        "((${term.parameter} : ${prettyTerm(term.parameterType)}) -> ${prettyTerm(term.body)})"
                    }
                }

                is Term.Case -> {
                    val branches = term.branches.joinToString("; ") { branch ->
                        val pattern = if (branch.parameters.isEmpty()) {
                            branch.constructorName
                        } else {
                            "${branch.constructorName}(${branch.parameters.joinToString(",") { it.name }})"
                        }
                        "$pattern => ${prettyTerm(branch.body)}"
                    }
                    "(case ${prettyTerm(term.scrutinee)} of { $branches })"
                }
            }
        }
    }
}
