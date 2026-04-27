package core.typecheck

import core.model.Term
import core.model.TextSpan

internal fun TypeChecker.inferType(term: Term, locals: Map<String, Term>): Term? {
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

        is Term.Case -> {
            report(term.span, "cannot infer type of case expression; type annotation required")
            null
        }
    }
    if (inferred != null) {
        inferredTypes[term] = inferred
    }
    return inferred
}

internal fun TypeChecker.inferApplicationType(term: Term.Application, locals: Map<String, Term>): Term? {
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

internal fun TypeChecker.checkTermAgainst(term: Term, expectedType: Term, locals: Map<String, Term>): Boolean {
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

    if (term is Term.Case) {
        val ok = checkCaseTermAgainst(term, expected, locals)
        if (ok) {
            inferredTypes[term] = expected
        }
        return ok
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

internal fun TypeChecker.instantiateLeadingImplicitPis(type: Term, locals: Map<String, Term>, span: TextSpan?): Term {
    var current = whnf(type)
    while (current is Term.Pi && current.visibility == Term.Visibility.IMPLICIT) {
        val meta = freshImplicitMeta(current.parameterType, locals, span)
        current = whnf(substitute(current.body, current.parameter, meta))
    }
    return current
}

internal fun TypeChecker.elaborateImplicitApplications(
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

        is Term.Case -> Term.Case(
            scrutinee = elaborateImplicitApplications(term.scrutinee, locals, requireMetaResolution),
            branches = term.branches.map { branch ->
                val branchLocals = locals + branch.parameters.associate { it.name to typeUniverseTerm() }
                branch.copy(body = elaborateImplicitApplications(branch.body, branchLocals, requireMetaResolution))
            },
            span = term.span,
        )
    }
}

internal fun TypeChecker.extendLocalsWithBinder(
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

internal fun TypeChecker.isReservedLocalName(name: String): Boolean = name == "Type"

internal fun TypeChecker.convertible(a: Term, b: Term): Boolean {
    return defEq(a, b)
}

internal fun TypeChecker.convertibleInContext(a: Term, b: Term, locals: Map<String, Term>): Boolean {
    val left = elaborateImplicitApplications(zonk(a), locals, requireMetaResolution = false)
    val right = elaborateImplicitApplications(zonk(b), locals, requireMetaResolution = false)
    return defEq(left, right)
}

internal fun TypeChecker.defEq(a: Term, b: Term): Boolean {
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

internal fun TypeChecker.structuralDefEq(a: Term, b: Term): Boolean {
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

internal fun TypeChecker.substitute(term: Term, name: String, replacement: Term): Term {
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

        is Term.Case -> {
            val newScrutinee = substitute(term.scrutinee, name, replacement)
            val replacementVars = freeVariables(replacement)
            val newBranches = term.branches.map { branch ->
                var nextBranch = branch
                var nextBody = branch.body
                val boundNames = branch.parameters.map { it.name }
                val renames = linkedMapOf<String, String>()
                boundNames.forEach { boundName ->
                    if (boundName != name && boundName in replacementVars) {
                        val fresh = freshName(boundName, nextBody, replacement)
                        renames[boundName] = fresh
                        nextBody = substitute(nextBody, boundName, Term.Variable(fresh, branch.constructorSpan))
                    }
                }
                val updatedParameters = branch.parameters.map { parameter ->
                    val fresh = renames[parameter.name]
                    if (fresh == null) parameter else parameter.copy(name = fresh)
                }
                nextBranch = nextBranch.copy(parameters = updatedParameters)
                if (name !in boundNames) {
                    nextBody = substitute(nextBody, name, replacement)
                }
                nextBranch.copy(body = nextBody)
            }
            Term.Case(newScrutinee, newBranches, term.span)
        }
    }
}

internal fun TypeChecker.freshImplicitMeta(
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

internal fun TypeChecker.zonk(term: Term): Term {
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
        is Term.Case -> term.copy(
            scrutinee = zonk(term.scrutinee),
            branches = term.branches.map { it.copy(body = zonk(it.body)) },
        )
    }
}

internal fun TypeChecker.trySolveMeta(meta: Term.Meta, term: Term): Boolean {
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

internal fun TypeChecker.containsUnresolvedMeta(term: Term): Boolean {
    return when (term) {
        is Term.Meta -> metaContext[term.id]?.solution == null
        is Term.Variable,
        is Term.Constant,
        -> false

        is Term.Typed -> containsUnresolvedMeta(term.term) || containsUnresolvedMeta(term.type)
        is Term.Application -> containsUnresolvedMeta(term.function) || containsUnresolvedMeta(term.argument)
        is Term.Lambda -> containsUnresolvedMeta(term.parameterType) || containsUnresolvedMeta(term.body)
        is Term.Pi -> containsUnresolvedMeta(term.parameterType) || containsUnresolvedMeta(term.body)
        is Term.Case -> containsUnresolvedMeta(term.scrutinee) || term.branches.any { containsUnresolvedMeta(it.body) }
    }
}

internal fun TypeChecker.containsMeta(term: Term, id: Int): Boolean {
    return when (term) {
        is Term.Meta -> term.id == id
        is Term.Variable,
        is Term.Constant,
        -> false

        is Term.Typed -> containsMeta(term.term, id) || containsMeta(term.type, id)
        is Term.Application -> containsMeta(term.function, id) || containsMeta(term.argument, id)
        is Term.Lambda -> containsMeta(term.parameterType, id) || containsMeta(term.body, id)
        is Term.Pi -> containsMeta(term.parameterType, id) || containsMeta(term.body, id)
        is Term.Case -> containsMeta(term.scrutinee, id) || term.branches.any { containsMeta(it.body, id) }
    }
}

internal fun TypeChecker.freeVariables(term: Term, bound: Set<String> = emptySet()): Set<String> {
    return when (term) {
        is Term.Meta,
        is Term.Constant,
        -> emptySet()

        is Term.Variable -> if (term.name in bound) emptySet() else setOf(term.name)
        is Term.Typed -> freeVariables(term.term, bound) + freeVariables(term.type, bound)
        is Term.Application -> freeVariables(term.function, bound) + freeVariables(term.argument, bound)
        is Term.Lambda -> freeVariables(term.parameterType, bound) + freeVariables(term.body, bound + term.parameter)
        is Term.Pi -> freeVariables(term.parameterType, bound) + freeVariables(term.body, bound + term.parameter)
        is Term.Case -> {
            val scrutineeVars = freeVariables(term.scrutinee, bound)
            val branchVars = term.branches.flatMap { branch ->
                val branchBound = bound + branch.parameters.map { it.name }
                freeVariables(branch.body, branchBound).toList()
            }.toSet()
            scrutineeVars + branchVars
        }
    }
}

internal fun TypeChecker.collectRuleVariables(term: Term, bound: Set<String> = emptySet()): Set<String> {
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
        is Term.Case -> {
            val scrutineeVars = collectRuleVariables(term.scrutinee, bound)
            val branchVars = term.branches.flatMap { branch ->
                val branchBound = bound + branch.parameters.map { it.name }
                collectRuleVariables(branch.body, branchBound).toList()
            }.toSet()
            scrutineeVars + branchVars
        }
    }
}

internal fun TypeChecker.freshName(base: String, vararg terms: Term): String {
    val occupied = terms.flatMap { freeVariables(it) }.toSet() + globals.keys
    var candidate = "${base}_0"
    var i = 1
    while (candidate in occupied) {
        candidate = "${base}_${i++}"
    }
    return candidate
}

internal fun TypeChecker.alphaEquivalent(a: Term, b: Term, env: Map<String, String> = emptyMap()): Boolean {
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

        a is Term.Case && b is Term.Case -> {
            if (!alphaEquivalent(a.scrutinee, b.scrutinee, env)) {
                return false
            }
            if (a.branches.size != b.branches.size) {
                return false
            }
            a.branches.zip(b.branches).all { (leftBranch, rightBranch) ->
                if (leftBranch.constructorName != rightBranch.constructorName) {
                    return@all false
                }
                if (leftBranch.parameters.size != rightBranch.parameters.size) {
                    return@all false
                }
                val branchEnv = leftBranch.parameters.zip(rightBranch.parameters)
                    .fold(env) { acc, (leftParam, rightParam) -> acc + (leftParam.name to rightParam.name) }
                alphaEquivalent(leftBranch.body, rightBranch.body, branchEnv)
            }
        }

        else -> false
    }
}
