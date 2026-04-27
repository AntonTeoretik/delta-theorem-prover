package core.typecheck

import core.model.Definition
import core.model.EvaluationStep
import core.model.EvaluationTrace
import core.model.Term
import core.model.TextSpan

internal fun TypeChecker.whnf(term: Term, unfolding: MutableSet<String> = linkedSetOf()): Term {
    return when (term) {
        is Term.Meta -> term
        is Term.Lambda -> term
        is Term.Pi -> term
        is Term.Constant -> {
            val impl = globals[term.name]?.implementation
            if (impl == null || !unfolding.add(term.name)) term else {
                val result = whnf(impl, unfolding)
                unfolding.remove(term.name)
                result
            }
        }

        is Term.Variable -> {
            val impl = globals[term.name]?.implementation
            if (impl == null || !unfolding.add(term.name)) term else {
                val result = whnf(impl, unfolding)
                unfolding.remove(term.name)
                result
            }
        }

        is Term.Typed -> whnf(term.term, unfolding)
        is Term.Application -> {
            val fn = whnf(term.function, unfolding)
            if (fn is Term.Lambda) {
                if (fn.visibility == term.visibility) {
                    whnf(substitute(fn.body, fn.parameter, term.argument), unfolding)
                } else if (fn.visibility == Term.Visibility.IMPLICIT && term.visibility == Term.Visibility.EXPLICIT) {
                    val placeholder = freshRewritePlaceholderMeta(spanOf(term.argument))
                    val afterImplicit = substitute(fn.body, fn.parameter, placeholder)
                    whnf(Term.Application(afterImplicit, term.argument, Term.Visibility.EXPLICIT), unfolding)
                } else {
                    val candidate = Term.Application(fn, term.argument, term.visibility)
                    val rewrite = rewriteAtRoot(candidate)
                    if (rewrite != null) {
                        traceStep("whnf rewrite: ${rewrite.ruleName}")
                        whnf(rewrite.term, unfolding)
                    } else candidate
                }
            } else {
                val candidate = Term.Application(fn, term.argument, term.visibility)
                val rewrite = rewriteAtRoot(candidate)
                if (rewrite != null) {
                    traceStep("whnf rewrite: ${rewrite.ruleName}")
                    whnf(rewrite.term, unfolding)
                } else candidate
            }
        }
    }
}

internal fun TypeChecker.normalize(term: Term, unfolding: MutableSet<String> = linkedSetOf()): Term {
    return when (term) {
        is Term.Meta -> term
        is Term.Constant -> {
            val impl = globals[term.name]?.implementation
            if (impl == null || !unfolding.add(term.name)) term else {
                val result = normalize(impl, unfolding)
                unfolding.remove(term.name)
                result
            }
        }

        is Term.Variable -> {
            val impl = globals[term.name]?.implementation
            if (impl == null || !unfolding.add(term.name)) term else {
                val result = normalize(impl, unfolding)
                unfolding.remove(term.name)
                result
            }
        }

        is Term.Typed -> normalize(term.term, unfolding)
        is Term.Pi -> term.copy(
            parameterType = normalize(term.parameterType, unfolding),
            body = normalize(term.body, unfolding),
        )

        is Term.Lambda -> term.copy(
            parameterType = normalize(term.parameterType, unfolding),
            body = normalize(term.body, unfolding),
        )

        is Term.Application -> {
            val fn = normalize(term.function, unfolding)
            val arg = normalize(term.argument, unfolding)
            if (fn is Term.Lambda) {
                if (fn.visibility == term.visibility) {
                    normalize(substitute(fn.body, fn.parameter, arg), unfolding)
                } else if (fn.visibility == Term.Visibility.IMPLICIT && term.visibility == Term.Visibility.EXPLICIT) {
                    val placeholder = freshRewritePlaceholderMeta(spanOf(arg))
                    val afterImplicit = substitute(fn.body, fn.parameter, placeholder)
                    normalize(Term.Application(afterImplicit, arg, Term.Visibility.EXPLICIT), unfolding)
                } else {
                    applyRewriteRules(Term.Application(fn, arg, term.visibility), unfolding)
                }
            } else {
                applyRewriteRules(Term.Application(fn, arg, term.visibility), unfolding)
            }
        }
    }
}

internal fun TypeChecker.applyRewriteRules(term: Term, unfolding: MutableSet<String>): Term {
    var current = term
    while (true) {
        val rewrite = rewriteAtRoot(current) ?: return current
        traceStep("normalize rewrite: ${rewrite.ruleName}")
        current = normalize(rewrite.term, unfolding)
    }
}

internal fun TypeChecker.buildEvaluationTrace(definition: Definition): EvaluationTrace? {
    val implementation = definition.implementation ?: return null
    val startOffset = definition.nameSpan?.startOffset ?: 0
    val line = offsetToLineColumn(startOffset).first
    val steps = mutableListOf<EvaluationStep>()
    var current = implementation
    var counter = 0
    while (counter < TypeChecker.EVALUATION_STEP_LIMIT) {
        val outcome = reduceOneStep(current) ?: break
        steps += EvaluationStep(reason = outcome.reason, from = pretty(current), to = pretty(outcome.term))
        current = outcome.term
        counter += 1
    }
    return EvaluationTrace(title = definition.name, line = line, steps = steps)
}

internal fun TypeChecker.reduceOneStep(term: Term, unfolding: MutableSet<String> = linkedSetOf()): ReductionOutcome? {
    return when (term) {
        is Term.Meta, is Term.Constant -> {
            val name = (term as? Term.Constant)?.name
            if (name != null) {
                val impl = globals[name]?.implementation
                if (impl != null && unfolding.add(name)) ReductionOutcome(impl, "unfold $name") else null
            } else null
        }

        is Term.Variable -> {
            val impl = globals[term.name]?.implementation
            if (impl != null && unfolding.add(term.name)) ReductionOutcome(impl, "unfold ${term.name}") else null
        }

        is Term.Typed -> {
            val reducedTerm = reduceOneStep(term.term, unfolding)
            if (reducedTerm != null) ReductionOutcome(Term.Typed(reducedTerm.term, term.type), "in annotation: ${reducedTerm.reason}")
            else {
                val reducedType = reduceOneStep(term.type, unfolding) ?: return null
                ReductionOutcome(Term.Typed(term.term, reducedType.term), "in annotation type: ${reducedType.reason}")
            }
        }

        is Term.Pi -> {
            val reducedType = reduceOneStep(term.parameterType, unfolding)
            if (reducedType != null) ReductionOutcome(term.copy(parameterType = reducedType.term), "in Pi domain: ${reducedType.reason}")
            else {
                val reducedBody = reduceOneStep(term.body, unfolding) ?: return null
                ReductionOutcome(term.copy(body = reducedBody.term), "in Pi body: ${reducedBody.reason}")
            }
        }

        is Term.Lambda -> {
            val reducedType = reduceOneStep(term.parameterType, unfolding)
            if (reducedType != null) ReductionOutcome(term.copy(parameterType = reducedType.term), "in lambda domain: ${reducedType.reason}")
            else {
                val reducedBody = reduceOneStep(term.body, unfolding) ?: return null
                ReductionOutcome(term.copy(body = reducedBody.term), "in lambda body: ${reducedBody.reason}")
            }
        }

        is Term.Application -> {
            val reducedFunction = reduceOneStep(term.function, unfolding)
            if (reducedFunction != null) return ReductionOutcome(Term.Application(reducedFunction.term, term.argument, term.visibility), "in function: ${reducedFunction.reason}")
            if (term.function is Term.Lambda && term.function.visibility == term.visibility) {
                return ReductionOutcome(substitute(term.function.body, term.function.parameter, term.argument), "beta")
            }
            val rewrite = rewriteAtRoot(term)
            if (rewrite != null) return ReductionOutcome(rewrite.term, "rewrite ${rewrite.ruleName}")
            val reducedArgument = reduceOneStep(term.argument, unfolding)
            if (reducedArgument != null) return ReductionOutcome(Term.Application(term.function, reducedArgument.term, term.visibility), "in argument: ${reducedArgument.reason}")
            null
        }
    }
}

internal fun TypeChecker.rewriteAtRoot(term: Term): RewriteOutcome? {
    val (head, _) = decomposeApplication(term)
    val headName = when (head) {
        is Term.Variable -> head.name
        is Term.Constant -> head.name
        else -> return null
    }
    val rules = rewriteRulesByHead[headName] ?: return null
    val alignedTarget = canonicalizeRewriteTerm(alignRewriteApplicationForHead(term, headName))
    rules.forEach { rule ->
        val substitutions = linkedMapOf<String, Term>()
        val alignedPattern = canonicalizeRewriteTerm(alignRewriteApplicationForHead(rule.lhs, headName))
        if (matchRewritePattern(alignedPattern, alignedTarget, rule.patternVariables, substitutions)) {
            return RewriteOutcome(ruleName = rule.name, term = instantiateRuleRhs(rule.rhs, substitutions))
        }
    }
    return null
}

internal fun TypeChecker.alignRewriteApplicationForHead(term: Term, headName: String): Term {
    val headType = globals[headName]?.type ?: return term
    val (head, args) = decomposeApplicationWithVisibility(term)
    var currentType: Term = whnf(zonk(headType))
    val alignedArgs = mutableListOf<AppliedArg>()
    args.forEach { arg ->
        while (currentType is Term.Pi && (currentType as Term.Pi).visibility == Term.Visibility.IMPLICIT && arg.visibility == Term.Visibility.EXPLICIT) {
            val inferredFromExplicit = withSuppressedDiagnostics { inferType(arg.term, emptyMap())?.let { zonk(it) } }
            val implicitArgument = inferredFromExplicit ?: freshRewritePlaceholderMeta(spanOf(arg.term))
            alignedArgs += AppliedArg(implicitArgument, Term.Visibility.IMPLICIT)
            currentType = whnf(zonk(substitute((currentType as Term.Pi).body, (currentType as Term.Pi).parameter, implicitArgument)))
        }
        if (currentType is Term.Pi) {
            alignedArgs += arg
            currentType = whnf(zonk(substitute((currentType as Term.Pi).body, (currentType as Term.Pi).parameter, arg.term)))
        } else alignedArgs += arg
    }
    return alignedArgs.fold(head) { acc, arg -> Term.Application(acc, arg.term, arg.visibility) }
}

internal fun decomposeApplicationWithVisibility(term: Term): Pair<Term, List<AppliedArg>> {
    val args = mutableListOf<AppliedArg>()
    var current = term
    while (current is Term.Application) {
        args.add(AppliedArg(current.argument, current.visibility))
        current = current.function
    }
    args.reverse()
    return current to args
}

internal fun TypeChecker.freshRewritePlaceholderMeta(span: TextSpan?): Term.Meta {
    val safeSpan = span ?: TextSpan(0, 0)
    return Term.Meta(nextRewritePlaceholderMetaId--, safeSpan)
}

internal fun TypeChecker.canonicalizeRewriteTerm(term: Term): Term {
    return when (term) {
        is Term.Meta, is Term.Variable, is Term.Constant -> term
        is Term.Typed -> Term.Typed(canonicalizeRewriteTerm(term.term), canonicalizeRewriteTerm(term.type))
        is Term.Lambda -> term.copy(parameterType = canonicalizeRewriteTerm(term.parameterType), body = canonicalizeRewriteTerm(term.body))
        is Term.Pi -> term.copy(parameterType = canonicalizeRewriteTerm(term.parameterType), body = canonicalizeRewriteTerm(term.body))
        is Term.Application -> {
            val rebuilt = Term.Application(canonicalizeRewriteTerm(term.function), canonicalizeRewriteTerm(term.argument), term.visibility)
            val (head, _) = decomposeApplication(rebuilt)
            val headName = when (head) {
                is Term.Variable -> head.name
                is Term.Constant -> head.name
                else -> null
            }
            if (headName != null && globals.containsKey(headName)) alignRewriteApplicationForHead(rebuilt, headName) else rebuilt
        }
    }
}

internal fun TypeChecker.matchRewritePattern(
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
        pattern is Term.Application && target is Term.Application ->
            pattern.visibility == target.visibility &&
                matchRewritePattern(pattern.function, target.function, patternVariables, substitutions) &&
                matchRewritePattern(pattern.argument, target.argument, patternVariables, substitutions)
        pattern is Term.Typed && target is Term.Typed ->
            matchRewritePattern(pattern.term, target.term, patternVariables, substitutions) &&
                matchRewritePattern(pattern.type, target.type, patternVariables, substitutions)
        pattern is Term.Lambda && target is Term.Lambda ->
            pattern.visibility == target.visibility &&
                pattern.parameter == target.parameter &&
                matchRewritePattern(pattern.parameterType, target.parameterType, patternVariables, substitutions) &&
                matchRewritePattern(pattern.body, target.body, patternVariables, substitutions)
        pattern is Term.Pi && target is Term.Pi ->
            pattern.visibility == target.visibility &&
                pattern.parameter == target.parameter &&
                matchRewritePattern(pattern.parameterType, target.parameterType, patternVariables, substitutions) &&
                matchRewritePattern(pattern.body, target.body, patternVariables, substitutions)
        else -> false
    }
}

internal fun TypeChecker.instantiateRuleRhs(rhs: Term, substitutions: Map<String, Term>): Term {
    return applySimultaneousSubstitution(rhs, substitutions)
}

internal fun TypeChecker.applySimultaneousSubstitution(term: Term, substitutions: Map<String, Term>): Term {
    return when (term) {
        is Term.Meta -> term
        is Term.Constant -> term
        is Term.Variable -> substitutions[term.name] ?: term
        is Term.Typed -> Term.Typed(applySimultaneousSubstitution(term.term, substitutions), applySimultaneousSubstitution(term.type, substitutions))
        is Term.Application -> Term.Application(
            function = applySimultaneousSubstitution(term.function, substitutions),
            argument = applySimultaneousSubstitution(term.argument, substitutions),
            visibility = term.visibility,
        )
        is Term.Lambda -> {
            val next = substitutions - term.parameter
            term.copy(
                parameterType = applySimultaneousSubstitution(term.parameterType, substitutions),
                body = applySimultaneousSubstitution(term.body, next),
            )
        }
        is Term.Pi -> {
            val next = substitutions - term.parameter
            term.copy(
                parameterType = applySimultaneousSubstitution(term.parameterType, substitutions),
                body = applySimultaneousSubstitution(term.body, next),
            )
        }
    }
}
