package core.parser

import core.model.Definition
import core.model.Diagnostic
import core.model.InfixDeclaration
import core.model.NewtypeRegistry
import core.model.RewriteRule

internal enum class TokenType {
    IDENT,
    CONST_IDENT,
    BACKSLASH_CONST,
    SYMBOLIC_IDENT,
    INTEGER,
    LAMBDA,
    FORALL,
    ARROW,
    FAT_ARROW,
    REWRITE_ARROW,
    DOT,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    COMMA,
    COLON,
    ASSIGN,
    SEMICOLON,
    BACKTICK,
    EOF,
}

internal data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class ParseResult(
    val definitions: List<Definition>,
    val rewriteRules: List<RewriteRule>,
    val newtypeRegistries: List<NewtypeRegistry>,
    val infixDeclarations: List<InfixDeclaration>,
    val diagnostics: List<Diagnostic>,
)
