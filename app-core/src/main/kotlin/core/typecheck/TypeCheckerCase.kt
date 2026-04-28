package core.typecheck

import core.model.Term
import core.model.TextSpan

internal fun TypeChecker.checkCaseTermAgainst(term: Term.Case, expectedType: Term, locals: Map<String, Term>): Boolean {
    val expected = zonk(expectedType)
    val elaborated = elaborateCaseTerm(term, expected, locals) ?: return false
    val ok = checkTermAgainst(elaborated, expectedType, locals)
    if (ok) {
        inferredTypes[term] = expected
    }
    return ok
}

internal fun TypeChecker.elaborateCaseTerm(term: Term.Case, expectedType: Term, locals: Map<String, Term>): Term? {
    val scrutineeType = inferType(term.scrutinee, locals)?.let { whnf(zonk(it)) } ?: return null
    val (scrutineeHead, scrutineeArgs) = decomposeApplicationWithVisibility(scrutineeType)
    val typeName = when (scrutineeHead) {
        is Term.Variable -> scrutineeHead.name
        is Term.Constant -> scrutineeHead.name
        else -> null
    }
    if (typeName == null) {
        report(term.span, "cannot extract parameters from scrutinee type")
        return null
    }

    val registry = document.newtypeRegistries.firstOrNull { it.typeName == typeName }
    if (registry == null) {
        report(term.span, "case scrutinee type is not an inductive")
        return null
    }

    val recursorName = registry.recursor?.name
    if (recursorName.isNullOrBlank()) {
        report(term.span, "case requires a registered recursor")
        return null
    }
    val recursorType = globals[recursorName]?.type
    if (recursorType == null) {
        report(term.span, "case requires a registered recursor")
        return null
    }

    val typeParameters = peelPis(registry.typeSignature).first
    if (scrutineeArgs.size != typeParameters.size) {
        report(term.span, "cannot extract parameters from scrutinee type")
        return null
    }

    val constructorsByName = registry.constructors.associateBy { it.name }
    val branchByConstructor = linkedMapOf<String, Term.CaseBranch>()
    var valid = true

    term.branches.forEach { branch ->
        if (branch.constructorName !in constructorsByName) {
            report(branch.constructorSpan, "unknown constructor ${branch.constructorName} for type $typeName")
            valid = false
            return@forEach
        }
        if (branchByConstructor.put(branch.constructorName, branch) != null) {
            report(branch.constructorSpan, "duplicate case for constructor ${branch.constructorName}")
            valid = false
        }
    }

    registry.constructors.forEach { constructor ->
        if (constructor.name !in branchByConstructor) {
            report(term.span, "missing case for constructor ${constructor.name}")
            valid = false
        }
    }
    if (!valid) {
        return null
    }

    val motiveParameter = freshName("_caseTarget", expectedType, term.scrutinee)
    val motiveVariable = Term.Variable(motiveParameter, term.span)
    val motiveBody = when (val scrutinee = term.scrutinee) {
        is Term.Variable -> {
            if (scrutinee.name in locals) {
                substitute(expectedType, scrutinee.name, motiveVariable)
            } else {
                replaceSubterm(expectedType, scrutinee, motiveVariable)
            }
        }

        else -> replaceSubterm(expectedType, scrutinee, motiveVariable)
    }
    val motive = Term.Lambda(
        parameter = motiveParameter,
        parameterType = scrutineeType,
        body = motiveBody,
        parameterSpan = term.span,
        visibility = Term.Visibility.EXPLICIT,
    )

    val caseFunctions = mutableListOf<Term>()
    registry.constructors.forEach { constructor ->
        val branch = branchByConstructor.getValue(constructor.name)
        val constructorSignature = peelPis(constructor.type)
        val constructorArgs = constructorSignature.first
        val constructorResult = constructorSignature.second

        if (constructorArgs.size < typeParameters.size) {
            report(branch.constructorSpan, "wrong number of arguments in pattern for constructor ${constructor.name}")
            valid = false
            return@forEach
        }

        val constructorParameters = constructorArgs.take(typeParameters.size)
        val constructorDataArgs = constructorArgs.drop(typeParameters.size)
        if (branch.parameters.size != constructorDataArgs.size) {
            report(branch.constructorSpan, "wrong number of arguments in pattern for constructor ${constructor.name}")
            valid = false
            return@forEach
        }

        val parameterSubstitution = linkedMapOf<String, Term>()
        constructorParameters.zip(typeParameters).forEachIndexed { index, (ctorParam, typeParam) ->
            val scrutineeParameter = scrutineeArgs[index]
            val expectedParamType = applySubstitutions(typeParam.type, parameterSubstitution)
            val actualParamType = applySubstitutions(ctorParam.type, parameterSubstitution)
            if (!convertibleInContext(actualParamType, expectedParamType, locals)) {
                report(branch.constructorSpan, "cannot extract parameters from scrutinee type")
                valid = false
            }
            parameterSubstitution[ctorParam.name] = scrutineeParameter.term
        }
        if (!valid) {
            return@forEach
        }

        if (!matchesTypeApplication(constructorResult, typeName, scrutineeArgs, parameterSubstitution, locals)) {
            report(branch.constructorSpan, "cannot extract parameters from scrutinee type")
            valid = false
            return@forEach
        }

        val branchContextSubstitution = linkedMapOf<String, Term>()
        branchContextSubstitution.putAll(parameterSubstitution)
        val branchLocals = locals.toMutableMap()
        val branchArgVars = mutableListOf<Term.Variable>()

        constructorDataArgs.forEachIndexed { index, dataArg ->
            val parameter = branch.parameters[index]
            val rawName = parameter.name
            val name = if (rawName == "_") "_case_${constructor.name}_$index" else rawName
            if (name == "Type") {
                report(parameter.span, "'Type' is reserved")
                valid = false
                return@forEachIndexed
            }

            val argType = applySubstitutions(dataArg.type, branchContextSubstitution)
            branchLocals[name] = argType
            val variable = Term.Variable(name, parameter.span)
            branchArgVars += variable
            branchContextSubstitution[dataArg.name] = variable
        }
        if (!valid) {
            return@forEach
        }

        var constructorTerm: Term = Term.Variable(constructor.name, branch.constructorSpan)
        constructorParameters.forEachIndexed { index, constructorParameter ->
            constructorTerm = Term.Application(
                function = constructorTerm,
                argument = scrutineeArgs[index].term,
                visibility = constructorParameter.visibility,
            )
        }
        constructorDataArgs.forEachIndexed { index, constructorArgument ->
            constructorTerm = Term.Application(
                function = constructorTerm,
                argument = branchArgVars[index],
                visibility = constructorArgument.visibility,
            )
        }
        val branchExpectedType = reduceCaseRedexes(whnf(Term.Application(motive, constructorTerm, Term.Visibility.EXPLICIT)))

        if (!checkTermAgainst(branch.body, branchExpectedType, branchLocals)) {
            valid = false
            return@forEach
        }

        var caseBody: Term = branch.body
        for (index in constructorDataArgs.indices.reversed()) {
            val dataArg = constructorDataArgs[index]
            val variable = branchArgVars[index]
            val argType = applySubstitutions(dataArg.type, branchContextSubstitution)

            if (matchesTypeApplication(argType, typeName, scrutineeArgs, emptyMap(), locals)) {
                val ihType = Term.Application(motive, variable, Term.Visibility.EXPLICIT)
                caseBody = Term.Lambda(
                    parameter = "_ih$index",
                    parameterType = ihType,
                    body = caseBody,
                    parameterSpan = variable.span,
                    visibility = Term.Visibility.EXPLICIT,
                )
            }

            caseBody = Term.Lambda(
                parameter = variable.name,
                parameterType = argType,
                body = caseBody,
                parameterSpan = variable.span,
                visibility = Term.Visibility.EXPLICIT,
            )
        }

        caseFunctions += caseBody
    }

    if (!valid) {
        return null
    }

    var elaborated: Term = Term.Variable(recursorName, term.span)
    val recursorParameters = peelPis(recursorType).first.take(typeParameters.size)
    if (recursorParameters.size != typeParameters.size) {
        report(term.span, "cannot extract parameters from scrutinee type")
        return null
    }
    scrutineeArgs.forEachIndexed { index, arg ->
        elaborated = Term.Application(elaborated, arg.term, recursorParameters[index].visibility)
    }
    elaborated = Term.Application(elaborated, motive, Term.Visibility.EXPLICIT)
    caseFunctions.forEach { caseFunction ->
        elaborated = Term.Application(elaborated, caseFunction, Term.Visibility.EXPLICIT)
    }
    elaborated = Term.Application(elaborated, term.scrutinee, Term.Visibility.EXPLICIT)
    return elaborated
}

internal fun TypeChecker.reduceCaseRedexes(term: Term): Term {
    return when (term) {
        is Term.Meta,
        is Term.Variable,
        is Term.Constant,
        -> term

        is Term.Typed -> Term.Typed(reduceCaseRedexes(term.term), reduceCaseRedexes(term.type))
        is Term.Application -> Term.Application(
            function = reduceCaseRedexes(term.function),
            argument = reduceCaseRedexes(term.argument),
            visibility = term.visibility,
        )

        is Term.Lambda -> term.copy(
            parameterType = reduceCaseRedexes(term.parameterType),
            body = reduceCaseRedexes(term.body),
        )

        is Term.Pi -> term.copy(
            parameterType = reduceCaseRedexes(term.parameterType),
            body = reduceCaseRedexes(term.body),
        )

        is Term.Case -> {
            val reducedScrutinee = reduceCaseRedexes(term.scrutinee)
            val candidate = term.copy(scrutinee = reducedScrutinee)
            val reduced = reduceCaseAtRoot(candidate)
            if (reduced != null) {
                reduceCaseRedexes(reduced)
            } else {
                candidate.copy(branches = candidate.branches.map { it.copy(body = reduceCaseRedexes(it.body)) })
            }
        }
    }
}

internal fun TypeChecker.applySubstitutions(type: Term, substitutions: Map<String, Term>): Term {
    if (substitutions.isEmpty()) {
        return type
    }

    val tempNames = linkedMapOf<String, String>()
    val usedNames = substitutions.keys.toMutableSet()
    usedNames += freeVariables(type)
    substitutions.values.forEach { usedNames += freeVariables(it) }

    substitutions.keys.forEach { name ->
        var candidate = "_case_subst_$name"
        var index = 0
        while (candidate in usedNames || candidate in globals) {
            candidate = "_case_subst_${name}_${index++}"
        }
        usedNames += candidate
        tempNames[name] = candidate
    }

    var result = type
    tempNames.forEach { (name, tempName) ->
        result = substitute(result, name, Term.Variable(tempName, spanOf(type) ?: TextSpan(0, 0)))
    }
    tempNames.forEach { (name, tempName) ->
        result = substitute(result, tempName, substitutions.getValue(name))
    }
    return result
}

internal fun TypeChecker.replaceSubterm(term: Term, target: Term, replacement: Term): Term {
    if (alphaEquivalent(term, target)) {
        return replacement
    }
    return when (term) {
        is Term.Meta,
        is Term.Variable,
        is Term.Constant,
        -> term

        is Term.Typed -> Term.Typed(
            term = replaceSubterm(term.term, target, replacement),
            type = replaceSubterm(term.type, target, replacement),
        )

        is Term.Application -> Term.Application(
            function = replaceSubterm(term.function, target, replacement),
            argument = replaceSubterm(term.argument, target, replacement),
            visibility = term.visibility,
        )

        is Term.Lambda -> {
            val newParameterType = replaceSubterm(term.parameterType, target, replacement)
            val replacementVars = freeVariables(replacement)
            if (term.parameter in replacementVars) {
                val fresh = freshName(term.parameter, term.body, replacement, target)
                val renamedBody = substitute(term.body, term.parameter, Term.Variable(fresh, term.parameterSpan))
                Term.Lambda(
                    parameter = fresh,
                    parameterType = newParameterType,
                    body = replaceSubterm(renamedBody, target, replacement),
                    parameterSpan = term.parameterSpan,
                    visibility = term.visibility,
                )
            } else {
                term.copy(
                    parameterType = newParameterType,
                    body = replaceSubterm(term.body, target, replacement),
                )
            }
        }

        is Term.Pi -> {
            val newParameterType = replaceSubterm(term.parameterType, target, replacement)
            val replacementVars = freeVariables(replacement)
            if (term.parameter in replacementVars) {
                val fresh = freshName(term.parameter, term.body, replacement, target)
                val renamedBody = substitute(term.body, term.parameter, Term.Variable(fresh, term.parameterSpan))
                Term.Pi(
                    parameter = fresh,
                    parameterType = newParameterType,
                    body = replaceSubterm(renamedBody, target, replacement),
                    parameterSpan = term.parameterSpan,
                    visibility = term.visibility,
                )
            } else {
                term.copy(
                    parameterType = newParameterType,
                    body = replaceSubterm(term.body, target, replacement),
                )
            }
        }

        is Term.Case -> {
            val newScrutinee = replaceSubterm(term.scrutinee, target, replacement)
            val replacementVars = freeVariables(replacement)
            val newBranches = term.branches.map { branch ->
                var nextBody = branch.body
                val renames = linkedMapOf<String, String>()
                branch.parameters.forEach { parameter ->
                    val boundName = parameter.name
                    if (boundName in replacementVars) {
                        val fresh = freshName(boundName, nextBody, replacement, target)
                        renames[boundName] = fresh
                        nextBody = substitute(nextBody, boundName, Term.Variable(fresh, parameter.span))
                    }
                }
                val updatedParameters = branch.parameters.map { parameter ->
                    val fresh = renames[parameter.name]
                    if (fresh == null) parameter else parameter.copy(name = fresh)
                }
                branch.copy(parameters = updatedParameters, body = replaceSubterm(nextBody, target, replacement))
            }
            term.copy(scrutinee = newScrutinee, branches = newBranches)
        }
    }
}

internal fun TypeChecker.matchesTypeApplication(
    term: Term,
    typeName: String,
    expectedArgs: List<AppliedArg>,
    substitutions: Map<String, Term>,
    locals: Map<String, Term>,
): Boolean {
    val normalized = whnf(zonk(applySubstitutions(term, substitutions)))
    val (head, args) = decomposeApplicationWithVisibility(normalized)
    val headName = when (head) {
        is Term.Variable -> head.name
        is Term.Constant -> head.name
        else -> null
    }
    if (headName != typeName || args.size != expectedArgs.size) {
        return false
    }
    return args.zip(expectedArgs).all { (actual, expected) ->
        convertibleInContext(actual.term, expected.term, locals)
    }
}

internal fun peelPis(type: Term): Pair<List<CaseBinder>, Term> {
    val binders = mutableListOf<CaseBinder>()
    var current = type
    var index = 0
    while (current is Term.Pi) {
        val name = current.parameter
            .takeIf { it.isNotBlank() && it != "_" }
            ?: "arg$index"
        binders += CaseBinder(name, current.parameterType, current.visibility, current.parameterSpan)
        current = current.body
        index += 1
    }
    return binders to current
}

internal data class CaseBinder(
    val name: String,
    val type: Term,
    val visibility: Term.Visibility,
    val span: TextSpan,
)
