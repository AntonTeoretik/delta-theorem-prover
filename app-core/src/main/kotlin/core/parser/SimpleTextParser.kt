package core.parser

import core.model.ParsedDocument

class SimpleTextParser : TextParser {
    override fun parse(source: String): ParsedDocument {
        val normalized = source.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        return ParsedDocument(sourceText = normalized, lines = lines)
    }
}
