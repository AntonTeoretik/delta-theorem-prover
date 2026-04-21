package core.parser

import core.model.Diagnostic
import core.model.ParsedDocument
import core.model.Term

class SimpleTextParser : TextParser {
    override fun parse(source: String): ParsedDocument {
        val normalized = source.replace("\r\n", "\n")
        val lexer = TermLexer(normalized)
        val tokens = lexer.lex()
        val diagnostics = lexer.diagnostics.toMutableList()

        val parser = TermSyntaxParser(tokens)
        val term = parser.parseTerm()
        diagnostics.addAll(parser.diagnostics)

        return ParsedDocument(
            sourceText = normalized,
            term = if (diagnostics.any()) null else term,
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
    EOF,
}

private data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
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
                ch.isWhitespace() -> consumeWhitespace(ch)
                ch == '\\' -> tokens.add(singleToken(TokenType.LAMBDA, "\\"))
                ch == '.' -> tokens.add(singleToken(TokenType.DOT, "."))
                ch == '(' -> tokens.add(singleToken(TokenType.LPAREN, "("))
                ch == ')' -> tokens.add(singleToken(TokenType.RPAREN, ")"))
                ch == ',' -> tokens.add(singleToken(TokenType.COMMA, ","))
                ch == '$' -> tokens.add(readConstant())
                ch.isLetter() || ch == '_' -> tokens.add(readIdentifier(TokenType.IDENT))
                else -> {
                    diagnostics.add(Diagnostic("Unexpected character '$ch'", line, column))
                    advance(ch)
                }
            }
        }

        tokens.add(Token(TokenType.EOF, "", line, column))
        return tokens
    }

    private fun consumeWhitespace(ch: Char) {
        advance(ch)
    }

    private fun singleToken(type: TokenType, text: String): Token {
        val token = Token(type, text, line, column)
        advance(source[index])
        return token
    }

    private fun readConstant(): Token {
        val startLine = line
        val startColumn = column
        advance('$')

        if (index >= source.length || !(source[index].isLetter() || source[index] == '_')) {
            diagnostics.add(Diagnostic("Expected identifier after '$'", startLine, startColumn))
            return Token(TokenType.CONST_IDENT, "", startLine, startColumn)
        }

        val ident = readIdentifier(TokenType.IDENT)
        return Token(TokenType.CONST_IDENT, ident.text, startLine, startColumn)
    }

    private fun readIdentifier(type: TokenType): Token {
        val startLine = line
        val startColumn = column
        val buffer = StringBuilder()

        while (index < source.length) {
            val ch = source[index]
            if (!ch.isLetterOrDigit() && ch != '_') {
                break
            }
            buffer.append(ch)
            advance(ch)
        }

        return Token(type, buffer.toString(), startLine, startColumn)
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
}

private class TermSyntaxParser(private val tokens: List<Token>) {
    val diagnostics: MutableList<Diagnostic> = mutableListOf()
    private var cursor: Int = 0

    fun parseTerm(): Term {
        val term = parseExpression()
        if (!isAtEnd()) {
            val token = peek()
            diagnostics.add(Diagnostic("Unexpected token '${token.text.ifEmpty { token.type.name }}'", token.line, token.column))
        }
        return term
    }

    private fun parseExpression(): Term {
        if (match(TokenType.LAMBDA)) {
            val lambdaToken = previous()
            val parameter = consume(TokenType.IDENT, "Expected identifier after '\\'")
            consume(TokenType.DOT, "Expected '.' after lambda parameter")
            val body = parseExpression()
            return Term.Lambda(parameter.text, body)
        }
        return parseApplication()
    }

    private fun parseApplication(): Term {
        var expression = parseAtom()

        while (match(TokenType.LPAREN)) {
            if (check(TokenType.RPAREN)) {
                val token = peek()
                diagnostics.add(Diagnostic("Expected at least one argument", token.line, token.column))
                advance()
                continue
            }

            val arguments = mutableListOf<Term>()
            arguments.add(parseExpression())

            while (match(TokenType.COMMA)) {
                if (check(TokenType.RPAREN)) {
                    val token = peek()
                    diagnostics.add(Diagnostic("Expected term after ','", token.line, token.column))
                    break
                }
                arguments.add(parseExpression())
            }

            consume(TokenType.RPAREN, "Expected ')' after arguments")
            arguments.forEach { argument ->
                expression = Term.Application(expression, argument)
            }
        }

        return expression
    }

    private fun parseAtom(): Term {
        if (match(TokenType.IDENT)) {
            return Term.Variable(previous().text)
        }
        if (match(TokenType.CONST_IDENT)) {
            return Term.Constant(previous().text)
        }
        if (match(TokenType.LPAREN)) {
            val term = parseExpression()
            consume(TokenType.RPAREN, "Expected ')' after term")
            return term
        }

        val token = peek()
        diagnostics.add(Diagnostic("Expected term", token.line, token.column))
        advance()
        return Term.Variable("_")
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) {
            return advance()
        }

        val token = peek()
        diagnostics.add(Diagnostic(message, token.line, token.column))
        return Token(type, "", token.line, token.column)
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
