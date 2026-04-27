package core.parser

import core.model.Definition
import core.model.Diagnostic
import core.model.InfixAssociativity
import core.model.InfixDeclaration
import core.model.RewriteRule
import core.model.Term
import core.model.TextSpan

internal class TermProgramParser(private val parser: TermSyntaxParser) {
    fun parseProgramOrTerm(): ParseResult {
        val diagnostics = mutableListOf<Diagnostic>()
        parseLeadingInfixDirectives(diagnostics)

        if (parser.isAtEnd()) {
            return ParseResult(
                definitions = emptyList(),
                rewriteRules = emptyList(),
                infixDeclarations = parser.infixDeclarations.toList(),
                diagnostics = diagnostics,
            )
        }

        if (shouldParseDefinitions()) {
            val definitions = parseDefinitions(diagnostics)
            if (!parser.isAtEnd()) {
                val token = parser.peek()
                diagnostics.add(
                    Diagnostic(
                        message = "Unexpected token '${token.text.ifEmpty { token.type.name }}'",
                        line = token.line,
                        column = token.column,
                        startOffset = token.startOffset,
                        endOffset = token.endOffset,
                    ),
                )
            }
            return ParseResult(
                definitions = definitions,
                rewriteRules = parser.rewriteRules.toList(),
                infixDeclarations = parser.infixDeclarations.toList(),
                diagnostics = diagnostics,
            )
        }

        val termDiagnostics = mutableListOf<Diagnostic>()
        val term = parser.parseExpression(termDiagnostics)
        if (!parser.isAtEnd()) {
            val token = parser.peek()
            termDiagnostics.add(
                Diagnostic(
                    message = "Unexpected token '${token.text.ifEmpty { token.type.name }}'",
                    line = token.line,
                    column = token.column,
                    startOffset = token.startOffset,
                    endOffset = token.endOffset,
                ),
            )
        }

        return ParseResult(
            definitions = if (termDiagnostics.any()) {
                emptyList()
            } else {
                listOf(Definition(name = "main", type = null, implementation = term, nameSpan = null))
            },
            rewriteRules = emptyList(),
            infixDeclarations = parser.infixDeclarations.toList(),
            diagnostics = diagnostics + termDiagnostics,
        )
    }

    private fun shouldParseDefinitions(): Boolean {
        if (isRuleStart()) {
            return true
        }
        if (!isDefinitionStart()) {
            return false
        }
        return when (parser.peek(1).type) {
            TokenType.ASSIGN, TokenType.SEMICOLON -> true
            TokenType.COLON -> parser.tokens
                .subList((parser.cursor + 2).coerceAtMost(parser.tokens.size), parser.tokens.size)
                .any { it.type == TokenType.ASSIGN || it.type == TokenType.SEMICOLON }

            else -> false
        }
    }

    private fun parseLeadingInfixDirectives(diagnostics: MutableList<Diagnostic>) {
        while (parser.isDirectiveKeyword(parser.peek())) {
            parseInfixDirective(diagnostics)
        }
    }

    private fun isRuleStart(): Boolean {
        val token = parser.peek()
        return isRuleKeyword(token) && isDefinitionNameToken(parser.peek(1))
    }

    private fun isRuleKeyword(token: Token): Boolean {
        return token.type == TokenType.IDENT && token.text == "rule"
    }

    private fun parseInfixDirective(diagnostics: MutableList<Diagnostic>) {
        val keyword = parser.advance()
        val associativity = when (keyword.text) {
            "infixl" -> InfixAssociativity.LEFT
            "infixr" -> InfixAssociativity.RIGHT
            else -> InfixAssociativity.LEFT
        }

        val precedenceToken = parser.consume(TokenType.INTEGER, "Expected precedence after '${keyword.text}'", diagnostics)
        val precedence = precedenceToken.text.toIntOrNull()
        if (precedence == null) {
            diagnostics.add(
                Diagnostic(
                    message = "Expected integer precedence",
                    line = precedenceToken.line,
                    column = precedenceToken.column,
                    startOffset = precedenceToken.startOffset,
                    endOffset = precedenceToken.endOffset,
                ),
            )
        }

        val nameToken = consumeInfixName(diagnostics)
        parser.consume(TokenType.SEMICOLON, "Expected ';' after infix declaration", diagnostics)

        if (precedence != null && nameToken.text.isNotBlank()) {
            val declaration = InfixDeclaration(
                name = nameToken.text,
                precedence = precedence,
                associativity = associativity,
                nameSpan = TextSpan(nameToken.startOffset, nameToken.endOffset),
            )
            parser.infixDeclarations += declaration
            parser.infixByName[declaration.name] = declaration
        }
    }

    private fun consumeInfixName(diagnostics: MutableList<Diagnostic>): Token {
        if (parser.isOperatorToken(parser.peek())) {
            return parser.advance()
        }
        val token = parser.peek()
        diagnostics.add(
            Diagnostic(
                message = "Expected operator name in infix declaration",
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            ),
        )
        return Token(TokenType.IDENT, "", token.line, token.column, token.startOffset, token.endOffset)
    }

    private fun isDefinitionStart(): Boolean {
        if (!isDefinitionNameToken(parser.peek())) {
            return false
        }
        val next = parser.peek(1).type
        return next == TokenType.COLON || next == TokenType.ASSIGN || next == TokenType.SEMICOLON
    }

    private fun isDefinitionNameToken(token: Token): Boolean {
        return token.type == TokenType.IDENT ||
            token.type == TokenType.CONST_IDENT ||
            token.type == TokenType.BACKSLASH_CONST ||
            token.type == TokenType.SYMBOLIC_IDENT
    }

    private fun parseDefinitions(diagnostics: MutableList<Diagnostic>): MutableList<Definition> {
        val definitions = mutableListOf<Definition>()

        while (!parser.isAtEnd()) {
            if (parser.isDirectiveKeyword(parser.peek())) {
                parseInfixDirective(diagnostics)
                continue
            }
            if (isRuleStart()) {
                parseRule(diagnostics)
                continue
            }
            if (!isDefinitionStart()) {
                val token = parser.peek()
                diagnostics.add(
                    Diagnostic(
                        message = "Expected definition name like 'name'",
                        line = token.line,
                        column = token.column,
                        startOffset = token.startOffset,
                        endOffset = token.endOffset,
                    ),
                )
                break
            }

            val nameToken = parser.advance()
            var type: Term? = null
            var implementation: Term? = null

            if (parser.match(TokenType.COLON)) {
                type = parser.parseExpression(diagnostics)
            }
            if (parser.match(TokenType.ASSIGN)) {
                implementation = parser.parseExpression(diagnostics)
            }

            definitions.add(
                Definition(
                    name = nameToken.text,
                    type = type,
                    implementation = implementation,
                    nameSpan = TextSpan(nameToken.startOffset, nameToken.endOffset),
                ),
            )

            if (!parser.match(TokenType.SEMICOLON)) {
                val token = parser.peek()
                diagnostics.add(
                    Diagnostic(
                        message = "Expected ';' after definition",
                        line = token.line,
                        column = token.column,
                        startOffset = token.startOffset,
                        endOffset = token.endOffset,
                    ),
                )
                break
            }
        }

        return definitions
    }

    private fun parseRule(diagnostics: MutableList<Diagnostic>) {
        val ruleKeyword = parser.advance()
        if (!isRuleKeyword(ruleKeyword)) {
            diagnostics += Diagnostic(
                message = "Expected 'rule' keyword",
                line = ruleKeyword.line,
                column = ruleKeyword.column,
                startOffset = ruleKeyword.startOffset,
                endOffset = ruleKeyword.endOffset,
            )
            return
        }

        val (ruleName, ruleNameSpan) = parseRuleName(diagnostics)
        parser.consume(TokenType.COLON, "Expected ':' after rule name", diagnostics)
        val lhs = parser.parseExpression(diagnostics)
        consumeRewriteArrow(diagnostics)
        val rhs = parser.parseExpression(diagnostics)
        parser.consume(TokenType.SEMICOLON, "Expected ';' after rule", diagnostics)

        parser.rewriteRules += RewriteRule(
            name = ruleName,
            lhs = lhs,
            rhs = rhs,
            keywordSpan = TextSpan(ruleKeyword.startOffset, ruleKeyword.endOffset),
            nameSpan = ruleNameSpan,
        )
    }

    private fun parseRuleName(diagnostics: MutableList<Diagnostic>): Pair<String, TextSpan> {
        val first = parser.consume(TokenType.IDENT, "Expected rule name after 'rule'", diagnostics)
        val builder = StringBuilder(first.text)
        var last = first

        while (parser.match(TokenType.DOT)) {
            val segment = parser.consume(TokenType.IDENT, "Expected identifier after '.' in rule name", diagnostics)
            if (builder.isNotEmpty()) {
                builder.append('.')
            }
            builder.append(segment.text)
            last = segment
        }

        val name = builder.toString().ifBlank { "rule" }
        return name to TextSpan(first.startOffset, last.endOffset)
    }

    private fun consumeRewriteArrow(diagnostics: MutableList<Diagnostic>): Token {
        if (parser.match(TokenType.REWRITE_ARROW)) {
            return parser.previous()
        }
        if (parser.check(TokenType.SYMBOLIC_IDENT) && parser.peek().text == "↦") {
            return parser.advance()
        }
        if (parser.check(TokenType.BACKSLASH_CONST) && parser.peek().text == "\\mapsto") {
            return parser.advance()
        }

        val token = parser.peek()
        diagnostics += Diagnostic(
            message = "Expected '↦' in rule declaration",
            line = token.line,
            column = token.column,
            startOffset = token.startOffset,
            endOffset = token.endOffset,
        )
        return Token(TokenType.REWRITE_ARROW, "", token.line, token.column, token.startOffset, token.endOffset)
    }
}
