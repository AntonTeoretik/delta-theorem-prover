package core.parser

import core.model.Diagnostic
import core.model.SymbolDisplay
import core.model.TextSpan

internal class TermLexer(private val source: String) {
    val diagnostics: MutableList<Diagnostic> = mutableListOf()
    val commentSpans: MutableList<TextSpan> = mutableListOf()

    private var index: Int = 0
    private var line: Int = 1
    private var column: Int = 1

    fun lex(): List<Token> {
        val tokens = mutableListOf<Token>()

        while (index < source.length) {
            val ch = source[index]
            when {
                ch.isWhitespace() -> advance(ch)
                ch == '-' && index + 1 < source.length && source[index + 1] == '-' -> skipLineComment()
                ch == '\\' -> tokens.add(readBackslashPrefixed())
                ch == 'λ' -> tokens.add(singleToken(TokenType.LAMBDA, "λ"))
                ch == '∀' -> tokens.add(singleToken(TokenType.FORALL, "∀"))
                ch == '→' -> tokens.add(singleToken(TokenType.ARROW, "→"))
                ch == '↦' -> tokens.add(singleToken(TokenType.REWRITE_ARROW, "↦"))
                ch == '=' && index + 1 < source.length && source[index + 1] == '>' -> tokens.add(readFatArrow())
                ch.isCommittedSymbolConstant() -> tokens.add(singleToken(TokenType.SYMBOLIC_IDENT, ch.toString()))
                ch == '.' -> tokens.add(singleToken(TokenType.DOT, "."))
                ch == '(' -> tokens.add(singleToken(TokenType.LPAREN, "("))
                ch == ')' -> tokens.add(singleToken(TokenType.RPAREN, ")"))
                ch == '{' -> tokens.add(singleToken(TokenType.LBRACE, "{"))
                ch == '}' -> tokens.add(singleToken(TokenType.RBRACE, "}"))
                ch == ',' -> tokens.add(singleToken(TokenType.COMMA, ","))
                ch == ';' -> tokens.add(singleToken(TokenType.SEMICOLON, ";"))
                ch == '`' -> tokens.add(singleToken(TokenType.BACKTICK, "`"))
                ch == ':' -> tokens.add(readColonOrAssign())
                ch == '$' -> tokens.add(readDollarConstant())
                ch.isDigit() -> tokens.add(readInteger())
                ch.isLetter() || ch == '_' -> tokens.add(readIdentifier(TokenType.IDENT))
                ch.isSymbolicIdentifierStart() -> tokens.add(readSymbolicIdentifier())
                else -> {
                    diagnostics.add(
                        Diagnostic(
                            message = "Unexpected character '$ch'",
                            line = line,
                            column = column,
                            startOffset = index,
                            endOffset = index + 1,
                        ),
                    )
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

    private fun readFatArrow(): Token {
        val startLine = line
        val startColumn = column
        val startOffset = index
        advance('=')
        advance('>')
        return Token(TokenType.FAT_ARROW, "=>", startLine, startColumn, startOffset, index)
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
            diagnostics.add(
                Diagnostic(
                    message = "Expected constant name after '\\'",
                    line = startLine,
                    column = startColumn,
                    startOffset = startOffset,
                    endOffset = index,
                ),
            )
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
            diagnostics.add(
                Diagnostic(
                    message = "Expected identifier after '$'",
                    line = startLine,
                    column = startColumn,
                    startOffset = startOffset,
                    endOffset = index,
                ),
            )
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

    private fun skipLineComment() {
        val start = index
        advance('-')
        advance('-')
        while (index < source.length && source[index] != '\n') {
            advance(source[index])
        }
        commentSpans += TextSpan(startOffset = start, endOffset = index)
    }

    private fun Char.isLambdaSpacing(): Boolean = this == ' ' || this == '\t'

    private fun Char.isBackslashConstantDelimiter(): Boolean {
        return this == '.' ||
            this == '(' ||
            this == ')' ||
            this == '{' ||
            this == '}' ||
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
            this != '{' &&
            this != '}' &&
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
