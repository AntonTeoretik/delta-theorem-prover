package core.parser

import core.model.Diagnostic
import core.model.InfixAssociativity
import core.model.Term
import core.model.TextSpan

internal class TermExpressionParser(private val parser: TermSyntaxParser) {
    fun parseExpression(diagnostics: MutableList<Diagnostic>, minPrecedence: Int = 0): Term {
        var expression = when {
            parser.match(TokenType.LAMBDA) -> parseLambdaExpression(diagnostics)
            parser.match(TokenType.FORALL) -> parseForallExpression(diagnostics)
            isKeyword(parser.peek(), "case") -> parseCaseExpression(diagnostics)
            else -> parseApplication(diagnostics)
        }

        while (true) {
            val candidate = peekInfixCandidate()
            if (candidate == null) {
                if (parser.check(TokenType.BACKTICK)) {
                    recoverMalformedBacktick(diagnostics)
                    continue
                }
                break
            }

            if (candidate.precedence < minPrecedence) {
                break
            }

            repeat(candidate.tokenCount) { parser.advance() }
            val rhsMinPrecedence = if (candidate.associativity == InfixAssociativity.LEFT) {
                candidate.precedence + 1
            } else {
                candidate.precedence
            }
            val rhs = parseExpression(diagnostics, rhsMinPrecedence)
            expression = makeInfixApplication(candidate, expression, rhs)
        }

        return expression
    }

    private fun parseLambdaExpression(diagnostics: MutableList<Diagnostic>): Term {
        val parameters = if (isGroupedLambdaParameterListStart()) {
            parseLambdaParameterGroup(diagnostics)
        } else {
            val list = mutableListOf<LambdaParameter>()
            list += parseLambdaParameter(diagnostics)
            while (parser.match(TokenType.COMMA)) {
                list += parseLambdaParameter(diagnostics)
            }
            list
        }

        parser.consume(TokenType.FAT_ARROW, "Expected '=>' after lambda parameter", diagnostics)
        val body = parseExpression(diagnostics)

        return parameters.asReversed().fold(body) { acc, parameter ->
            val parameterType = parameter.type ?: parser.freshMeta(parameter.span)
            Term.Lambda(parameter.name, parameterType, acc, parameter.span, parameter.visibility)
        }
    }

    private fun parseForallExpression(diagnostics: MutableList<Diagnostic>): Term {
        val parameters = if (isGroupedBinderListStart(TokenType.FAT_ARROW)) {
            parseBinderParameterGroup(diagnostics, "forall")
        } else {
            val list = mutableListOf<LambdaParameter>()
            list += parseForallParameter(diagnostics)
            while (parser.match(TokenType.COMMA)) {
                list += parseForallParameter(diagnostics)
            }
            list
        }

        parser.consume(TokenType.FAT_ARROW, "Expected '=>' after forall parameter", diagnostics)
        val body = parseExpression(diagnostics)

        return parameters.asReversed().fold(body) { acc, parameter ->
            val parameterType = parameter.type ?: parser.freshMeta(parameter.span)
            Term.Pi(parameter.name, parameterType, acc, parameter.span, parameter.visibility)
        }
    }

    private fun isGroupedLambdaParameterListStart(): Boolean = isGroupedBinderListStart(TokenType.FAT_ARROW)

    private fun isGroupedBinderListStart(terminator: TokenType): Boolean {
        if (!parser.check(TokenType.LPAREN)) {
            return false
        }

        var depth = 0
        var offset = 0
        while (true) {
            val token = parser.peek(offset)
            when (token.type) {
                TokenType.LPAREN -> depth += 1
                TokenType.RPAREN -> {
                    depth -= 1
                    if (depth == 0) {
                        return parser.peek(offset + 1).type == terminator
                    }
                }

                TokenType.EOF -> return false
                else -> Unit
            }
            offset += 1
        }
    }

    private fun parseLambdaParameterGroup(diagnostics: MutableList<Diagnostic>): MutableList<LambdaParameter> {
        return parseBinderParameterGroup(diagnostics, "lambda")
    }

    private fun parseBinderParameterGroup(
        diagnostics: MutableList<Diagnostic>,
        kind: String,
    ): MutableList<LambdaParameter> {
        val parameters = mutableListOf<LambdaParameter>()
        parser.consume(TokenType.LPAREN, "Expected '(' after $kind", diagnostics)

        if (parser.check(TokenType.RPAREN)) {
            val token = parser.peek()
            diagnostics.add(
                Diagnostic(
                    message = "Expected $kind parameter",
                    line = token.line,
                    column = token.column,
                    startOffset = token.startOffset,
                    endOffset = token.endOffset,
                ),
            )
            parser.advance()
            return parameters
        }

        parameters += parseLambdaParameterInGroup(diagnostics)
        while (parser.match(TokenType.COMMA)) {
            parameters += parseLambdaParameterInGroup(diagnostics)
        }

        parser.consume(TokenType.RPAREN, "Expected ')' after $kind parameters", diagnostics)
        return parameters
    }

    private fun parseForallParameter(diagnostics: MutableList<Diagnostic>): LambdaParameter {
        if (parser.match(TokenType.LBRACE)) {
            val identifier = parser.consume(TokenType.IDENT, "Expected identifier in forall binder", diagnostics)
            val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
            parser.consume(TokenType.RBRACE, "Expected '}' after forall binder", diagnostics)
            return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.IMPLICIT)
        }

        if (parser.match(TokenType.LPAREN)) {
            val identifier = parser.consume(TokenType.IDENT, "Expected identifier in forall binder", diagnostics)
            val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
            parser.consume(TokenType.RPAREN, "Expected ')' after forall binder", diagnostics)
            return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.EXPLICIT)
        }

        val identifier = parser.consume(TokenType.IDENT, "Expected identifier after '∀'", diagnostics)
        val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
        return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.EXPLICIT)
    }

    private fun parseLambdaParameterInGroup(diagnostics: MutableList<Diagnostic>): LambdaParameter {
        if (parser.match(TokenType.LPAREN)) {
            val identifier = parser.consume(TokenType.IDENT, "Expected identifier in lambda binder", diagnostics)
            val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
            parser.consume(TokenType.RPAREN, "Expected ')' after lambda binder", diagnostics)
            return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.EXPLICIT)
        }

        val identifier = parser.consume(TokenType.IDENT, "Expected identifier in lambda parameters", diagnostics)
        val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
        return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.EXPLICIT)
    }

    private fun parseLambdaParameter(diagnostics: MutableList<Diagnostic>): LambdaParameter {
        if (parser.match(TokenType.LBRACE)) {
            val identifier = parser.consume(TokenType.IDENT, "Expected identifier in lambda binder", diagnostics)
            val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
            parser.consume(TokenType.RBRACE, "Expected '}' after lambda binder", diagnostics)
            return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.IMPLICIT)
        }

        if (parser.match(TokenType.LPAREN)) {
            val identifier = parser.consume(TokenType.IDENT, "Expected identifier in lambda binder", diagnostics)
            val parameterType = if (parser.match(TokenType.COLON)) parseExpression(diagnostics) else null
            parser.consume(TokenType.RPAREN, "Expected ')' after lambda binder", diagnostics)
            return binderParameter(identifier, parameterType, diagnostics, Term.Visibility.EXPLICIT)
        }

        val identifier = parser.consume(TokenType.IDENT, "Expected identifier after '\\'", diagnostics)
        return binderParameter(identifier, null, diagnostics, Term.Visibility.EXPLICIT)
    }

    private fun binderParameter(
        identifier: Token,
        parameterType: Term?,
        diagnostics: MutableList<Diagnostic>,
        visibility: Term.Visibility,
    ): LambdaParameter {
        val span = TextSpan(identifier.startOffset, identifier.endOffset)
        val rawName = identifier.text.ifBlank { "_" }
        val name = if (rawName == TermSyntaxParser.RESERVED_LOCAL_NAME) {
            diagnostics += Diagnostic(
                message = "'Type' is reserved",
                line = identifier.line,
                column = identifier.column,
                startOffset = identifier.startOffset,
                endOffset = identifier.endOffset,
            )
            "_"
        } else {
            rawName
        }

        return LambdaParameter(name = name, span = span, type = parameterType, visibility = visibility)
    }

    private fun parseApplication(diagnostics: MutableList<Diagnostic>): Term {
        var expression = parseAtom(diagnostics)

        while (true) {
            if (parser.match(TokenType.LBRACE)) {
                val argument = parseExpression(diagnostics)
                parser.consume(TokenType.RBRACE, "Expected '}' after implicit argument", diagnostics)
                expression = Term.Application(expression, argument, Term.Visibility.IMPLICIT)
                continue
            }

            if (!parser.match(TokenType.LPAREN)) {
                break
            }

            if (parser.check(TokenType.RPAREN)) {
                val token = parser.peek()
                diagnostics.add(
                    Diagnostic(
                        message = "Expected at least one argument",
                        line = token.line,
                        column = token.column,
                        startOffset = token.startOffset,
                        endOffset = token.endOffset,
                    ),
                )
                parser.advance()
                continue
            }

            val arguments = mutableListOf<CallArgument>()
            arguments.add(parseCallArgument(diagnostics))

            while (parser.match(TokenType.COMMA)) {
                if (parser.check(TokenType.RPAREN)) {
                    val token = parser.peek()
                    diagnostics.add(
                        Diagnostic(
                            message = "Expected term after ','",
                            line = token.line,
                            column = token.column,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset,
                        ),
                    )
                    break
                }
                arguments.add(parseCallArgument(diagnostics))
            }

            parser.consume(TokenType.RPAREN, "Expected ')' after arguments", diagnostics)
            arguments.forEach { argument ->
                expression = Term.Application(expression, argument.term, argument.visibility)
            }
        }

        return expression
    }

    private fun parseAtom(diagnostics: MutableList<Diagnostic>): Term {
        val firstNameToken = consumeNameSegmentToken() 
        if (firstNameToken != null) {
            val (name, endOffset) = parseQualifiedName(firstNameToken)
            return Term.Variable(name, TextSpan(firstNameToken.startOffset, endOffset))
        }
        if (parser.match(TokenType.LPAREN)) {
            val term = parseExpression(diagnostics)
            parser.consume(TokenType.RPAREN, "Expected ')' after term", diagnostics)
            return term
        }
        if (parser.match(TokenType.LBRACE)) {
            val term = parseExpression(diagnostics)
            parser.consume(TokenType.RBRACE, "Expected '}' after term", diagnostics)
            return term
        }

        val token = parser.peek()
        diagnostics.add(
            Diagnostic(
                message = "Expected term",
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            ),
        )
        if (!parser.isAtEnd()) {
            parser.advance()
        }
        return Term.Variable("_", TextSpan(token.startOffset, token.endOffset))
    }

    private fun parseCaseExpression(diagnostics: MutableList<Diagnostic>): Term {
        val caseToken = parser.advance()
        val scrutinee = parseExpression(diagnostics)
        consumeKeyword("of", "Expected 'of' after case scrutinee", diagnostics)

        val branches = if (parser.match(TokenType.LBRACE)) {
            parseBracedCaseBranches(diagnostics)
        } else {
            parseUnbracedCaseBranches(diagnostics)
        }

        val endOffset = if (branches.isNotEmpty()) {
            spanOfBranchBody(branches.last()).endOffset
        } else {
            parser.previous().endOffset
        }
        return Term.Case(
            scrutinee = scrutinee,
            branches = branches,
            span = TextSpan(caseToken.startOffset, endOffset),
        )
    }

    private fun parseBracedCaseBranches(diagnostics: MutableList<Diagnostic>): List<Term.CaseBranch> {
        val branches = mutableListOf<Term.CaseBranch>()
        while (!parser.isAtEnd() && !parser.check(TokenType.RBRACE)) {
            maybeConsumeCasePipe()
            branches += parseCaseBranch(diagnostics)
            if (parser.match(TokenType.SEMICOLON)) {
                continue
            }
            if (!parser.check(TokenType.RBRACE)) {
                val token = parser.peek()
                diagnostics += Diagnostic(
                    message = "Expected ';' between case branches",
                    line = token.line,
                    column = token.column,
                    startOffset = token.startOffset,
                    endOffset = token.endOffset,
                )
                if (!parser.isAtEnd()) {
                    parser.advance()
                }
            }
        }
        parser.consume(TokenType.RBRACE, "Expected '}' after case branches", diagnostics)
        return branches
    }

    private fun parseUnbracedCaseBranches(diagnostics: MutableList<Diagnostic>): List<Term.CaseBranch> {
        val branches = mutableListOf<Term.CaseBranch>()
        maybeConsumeCasePipe()
        branches += parseCaseBranch(diagnostics)
        while (maybeConsumeCasePipe()) {
            branches += parseCaseBranch(diagnostics)
        }
        return branches
    }

    private fun maybeConsumeCasePipe(): Boolean {
        val token = parser.peek()
        if (token.type != TokenType.SYMBOLIC_IDENT || token.text != "|") {
            return false
        }
        parser.advance()
        return true
    }

    private fun parseCaseBranch(diagnostics: MutableList<Diagnostic>): Term.CaseBranch {
        val constructorToken = consumeNameSegmentToken() ?: run {
            val token = parser.peek()
            diagnostics += Diagnostic(
                message = "Expected constructor pattern in case branch",
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            )
            return Term.CaseBranch(
                constructorName = "_",
                constructorSpan = TextSpan(token.startOffset, token.endOffset),
                parameters = emptyList(),
                body = parseFallbackCaseBranchBody(diagnostics),
            )
        }

        val (constructorName, constructorEndOffset) = parseQualifiedName(constructorToken)
        val constructorSpan = TextSpan(constructorToken.startOffset, constructorEndOffset)
        val parameters = parseCasePatternParameters(diagnostics)
        parser.consume(TokenType.FAT_ARROW, "Expected '=>' after case pattern", diagnostics)
        val body = parseExpression(diagnostics)
        return Term.CaseBranch(
            constructorName = constructorName,
            constructorSpan = constructorSpan,
            parameters = parameters,
            body = body,
        )
    }

    private fun parseFallbackCaseBranchBody(diagnostics: MutableList<Diagnostic>): Term {
        if (!parser.isAtEnd()) {
            parser.advance()
        }
        return parseExpression(diagnostics)
    }

    private fun parseCasePatternParameters(diagnostics: MutableList<Diagnostic>): List<Term.CasePatternParameter> {
        if (!parser.match(TokenType.LPAREN)) {
            return emptyList()
        }
        if (parser.match(TokenType.RPAREN)) {
            return emptyList()
        }

        val parameters = mutableListOf<Term.CasePatternParameter>()
        parameters += parseCasePatternParameter(diagnostics)
        while (parser.match(TokenType.COMMA)) {
            parameters += parseCasePatternParameter(diagnostics)
        }
        parser.consume(TokenType.RPAREN, "Expected ')' after case pattern parameters", diagnostics)
        return parameters
    }

    private fun parseCasePatternParameter(diagnostics: MutableList<Diagnostic>): Term.CasePatternParameter {
        val identifier = parser.consume(TokenType.IDENT, "Expected identifier in case pattern", diagnostics)
        val name = identifier.text.ifBlank { "_" }
        return Term.CasePatternParameter(name, TextSpan(identifier.startOffset, identifier.endOffset))
    }

    private fun spanOfBranchBody(branch: Term.CaseBranch): TextSpan {
        return spanOfTerm(branch.body)
    }

    private fun spanOfTerm(term: Term): TextSpan {
        return when (term) {
            is Term.Variable -> term.span
            is Term.Constant -> term.span
            is Term.Meta -> term.span
            is Term.Lambda -> term.parameterSpan
            is Term.Pi -> term.parameterSpan
            is Term.Typed -> spanOfTerm(term.term)
            is Term.Application -> spanOfTerm(term.argument)
            is Term.Case -> term.span
        }
    }

    private fun consumeKeyword(keyword: String, message: String, diagnostics: MutableList<Diagnostic>): Token {
        val token = parser.peek()
        if (isKeyword(token, keyword)) {
            return parser.advance()
        }
        diagnostics += Diagnostic(
            message = message,
            line = token.line,
            column = token.column,
            startOffset = token.startOffset,
            endOffset = token.endOffset,
        )
        return Token(TokenType.IDENT, "", token.line, token.column, token.startOffset, token.endOffset)
    }

    private fun isKeyword(token: Token, keyword: String): Boolean {
        return token.type == TokenType.IDENT && token.text == keyword
    }

    private fun consumeNameSegmentToken(): Token? {
        val type = parser.peek().type
        if (type == TokenType.IDENT || type == TokenType.CONST_IDENT || type == TokenType.BACKSLASH_CONST || type == TokenType.SYMBOLIC_IDENT) {
            parser.advance()
            return parser.previous()
        }
        return null
    }

    private fun parseQualifiedName(first: Token): Pair<String, Int> {
        val builder = StringBuilder(first.text)
        var endOffset = first.endOffset
        while (parser.check(TokenType.DOT) && isNameSegmentToken(parser.peek(1))) {
            parser.advance()
            val segment = parser.advance()
            builder.append('.')
            builder.append(segment.text)
            endOffset = segment.endOffset
        }
        return builder.toString() to endOffset
    }

    private fun isNameSegmentToken(token: Token): Boolean {
        return token.type == TokenType.IDENT ||
            token.type == TokenType.CONST_IDENT ||
            token.type == TokenType.BACKSLASH_CONST ||
            token.type == TokenType.SYMBOLIC_IDENT
    }

    private fun peekInfixCandidate(): InfixCandidate? {
        if (parser.check(TokenType.COLON)) {
            val token = parser.peek()
            return InfixCandidate(
                kind = InfixKind.TYPE_ANNOTATION,
                operatorToken = token,
                precedence = TermSyntaxParser.TYPE_ANNOTATION_PRECEDENCE,
                associativity = InfixAssociativity.RIGHT,
                tokenCount = 1,
            )
        }

        if (parser.check(TokenType.ARROW)) {
            val token = parser.peek()
            return InfixCandidate(
                kind = InfixKind.PI_ARROW,
                operatorToken = token,
                precedence = TermSyntaxParser.PI_ARROW_PRECEDENCE,
                associativity = InfixAssociativity.RIGHT,
                tokenCount = 1,
            )
        }

        if (parser.check(TokenType.BACKTICK)) {
            val operator = parser.peek(1)
            val closing = parser.peek(2)
            if (!parser.isOperatorToken(operator) || closing.type != TokenType.BACKTICK) {
                return null
            }
            val declaration = parser.infixByName[operator.text]
            return InfixCandidate(
                kind = InfixKind.OPERATOR,
                operatorToken = operator,
                precedence = declaration?.precedence ?: TermSyntaxParser.DEFAULT_BACKTICK_PRECEDENCE,
                associativity = declaration?.associativity ?: InfixAssociativity.LEFT,
                tokenCount = 3,
            )
        }

        val token = parser.peek()
        if (!parser.isOperatorToken(token)) {
            return null
        }
        val declaration = parser.infixByName[token.text] ?: return null
        return InfixCandidate(
            kind = InfixKind.OPERATOR,
            operatorToken = token,
            precedence = declaration.precedence,
            associativity = declaration.associativity,
            tokenCount = 1,
        )
    }

    private fun recoverMalformedBacktick(diagnostics: MutableList<Diagnostic>) {
        val opening = parser.advance()
        if (!parser.isOperatorToken(parser.peek())) {
            diagnostics.add(
                Diagnostic(
                    message = "Expected operator name after '`'",
                    line = opening.line,
                    column = opening.column,
                    startOffset = opening.startOffset,
                    endOffset = opening.endOffset,
                ),
            )
            if (parser.check(TokenType.BACKTICK)) {
                parser.advance()
            }
            return
        }

        val operator = parser.advance()
        if (!parser.match(TokenType.BACKTICK)) {
            diagnostics.add(
                Diagnostic(
                    message = "Expected closing '`' after operator name",
                    line = operator.line,
                    column = operator.column,
                    startOffset = operator.startOffset,
                    endOffset = operator.endOffset,
                ),
            )
        }
    }

    private fun makeInfixApplication(candidate: InfixCandidate, left: Term, right: Term): Term {
        if (candidate.kind == InfixKind.TYPE_ANNOTATION) {
            return Term.Typed(left, right)
        }
        if (candidate.kind == InfixKind.PI_ARROW) {
            return makePiFromArrow(left, right, candidate.operatorToken)
        }
        val operatorToken = candidate.operatorToken
        val operatorSpan = TextSpan(operatorToken.startOffset, operatorToken.endOffset)
        val operatorTerm = when (operatorToken.type) {
            TokenType.IDENT -> Term.Variable(operatorToken.text, operatorSpan)
            TokenType.CONST_IDENT, TokenType.BACKSLASH_CONST, TokenType.SYMBOLIC_IDENT -> Term.Variable(operatorToken.text, operatorSpan)
            else -> Term.Variable(operatorToken.text, operatorSpan)
        }
        return Term.Application(
            function = Term.Application(operatorTerm, left, Term.Visibility.EXPLICIT),
            argument = right,
            visibility = Term.Visibility.EXPLICIT,
        )
    }

    private fun makePiFromArrow(left: Term, right: Term, arrowToken: Token): Term {
        if (left is Term.Typed && left.term is Term.Variable) {
            val variable = left.term
            return Term.Pi(
                parameter = variable.name,
                parameterType = left.type,
                body = right,
                parameterSpan = variable.span,
                visibility = parser.inferArrowBinderVisibility(variable.span),
            )
        }

        val syntheticSpan = TextSpan(arrowToken.startOffset, arrowToken.startOffset)
        return Term.Pi("_", left, right, syntheticSpan, Term.Visibility.EXPLICIT)
    }

    private fun parseCallArgument(diagnostics: MutableList<Diagnostic>): CallArgument {
        if (parser.match(TokenType.LBRACE)) {
            val term = parseExpression(diagnostics)
            parser.consume(TokenType.RBRACE, "Expected '}' after implicit argument", diagnostics)
            return CallArgument(term, Term.Visibility.IMPLICIT)
        }
        return CallArgument(parseExpression(diagnostics), Term.Visibility.EXPLICIT)
    }

    private data class InfixCandidate(
        val kind: InfixKind,
        val operatorToken: Token,
        val precedence: Int,
        val associativity: InfixAssociativity,
        val tokenCount: Int,
    )

    private enum class InfixKind {
        TYPE_ANNOTATION,
        PI_ARROW,
        OPERATOR,
    }

    private data class LambdaParameter(
        val name: String,
        val span: TextSpan,
        val type: Term?,
        val visibility: Term.Visibility,
    )

    private data class CallArgument(
        val term: Term,
        val visibility: Term.Visibility,
    )
}
