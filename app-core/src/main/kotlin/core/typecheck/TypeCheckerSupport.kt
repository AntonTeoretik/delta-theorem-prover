package core.typecheck

import core.model.Diagnostic
import core.model.Term
import core.model.TextSpan
import core.model.TypeHint

internal fun TypeChecker.spanOf(term: Term): TextSpan? {
    return when (term) {
        is Term.Variable -> term.span
        is Term.Constant -> term.span
        is Term.Meta -> term.span
        is Term.Lambda -> term.parameterSpan
        is Term.Pi -> term.parameterSpan
        is Term.Typed -> spanOf(term.term) ?: spanOf(term.type)
        is Term.Application -> spanOf(term.function) ?: spanOf(term.argument)
        is Term.Case -> term.span
    }
}

internal fun TypeChecker.report(span: TextSpan?, message: String) {
    if (suppressedDiagnosticsDepth > 0) {
        return
    }
    val (line, column) = offsetToLineColumn(span?.startOffset ?: 0)
    diagnostics += Diagnostic(
        message = message,
        line = line,
        column = column,
        startOffset = span?.startOffset,
        endOffset = span?.endOffset,
    )
}

internal inline fun <T> TypeChecker.withSuppressedDiagnostics(block: () -> T): T {
    suppressedDiagnosticsDepth += 1
    return try {
        block()
    } finally {
        suppressedDiagnosticsDepth -= 1
    }
}

internal fun TypeChecker.traceStep(message: String) {
    val sink = traceSink ?: return
    if (sink.size >= TypeChecker.TRACE_STEP_LIMIT) {
        if (sink.lastOrNull() != "... trace truncated ...") {
            sink += "... trace truncated ..."
        }
        return
    }
    sink += message
}

internal fun TypeChecker.offsetToLineColumn(offset: Int): Pair<Int, Int> {
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

internal fun TypeChecker.addTypeHint(span: TextSpan, inferredType: Term) {
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

internal fun decomposeApplication(term: Term): Pair<Term, List<Term>> {
    val arguments = mutableListOf<Term>()
    var current = term
    while (current is Term.Application) {
        arguments.add(current.argument)
        current = current.function
    }
    arguments.reverse()
    return current to arguments
}
