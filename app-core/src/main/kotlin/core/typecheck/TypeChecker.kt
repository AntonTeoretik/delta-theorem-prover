package core.typecheck

import core.model.Definition
import core.model.Diagnostic
import core.model.ParsedDocument
import core.model.Term
import core.model.TextSpan
import core.model.TypeHint
import java.util.IdentityHashMap

data class TypeCheckResult(
    val diagnostics: List<Diagnostic>,
    val inferredTypes: Map<Term, Term>,
    val typeHints: List<TypeHint>,
)

class TypeChecker(private val document: ParsedDocument) {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val globals = linkedMapOf<String, GlobalEntry>()
    private val source = document.sourceText
    private val inferredTypes = IdentityHashMap<Term, Term>()
    private val typeHints = mutableListOf<TypeHint>()
    private val typeHintKeys = linkedSetOf<String>()
    private var nextTypeHintId: Int = 0

    init {
        val type = typeUniverseTerm()
        globals["Type"] = GlobalEntry(type = type, implementation = null)
    }

    fun checkProgram(): TypeCheckResult {
        document.definitions.forEach { definition ->
            checkDefinition(definition)
        }
        return TypeCheckResult(
            diagnostics = diagnostics.toList(),
            inferredTypes = IdentityHashMap(inferredTypes),
            typeHints = typeHints.toList(),
        )
    }

    private fun checkDefinition(definition: Definition) {
        val declaredType = definition.type
        val implementation = definition.implementation

        if (declaredType == null && implementation == null) {
            report(definition.nameSpan, "Definition '${definition.name}' must have type or implementation")
            return
        }

        if (declaredType != null) {
            inferType(declaredType, emptyMap())
        }

        val implementationType = implementation?.let { inferType(it, emptyMap()) }

        if (declaredType != null && implementationType != null) {
            if (!convertible(implementationType, declaredType)) {
                report(
                    definition.nameSpan,
                    "Definition '${definition.name}' has type mismatch. Declared: ${pretty(declaredType)}, inferred: ${pretty(implementationType)}",
                )
            }
        }

        val finalType = declaredType ?: implementationType
        if (finalType != null) {
            globals[definition.name] = GlobalEntry(type = finalType, implementation = implementation)
        }
    }

    private fun inferType(term: Term, locals: Map<String, Term>): Term? {
        val inferred = when (term) {
            is Term.Meta -> {
                report(term.span, "Meta variables are not supported in type checking phase (?m${term.id})")
                null
            }

            is Term.Variable -> {
                val resolvedType = locals[term.name]
                    ?: globals[term.name]?.type
                    ?: run {
                        report(term.span, "Unknown variable or constant '${term.name}'")
                        null
                    }
                if (resolvedType != null) {
                    addTypeHint(term.span, resolvedType)
                }
                resolvedType
            }

            is Term.Constant -> {
                globals[term.name]?.type
                    ?: run {
                        report(term.span, "Unknown constant '${term.name}'")
                        null
                    }
            }

            is Term.Typed -> {
                inferType(term.type, locals)
                val innerType = inferType(term.term, locals) ?: return null
                if (!convertible(innerType, term.type)) {
                    report(
                        null,
                        "Annotation mismatch: expected ${pretty(term.type)}, inferred ${pretty(innerType)}",
                    )
                }
                term.type
            }

            is Term.Pi -> {
                inferType(term.parameterType, locals)
                addTypeHint(term.parameterSpan, term.parameterType)
                val extended = extendLocalsWithBinder(locals, term.parameter, term.parameterType, term.parameterSpan)
                inferType(term.body, extended)
                typeUniverseTerm()
            }

            is Term.Lambda -> {
                inferType(term.parameterType, locals)
                addTypeHint(term.parameterSpan, term.parameterType)
                val extended = extendLocalsWithBinder(locals, term.parameter, term.parameterType, term.parameterSpan)
                val bodyType = inferType(term.body, extended) ?: return null
                Term.Pi(
                    parameter = term.parameter,
                    parameterType = term.parameterType,
                    body = bodyType,
                    parameterSpan = term.parameterSpan,
                )
            }

            is Term.Application -> {
                val functionType = inferType(term.function, locals)?.let { normalize(it) } ?: return null
                if (functionType !is Term.Pi) {
                    report(null, "Cannot apply non-function term: ${pretty(term.function)}")
                    return null
                }

                val argumentType = inferType(term.argument, locals) ?: return null
                if (!convertible(argumentType, functionType.parameterType)) {
                    report(
                        null,
                        "Application argument type mismatch. Expected ${pretty(functionType.parameterType)}, got ${pretty(argumentType)}",
                    )
                    return null
                }

                substitute(functionType.body, functionType.parameter, term.argument)
            }
        }
        if (inferred != null) {
            inferredTypes[term] = inferred
        }
        return inferred
    }

    private fun extendLocalsWithBinder(
        locals: Map<String, Term>,
        name: String,
        binderType: Term,
        span: TextSpan,
    ): Map<String, Term> {
        if (isReservedLocalName(name)) {
            report(span, "'Type' is reserved")
            return locals
        }
        return locals + (name to binderType)
    }

    private fun addTypeHint(span: TextSpan, inferredType: Term) {
        val typeText = pretty(inferredType)
        val key = "${span.startOffset}:${span.endOffset}:$typeText"
        if (!typeHintKeys.add(key)) {
            return
        }
        typeHints += TypeHint(
            id = "th${nextTypeHintId++}",
            span = span,
            type = typeText,
        )
    }

    private fun isReservedLocalName(name: String): Boolean = name == "Type"

    private fun convertible(a: Term, b: Term): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return alphaEquivalent(na, nb)
    }

    private fun normalize(term: Term, unfolding: MutableSet<String> = linkedSetOf()): Term {
        return when (term) {
            is Term.Meta -> term
            is Term.Constant -> {
                val impl = globals[term.name]?.implementation
                if (impl == null || !unfolding.add(term.name)) {
                    term
                } else {
                    val result = normalize(impl, unfolding)
                    unfolding.remove(term.name)
                    result
                }
            }

            is Term.Variable -> {
                val impl = globals[term.name]?.implementation
                if (impl == null || !unfolding.add(term.name)) {
                    term
                } else {
                    val result = normalize(impl, unfolding)
                    unfolding.remove(term.name)
                    result
                }
            }

            is Term.Typed -> normalize(term.term, unfolding)
            is Term.Pi -> Term.Pi(
                parameter = term.parameter,
                parameterType = normalize(term.parameterType, unfolding),
                body = normalize(term.body, unfolding),
                parameterSpan = term.parameterSpan,
            )

            is Term.Lambda -> Term.Lambda(
                parameter = term.parameter,
                parameterType = normalize(term.parameterType, unfolding),
                body = normalize(term.body, unfolding),
                parameterSpan = term.parameterSpan,
            )

            is Term.Application -> {
                val fn = normalize(term.function, unfolding)
                val arg = normalize(term.argument, unfolding)
                if (fn is Term.Lambda) {
                    normalize(substitute(fn.body, fn.parameter, arg), unfolding)
                } else {
                    Term.Application(fn, arg)
                }
            }
        }
    }

    private fun substitute(term: Term, name: String, replacement: Term): Term {
        return when (term) {
            is Term.Meta -> term
            is Term.Constant -> term
            is Term.Variable -> if (term.name == name) replacement else term
            is Term.Typed -> Term.Typed(
                term = substitute(term.term, name, replacement),
                type = substitute(term.type, name, replacement),
            )

            is Term.Application -> Term.Application(
                function = substitute(term.function, name, replacement),
                argument = substitute(term.argument, name, replacement),
            )

            is Term.Lambda -> {
                val newParameterType = substitute(term.parameterType, name, replacement)
                if (term.parameter == name) {
                    term.copy(parameterType = newParameterType)
                } else {
                    val replacementVars = freeVariables(replacement)
                    if (term.parameter in replacementVars) {
                        val fresh = freshName(term.parameter, term.body, replacement)
                        val renamedBody = substitute(term.body, term.parameter, Term.Variable(fresh, term.parameterSpan))
                        Term.Lambda(
                            parameter = fresh,
                            parameterType = newParameterType,
                            body = substitute(renamedBody, name, replacement),
                            parameterSpan = term.parameterSpan,
                        )
                    } else {
                        term.copy(
                            parameterType = newParameterType,
                            body = substitute(term.body, name, replacement),
                        )
                    }
                }
            }

            is Term.Pi -> {
                val newParameterType = substitute(term.parameterType, name, replacement)
                if (term.parameter == name) {
                    term.copy(parameterType = newParameterType)
                } else {
                    val replacementVars = freeVariables(replacement)
                    if (term.parameter in replacementVars) {
                        val fresh = freshName(term.parameter, term.body, replacement)
                        val renamedBody = substitute(term.body, term.parameter, Term.Variable(fresh, term.parameterSpan))
                        Term.Pi(
                            parameter = fresh,
                            parameterType = newParameterType,
                            body = substitute(renamedBody, name, replacement),
                            parameterSpan = term.parameterSpan,
                        )
                    } else {
                        term.copy(
                            parameterType = newParameterType,
                            body = substitute(term.body, name, replacement),
                        )
                    }
                }
            }
        }
    }

    private fun freeVariables(term: Term, bound: Set<String> = emptySet()): Set<String> {
        return when (term) {
            is Term.Meta,
            is Term.Constant,
            -> emptySet()

            is Term.Variable -> if (term.name in bound) emptySet() else setOf(term.name)
            is Term.Typed -> freeVariables(term.term, bound) + freeVariables(term.type, bound)
            is Term.Application -> freeVariables(term.function, bound) + freeVariables(term.argument, bound)
            is Term.Lambda -> freeVariables(term.parameterType, bound) + freeVariables(term.body, bound + term.parameter)
            is Term.Pi -> freeVariables(term.parameterType, bound) + freeVariables(term.body, bound + term.parameter)
        }
    }

    private fun freshName(base: String, vararg terms: Term): String {
        val occupied = terms.flatMap { freeVariables(it) }.toSet() + globals.keys
        var candidate = "${base}_0"
        var i = 1
        while (candidate in occupied) {
            candidate = "${base}_${i++}"
        }
        return candidate
    }

    private fun alphaEquivalent(a: Term, b: Term, env: Map<String, String> = emptyMap()): Boolean {
        return when {
            a is Term.Meta && b is Term.Meta -> a.id == b.id
            a is Term.Constant && b is Term.Constant -> a.name == b.name
            a is Term.Variable && b is Term.Variable -> env[a.name]?.let { it == b.name } ?: (a.name == b.name)
            a is Term.Typed && b is Term.Typed ->
                alphaEquivalent(a.term, b.term, env) && alphaEquivalent(a.type, b.type, env)

            a is Term.Application && b is Term.Application ->
                alphaEquivalent(a.function, b.function, env) && alphaEquivalent(a.argument, b.argument, env)

            a is Term.Lambda && b is Term.Lambda -> {
                alphaEquivalent(a.parameterType, b.parameterType, env) &&
                    alphaEquivalent(a.body, b.body, env + (a.parameter to b.parameter))
            }

            a is Term.Pi && b is Term.Pi -> {
                alphaEquivalent(a.parameterType, b.parameterType, env) &&
                    alphaEquivalent(a.body, b.body, env + (a.parameter to b.parameter))
            }

            else -> false
        }
    }

    private fun typeUniverseTerm(): Term {
        return Term.Variable("Type", TextSpan(0, 0))
    }

    private fun pretty(term: Term): String = prettyTerm(term)

    companion object {
        fun prettyTerm(term: Term): String {
            return when (term) {
                is Term.Meta -> "?m${term.id}"
                is Term.Variable -> term.name
                is Term.Constant -> term.name
                is Term.Typed -> "(${prettyTerm(term.term)} : ${prettyTerm(term.type)})"
                is Term.Application -> "(${prettyTerm(term.function)} ${prettyTerm(term.argument)})"
                is Term.Lambda -> "(λ${term.parameter}. ${prettyTerm(term.body)})"
                is Term.Pi -> "(Π(${term.parameter} : ${prettyTerm(term.parameterType)}). ${prettyTerm(term.body)})"
            }
        }
    }

    private fun report(span: TextSpan?, message: String) {
        val (line, column) = offsetToLineColumn(span?.startOffset ?: 0)
        diagnostics += Diagnostic(message = message, line = line, column = column)
    }

    private fun offsetToLineColumn(offset: Int): Pair<Int, Int> {
        val safe = offset.coerceAtLeast(0).coerceAtMost(source.length)
        var line = 1
        var column = 1
        var i = 0
        while (i < safe) {
            val ch = source[i]
            if (ch == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
            i += 1
        }
        return line to column
    }

    private data class GlobalEntry(
        val type: Term,
        val implementation: Term?,
    )
}
