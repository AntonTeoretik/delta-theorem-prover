package core.parser

import core.model.Definition
import core.model.Diagnostic
import core.model.InfixAssociativity
import core.model.InfixDeclaration
import core.model.ParsedDocument
import core.model.SymbolDisplay
import core.model.Term
import core.model.TextSpan

class SimpleTextParser : TextParser {
    override fun parse(source: String): ParsedDocument {
        val normalized = source.replace("\r\n", "\n")
        val lexer = TermLexer(normalized)
        val tokens = lexer.lex()
        val diagnostics = lexer.diagnostics.toMutableList()

        val parser = TermSyntaxParser(tokens)
        val parseResult = parser.parseProgramOrTerm()
        diagnostics.addAll(parseResult.diagnostics)

        return ParsedDocument(
            sourceText = normalized,
            definitions = parseResult.definitions,
            infixDeclarations = parseResult.infixDeclarations,
            diagnostics = diagnostics,
        )
    }
}

private enum class TokenType {
    IDENT,
    CONST_IDENT,
    BACKSLASH_CONST,
    SYMBOLIC_IDENT,
    INTEGER,
    LAMBDA,
    FORALL,
    ARROW,
    DOT,
    LPAREN,
    RPAREN,
    COMMA,
    COLON,
    ASSIGN,
    SEMICOLON,
    BACKTICK,
    EOF,
}

private data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
    val startOffset: Int,
    val endOffset: Int,
)

private data class ParseResult(
    val definitions: List<Definition>,
    val infixDeclarations: List<InfixDeclaration>,
    val diagnostics: List<Diagnostic>,
)

private class TermLexer(private val source: String) {
    val diagnostics: MutableList<Diagnostic> = mutableListOf()

    private var index: Int = 0
    private var line: Int = 1
    private var column: Int = 1

    fun lex(): List<Token> {
        val tokens = mutableListOf<Token>()

        while (index < source.length) {
            val ch = source[index]
            when {
                ch.isWhitespace() -> advance(ch)
                ch == '\\' -> tokens.add(readBackslashPrefixed())
                ch == 'λ' -> tokens.add(singleToken(TokenType.LAMBDA, "λ"))
                ch == '∀' -> tokens.add(singleToken(TokenType.FORALL, "∀"))
                ch == '→' -> tokens.add(singleToken(TokenType.ARROW, "→"))
                ch.isCommittedSymbolConstant() -> tokens.add(singleToken(TokenType.SYMBOLIC_IDENT, ch.toString()))
                ch == '.' -> tokens.add(singleToken(TokenType.DOT, "."))
                ch == '(' -> tokens.add(singleToken(TokenType.LPAREN, "("))
                ch == ')' -> tokens.add(singleToken(TokenType.RPAREN, ")"))
                ch == ',' -> tokens.add(singleToken(TokenType.COMMA, ","))
                ch == ';' -> tokens.add(singleToken(TokenType.SEMICOLON, ";"))
                ch == '`' -> tokens.add(singleToken(TokenType.BACKTICK, "`"))
                ch == ':' -> tokens.add(readColonOrAssign())
                ch == '$' -> tokens.add(readDollarConstant())
                ch.isDigit() -> tokens.add(readInteger())
                ch.isLetter() || ch == '_' -> tokens.add(readIdentifier(TokenType.IDENT))
                ch.isSymbolicIdentifierStart() -> tokens.add(readSymbolicIdentifier())
                else -> {
                    diagnostics.add(Diagnostic("Unexpected character '$ch'", line, column))
                    advance(ch)
                }
            }
        }

        tokens.add(Token(TokenType.EOF, "", line, column, index, index))
        return tokens
    }

    private fun singleToken(type: TokenType, text: String): Token {
        val startOffset = index
        val token = Token(type, text, line, column, startOffset, startOffset + 1)
        advance(source[index])
        return token
    }

    private fun readColonOrAssign(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        advance(':')

        if (index >= source.length || source[index] != '=') {
            return Token(TokenType.COLON, ":", startLine, startColumn, startOffset, index)
        }

        advance('=')
        return Token(TokenType.ASSIGN, ":=", startLine, startColumn, startOffset, index)
    }

    private fun readBackslashPrefixed(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        advance('\\')

        if (index < source.length && source[index].isLambdaSpacing()) {
            return Token(TokenType.LAMBDA, "\\", startLine, startColumn, startOffset, index)
        }

        val buffer = StringBuilder()
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace() || ch.isBackslashConstantDelimiter()) {
                break
            }
            buffer.append(ch)
            advance(ch)
        }

        if (buffer.isEmpty()) {
            diagnostics.add(Diagnostic("Expected constant name after '\\'", startLine, startColumn))
        }

        if (buffer.toString() == "to") {
            return Token(
                type = TokenType.ARROW,
                text = "\\to",
                line = startLine,
                column = startColumn,
                startOffset = startOffset,
                endOffset = index,
            )
        }

        if (buffer.toString() == "forall") {
            return Token(
                type = TokenType.FORALL,
                text = "\\forall",
                line = startLine,
                column = startColumn,
                startOffset = startOffset,
                endOffset = index,
            )
        }

        return Token(
            type = TokenType.BACKSLASH_CONST,
            text = "\\${buffer}",
            line = startLine,
            column = startColumn,
            startOffset = startOffset,
            endOffset = index,
        )
    }

    private fun readDollarConstant(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        advance('$')

        if (index >= source.length || !(source[index].isLetter() || source[index] == '_')) {
            diagnostics.add(Diagnostic("Expected identifier after '$'", startLine, startColumn))
            return Token(TokenType.CONST_IDENT, "", startLine, startColumn, startOffset, index)
        }

        val ident = readIdentifier(TokenType.IDENT)
        return Token(TokenType.CONST_IDENT, ident.text, startLine, startColumn, startOffset, ident.endOffset)
    }

    private fun readIdentifier(type: TokenType): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        val buffer = StringBuilder()

        while (index < source.length) {
            val ch = source[index]
            if (!ch.isLetterOrDigit() && ch != '_') {
                break
            }
            buffer.append(ch)
            advance(ch)
        }

        return Token(type, buffer.toString(), startLine, startColumn, startOffset, index)
    }

    private fun readInteger(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        val buffer = StringBuilder()

        while (index < source.length && source[index].isDigit()) {
            val ch = source[index]
            buffer.append(ch)
            advance(ch)
        }

        return Token(TokenType.INTEGER, buffer.toString(), startLine, startColumn, startOffset, index)
    }

    private fun readSymbolicIdentifier(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        val buffer = StringBuilder()

        while (index < source.length) {
            val ch = source[index]
            if (!ch.isSymbolicIdentifierPart()) {
                break
            }
            buffer.append(ch)
            advance(ch)
        }

        return Token(TokenType.SYMBOLIC_IDENT, buffer.toString(), startLine, startColumn, startOffset, index)
    }

    private fun advance(ch: Char) {
        index += 1
        if (ch == '\n') {
            line += 1
            column = 1
        } else {
            column += 1
        }
    }

    private fun Char.isLambdaSpacing(): Boolean = this == ' ' || this == '\t'

    private fun Char.isBackslashConstantDelimiter(): Boolean {
        return this == '.' ||
            this == '(' ||
            this == ')' ||
            this == ',' ||
            this == ';' ||
            this == ':' ||
            this == '`' ||
            this == '\\' ||
            this == '$'
    }

    private fun Char.isSymbolicIdentifierStart(): Boolean {
        return !isLetterOrDigit() &&
            !isWhitespace() &&
            this != '_' &&
            this != '.' &&
            this != '(' &&
            this != ')' &&
            this != ',' &&
            this != ';' &&
            this != ':' &&
            this != '`' &&
            this != '\\' &&
            this != '$'
    }

    private fun Char.isSymbolicIdentifierPart(): Boolean = isSymbolicIdentifierStart()

    private fun Char.isCommittedSymbolConstant(): Boolean {
        return this in COMMITTED_SYMBOL_CONSTANTS
    }

    private companion object {
        val COMMITTED_SYMBOL_CONSTANTS: Set<Char> = SymbolDisplay.symbolReplacements
            .values
            .filter { it.length == 1 }
            .map { it.single() }
            .toSet()
    }
}

private class TermSyntaxParser(private val tokens: List<Token>) {
    private var cursor: Int = 0
    private var nextMetaId: Int = 0

    private val infixDeclarations = mutableListOf<InfixDeclaration>()
    private val infixByName = linkedMapOf<String, InfixDeclaration>()

    fun parseProgramOrTerm(): ParseResult {
        val diagnostics = mutableListOf<Diagnostic>()
        parseLeadingInfixDirectives(diagnostics)

        if (isAtEnd()) {
            return ParseResult(
                definitions = emptyList(),
                infixDeclarations = infixDeclarations.toList(),
                diagnostics = diagnostics,
            )
        }

        if (shouldParseDefinitions()) {
            val definitions = parseDefinitions(diagnostics)
            if (!isAtEnd()) {
                val token = peek()
                diagnostics.add(Diagnostic("Unexpected token '${token.text.ifEmpty { token.type.name }}'", token.line, token.column))
            }
            return ParseResult(
                definitions = definitions,
                infixDeclarations = infixDeclarations.toList(),
                diagnostics = diagnostics,
            )
        }

        val termDiagnostics = mutableListOf<Diagnostic>()
        val term = parseExpression(termDiagnostics)
        if (!isAtEnd()) {
            val token = peek()
            termDiagnostics.add(Diagnostic("Unexpected token '${token.text.ifEmpty { token.type.name }}'", token.line, token.column))
        }

        return ParseResult(
            definitions = if (termDiagnostics.any()) {
                emptyList()
            } else {
                listOf(Definition(name = "main", type = null, implementation = term, nameSpan = null))
            },
            infixDeclarations = infixDeclarations.toList(),
            diagnostics = diagnostics + termDiagnostics,
        )
    }

    private fun shouldParseDefinitions(): Boolean {
        if (!isDefinitionStart()) {
            return false
        }
        return tokens.subList(cursor, tokens.size).any { it.type == TokenType.SEMICOLON }
    }

    private fun parseLeadingInfixDirectives(diagnostics: MutableList<Diagnostic>) {
        while (isDirectiveKeyword(peek())) {
            parseInfixDirective(diagnostics)
        }
    }

    private fun parseInfixDirective(diagnostics: MutableList<Diagnostic>) {
        val keyword = advance()
        val associativity = when (keyword.text) {
            "infixl" -> InfixAssociativity.LEFT
            "infixr" -> InfixAssociativity.RIGHT
            else -> InfixAssociativity.LEFT
        }

        val precedenceToken = consume(TokenType.INTEGER, "Expected precedence after '${keyword.text}'", diagnostics)
        val precedence = precedenceToken.text.toIntOrNull()
        if (precedence == null) {
            diagnostics.add(Diagnostic("Expected integer precedence", precedenceToken.line, precedenceToken.column))
        }

        val nameToken = consumeInfixName(diagnostics)
        consume(TokenType.SEMICOLON, "Expected ';' after infix declaration", diagnostics)

        if (precedence != null && nameToken.text.isNotBlank()) {
            val declaration = InfixDeclaration(
                name = nameToken.text,
                precedence = precedence,
                associativity = associativity,
                nameSpan = TextSpan(nameToken.startOffset, nameToken.endOffset),
            )
            infixDeclarations += declaration
            infixByName[declaration.name] = declaration
        }
    }

    private fun consumeInfixName(diagnostics: MutableList<Diagnostic>): Token {
        if (isOperatorToken(peek())) {
            return advance()
        }
        val token = peek()
        diagnostics.add(Diagnostic("Expected operator name in infix declaration", token.line, token.column))
        return Token(TokenType.IDENT, "", token.line, token.column, token.startOffset, token.endOffset)
    }

    private fun isDefinitionStart(): Boolean {
        if (!check(TokenType.CONST_IDENT)) {
            return false
        }
        val next = peek(1).type
        return next == TokenType.COLON || next == TokenType.ASSIGN || next == TokenType.SEMICOLON
    }

    private fun parseDefinitions(diagnostics: MutableList<Diagnostic>): MutableList<Definition> {
        val definitions = mutableListOf<Definition>()

        while (!isAtEnd()) {
            if (!isDefinitionStart()) {
                val token = peek()
                diagnostics.add(Diagnostic("Expected definition name like '\$name'", token.line, token.column))
                break
            }

            val nameToken = advance()
            var type: Term? = null
            var implementation: Term? = null

            if (match(TokenType.COLON)) {
                type = parseExpression(diagnostics)
            }
            if (match(TokenType.ASSIGN)) {
                implementation = parseExpression(diagnostics)
            }

            definitions.add(
                Definition(
                    name = nameToken.text,
                    type = type,
                    implementation = implementation,
                    nameSpan = TextSpan(nameToken.startOffset, nameToken.endOffset),
                ),
            )

            if (!match(TokenType.SEMICOLON)) {
                diagnostics.add(Diagnostic("Expected ';' after definition", peek().line, peek().column))
                break
            }
        }

        return definitions
    }

    private fun parseExpression(diagnostics: MutableList<Diagnostic>, minPrecedence: Int = 0): Term {
        var expression = when {
            match(TokenType.LAMBDA) -> parseLambdaExpression(diagnostics)
            match(TokenType.FORALL) -> parseForallExpression(diagnostics)
            else -> parseApplication(diagnostics)
        }

        while (true) {
            val candidate = peekInfixCandidate()
            if (candidate == null) {
                if (check(TokenType.BACKTICK)) {
                    recoverMalformedBacktick(diagnostics)
                    continue
                }
                break
            }

            if (candidate.precedence < minPrecedence) {
                break
            }

            repeat(candidate.tokenCount) { advance() }
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
            while (match(TokenType.COMMA)) {
                list += parseLambdaParameter(diagnostics)
            }
            list
        }

        consume(TokenType.DOT, "Expected '.' after lambda parameter", diagnostics)
        val body = parseExpression(diagnostics)

        return parameters.asReversed().fold(body) { acc, parameter ->
            val parameterType = parameter.type ?: freshMeta(parameter.span)
            Term.Lambda(parameter.name, parameterType, acc, parameter.span)
        }
    }

    private fun parseForallExpression(diagnostics: MutableList<Diagnostic>): Term {
        val parameters = if (isGroupedBinderListStart(TokenType.DOT)) {
            parseBinderParameterGroup(diagnostics, "forall")
        } else {
            val list = mutableListOf<LambdaParameter>()
            list += parseForallParameter(diagnostics)
            while (match(TokenType.COMMA)) {
                list += parseForallParameter(diagnostics)
            }
            list
        }

        consume(TokenType.DOT, "Expected '.' after forall parameter", diagnostics)
        val body = parseExpression(diagnostics)

        return parameters.asReversed().fold(body) { acc, parameter ->
            val parameterType = parameter.type ?: freshMeta(parameter.span)
            Term.Pi(parameter.name, parameterType, acc, parameter.span)
        }
    }

    private fun isGroupedLambdaParameterListStart(): Boolean = isGroupedBinderListStart(TokenType.DOT)

    private fun isGroupedBinderListStart(terminator: TokenType): Boolean {
        if (!check(TokenType.LPAREN)) {
            return false
        }

        var depth = 0
        var offset = 0
        while (true) {
            val token = peek(offset)
            when (token.type) {
                TokenType.LPAREN -> depth += 1
                TokenType.RPAREN -> {
                    depth -= 1
                    if (depth == 0) {
                        return peek(offset + 1).type == terminator
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
        consume(TokenType.LPAREN, "Expected '(' after $kind", diagnostics)

        if (check(TokenType.RPAREN)) {
            diagnostics.add(Diagnostic("Expected $kind parameter", peek().line, peek().column))
            advance()
            return parameters
        }

        parameters += parseLambdaParameterInGroup(diagnostics)
        while (match(TokenType.COMMA)) {
            parameters += parseLambdaParameterInGroup(diagnostics)
        }

        consume(TokenType.RPAREN, "Expected ')' after $kind parameters", diagnostics)
        return parameters
    }

    private fun parseForallParameter(diagnostics: MutableList<Diagnostic>): LambdaParameter {
        if (match(TokenType.LPAREN)) {
            val identifier = consume(TokenType.IDENT, "Expected identifier in forall binder", diagnostics)
            val parameterType = if (match(TokenType.COLON)) {
                parseExpression(diagnostics)
            } else {
                null
            }
            consume(TokenType.RPAREN, "Expected ')' after forall binder", diagnostics)
            return LambdaParameter(
                name = identifier.text.ifBlank { "_" },
                span = TextSpan(identifier.startOffset, identifier.endOffset),
                type = parameterType,
            )
        }

        val identifier = consume(TokenType.IDENT, "Expected identifier after '∀'", diagnostics)
        val parameterType = if (match(TokenType.COLON)) {
            parseExpression(diagnostics)
        } else {
            null
        }
        return LambdaParameter(
            name = identifier.text.ifBlank { "_" },
            span = TextSpan(identifier.startOffset, identifier.endOffset),
            type = parameterType,
        )
    }

    private fun parseLambdaParameterInGroup(diagnostics: MutableList<Diagnostic>): LambdaParameter {
        if (match(TokenType.LPAREN)) {
            val identifier = consume(TokenType.IDENT, "Expected identifier in lambda binder", diagnostics)
            val parameterType = if (match(TokenType.COLON)) {
                parseExpression(diagnostics)
            } else {
                null
            }
            consume(TokenType.RPAREN, "Expected ')' after lambda binder", diagnostics)
            return LambdaParameter(
                name = identifier.text.ifBlank { "_" },
                span = TextSpan(identifier.startOffset, identifier.endOffset),
                type = parameterType,
            )
        }

        val identifier = consume(TokenType.IDENT, "Expected identifier in lambda parameters", diagnostics)
        val parameterType = if (match(TokenType.COLON)) {
            parseExpression(diagnostics)
        } else {
            null
        }
        return LambdaParameter(
            name = identifier.text.ifBlank { "_" },
            span = TextSpan(identifier.startOffset, identifier.endOffset),
            type = parameterType,
        )
    }

    private fun parseLambdaParameter(diagnostics: MutableList<Diagnostic>): LambdaParameter {
        if (match(TokenType.LPAREN)) {
            val identifier = consume(TokenType.IDENT, "Expected identifier in lambda binder", diagnostics)
            val parameterType = if (match(TokenType.COLON)) {
                parseExpression(diagnostics)
            } else {
                null
            }
            consume(TokenType.RPAREN, "Expected ')' after lambda binder", diagnostics)
            return LambdaParameter(
                name = identifier.text.ifBlank { "_" },
                span = TextSpan(identifier.startOffset, identifier.endOffset),
                type = parameterType,
            )
        }

        val identifier = consume(TokenType.IDENT, "Expected identifier after '\\'", diagnostics)
        return LambdaParameter(
            name = identifier.text.ifBlank { "_" },
            span = TextSpan(identifier.startOffset, identifier.endOffset),
            type = null,
        )
    }

    private fun parseApplication(diagnostics: MutableList<Diagnostic>): Term {
        var expression = parseAtom(diagnostics)

        while (match(TokenType.LPAREN)) {
            if (check(TokenType.RPAREN)) {
                diagnostics.add(Diagnostic("Expected at least one argument", peek().line, peek().column))
                advance()
                continue
            }

            val arguments = mutableListOf<Term>()
            arguments.add(parseExpression(diagnostics))

            while (match(TokenType.COMMA)) {
                if (check(TokenType.RPAREN)) {
                    diagnostics.add(Diagnostic("Expected term after ','", peek().line, peek().column))
                    break
                }
                arguments.add(parseExpression(diagnostics))
            }

            consume(TokenType.RPAREN, "Expected ')' after arguments", diagnostics)
            arguments.forEach { argument ->
                expression = Term.Application(expression, argument)
            }
        }

        return expression
    }

    private fun parseAtom(diagnostics: MutableList<Diagnostic>): Term {
        if (match(TokenType.IDENT)) {
            val token = previous()
            return Term.Variable(token.text, TextSpan(token.startOffset, token.endOffset))
        }
        if (match(TokenType.CONST_IDENT)) {
            val token = previous()
            return Term.Constant(token.text, TextSpan(token.startOffset, token.endOffset))
        }
        if (match(TokenType.BACKSLASH_CONST)) {
            val token = previous()
            return Term.Constant(token.text, TextSpan(token.startOffset, token.endOffset))
        }
        if (match(TokenType.SYMBOLIC_IDENT)) {
            val token = previous()
            val span = TextSpan(token.startOffset, token.endOffset)
            return if (token.text.isCommittedVariableSymbol()) {
                Term.Variable(token.text, span)
            } else {
                Term.Constant(token.text, span)
            }
        }
        if (match(TokenType.LPAREN)) {
            val term = parseExpression(diagnostics)
            consume(TokenType.RPAREN, "Expected ')' after term", diagnostics)
            return term
        }

        val token = peek()
        diagnostics.add(Diagnostic("Expected term", token.line, token.column))
        if (!isAtEnd()) {
            advance()
        }
        return Term.Variable("_", TextSpan(token.startOffset, token.endOffset))
    }

    private fun peekInfixCandidate(): InfixCandidate? {
        if (check(TokenType.COLON)) {
            val token = peek()
            return InfixCandidate(
                kind = InfixKind.TYPE_ANNOTATION,
                operatorToken = token,
                precedence = TYPE_ANNOTATION_PRECEDENCE,
                associativity = InfixAssociativity.RIGHT,
                tokenCount = 1,
            )
        }

        if (check(TokenType.ARROW)) {
            val token = peek()
            return InfixCandidate(
                kind = InfixKind.PI_ARROW,
                operatorToken = token,
                precedence = PI_ARROW_PRECEDENCE,
                associativity = InfixAssociativity.RIGHT,
                tokenCount = 1,
            )
        }

        if (check(TokenType.BACKTICK)) {
            val operator = peek(1)
            val closing = peek(2)
            if (!isOperatorToken(operator) || closing.type != TokenType.BACKTICK) {
                return null
            }
            val declaration = infixByName[operator.text]
            return InfixCandidate(
                kind = InfixKind.OPERATOR,
                operatorToken = operator,
                precedence = declaration?.precedence ?: DEFAULT_BACKTICK_PRECEDENCE,
                associativity = declaration?.associativity ?: InfixAssociativity.LEFT,
                tokenCount = 3,
            )
        }

        val token = peek()
        if (!isOperatorToken(token)) {
            return null
        }
        val declaration = infixByName[token.text] ?: return null
        return InfixCandidate(
            kind = InfixKind.OPERATOR,
            operatorToken = token,
            precedence = declaration.precedence,
            associativity = declaration.associativity,
            tokenCount = 1,
        )
    }

    private fun recoverMalformedBacktick(diagnostics: MutableList<Diagnostic>) {
        val opening = advance()
        if (!isOperatorToken(peek())) {
            diagnostics.add(Diagnostic("Expected operator name after '`'", opening.line, opening.column))
            if (check(TokenType.BACKTICK)) {
                advance()
            }
            return
        }

        val operator = advance()
        if (!match(TokenType.BACKTICK)) {
            diagnostics.add(Diagnostic("Expected closing '`' after operator name", operator.line, operator.column))
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
            TokenType.CONST_IDENT, TokenType.BACKSLASH_CONST -> Term.Constant(operatorToken.text, operatorSpan)
            TokenType.SYMBOLIC_IDENT -> if (operatorToken.text.isCommittedVariableSymbol()) {
                Term.Variable(operatorToken.text, operatorSpan)
            } else {
                Term.Constant(operatorToken.text, operatorSpan)
            }
            else -> Term.Variable(operatorToken.text, operatorSpan)
        }
        return Term.Application(Term.Application(operatorTerm, left), right)
    }

    private fun String.isCommittedVariableSymbol(): Boolean = this in COMMITTED_SYMBOL_CONSTANTS

    private fun makePiFromArrow(left: Term, right: Term, arrowToken: Token): Term {
        if (left is Term.Typed && left.term is Term.Variable) {
            val variable = left.term
            return Term.Pi(
                parameter = variable.name,
                parameterType = left.type,
                body = right,
                parameterSpan = variable.span,
            )
        }

        val syntheticSpan = TextSpan(arrowToken.startOffset, arrowToken.startOffset)
        return Term.Pi("_", left, right, syntheticSpan)
    }

    private fun freshMeta(span: TextSpan): Term.Meta {
        return Term.Meta(nextMetaId++, span)
    }

    private fun consume(type: TokenType, message: String, diagnostics: MutableList<Diagnostic>): Token {
        if (check(type)) {
            return advance()
        }

        val token = peek()
        diagnostics.add(Diagnostic(message, token.line, token.column))
        return Token(type, "", token.line, token.column, token.startOffset, token.endOffset)
    }

    private fun isDirectiveKeyword(token: Token): Boolean {
        return token.type == TokenType.IDENT && (token.text == "infixl" || token.text == "infixr")
    }

    private fun isOperatorToken(token: Token): Boolean {
        return token.type == TokenType.IDENT ||
            token.type == TokenType.CONST_IDENT ||
            token.type == TokenType.BACKSLASH_CONST ||
            token.type == TokenType.SYMBOLIC_IDENT
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) {
            return false
        }
        advance()
        return true
    }

    private fun check(type: TokenType): Boolean {
        if (isAtEnd()) {
            return type == TokenType.EOF
        }
        return peek().type == type
    }

    private fun advance(): Token {
        if (!isAtEnd()) {
            cursor += 1
        }
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(offset: Int = 0): Token {
        val position = cursor + offset
        if (position >= tokens.size) {
            return tokens.last()
        }
        return tokens[position]
    }

    private fun previous(): Token = tokens[cursor - 1]

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
    )

    private companion object {
        const val PI_ARROW_PRECEDENCE: Int = 0
        const val TYPE_ANNOTATION_PRECEDENCE: Int = 1
        const val DEFAULT_BACKTICK_PRECEDENCE: Int = 9
        val COMMITTED_SYMBOL_CONSTANTS: Set<String> = SymbolDisplay.symbolReplacements.values.toSet()
    }
}
