package core.parser

import core.model.Definition
import core.model.Diagnostic
import core.model.ParsedDocument
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
            diagnostics = diagnostics,
        )
    }
}

private enum class TokenType {
    IDENT,
    CONST_IDENT,
    LAMBDA,
    DOT,
    LPAREN,
    RPAREN,
    COMMA,
    ASSIGN,
    SEMICOLON,
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
                ch == '\\' -> tokens.add(readLambda())
                ch == '.' -> tokens.add(singleToken(TokenType.DOT, "."))
                ch == '(' -> tokens.add(singleToken(TokenType.LPAREN, "("))
                ch == ')' -> tokens.add(singleToken(TokenType.RPAREN, ")"))
                ch == ',' -> tokens.add(singleToken(TokenType.COMMA, ","))
                ch == ';' -> tokens.add(singleToken(TokenType.SEMICOLON, ";"))
                ch == ':' -> tokens.add(readAssign())
                ch == '$' -> tokens.add(readConstant())
                ch.isLetter() || ch == '_' -> tokens.add(readIdentifier(TokenType.IDENT))
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

    private fun readAssign(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        advance(':')

        if (index >= source.length || source[index] != '=') {
            diagnostics.add(Diagnostic("Expected '=' after ':'", startLine, startColumn))
            return Token(TokenType.ASSIGN, "", startLine, startColumn, startOffset, index)
        }

        advance('=')
        return Token(TokenType.ASSIGN, ":=", startLine, startColumn, startOffset, index)
    }

    private fun readLambda(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        advance('\\')

        if (index >= source.length || !source[index].isLambdaSpacing()) {
            diagnostics.add(Diagnostic("Expected whitespace after '\\'", startLine, startColumn))
        }

        return Token(TokenType.LAMBDA, "\\", startLine, startColumn, startOffset, index)
    }

    private fun readConstant(): Token {
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
}

private class TermSyntaxParser(private val tokens: List<Token>) {
    private var cursor: Int = 0

    fun parseProgramOrTerm(): ParseResult {
        val initialCursor = cursor
        val programDiagnostics = mutableListOf<Diagnostic>()
        val definitions = parseDefinitions(programDiagnostics)

        if (definitions.isNotEmpty() && isAtEnd()) {
            return ParseResult(definitions = definitions, diagnostics = programDiagnostics)
        }

        cursor = initialCursor
        val termDiagnostics = mutableListOf<Diagnostic>()
        val term = parseExpression(termDiagnostics)

        if (!isAtEnd()) {
            val token = peek()
            termDiagnostics.add(Diagnostic("Unexpected token '${token.text.ifEmpty { token.type.name }}'", token.line, token.column))
        }

        return ParseResult(
            definitions = if (termDiagnostics.any()) emptyList() else listOf(Definition(name = "main", term = term, nameSpan = null)),
            diagnostics = termDiagnostics,
        )
    }

    private fun parseDefinitions(diagnostics: MutableList<Diagnostic>): MutableList<Definition> {
        val definitions = mutableListOf<Definition>()

        while (!isAtEnd()) {
            if (!check(TokenType.CONST_IDENT)) {
                if (definitions.isEmpty()) {
                    return mutableListOf()
                }
                val token = peek()
                diagnostics.add(Diagnostic("Expected definition name like '\$name'", token.line, token.column))
                break
            }

            val nameToken = advance()
            if (!match(TokenType.ASSIGN)) {
                diagnostics.add(Diagnostic("Expected ':=' after definition name", peek().line, peek().column))
                break
            }

            val term = parseExpression(diagnostics)
            definitions.add(Definition(nameToken.text, term, TextSpan(nameToken.startOffset, nameToken.endOffset)))

            if (!match(TokenType.SEMICOLON)) {
                diagnostics.add(Diagnostic("Expected ';' after definition", peek().line, peek().column))
                break
            }
        }

        return definitions
    }

    private fun parseExpression(diagnostics: MutableList<Diagnostic>): Term {
        if (match(TokenType.LAMBDA)) {
            val parameters = mutableListOf<Pair<String, TextSpan>>()
            val first = consume(TokenType.IDENT, "Expected identifier after '\\'", diagnostics)
            parameters += first.text.ifBlank { "_" } to TextSpan(first.startOffset, first.endOffset)

            while (match(TokenType.COMMA)) {
                val next = consume(TokenType.IDENT, "Expected identifier after ',' in lambda", diagnostics)
                parameters += next.text.ifBlank { "_" } to TextSpan(next.startOffset, next.endOffset)
            }

            consume(TokenType.DOT, "Expected '.' after lambda parameter", diagnostics)
            val body = parseExpression(diagnostics)

            return parameters.asReversed().fold(body) { acc, (parameterName, parameterSpan) ->
                Term.Lambda(parameterName, acc, parameterSpan)
            }
        }
        return parseApplication(diagnostics)
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

    private fun consume(type: TokenType, message: String, diagnostics: MutableList<Diagnostic>): Token {
        if (check(type)) {
            return advance()
        }

        val token = peek()
        diagnostics.add(Diagnostic(message, token.line, token.column))
        return Token(type, "", token.line, token.column, token.startOffset, token.endOffset)
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

    private fun peek(): Token = tokens[cursor]

    private fun previous(): Token = tokens[cursor - 1]
}
