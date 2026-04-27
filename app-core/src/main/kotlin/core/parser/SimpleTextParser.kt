package core.parser

import core.model.ParsedDocument

class SimpleTextParser : TextParser {
    override fun parse(source: String): ParsedDocument {
        val normalized = source.replace("\r\n", "\n")
        val lexer = TermLexer(normalized)
        val tokens = lexer.lex()
        val diagnostics = lexer.diagnostics.toMutableList()

        val parser = TermSyntaxParser(tokens, normalized)
        val parseResult = parser.parseProgramOrTerm()
        diagnostics.addAll(parseResult.diagnostics)

        return ParsedDocument(
            sourceText = normalized,
            definitions = parseResult.definitions,
            rewriteRules = parseResult.rewriteRules,
            infixDeclarations = parseResult.infixDeclarations,
            diagnostics = diagnostics,
        )
    }
}
