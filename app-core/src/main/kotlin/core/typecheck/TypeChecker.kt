package core.typecheck

import core.model.Definition
import core.model.Diagnostic
import core.model.ParsedDocument
import core.model.RewriteRule
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
    private val rewriteRulesByHead = linkedMapOf<String, MutableList<RegisteredRewriteRule>>()
    private var nextTypeHintId: Int = 0

    init {
        val type = typeUniverseTerm()
        globals["Type"] = GlobalEntry(type = type, implementation = null)
    }

    fun checkProgram(): TypeCheckResult {
        topLevelEntries().forEach { entry ->
            when (entry) {
                is TopLevelEntry.DefinitionEntry -> checkDefinition(entry.definition)
                is TopLevelEntry.RuleEntry -> checkRewriteRule(entry.rule)
            }
        }
        return TypeCheckResult(
            diagnostics = diagnostics.toList(),
            inferredTypes = IdentityHashMap(inferredTypes),
            typeHints = typeHints.toList(),
        )
    }

    private fun topLevelEntries(): List<TopLevelEntry> {
        val entries = mutableListOf<TopLevelEntry>()
        var nextFallbackOffset = Int.MAX_VALUE / 4

        document.definitions.forEach { definition ->
            val offset = definition.nameSpan?.startOffset ?: nextFallbackOffset++
            entries += TopLevelEntry.DefinitionEntry(offset, definition)
        }

        document.rewriteRules.forEach { rule ->
            entries += TopLevelEntry.RuleEntry(rule.nameSpan.startOffset, rule)
        }

        return entries.sortedBy { it.startOffset }
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

    private fun checkRewriteRule(rule: RewriteRule) {
        val (head, args) = decomposeApplication(rule.lhs)
        if (head !is Term.Variable || args.isEmpty()) {
            report(rule.nameSpan, "Rule '${rule.name}': LHS must be an application of a constant")
            return
        }

        val headEntry = globals[head.name]
        if (headEntry == null) {
            report(rule.nameSpan, "Rule '${rule.name}': Unknown constant '${head.name}' on LHS")
            return
        }

        if (headEntry.implementation != null) {
            report(rule.nameSpan, "Rule '${rule.name}': '${head.name}' is not axiomatic (it has ':=')")
            return
        }

        val patternLocals = linkedMapOf<String, Term>()
        var currentType: Term = normalize(headEntry.type)
        var valid = true

        args.forEach { argument ->
            val functionType = normalize(currentType)
            if (functionType !is Term.Pi) {
                report(rule.nameSpan, "Rule '${rule.name}': too many arguments on LHS for '${head.name}'")
                valid = false
                return@forEach
            }

            if (!checkRulePatternAgainstExpectedType(argument, functionType.parameterType, patternLocals)) {
                valid = false
                return@forEach
            }

            currentType = normalize(substitute(functionType.body, functionType.parameter, argument))
        }

        if (!valid) {
            return
        }

        val lhsType = inferType(rule.lhs, patternLocals) ?: currentType
        val rhsType = inferType(rule.rhs, patternLocals)
        if (rhsType == null) {
            return
        }

        if (!convertible(lhsType, rhsType)) {
            report(
                rule.nameSpan,
                "Rule '${rule.name}' type mismatch: lhs has ${pretty(lhsType)}, rhs has ${pretty(rhsType)}",
            )
            return
        }

        val lhsVariables = collectRuleVariables(rule.lhs)
        val rhsVariables = collectRuleVariables(rule.rhs)
        val missing = rhsVariables - lhsVariables
        if (missing.isNotEmpty()) {
            report(
                rule.nameSpan,
                "Rule '${rule.name}' is not closed: RHS variables not in LHS: ${missing.sorted().joinToString(", ")}",
            )
            return
        }

        rewriteRulesByHead
            .getOrPut(head.name) { mutableListOf() }
            .add(
                RegisteredRewriteRule(
                    name = rule.name,
                    headConstant = head.name,
                    lhs = rule.lhs,
                    rhs = rule.rhs,
                    patternVariables = lhsVariables,
                ),
            )
    }

    private fun checkRulePatternAgainstExpectedType(
        term: Term,
        expectedType: Term,
        locals: MutableMap<String, Term>,
    ): Boolean {
        return when (term) {
            is Term.Variable -> {
                if (term.name == "Type") {
                    val inferred = inferType(term, locals) ?: return false
                    if (!convertible(inferred, expectedType)) {
                        report(term.span, "Rule pattern expected ${pretty(expectedType)}, got ${pretty(inferred)}")
                        false
                    } else {
                        true
                    }
                } else if (term.name in globals) {
                    val inferred = inferType(term, locals) ?: return false
                    if (!convertible(inferred, expectedType)) {
                        report(term.span, "Rule pattern expected ${pretty(expectedType)}, got ${pretty(inferred)}")
                        false
                    } else {
                        true
                    }
                } else {
                    val existing = locals[term.name]
                    if (existing != null && !convertible(existing, expectedType)) {
                        report(term.span, "Rule variable '${term.name}' has inconsistent type")
                        false
                    } else {
                        val normalizedExpected = normalize(expectedType)
                        locals[term.name] = existing ?: normalizedExpected
                        addTypeHint(term.span, normalizedExpected)
                        true
                    }
                }
            }

            is Term.Application -> {
                val functionType = inferType(term.function, locals)?.let { normalize(it) }
                if (functionType !is Term.Pi) {
                    report(spanOf(term), "Cannot apply non-function term in rule pattern: ${pretty(term.function)}")
                    return false
                }

                if (!checkRulePatternAgainstExpectedType(term.argument, functionType.parameterType, locals)) {
                    return false
                }

                val resulting = normalize(substitute(functionType.body, functionType.parameter, term.argument))
                if (!convertible(resulting, expectedType)) {
                    report(spanOf(term), "Rule pattern expected ${pretty(expectedType)}, got ${pretty(resulting)}")
                    return false
                }

                true
            }

            else -> {
                val inferred = inferType(term, locals) ?: return false
                if (!convertible(inferred, expectedType)) {
                    report(spanOf(term), "Rule pattern expected ${pretty(expectedType)}, got ${pretty(inferred)}")
                    false
                } else {
                    true
                }
            }
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
                        spanOf(term.term),
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
                        spanOf(term.argument),
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
        return defEq(a, b)
    }

    private fun defEq(a: Term, b: Term): Boolean {
        val wa = whnf(a)
        val wb = whnf(b)

        if (structuralDefEq(wa, wb)) {
            return true
        }

        return alphaEquivalent(normalize(a), normalize(b))
    }

    private fun structuralDefEq(a: Term, b: Term): Boolean {
        return when {
            a is Term.Meta && b is Term.Meta -> a.id == b.id
            a is Term.Variable && b is Term.Variable -> a.name == b.name
            a is Term.Constant && b is Term.Constant -> a.name == b.name
            a is Term.Variable && b is Term.Constant -> a.name == b.name
            a is Term.Constant && b is Term.Variable -> a.name == b.name

            a is Term.Typed && b is Term.Typed -> {
                defEq(a.term, b.term) && defEq(a.type, b.type)
            }

            a is Term.Application && b is Term.Application -> {
                defEq(a.function, b.function) && defEq(a.argument, b.argument)
            }

            a is Term.Lambda && b is Term.Lambda -> {
                if (!defEq(a.parameterType, b.parameterType)) {
                    false
                } else {
                    val fresh = freshName(a.parameter, a.body, b.body)
                    val freshVar = Term.Variable(fresh, a.parameterSpan)
                    val leftBody = substitute(a.body, a.parameter, freshVar)
                    val rightBody = substitute(b.body, b.parameter, freshVar)
                    defEq(leftBody, rightBody)
                }
            }

            a is Term.Pi && b is Term.Pi -> {
                if (!defEq(a.parameterType, b.parameterType)) {
                    false
                } else {
                    val fresh = freshName(a.parameter, a.body, b.body)
                    val freshVar = Term.Variable(fresh, a.parameterSpan)
                    val leftBody = substitute(a.body, a.parameter, freshVar)
                    val rightBody = substitute(b.body, b.parameter, freshVar)
                    defEq(leftBody, rightBody)
                }
            }

            else -> false
        }
    }

    private fun whnf(term: Term, unfolding: MutableSet<String> = linkedSetOf()): Term {
        return when (term) {
            is Term.Meta -> term
            is Term.Lambda -> term
            is Term.Pi -> term
            is Term.Constant -> {
                val impl = globals[term.name]?.implementation
                if (impl == null || !unfolding.add(term.name)) {
                    term
                } else {
                    val result = whnf(impl, unfolding)
                    unfolding.remove(term.name)
                    result
                }
            }

            is Term.Variable -> {
                val impl = globals[term.name]?.implementation
                if (impl == null || !unfolding.add(term.name)) {
                    term
                } else {
                    val result = whnf(impl, unfolding)
                    unfolding.remove(term.name)
                    result
                }
            }

            is Term.Typed -> whnf(term.term, unfolding)
            is Term.Application -> {
                val fn = whnf(term.function, unfolding)
                if (fn is Term.Lambda) {
                    whnf(substitute(fn.body, fn.parameter, term.argument), unfolding)
                } else {
                    val candidate = Term.Application(fn, term.argument)
                    val rewritten = rewriteAtRoot(candidate)
                    if (rewritten != null) {
                        whnf(rewritten, unfolding)
                    } else {
                        candidate
                    }
                }
            }
        }
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
                    val normalizedApplication = Term.Application(fn, arg)
                    applyRewriteRules(normalizedApplication, unfolding)
                }
            }
        }
    }

    private fun applyRewriteRules(term: Term, unfolding: MutableSet<String>): Term {
        var current = term
        while (true) {
            val rewritten = rewriteAtRoot(current) ?: return current
            current = normalize(rewritten, unfolding)
        }
    }

    private fun rewriteAtRoot(term: Term): Term? {
        val (head, _) = decomposeApplication(term)
        val headName = when (head) {
            is Term.Variable -> head.name
            is Term.Constant -> head.name
            else -> return null
        }
        val rules = rewriteRulesByHead[headName] ?: return null

        rules.forEach { rule ->
            val substitutions = linkedMapOf<String, Term>()
            if (matchRewritePattern(rule.lhs, term, rule.patternVariables, substitutions)) {
                return instantiateRuleRhs(rule.rhs, substitutions)
            }
        }

        return null
    }

    private fun matchRewritePattern(
        pattern: Term,
        target: Term,
        patternVariables: Set<String>,
        substitutions: MutableMap<String, Term>,
    ): Boolean {
        return when {
            pattern is Term.Variable && pattern.name in patternVariables -> {
                val existing = substitutions[pattern.name]
                if (existing == null) {
                    substitutions[pattern.name] = target
                    true
                } else {
                    alphaEquivalent(normalize(existing), normalize(target))
                }
            }

            pattern is Term.Variable && target is Term.Variable -> pattern.name == target.name
            pattern is Term.Variable && target is Term.Constant -> pattern.name == target.name
            pattern is Term.Constant && target is Term.Variable -> pattern.name == target.name
            pattern is Term.Constant && target is Term.Constant -> pattern.name == target.name
            pattern is Term.Meta && target is Term.Meta -> pattern.id == target.id

            pattern is Term.Application && target is Term.Application -> {
                matchRewritePattern(pattern.function, target.function, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.argument, target.argument, patternVariables, substitutions)
            }

            pattern is Term.Typed && target is Term.Typed -> {
                matchRewritePattern(pattern.term, target.term, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.type, target.type, patternVariables, substitutions)
            }

            pattern is Term.Lambda && target is Term.Lambda -> {
                pattern.parameter == target.parameter &&
                    matchRewritePattern(pattern.parameterType, target.parameterType, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.body, target.body, patternVariables, substitutions)
            }

            pattern is Term.Pi && target is Term.Pi -> {
                pattern.parameter == target.parameter &&
                    matchRewritePattern(pattern.parameterType, target.parameterType, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.body, target.body, patternVariables, substitutions)
            }

            else -> false
        }
    }

    private fun instantiateRuleRhs(rhs: Term, substitutions: Map<String, Term>): Term {
        return applySimultaneousSubstitution(rhs, substitutions)
    }

    private fun applySimultaneousSubstitution(term: Term, substitutions: Map<String, Term>): Term {
        return when (term) {
            is Term.Meta -> term
            is Term.Constant -> term
            is Term.Variable -> substitutions[term.name] ?: term
            is Term.Typed -> Term.Typed(
                term = applySimultaneousSubstitution(term.term, substitutions),
                type = applySimultaneousSubstitution(term.type, substitutions),
            )

            is Term.Application -> Term.Application(
                function = applySimultaneousSubstitution(term.function, substitutions),
                argument = applySimultaneousSubstitution(term.argument, substitutions),
            )

            is Term.Lambda -> {
                val nextSubstitutions = substitutions - term.parameter
                Term.Lambda(
                    parameter = term.parameter,
                    parameterType = applySimultaneousSubstitution(term.parameterType, substitutions),
                    body = applySimultaneousSubstitution(term.body, nextSubstitutions),
                    parameterSpan = term.parameterSpan,
                )
            }

            is Term.Pi -> {
                val nextSubstitutions = substitutions - term.parameter
                Term.Pi(
                    parameter = term.parameter,
                    parameterType = applySimultaneousSubstitution(term.parameterType, substitutions),
                    body = applySimultaneousSubstitution(term.body, nextSubstitutions),
                    parameterSpan = term.parameterSpan,
                )
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

    private fun collectRuleVariables(term: Term, bound: Set<String> = emptySet()): Set<String> {
        return when (term) {
            is Term.Meta,
            is Term.Constant,
            -> emptySet()

            is Term.Variable -> {
                if (term.name in bound || term.name == "Type" || term.name in globals) {
                    emptySet()
                } else {
                    setOf(term.name)
                }
            }

            is Term.Typed -> collectRuleVariables(term.term, bound) + collectRuleVariables(term.type, bound)
            is Term.Application -> collectRuleVariables(term.function, bound) + collectRuleVariables(term.argument, bound)
            is Term.Lambda -> collectRuleVariables(term.parameterType, bound) + collectRuleVariables(term.body, bound + term.parameter)
            is Term.Pi -> collectRuleVariables(term.parameterType, bound) + collectRuleVariables(term.body, bound + term.parameter)
        }
    }

    private fun decomposeApplication(term: Term): Pair<Term, List<Term>> {
        val arguments = mutableListOf<Term>()
        var current = term
        while (current is Term.Application) {
            arguments.add(current.argument)
            current = current.function
        }
        arguments.reverse()
        return current to arguments
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
                is Term.Lambda -> "(λ${term.parameter} => ${prettyTerm(term.body)})"
                is Term.Pi -> "(Π(${term.parameter} : ${prettyTerm(term.parameterType)}) => ${prettyTerm(term.body)})"
            }
        }
    }

    private fun spanOf(term: Term): TextSpan? {
        return when (term) {
            is Term.Variable -> term.span
            is Term.Constant -> term.span
            is Term.Meta -> term.span
            is Term.Lambda -> term.parameterSpan
            is Term.Pi -> term.parameterSpan
            is Term.Typed -> spanOf(term.term) ?: spanOf(term.type)
            is Term.Application -> spanOf(term.function) ?: spanOf(term.argument)
        }
    }

    private fun report(span: TextSpan?, message: String) {
        val (line, column) = offsetToLineColumn(span?.startOffset ?: 0)
        diagnostics += Diagnostic(
            message = message,
            line = line,
            column = column,
            startOffset = span?.startOffset,
            endOffset = span?.endOffset,
        )
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

    private data class RegisteredRewriteRule(
        val name: String,
        val headConstant: String,
        val lhs: Term,
        val rhs: Term,
        val patternVariables: Set<String>,
    )

    private sealed interface TopLevelEntry {
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
}
