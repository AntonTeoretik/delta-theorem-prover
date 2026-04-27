package core.typecheck

import core.model.Term

internal fun TypeChecker.checkCaseTermAgainst(term: Term.Case, expectedType: Term, locals: Map<String, Term>): Boolean {
    val scrutineeType = inferType(term.scrutinee, locals)?.let { whnf(zonk(it)) } ?: return false
    val (scrutineeHead, scrutineeArgs) = decomposeApplication(scrutineeType)
    val typeName = when (scrutineeHead) {
        is Term.Variable -> scrutineeHead.name
        is Term.Constant -> scrutineeHead.name
        else -> null
    }
    if (typeName == null) {
        report(term.span, "case scrutinee must have inductive/newtype type")
        return false
    }
    if (scrutineeArgs.isNotEmpty()) {
        report(term.span, "case is supported only for non-parameterized, non-indexed types")
        return false
    }

    val registry = document.newtypeRegistries.firstOrNull { it.typeName == typeName }
    if (registry == null) {
        report(term.span, "no inductive/newtype registry found for '$typeName'")
        return false
    }
    val recursorName = registry.recursor?.name
    if (recursorName.isNullOrBlank()) {
        report(term.span, "registry '$typeName' does not define a recursor")
        return false
    }
    if (recursorName !in globals) {
        report(term.span, "recursor '$recursorName' is not available in scope")
        return false
    }

    val constructorsByName = registry.constructors.associateBy { it.name }
    val branchByConstructor = linkedMapOf<String, Term.CaseBranch>()
    var valid = true

    term.branches.forEach { branch ->
        if (branch.constructorName !in constructorsByName) {
            report(branch.constructorSpan, "unknown constructor '${branch.constructorName}' in case branch")
            valid = false
            return@forEach
        }
        if (branchByConstructor.put(branch.constructorName, branch) != null) {
            report(branch.constructorSpan, "duplicate case branch for constructor '${branch.constructorName}'")
            valid = false
        }
    }

    val missingConstructors = constructorsByName.keys - branchByConstructor.keys
    if (missingConstructors.isNotEmpty()) {
        report(term.span, "missing case branches for: ${missingConstructors.sorted().joinToString(", ")}")
        valid = false
    }
    if (term.branches.size != constructorsByName.size) {
        report(term.span, "case branches must match constructors exactly")
        valid = false
    }
    if (!valid) {
        return false
    }

    val expected = zonk(expectedType)
    val motiveParameterName = "caseScrutinee"
    val motiveParameter = Term.Variable(motiveParameterName, term.span)
    val motiveBody = when (val scrutinee = term.scrutinee) {
        is Term.Variable -> {
            if (scrutinee.name in locals) {
                substitute(expected, scrutinee.name, motiveParameter)
            } else {
                expected
            }
        }

        else -> expected
    }
    val motive = Term.Lambda(
        parameter = motiveParameterName,
        parameterType = scrutineeType,
        body = motiveBody,
        parameterSpan = term.span,
        visibility = Term.Visibility.EXPLICIT,
    )

    val caseArguments = mutableListOf<Term>()
    registry.constructors.forEach { constructor ->
        val branch = branchByConstructor.getValue(constructor.name)
        val constructorSignature = decomposeConstructorArguments(constructor.type)
        val constructorArgs = constructorSignature.arguments
        if (constructorArgs.any { it.visibility == Term.Visibility.IMPLICIT }) {
            report(branch.constructorSpan, "case does not support constructors with implicit arguments")
            valid = false
            return@forEach
        }

        if (!isExactlyHeadTypeName(constructorSignature.resultType, typeName)) {
            report(branch.constructorSpan, "constructor '${constructor.name}' is not supported in case elaboration")
            valid = false
            return@forEach
        }

        if (branch.parameters.size != constructorArgs.size) {
            report(
                branch.constructorSpan,
                "constructor '${constructor.name}' expects ${constructorArgs.size} pattern arguments, got ${branch.parameters.size}",
            )
            valid = false
            return@forEach
        }

        val argumentTerms = mutableMapOf<String, Term>()
        val branchLocals = locals.toMutableMap()
        constructorArgs.forEachIndexed { index, argument ->
            val branchParameter = branch.parameters[index]
            if (branchParameter.name == "Type") {
                report(branchParameter.span, "'Type' is reserved")
                valid = false
                return@forEachIndexed
            }
            val argumentType = applyConstructorSubstitution(argument.type, argumentTerms)
            val branchVariable = Term.Variable(branchParameter.name, branchParameter.span)
            argumentTerms[argument.name] = branchVariable
            branchLocals[branchParameter.name] = argumentType
        }
        if (!valid) {
            return@forEach
        }

        var constructorTerm: Term = Term.Variable(constructor.name, branch.constructorSpan)
        constructorArgs.forEachIndexed { index, _ ->
            val branchParameter = branch.parameters[index]
            constructorTerm = Term.Application(
                function = constructorTerm,
                argument = Term.Variable(branchParameter.name, branchParameter.span),
                visibility = Term.Visibility.EXPLICIT,
            )
        }
        val branchExpectedType = whnf(
            Term.Application(
                function = motive,
                argument = constructorTerm,
                visibility = Term.Visibility.EXPLICIT,
            ),
        )

        if (!checkTermAgainst(branch.body, branchExpectedType, branchLocals)) {
            valid = false
            return@forEach
        }

        var caseBody: Term = branch.body
        constructorArgs.indices.reversed().forEach { index ->
            val argument = constructorArgs[index]
            val branchParameter = branch.parameters[index]
            val argumentType = applyConstructorSubstitution(argument.type, argumentTerms)
            if (isExactlyHeadTypeName(argumentType, typeName)) {
                val ihName = "ih$index"
                val ihType = Term.Application(motive, Term.Variable(branchParameter.name, branchParameter.span), Term.Visibility.EXPLICIT)
                caseBody = Term.Lambda(
                    parameter = ihName,
                    parameterType = ihType,
                    body = caseBody,
                    parameterSpan = branchParameter.span,
                    visibility = Term.Visibility.EXPLICIT,
                )
            }

            caseBody = Term.Lambda(
                parameter = branchParameter.name,
                parameterType = argumentType,
                body = caseBody,
                parameterSpan = branchParameter.span,
                visibility = Term.Visibility.EXPLICIT,
            )
        }

        caseArguments += caseBody
    }

    if (!valid) {
        return false
    }

    var elaborated: Term = Term.Variable(recursorName, term.span)
    elaborated = Term.Application(elaborated, motive, Term.Visibility.EXPLICIT)
    caseArguments.forEach { caseArgument ->
        elaborated = Term.Application(elaborated, caseArgument, Term.Visibility.EXPLICIT)
    }
    elaborated = Term.Application(elaborated, term.scrutinee, Term.Visibility.EXPLICIT)

    val ok = checkTermAgainst(elaborated, expected, locals)
    if (ok) {
        inferredTypes[term] = expected
    }
    return ok
}

internal fun TypeChecker.applyConstructorSubstitution(type: Term, substitutions: Map<String, Term>): Term {
    var result = type
    substitutions.forEach { (name, value) ->
        result = substitute(result, name, value)
    }
    return result
}

internal fun TypeChecker.isExactlyHeadTypeName(term: Term, expectedName: String): Boolean {
    val normalized = whnf(zonk(term))
    val (head, args) = decomposeApplication(normalized)
    if (args.isNotEmpty()) {
        return false
    }
    return when (head) {
        is Term.Variable -> head.name == expectedName
        is Term.Constant -> head.name == expectedName
        else -> false
    }
}

internal fun TypeChecker.decomposeConstructorArguments(type: Term): ConstructorSignature {
    val arguments = mutableListOf<ConstructorArgument>()
    var current = whnf(zonk(type))
    var index = 0
    while (current is Term.Pi) {
        val argumentName = current.parameter
            .takeIf { it.isNotBlank() && it != "_" }
            ?: "arg$index"
        arguments += ConstructorArgument(
            name = argumentName,
            type = current.parameterType,
            visibility = current.visibility,
        )
        current = whnf(zonk(current.body))
        index += 1
    }
    return ConstructorSignature(arguments, current)
}

internal data class ConstructorSignature(
    val arguments: List<ConstructorArgument>,
    val resultType: Term,
)

internal data class ConstructorArgument(
    val name: String,
    val type: Term,
    val visibility: Term.Visibility,
)
