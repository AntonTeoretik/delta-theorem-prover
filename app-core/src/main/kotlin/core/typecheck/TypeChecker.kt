package core.typecheck

import core.model.Definition
import core.model.Diagnostic
import core.model.EvaluationStep
import core.model.EvaluationTrace
import core.model.ParsedDocument
import core.model.RewriteRule
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
    private val document: ParsedDocument,
    private val caretOffset: Int? = null,
) {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val globals = linkedMapOf<String, GlobalEntry>()
    private val source = document.sourceText
    private val inferredTypes = IdentityHashMap<Term, Term>()
    private val typeHints = mutableListOf<TypeHint>()
    private val typeHintKeys = linkedSetOf<String>()
    private val rewriteRulesByHead = linkedMapOf<String, MutableList<RegisteredRewriteRule>>()
    private val metaContext = linkedMapOf<Int, MetaEntry>()
    private var nextMetaId: Int = 0
    private var nextTypeHintId: Int = 0
    private var traceSink: MutableList<String>? = null

    init {
        val type = typeUniverseTerm()
        globals["Type"] = GlobalEntry(type = type, implementation = null)
        nextMetaId = maxMetaIdInDocument() + 1
    }

    fun checkProgram(): TypeCheckResult {
        val entries = topLevelEntries()
        var activeTrace: TypeCheckTrace? = null
        var activeEvaluationTrace: EvaluationTrace? = null

        entries.forEachIndexed { index, entry ->
            val nextStart = entries.getOrNull(index + 1)?.startOffset ?: source.length + 1
            val isActive = caretOffset != null && caretOffset >= entry.startOffset && caretOffset < nextStart
            val localTrace = if (isActive) mutableListOf<String>() else null
            traceSink = localTrace

            when (entry) {
                is TopLevelEntry.DefinitionEntry -> checkDefinition(entry.definition)
                is TopLevelEntry.RuleEntry -> checkRewriteRule(entry.rule)
            }

            if (isActive) {
                val title = when (entry) {
                    is TopLevelEntry.DefinitionEntry -> "definition ${entry.definition.name}"
                    is TopLevelEntry.RuleEntry -> "rule ${entry.rule.name}"
                }
                val line = offsetToLineColumn(entry.startOffset).first
                activeTrace = TypeCheckTrace(
                    title = title,
                    line = line,
                    steps = localTrace?.toList().orEmpty(),
                )
                activeEvaluationTrace = when (entry) {
                    is TopLevelEntry.DefinitionEntry -> buildEvaluationTrace(entry.definition)
                    is TopLevelEntry.RuleEntry -> null
                }
            }

            traceSink = null
        }

        metaContext.values.forEach { entry ->
            if (entry.requiresResolution && entry.solution == null) {
                report(entry.span, "cannot infer implicit argument")
            }
        }

        return TypeCheckResult(
            diagnostics = diagnostics.toList(),
            inferredTypes = IdentityHashMap(inferredTypes),
            typeHints = typeHints.toList(),
            activeTrace = activeTrace,
            activeEvaluationTrace = activeEvaluationTrace,
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

    private fun maxMetaIdInDocument(): Int {
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

    private fun checkDefinition(definition: Definition) {
        val declaredType = definition.type
        val implementation = definition.implementation

        traceStep("check definition '${definition.name}'")

        if (declaredType == null && implementation == null) {
            report(definition.nameSpan, "Definition '${definition.name}' must have type or implementation")
            traceStep("error: missing both type and implementation")
            return
        }

        if (declaredType != null) {
            traceStep("infer declared type kind")
            inferType(declaredType, emptyMap())
            checkLeadingImplicitArguments(declaredType)
        }

        val implementationType = implementation?.let {
            if (declaredType != null) {
                traceStep("check implementation against declared type")
                val ok = checkTermAgainst(it, declaredType, emptyMap())
                if (!ok) {
                    null
                } else {
                    declaredType
                }
            } else {
                traceStep("infer implementation type")
                inferType(it, emptyMap())
            }
        }

        if (declaredType != null && implementationType != null) {
            traceStep("check convertibility declared ~ inferred")
            if (!convertible(implementationType, declaredType)) {
                report(
                    definition.nameSpan,
                    "Definition '${definition.name}' has type mismatch. Declared: ${pretty(declaredType)}, inferred: ${pretty(implementationType)}",
                )
                traceStep("convertibility failed")
            } else {
                traceStep("convertibility ok")
            }
        }

        val finalType = declaredType ?: implementationType
        if (finalType != null) {
            globals[definition.name] = GlobalEntry(type = finalType, implementation = implementation)
            traceStep("register global '${definition.name}'")
        }
    }

    private fun checkRewriteRule(rule: RewriteRule) {
        traceStep("check rule '${rule.name}'")
        val (head, args) = decomposeApplication(rule.lhs)
        if (head !is Term.Variable || args.isEmpty()) {
            report(rule.nameSpan, "Rule '${rule.name}': LHS must be an application of a constant")
            traceStep("error: lhs is not a constant application")
            return
        }

        val headEntry = globals[head.name]
        if (headEntry == null) {
            report(rule.nameSpan, "Rule '${rule.name}': Unknown constant '${head.name}' on LHS")
            traceStep("error: unknown constant '${head.name}'")
            return
        }

        if (headEntry.implementation != null) {
            report(rule.nameSpan, "Rule '${rule.name}': '${head.name}' is not axiomatic (it has ':=')")
            traceStep("error: '${head.name}' is not axiomatic")
            return
        }

        val patternLocals = linkedMapOf<String, Term>()
        var currentType: Term = normalize(headEntry.type)
        var valid = true

        traceStep("lhs head constant: ${head.name}")

        args.forEach { argument ->
            val functionType = normalize(currentType)
            if (functionType !is Term.Pi) {
                report(rule.nameSpan, "Rule '${rule.name}': too many arguments on LHS for '${head.name}'")
                valid = false
                traceStep("error: too many lhs arguments")
                return@forEach
            }

            if (!checkRulePatternAgainstExpectedType(argument, functionType.parameterType, patternLocals)) {
                valid = false
                return@forEach
            }

            currentType = normalize(substitute(functionType.body, functionType.parameter, argument))
        }

        if (!valid) {
            traceStep("rule rejected during lhs pattern typing")
            return
        }

        val lhsType = inferType(rule.lhs, patternLocals) ?: currentType
        val rhsType = inferType(rule.rhs, patternLocals)
        if (rhsType == null) {
            traceStep("rule rejected: cannot infer rhs type")
            return
        }

        traceStep("lhs type: ${pretty(lhsType)}")
        traceStep("rhs type: ${pretty(rhsType)}")

        if (!convertibleInContext(lhsType, rhsType, patternLocals)) {
            report(
                rule.nameSpan,
                "Rule '${rule.name}' type mismatch: lhs has ${pretty(lhsType)}, rhs has ${pretty(rhsType)}",
            )
            traceStep("rule rejected: lhs/rhs types not convertible")
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
            traceStep("rule rejected: rhs variables not in lhs")
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
        traceStep("register rewrite rule for '${head.name}'")
    }

    private fun checkLeadingImplicitArguments(type: Term) {
        var current = whnf(type)
        var seenExplicit = false
        while (current is Term.Pi) {
            if (current.visibility == Term.Visibility.EXPLICIT) {
                seenExplicit = true
            } else if (seenExplicit) {
                report(current.parameterSpan, "implicit arguments must be leading")
                break
            }
            current = whnf(current.body)
        }
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
                    if (!convertibleInContext(inferred, expectedType, locals)) {
                        report(term.span, "Rule pattern expected ${pretty(expectedType)}, got ${pretty(inferred)}")
                        false
                    } else {
                        true
                    }
                } else if (term.name in globals) {
                    val inferred = inferType(term, locals) ?: return false
                    if (!convertibleInContext(inferred, expectedType, locals)) {
                        report(term.span, "Rule pattern expected ${pretty(expectedType)}, got ${pretty(inferred)}")
                        false
                    } else {
                        true
                    }
                } else {
                    val existing = locals[term.name]
                    if (existing != null && !convertibleInContext(existing, expectedType, locals)) {
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
                if (!convertibleInContext(resulting, expectedType, locals)) {
                    report(spanOf(term), "Rule pattern expected ${pretty(expectedType)}, got ${pretty(resulting)}")
                    return false
                }

                true
            }

            else -> {
                val inferred = inferType(term, locals) ?: return false
                if (!convertibleInContext(inferred, expectedType, locals)) {
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
                val entry = metaContext.getOrPut(term.id) {
                    MetaEntry(
                        id = term.id,
                        expectedType = null,
                        solution = null,
                        allowedLocals = locals.keys.toMutableSet(),
                        span = term.span,
                    )
                }
                entry.allowedLocals.addAll(locals.keys)
                entry.solution?.let { inferType(it, locals) } ?: entry.expectedType ?: typeUniverseTerm()
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
                    visibility = term.visibility,
                )
            }

            is Term.Application -> {
                inferApplicationType(term, locals)
            }
        }
        if (inferred != null) {
            inferredTypes[term] = inferred
        }
        return inferred
    }

    private fun inferApplicationType(term: Term.Application, locals: Map<String, Term>): Term? {
        var functionType = inferType(term.function, locals)?.let { whnf(it) } ?: return null

        if (term.visibility == Term.Visibility.EXPLICIT) {
            while (functionType is Term.Pi && functionType.visibility == Term.Visibility.IMPLICIT) {
                val meta = freshImplicitMeta(functionType.parameterType, locals, spanOf(term.function))
                functionType = whnf(substitute(functionType.body, functionType.parameter, meta))
            }

            if (functionType !is Term.Pi || functionType.visibility != Term.Visibility.EXPLICIT) {
                report(spanOf(term.function), "expected explicit function type")
                return null
            }

            if (!checkTermAgainst(term.argument, functionType.parameterType, locals)) {
                report(
                    spanOf(term.argument),
                    "Application argument type mismatch. Expected ${pretty(functionType.parameterType)}, got ${pretty(inferType(term.argument, locals) ?: Term.Variable("_", TextSpan(0, 0)))}",
                )
                return null
            }
            return substitute(functionType.body, functionType.parameter, term.argument)
        }

        if (functionType !is Term.Pi || functionType.visibility != Term.Visibility.IMPLICIT) {
            report(spanOf(term), "unexpected implicit argument")
            return null
        }

        if (!checkTermAgainst(term.argument, functionType.parameterType, locals)) {
            report(spanOf(term.argument), "type mismatch for implicit argument")
            return null
        }

        return substitute(functionType.body, functionType.parameter, term.argument)
    }

    private fun checkTermAgainst(term: Term, expectedType: Term, locals: Map<String, Term>): Boolean {
        val expectedElaborated = elaborateImplicitApplications(zonk(expectedType), locals, requireMetaResolution = false)
        val expected = whnf(expectedElaborated)

        if (expected is Term.Pi && expected.visibility == Term.Visibility.IMPLICIT) {
            if (term !is Term.Lambda || term.visibility != Term.Visibility.IMPLICIT) {
                val freshVar = Term.Variable(expected.parameter, expected.parameterSpan)
                val extended = extendLocalsWithBinder(locals, expected.parameter, expected.parameterType, expected.parameterSpan)
                val expectedBody = substitute(expected.body, expected.parameter, freshVar)
                return checkTermAgainst(term, expectedBody, extended)
            }
        }

        if (term is Term.Lambda) {
            if (expected !is Term.Pi) {
                report(spanOf(term), if (term.visibility == Term.Visibility.IMPLICIT) "expected implicit function type" else "expected explicit function type")
                return false
            }
            if (term.visibility != expected.visibility) {
                report(spanOf(term), if (term.visibility == Term.Visibility.IMPLICIT) "expected implicit function type" else "expected explicit function type")
                return false
            }
            if (!convertibleInContext(term.parameterType, expected.parameterType, locals)) {
                if (containsUnresolvedMeta(term.parameterType)) {
                    report(term.parameterSpan, "cannot infer type for lambda binder '${term.parameter}'")
                } else {
                    report(term.parameterSpan, "Lambda binder type mismatch")
                }
                return false
            }
            val extended = extendLocalsWithBinder(locals, term.parameter, expected.parameterType, term.parameterSpan)
            val expectedBody = substitute(expected.body, expected.parameter, Term.Variable(term.parameter, term.parameterSpan))
            return checkTermAgainst(term.body, expectedBody, extended)
        }

        val inferred = inferType(term, locals) ?: return false
        val instantiatedInferred = instantiateLeadingImplicitPis(inferred, locals, spanOf(term))
        val inferredElaborated = elaborateImplicitApplications(zonk(instantiatedInferred), locals, requireMetaResolution = false)
        if (!convertible(inferredElaborated, expected)) {
            report(spanOf(term), "Type mismatch. Expected ${pretty(expected)}, got ${pretty(inferred)}")
            return false
        }
        return true
    }

    private fun instantiateLeadingImplicitPis(type: Term, locals: Map<String, Term>, span: TextSpan?): Term {
        var current = whnf(type)
        while (current is Term.Pi && current.visibility == Term.Visibility.IMPLICIT) {
            val meta = freshImplicitMeta(current.parameterType, locals, span)
            current = whnf(substitute(current.body, current.parameter, meta))
        }
        return current
    }

    private fun elaborateImplicitApplications(
        term: Term,
        locals: Map<String, Term>,
        requireMetaResolution: Boolean,
    ): Term {
        return when (term) {
            is Term.Meta,
            is Term.Variable,
            is Term.Constant,
            -> term

            is Term.Typed -> Term.Typed(
                term = elaborateImplicitApplications(term.term, locals, requireMetaResolution),
                type = elaborateImplicitApplications(term.type, locals, requireMetaResolution),
            )

            is Term.Lambda -> term.copy(
                parameterType = elaborateImplicitApplications(term.parameterType, locals, requireMetaResolution),
                body = elaborateImplicitApplications(term.body, locals + (term.parameter to term.parameterType), requireMetaResolution),
            )

            is Term.Pi -> term.copy(
                parameterType = elaborateImplicitApplications(term.parameterType, locals, requireMetaResolution),
                body = elaborateImplicitApplications(term.body, locals + (term.parameter to term.parameterType), requireMetaResolution),
            )

            is Term.Application -> {
                val function = elaborateImplicitApplications(term.function, locals, requireMetaResolution)
                val argument = elaborateImplicitApplications(term.argument, locals, requireMetaResolution)

                if (term.visibility == Term.Visibility.IMPLICIT) {
                    return Term.Application(function, argument, Term.Visibility.IMPLICIT)
                }

                var fnType = inferType(function, locals)?.let { whnf(it) }
                var elaboratedFunction = function
                while (fnType is Term.Pi && fnType.visibility == Term.Visibility.IMPLICIT) {
                    val meta = freshImplicitMeta(
                        expectedType = fnType.parameterType,
                        locals = locals,
                        span = spanOf(function),
                        requiresResolution = requireMetaResolution,
                    )
                    elaboratedFunction = Term.Application(elaboratedFunction, meta, Term.Visibility.IMPLICIT)
                    fnType = whnf(substitute(fnType.body, fnType.parameter, meta))
                }

                Term.Application(elaboratedFunction, argument, Term.Visibility.EXPLICIT)
            }
        }
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

    private fun convertibleInContext(a: Term, b: Term, locals: Map<String, Term>): Boolean {
        val left = elaborateImplicitApplications(zonk(a), locals, requireMetaResolution = false)
        val right = elaborateImplicitApplications(zonk(b), locals, requireMetaResolution = false)
        return defEq(left, right)
    }

    private fun defEq(a: Term, b: Term): Boolean {
        traceStep("defEq: ${pretty(a)} ~ ${pretty(b)}")
        val za = zonk(a)
        val zb = zonk(b)

        if (za is Term.Meta && trySolveMeta(za, zb)) {
            return true
        }
        if (zb is Term.Meta && trySolveMeta(zb, za)) {
            return true
        }

        val wa = whnf(za)
        val wb = whnf(zb)

        if (wa is Term.Meta && trySolveMeta(wa, wb)) {
            return true
        }
        if (wb is Term.Meta && trySolveMeta(wb, wa)) {
            return true
        }

        if (structuralDefEq(wa, wb)) {
            traceStep("defEq success at whnf")
            return true
        }

        val na = normalize(za)
        val nb = normalize(zb)
        val ok = alphaEquivalent(na, nb)
        traceStep("defEq fallback normalize: ${pretty(na)} ~ ${pretty(nb)} => $ok")
        return ok
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
                if (a.visibility != b.visibility) {
                    return false
                }
                defEq(a.function, b.function) && defEq(a.argument, b.argument)
            }

            a is Term.Lambda && b is Term.Lambda -> {
                if (a.visibility != b.visibility) {
                    return false
                }
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
                if (a.visibility != b.visibility) {
                    return false
                }
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
                if (fn is Term.Lambda && fn.visibility == term.visibility) {
                    whnf(substitute(fn.body, fn.parameter, term.argument), unfolding)
                } else {
                    val candidate = Term.Application(fn, term.argument, term.visibility)
                    val rewrite = rewriteAtRoot(candidate)
                    if (rewrite != null) {
                        traceStep("whnf rewrite: ${rewrite.ruleName}")
                        whnf(rewrite.term, unfolding)
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
                visibility = term.visibility,
            )

            is Term.Lambda -> Term.Lambda(
                parameter = term.parameter,
                parameterType = normalize(term.parameterType, unfolding),
                body = normalize(term.body, unfolding),
                parameterSpan = term.parameterSpan,
                visibility = term.visibility,
            )

            is Term.Application -> {
                val fn = normalize(term.function, unfolding)
                val arg = normalize(term.argument, unfolding)
                if (fn is Term.Lambda && fn.visibility == term.visibility) {
                    normalize(substitute(fn.body, fn.parameter, arg), unfolding)
                } else {
                    val normalizedApplication = Term.Application(fn, arg, term.visibility)
                    applyRewriteRules(normalizedApplication, unfolding)
                }
            }
        }
    }

    private fun applyRewriteRules(term: Term, unfolding: MutableSet<String>): Term {
        var current = term
        while (true) {
            val rewrite = rewriteAtRoot(current) ?: return current
            traceStep("normalize rewrite: ${rewrite.ruleName}")
            current = normalize(rewrite.term, unfolding)
        }
    }

    private fun buildEvaluationTrace(definition: Definition): EvaluationTrace? {
        val implementation = definition.implementation ?: return null
        val startOffset = definition.nameSpan?.startOffset ?: 0
        val line = offsetToLineColumn(startOffset).first
        val steps = mutableListOf<EvaluationStep>()
        var current = implementation
        var counter = 0

        while (counter < EVALUATION_STEP_LIMIT) {
            val outcome = reduceOneStep(current) ?: break
            steps += EvaluationStep(
                reason = outcome.reason,
                from = pretty(current),
                to = pretty(outcome.term),
            )
            current = outcome.term
            counter += 1
        }

        return EvaluationTrace(
            title = definition.name,
            line = line,
            steps = steps,
        )
    }

    private fun reduceOneStep(term: Term, unfolding: MutableSet<String> = linkedSetOf()): ReductionOutcome? {
        return when (term) {
            is Term.Meta,
            is Term.Constant,
            -> {
                val name = (term as? Term.Constant)?.name
                if (name != null) {
                    val impl = globals[name]?.implementation
                    if (impl != null && unfolding.add(name)) {
                        ReductionOutcome(impl, "unfold $name")
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            is Term.Variable -> {
                val impl = globals[term.name]?.implementation
                if (impl != null && unfolding.add(term.name)) {
                    ReductionOutcome(impl, "unfold ${term.name}")
                } else {
                    null
                }
            }

            is Term.Typed -> {
                val reducedTerm = reduceOneStep(term.term, unfolding)
                if (reducedTerm != null) {
                    ReductionOutcome(Term.Typed(reducedTerm.term, term.type), "in annotation: ${reducedTerm.reason}")
                } else {
                    val reducedType = reduceOneStep(term.type, unfolding) ?: return null
                    ReductionOutcome(Term.Typed(term.term, reducedType.term), "in annotation type: ${reducedType.reason}")
                }
            }

            is Term.Pi -> {
                val reducedType = reduceOneStep(term.parameterType, unfolding)
                if (reducedType != null) {
                    ReductionOutcome(term.copy(parameterType = reducedType.term), "in Pi domain: ${reducedType.reason}")
                } else {
                    val reducedBody = reduceOneStep(term.body, unfolding) ?: return null
                    ReductionOutcome(term.copy(body = reducedBody.term), "in Pi body: ${reducedBody.reason}")
                }
            }

            is Term.Lambda -> {
                val reducedType = reduceOneStep(term.parameterType, unfolding)
                if (reducedType != null) {
                    ReductionOutcome(term.copy(parameterType = reducedType.term), "in lambda domain: ${reducedType.reason}")
                } else {
                    val reducedBody = reduceOneStep(term.body, unfolding) ?: return null
                    ReductionOutcome(term.copy(body = reducedBody.term), "in lambda body: ${reducedBody.reason}")
                }
            }

            is Term.Application -> {
                val reducedFunction = reduceOneStep(term.function, unfolding)
                if (reducedFunction != null) {
                    return ReductionOutcome(
                        term = Term.Application(reducedFunction.term, term.argument, term.visibility),
                        reason = "in function: ${reducedFunction.reason}",
                    )
                }

                if (term.function is Term.Lambda && term.function.visibility == term.visibility) {
                    return ReductionOutcome(
                        term = substitute(term.function.body, term.function.parameter, term.argument),
                        reason = "beta",
                    )
                }

                val rewrite = rewriteAtRoot(term)
                if (rewrite != null) {
                    return ReductionOutcome(
                        term = rewrite.term,
                        reason = "rewrite ${rewrite.ruleName}",
                    )
                }

                val reducedArgument = reduceOneStep(term.argument, unfolding)
                if (reducedArgument != null) {
                    return ReductionOutcome(
                        term = Term.Application(term.function, reducedArgument.term, term.visibility),
                        reason = "in argument: ${reducedArgument.reason}",
                    )
                }

                null
            }
        }
    }

    private fun rewriteAtRoot(term: Term): RewriteOutcome? {
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
                return RewriteOutcome(ruleName = rule.name, term = instantiateRuleRhs(rule.rhs, substitutions))
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
                if (pattern.visibility != target.visibility) {
                    return false
                }
                matchRewritePattern(pattern.function, target.function, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.argument, target.argument, patternVariables, substitutions)
            }

            pattern is Term.Typed && target is Term.Typed -> {
                matchRewritePattern(pattern.term, target.term, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.type, target.type, patternVariables, substitutions)
            }

            pattern is Term.Lambda && target is Term.Lambda -> {
                pattern.visibility == target.visibility &&
                    pattern.parameter == target.parameter &&
                    matchRewritePattern(pattern.parameterType, target.parameterType, patternVariables, substitutions) &&
                    matchRewritePattern(pattern.body, target.body, patternVariables, substitutions)
            }

            pattern is Term.Pi && target is Term.Pi -> {
                pattern.visibility == target.visibility &&
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
                visibility = term.visibility,
            )

            is Term.Lambda -> {
                val nextSubstitutions = substitutions - term.parameter
                Term.Lambda(
                    parameter = term.parameter,
                    parameterType = applySimultaneousSubstitution(term.parameterType, substitutions),
                    body = applySimultaneousSubstitution(term.body, nextSubstitutions),
                    parameterSpan = term.parameterSpan,
                    visibility = term.visibility,
                )
            }

            is Term.Pi -> {
                val nextSubstitutions = substitutions - term.parameter
                Term.Pi(
                    parameter = term.parameter,
                    parameterType = applySimultaneousSubstitution(term.parameterType, substitutions),
                    body = applySimultaneousSubstitution(term.body, nextSubstitutions),
                    parameterSpan = term.parameterSpan,
                    visibility = term.visibility,
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
                visibility = term.visibility,
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
                            visibility = term.visibility,
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
                            visibility = term.visibility,
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

    private fun freshImplicitMeta(
        expectedType: Term,
        locals: Map<String, Term>,
        span: TextSpan?,
        requiresResolution: Boolean = true,
    ): Term.Meta {
        val id = nextMetaId++
        val safeSpan = span ?: TextSpan(0, 0)
        metaContext[id] = MetaEntry(
            id = id,
            expectedType = zonk(expectedType),
            solution = null,
            allowedLocals = locals.keys.toMutableSet(),
            span = safeSpan,
            requiresResolution = requiresResolution,
        )
        return Term.Meta(id, safeSpan)
    }

    private fun zonk(term: Term): Term {
        return when (term) {
            is Term.Meta -> {
                val solved = metaContext[term.id]?.solution
                if (solved == null) {
                    term
                } else {
                    zonk(solved)
                }
            }

            is Term.Variable,
            is Term.Constant,
            -> term

            is Term.Typed -> Term.Typed(zonk(term.term), zonk(term.type))
            is Term.Application -> Term.Application(zonk(term.function), zonk(term.argument), term.visibility)
            is Term.Lambda -> term.copy(parameterType = zonk(term.parameterType), body = zonk(term.body))
            is Term.Pi -> term.copy(parameterType = zonk(term.parameterType), body = zonk(term.body))
        }
    }

    private fun trySolveMeta(meta: Term.Meta, term: Term): Boolean {
        val entry = metaContext.getOrPut(meta.id) {
            MetaEntry(meta.id, expectedType = null, solution = null, allowedLocals = mutableSetOf(), span = meta.span)
        }

        val solved = entry.solution
        if (solved != null) {
            return defEq(solved, term)
        }

        val candidate = zonk(term)
        if (entry.allowedLocals.isEmpty()) {
            entry.allowedLocals.addAll(freeVariables(candidate))
        }
        if (containsMeta(candidate, meta.id)) {
            return false
        }

        val unknownLocals = freeVariables(candidate)
            .filter { it !in globals.keys && it !in entry.allowedLocals }
        if (unknownLocals.isNotEmpty()) {
            return false
        }

        entry.solution = candidate
        return true
    }

    private fun containsUnresolvedMeta(term: Term): Boolean {
        return when (term) {
            is Term.Meta -> metaContext[term.id]?.solution == null
            is Term.Variable,
            is Term.Constant,
            -> false

            is Term.Typed -> containsUnresolvedMeta(term.term) || containsUnresolvedMeta(term.type)
            is Term.Application -> containsUnresolvedMeta(term.function) || containsUnresolvedMeta(term.argument)
            is Term.Lambda -> containsUnresolvedMeta(term.parameterType) || containsUnresolvedMeta(term.body)
            is Term.Pi -> containsUnresolvedMeta(term.parameterType) || containsUnresolvedMeta(term.body)
        }
    }

    private fun containsMeta(term: Term, id: Int): Boolean {
        return when (term) {
            is Term.Meta -> term.id == id
            is Term.Variable,
            is Term.Constant,
            -> false

            is Term.Typed -> containsMeta(term.term, id) || containsMeta(term.type, id)
            is Term.Application -> containsMeta(term.function, id) || containsMeta(term.argument, id)
            is Term.Lambda -> containsMeta(term.parameterType, id) || containsMeta(term.body, id)
            is Term.Pi -> containsMeta(term.parameterType, id) || containsMeta(term.body, id)
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
                a.visibility == b.visibility &&
                    alphaEquivalent(a.function, b.function, env) &&
                    alphaEquivalent(a.argument, b.argument, env)

            a is Term.Lambda && b is Term.Lambda -> {
                a.visibility == b.visibility &&
                    alphaEquivalent(a.parameterType, b.parameterType, env) &&
                    alphaEquivalent(a.body, b.body, env + (a.parameter to b.parameter))
            }

            a is Term.Pi && b is Term.Pi -> {
                a.visibility == b.visibility &&
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

    private fun traceStep(message: String) {
        val sink = traceSink ?: return
        if (sink.size >= TRACE_STEP_LIMIT) {
            if (sink.lastOrNull() != "... trace truncated ...") {
                sink += "... trace truncated ..."
            }
            return
        }
        sink += message
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

    private data class MetaEntry(
        val id: Int,
        var expectedType: Term?,
        var solution: Term?,
        val allowedLocals: MutableSet<String>,
        val span: TextSpan,
        val requiresResolution: Boolean = false,
    )

    private data class RegisteredRewriteRule(
        val name: String,
        val headConstant: String,
        val lhs: Term,
        val rhs: Term,
        val patternVariables: Set<String>,
    )

    private data class RewriteOutcome(
        val ruleName: String,
        val term: Term,
    )

    private data class ReductionOutcome(
        val term: Term,
        val reason: String,
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
