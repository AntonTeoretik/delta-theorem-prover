package core.parser

import core.model.ParsedDocument

interface TextParser {
    fun parse(source: String): ParsedDocument
}
