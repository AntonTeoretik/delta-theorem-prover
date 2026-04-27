package core.parser

import core.model.Diagnostic
import core.model.InfixDeclaration
import core.model.Term
import core.model.TextSpan

internal class TermSyntaxParser(internal val tokens: List<Token>, internal val sourceText: String) {
    internal var cursor: Int = 0
    private var nextMetaId: Int = 0

    internal val infixDeclarations = mutableListOf<InfixDeclaration>()
    internal val infixByName = linkedMapOf<String, InfixDeclaration>()
    internal val rewriteRules = mutableListOf<core.model.RewriteRule>()

    private val expressionParser = TermExpressionParser(this)
    private val programParser = TermProgramParser(this)

    fun parseProgramOrTerm(): ParseResult = programParser.parseProgramOrTerm()

    internal fun parseExpression(diagnostics: MutableList<Diagnostic>, minPrecedence: Int = 0): Term {
        return expressionParser.parseExpression(diagnostics, minPrecedence)
    }

    internal fun freshMeta(span: TextSpan): Term.Meta {
        return Term.Meta(nextMetaId++, span)
    }

    internal fun inferArrowBinderVisibility(variableSpan: TextSpan): Term.Visibility {
        var index = variableSpan.startOffset - 1
        while (index >= 0 && sourceText[index].isWhitespace()) {
            index -= 1
        }
        return if (index >= 0 && sourceText[index] == '{') {
            Term.Visibility.IMPLICIT
        } else {
            Term.Visibility.EXPLICIT
        }
    }

    internal fun consume(type: TokenType, message: String, diagnostics: MutableList<Diagnostic>): Token {
        if (check(type)) {
            return advance()
        }

        val token = peek()
        diagnostics.add(
            Diagnostic(
                message = message,
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            ),
        )
        return Token(type, "", token.line, token.column, token.startOffset, token.endOffset)
    }

    internal fun isDirectiveKeyword(token: Token): Boolean {
        return token.type == TokenType.IDENT && (token.text == "infixl" || token.text == "infixr")
    }

    internal fun isOperatorToken(token: Token): Boolean {
        return token.type == TokenType.IDENT ||
            token.type == TokenType.CONST_IDENT ||
            token.type == TokenType.BACKSLASH_CONST ||
            token.type == TokenType.SYMBOLIC_IDENT
    }

    internal fun match(type: TokenType): Boolean {
        if (!check(type)) {
            return false
        }
        advance()
        return true
    }

    internal fun check(type: TokenType): Boolean {
        if (isAtEnd()) {
            return type == TokenType.EOF
        }
        return peek().type == type
    }

    internal fun advance(): Token {
        if (!isAtEnd()) {
            cursor += 1
        }
        return previous()
    }

    internal fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    internal fun peek(offset: Int = 0): Token {
        val position = cursor + offset
        if (position >= tokens.size) {
            return tokens.last()
        }
        return tokens[position]
    }

    internal fun previous(): Token = tokens[cursor - 1]

    internal companion object {
        const val TYPE_ANNOTATION_PRECEDENCE: Int = 0
        const val PI_ARROW_PRECEDENCE: Int = 1
        const val DEFAULT_BACKTICK_PRECEDENCE: Int = 9
        const val RESERVED_LOCAL_NAME: String = "Type"
    }
}
