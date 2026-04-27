package core.typecheck

import core.model.Definition
import core.model.DefinitionKind
import core.model.EvaluationTrace
import core.model.RewriteRule
import core.model.Term
import core.model.TypeCheckTrace
import java.util.IdentityHashMap

internal fun TypeChecker.checkProgramInternal(): TypeCheckResult {
    val entries = topLevelEntries()
    var activeTrace: TypeCheckTrace? = null
    var activeEvaluationTrace: EvaluationTrace? = null

    entries.forEachIndexed { index, entry ->
        val nextStart = entries.getOrNull(index + 1)?.startOffset ?: (source.length + 1)
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

    validateNewtypeRegistries()

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

internal fun TypeChecker.topLevelEntries(): List<TopLevelEntry> {
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

internal fun TypeChecker.validateNewtypeRegistries() {
    document.newtypeRegistries.forEach { registry ->
        registry.constructors.forEach { constructor ->
            val finalResult = constructorResultType(constructor.type)
            val headName = headSymbolName(finalResult)
            if (headName != registry.typeName) {
                report(
                    constructor.nameSpan,
                    "Constructor '${constructor.name}' must return ${registry.typeName} (possibly applied), got ${pretty(finalResult)}",
                )
            }
        }
    }
}

internal fun TypeChecker.constructorResultType(type: Term): Term {
    var current = whnf(zonk(type))
    while (current is Term.Pi) {
        current = whnf(zonk(current.body))
    }
    return current
}

internal fun TypeChecker.headSymbolName(term: Term): String? {
    val (head, _) = decomposeApplication(term)
    return when (head) {
        is Term.Variable -> head.name
        is Term.Constant -> head.name
        else -> null
    }
}

internal fun TypeChecker.checkDefinition(definition: Definition) {
    var declaredType = definition.type
    var implementation = definition.implementation

    traceStep("check definition '${definition.name}'")

    if (declaredType == null && implementation == null) {
        report(definition.nameSpan, "Definition '${definition.name}' must have type or implementation")
        traceStep("error: missing both type and implementation")
        return
    }

    when (definition.kind) {
        DefinitionKind.AXIOM,
        DefinitionKind.RECURSOR,
        -> {
            if (implementation != null) {
                report(definition.nameSpan, "${definition.kind.name.lowercase()} '${definition.name}' must not have body")
                implementation = null
            }
        }

        DefinitionKind.NEWTYPE -> {
            if (implementation != null) {
                report(definition.nameSpan, "newtype '${definition.name}' must not have body")
                implementation = null
            }
            if (declaredType == null) {
                declaredType = typeUniverseTerm()
            }
            val newtypeType = declaredType
            if (!newtypeTargetsType(newtypeType)) {
                report(definition.nameSpan, "newtype '${definition.name}' must target Type")
            }
        }

        DefinitionKind.DEF,
        DefinitionKind.FUN,
        DefinitionKind.LEMMA,
        DefinitionKind.THEOREM,
        -> {
            if (implementation == null) {
                report(definition.nameSpan, "${definition.kind.name.lowercase()} '${definition.name}' must have body")
            }
        }

        DefinitionKind.LEGACY -> Unit
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

internal fun TypeChecker.checkRewriteRule(rule: RewriteRule) {
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

internal fun TypeChecker.checkLeadingImplicitArguments(type: Term) {
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

internal fun TypeChecker.newtypeTargetsType(type: Term): Boolean {
    var current = whnf(zonk(type))
    while (current is Term.Pi) {
        current = whnf(zonk(current.body))
    }
    return convertible(current, typeUniverseTerm())
}

internal fun TypeChecker.checkRulePatternAgainstExpectedType(
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
                val (head, args) = decomposeApplication(term)
                val headName = when (head) {
                    is Term.Variable -> head.name
                    is Term.Constant -> head.name
                    else -> null
                }

                if (headName != null && headName in globals) {
                    var currentType = normalize(globals.getValue(headName).type)
                    for (arg in args) {
                        val functionType = normalize(currentType)
                        if (functionType !is Term.Pi) {
                            report(spanOf(term), "Cannot apply non-function term in rule pattern: ${pretty(head)}")
                            return false
                        }

                        if (!checkRulePatternAgainstExpectedType(arg, functionType.parameterType, locals)) {
                            return false
                        }

                        currentType = normalize(substitute(functionType.body, functionType.parameter, arg))
                    }

                    if (!convertibleInContext(currentType, expectedType, locals)) {
                        report(spanOf(term), "Rule pattern expected ${pretty(expectedType)}, got ${pretty(currentType)}")
                        return false
                    }
                    return true
                }

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
