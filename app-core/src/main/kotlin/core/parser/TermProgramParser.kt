package core.parser

import core.model.Definition
import core.model.DefinitionKind
import core.model.Diagnostic
import core.model.InfixAssociativity
import core.model.InfixDeclaration
import core.model.NewtypeMember
import core.model.NewtypeRegistry
import core.model.RewriteRule
import core.model.Term
import core.model.TextSpan

internal class TermProgramParser(private val parser: TermSyntaxParser) {
    private var nextAnonymousBinderId: Int = 0

    fun parseProgramOrTerm(): ParseResult {
        val diagnostics = mutableListOf<Diagnostic>()
        parseLeadingInfixDirectives(diagnostics)

        if (parser.isAtEnd()) {
            return ParseResult(
                definitions = emptyList(),
                rewriteRules = emptyList(),
                newtypeRegistries = emptyList(),
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
                newtypeRegistries = newtypeRegistries.toList(),
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
                listOf(Definition(name = "main", type = null, implementation = term, nameSpan = null, keywordSpan = null))
            },
            rewriteRules = emptyList(),
            newtypeRegistries = emptyList(),
            infixDeclarations = parser.infixDeclarations.toList(),
            diagnostics = diagnostics + termDiagnostics,
        )
    }

    private val newtypeRegistries = mutableListOf<NewtypeRegistry>()

    private fun shouldParseDefinitions(): Boolean {
        if (isRuleStart()) {
            return true
        }
        if (!isDefinitionStart()) {
            return false
        }
        if (definitionKeywordOf(parser.peek()) != null) {
            return parser.tokens
                .subList((parser.cursor + 1).coerceAtMost(parser.tokens.size), parser.tokens.size)
                .any { it.type == TokenType.ASSIGN || it.type == TokenType.SEMICOLON }
        }
        return when (parser.peek(1).type) {
            TokenType.ASSIGN, TokenType.SEMICOLON -> true
            TokenType.COLON -> parser.tokens
                .subList((parser.cursor + 2).coerceAtMost(parser.tokens.size), parser.tokens.size)
                .any { it.type == TokenType.ASSIGN || it.type == TokenType.SEMICOLON }
            TokenType.LPAREN, TokenType.LBRACE -> parser.tokens
                .subList((parser.cursor + 1).coerceAtMost(parser.tokens.size), parser.tokens.size)
                .any { it.type == TokenType.ASSIGN || it.type == TokenType.SEMICOLON }
            TokenType.IDENT -> parser.peek(2).type == TokenType.COLON && parser.tokens
                .subList((parser.cursor + 1).coerceAtMost(parser.tokens.size), parser.tokens.size)
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

    private fun consumeDefinitionName(diagnostics: MutableList<Diagnostic>, message: String): Token {
        if (isDefinitionNameToken(parser.peek())) {
            return parser.advance()
        }
        val token = parser.peek()
        diagnostics.add(
            Diagnostic(
                message = message,
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            ),
        )
        return Token(TokenType.IDENT, "", token.line, token.column, token.startOffset, token.endOffset)
    }

    private fun isDefinitionStart(): Boolean {
        val keyword = definitionKeywordOf(parser.peek())
        if (keyword != null) {
            if (!isDefinitionNameToken(parser.peek(1))) {
                return false
            }
            val nextAfterName = parser.peek(2).type
            return nextAfterName == TokenType.COLON ||
                nextAfterName == TokenType.ASSIGN ||
                nextAfterName == TokenType.SEMICOLON ||
                nextAfterName == TokenType.LPAREN ||
                nextAfterName == TokenType.LBRACE ||
                (nextAfterName == TokenType.IDENT && parser.peek(3).type == TokenType.COLON)
        }

        if (!isDefinitionNameToken(parser.peek())) {
            return false
        }
        val next = parser.peek(1).type
        return next == TokenType.COLON ||
            next == TokenType.ASSIGN ||
            next == TokenType.SEMICOLON ||
            next == TokenType.LPAREN ||
            next == TokenType.LBRACE ||
            (next == TokenType.IDENT && parser.peek(2).type == TokenType.COLON)
    }

    private fun isDefinitionNameToken(token: Token): Boolean {
        return token.type == TokenType.IDENT ||
            token.type == TokenType.CONST_IDENT ||
            token.type == TokenType.BACKSLASH_CONST ||
            token.type == TokenType.SYMBOLIC_IDENT
    }

    private fun definitionKeywordOf(token: Token): DefinitionKind? {
        if (token.type != TokenType.IDENT) {
            return null
        }
        return when (token.text) {
            "def" -> DefinitionKind.DEF
            "fun" -> DefinitionKind.FUN
            "lemma" -> DefinitionKind.LEMMA
            "theorem" -> DefinitionKind.THEOREM
            "axiom" -> DefinitionKind.AXIOM
            "recursor" -> DefinitionKind.RECURSOR
            "newtype" -> DefinitionKind.NEWTYPE
            else -> null
        }
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

            val firstToken = parser.advance()
            val keywordKind = definitionKeywordOf(firstToken)
            val kind = keywordKind ?: DefinitionKind.LEGACY
            val keywordSpan = if (keywordKind != null) TextSpan(firstToken.startOffset, firstToken.endOffset) else null
            val nameToken = if (keywordKind != null) {
                consumeDefinitionName(diagnostics, "Expected name after '${firstToken.text}'")
            } else {
                firstToken
            }

            if (kind == DefinitionKind.NEWTYPE && tryParseNewtypeRegistryBlock(firstToken, nameToken, diagnostics, definitions)) {
                continue
            }

            var type: Term? = null
            var implementation: Term? = null
            val telescopeBinders = mutableListOf<TelescopeBinder>()

            if (parser.match(TokenType.COLON)) {
                type = parser.parseExpression(diagnostics)
            } else if (isTelescopeStart(parser.peek())) {
                telescopeBinders += parseTelescopeBinders(diagnostics)
                if (parser.match(TokenType.COLON)) {
                    type = parser.parseExpression(diagnostics)
                } else if (kind == DefinitionKind.NEWTYPE) {
                    type = Term.Variable("Type", TextSpan(nameToken.startOffset, nameToken.endOffset))
                } else {
                    parser.consume(TokenType.COLON, "Expected ':' before telescope result type", diagnostics)
                    type = parser.parseExpression(diagnostics)
                }
            }

            if (parser.match(TokenType.ASSIGN)) {
                implementation = parser.parseExpression(diagnostics)
            }

            if (kind == DefinitionKind.NEWTYPE && type == null) {
                type = Term.Variable("Type", TextSpan(nameToken.startOffset, nameToken.endOffset))
            }

            val desugaredType = if (telescopeBinders.isEmpty() || type == null) {
                type
            } else {
                telescopeBinders.asReversed().fold(type) { acc, binder ->
                    Term.Pi(
                        parameter = binder.name,
                        parameterType = binder.type,
                        body = acc,
                        parameterSpan = binder.span,
                        visibility = binder.visibility,
                    )
                }
            }

            val desugaredImplementation = if (telescopeBinders.isEmpty() || implementation == null) {
                implementation
            } else {
                telescopeBinders.asReversed().fold(implementation) { acc, binder ->
                    Term.Lambda(
                        parameter = binder.name,
                        parameterType = binder.type,
                        body = acc,
                        parameterSpan = binder.span,
                        visibility = binder.visibility,
                    )
                }
            }

            val terminatorSpan = if (parser.match(TokenType.SEMICOLON)) {
                val semicolon = parser.previous()
                TextSpan(semicolon.startOffset, semicolon.endOffset)
            } else {
                null
            }

            definitions.add(
                Definition(
                    name = nameToken.text,
                    kind = kind,
                    type = desugaredType,
                    implementation = desugaredImplementation,
                    nameSpan = TextSpan(nameToken.startOffset, nameToken.endOffset),
                    keywordSpan = keywordSpan,
                    terminatorSpan = terminatorSpan,
                ),
            )

            if (terminatorSpan == null) {
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

    private fun tryParseNewtypeRegistryBlock(
        keywordToken: Token,
        typeNameToken: Token,
        diagnostics: MutableList<Diagnostic>,
        definitions: MutableList<Definition>,
    ): Boolean {
        val startCursor = parser.cursor
        if (!parser.match(TokenType.COLON)) {
            return false
        }

        val blockStart = findTopLevelBlockStart(parser.cursor)
        if (blockStart == null) {
            parser.cursor = startCursor
            return false
        }
        val typeSignature = parseTypeSignatureUntil(blockStart, diagnostics)

        parser.cursor = blockStart
        parser.consume(TokenType.LBRACE, "Expected '{' to start newtype block", diagnostics)

        val constructors = mutableListOf<NewtypeMember>()
        var recursor: NewtypeMember? = null
        val attachedRules = mutableListOf<RewriteRule>()

        while (!parser.isAtEnd() && !parser.check(TokenType.RBRACE)) {
            val token = parser.peek()
            if (token.type == TokenType.IDENT && token.text == "constructor") {
                val member = parseNewtypeMember(diagnostics, "constructor")
                if (member != null) {
                    constructors += member
                    definitions += Definition(
                        name = member.name,
                        kind = DefinitionKind.AXIOM,
                        type = member.type,
                        implementation = null,
                        nameSpan = member.nameSpan,
                        keywordSpan = TextSpan(token.startOffset, token.endOffset),
                        terminatorSpan = null,
                    )
                }
                continue
            }

            if (token.type == TokenType.IDENT && token.text == "recursor") {
                val member = parseNewtypeMember(diagnostics, "recursor")
                if (member != null) {
                    recursor = member
                    definitions += Definition(
                        name = member.name,
                        kind = DefinitionKind.RECURSOR,
                        type = member.type,
                        implementation = null,
                        nameSpan = member.nameSpan,
                        keywordSpan = TextSpan(token.startOffset, token.endOffset),
                        terminatorSpan = null,
                    )
                }
                continue
            }

            if (token.type == TokenType.IDENT && token.text == "rule") {
                val before = parser.rewriteRules.size
                parseRule(diagnostics)
                val added = parser.rewriteRules.drop(before)
                attachedRules += added
                continue
            }

            diagnostics += Diagnostic(
                message = "Expected 'constructor', 'recursor', or 'rule' in newtype block",
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            )
            parser.advance()
        }

        parser.consume(TokenType.RBRACE, "Expected '}' after newtype block", diagnostics)

        val typeNameSpan = TextSpan(typeNameToken.startOffset, typeNameToken.endOffset)
        definitions += Definition(
            name = typeNameToken.text,
            kind = DefinitionKind.NEWTYPE,
            type = typeSignature,
            implementation = null,
            nameSpan = typeNameSpan,
            keywordSpan = TextSpan(keywordToken.startOffset, keywordToken.endOffset),
            terminatorSpan = null,
        )

        newtypeRegistries += NewtypeRegistry(
            typeName = typeNameToken.text,
            typeSignature = typeSignature,
            typeNameSpan = typeNameSpan,
            constructors = constructors.toList(),
            recursor = recursor,
            rules = attachedRules.toList(),
        )

        return true
    }

    private fun findTopLevelBlockStart(start: Int): Int? {
        var parenDepth = 0
        var braceDepth = 0
        var index = start
        while (index < parser.tokens.size) {
            val token = parser.tokens[index]
            when (token.type) {
                TokenType.LPAREN -> parenDepth += 1
                TokenType.RPAREN -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                TokenType.LBRACE -> {
                    if (parenDepth == 0 && braceDepth == 0 && index != start) {
                        return index
                    }
                    braceDepth += 1
                }

                TokenType.RBRACE -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                TokenType.SEMICOLON,
                TokenType.ASSIGN,
                -> {
                    if (parenDepth == 0 && braceDepth == 0) {
                        return null
                    }
                }

                TokenType.EOF -> return null
                else -> Unit
            }
            index += 1
        }
        return null
    }

    private fun parseTypeSignatureUntil(endExclusive: Int, diagnostics: MutableList<Diagnostic>): Term {
        val tokenSlice = parser.tokens.subList(parser.cursor, endExclusive)
        val eofAnchor = parser.tokens.getOrNull(endExclusive) ?: parser.tokens.last()
        val localTokens = tokenSlice + Token(
            type = TokenType.EOF,
            text = "",
            line = eofAnchor.line,
            column = eofAnchor.column,
            startOffset = eofAnchor.startOffset,
            endOffset = eofAnchor.endOffset,
        )

        val localParser = TermSyntaxParser(localTokens, parser.sourceText)
        val localDiagnostics = mutableListOf<Diagnostic>()
        val term = localParser.parseExpression(localDiagnostics)
        if (!localParser.isAtEnd()) {
            val token = localParser.peek()
            localDiagnostics += Diagnostic(
                message = "Unexpected token '${token.text.ifEmpty { token.type.name }}' in newtype type signature",
                line = token.line,
                column = token.column,
                startOffset = token.startOffset,
                endOffset = token.endOffset,
            )
        }
        diagnostics += localDiagnostics
        return term
    }

    private fun parseNewtypeMember(diagnostics: MutableList<Diagnostic>, kind: String): NewtypeMember? {
        parser.advance()
        val nameToken = consumeDefinitionName(diagnostics, "Expected name after '$kind'")
        parser.consume(TokenType.COLON, "Expected ':' after $kind name", diagnostics)
        val memberType = parser.parseExpression(diagnostics)
        parser.consume(TokenType.SEMICOLON, "Expected ';' after $kind declaration", diagnostics)
        return NewtypeMember(
            name = nameToken.text,
            type = memberType,
            nameSpan = TextSpan(nameToken.startOffset, nameToken.endOffset),
        )
    }

    private fun isTelescopeStart(token: Token): Boolean {
        return token.type == TokenType.LPAREN ||
            token.type == TokenType.LBRACE ||
            (token.type == TokenType.IDENT && parser.peek(1).type == TokenType.COLON)
    }

    private fun parseTelescopeBinders(diagnostics: MutableList<Diagnostic>): List<TelescopeBinder> {
        val binders = mutableListOf<TelescopeBinder>()
        while (isTelescopeStart(parser.peek())) {
            binders += parseTelescopeBinder(diagnostics)
            if (!parser.match(TokenType.COMMA)) {
                break
            }
        }
        return binders
    }

    private fun parseTelescopeBinder(diagnostics: MutableList<Diagnostic>): TelescopeBinder {
        if (parser.match(TokenType.LBRACE)) {
            val nameToken = parser.consume(TokenType.IDENT, "Expected identifier in telescope binder", diagnostics)
            val binderType = if (parser.match(TokenType.COLON)) {
                parser.parseExpression(diagnostics, TermSyntaxParser.TYPE_ANNOTATION_PRECEDENCE + 1)
            } else {
                diagnostics += Diagnostic(
                    message = "Expected ':' in implicit telescope binder",
                    line = nameToken.line,
                    column = nameToken.column,
                    startOffset = nameToken.startOffset,
                    endOffset = nameToken.endOffset,
                )
                parser.freshMeta(TextSpan(nameToken.startOffset, nameToken.endOffset))
            }
            parser.consume(TokenType.RBRACE, "Expected '}' after implicit telescope binder", diagnostics)
            return TelescopeBinder(
                name = normalizeTelescopeBinderName(nameToken.text),
                type = binderType,
                span = TextSpan(nameToken.startOffset, nameToken.endOffset),
                visibility = Term.Visibility.IMPLICIT,
            )
        }

        if (parser.match(TokenType.LPAREN)) {
            val nameToken = parser.consume(TokenType.IDENT, "Expected identifier in telescope binder", diagnostics)
            parser.consume(TokenType.COLON, "Expected ':' in telescope binder", diagnostics)
            val binderType = parser.parseExpression(diagnostics, TermSyntaxParser.TYPE_ANNOTATION_PRECEDENCE + 1)
            parser.consume(TokenType.RPAREN, "Expected ')' after telescope binder", diagnostics)
            return TelescopeBinder(
                name = normalizeTelescopeBinderName(nameToken.text),
                type = binderType,
                span = TextSpan(nameToken.startOffset, nameToken.endOffset),
                visibility = Term.Visibility.EXPLICIT,
            )
        }

        val nameToken = parser.consume(TokenType.IDENT, "Expected identifier in telescope binder", diagnostics)
        parser.consume(TokenType.COLON, "Expected ':' in telescope binder", diagnostics)
        val binderType = parser.parseExpression(diagnostics, TermSyntaxParser.TYPE_ANNOTATION_PRECEDENCE + 1)
        return TelescopeBinder(
            name = normalizeTelescopeBinderName(nameToken.text),
            type = binderType,
            span = TextSpan(nameToken.startOffset, nameToken.endOffset),
            visibility = Term.Visibility.EXPLICIT,
        )
    }

    private fun normalizeTelescopeBinderName(raw: String): String {
        val value = raw.ifBlank { "_" }
        if (value == "_") {
            val id = nextAnonymousBinderId++
            return "_anon$id"
        }
        return value
    }

    private data class TelescopeBinder(
        val name: String,
        val type: Term,
        val span: TextSpan,
        val visibility: Term.Visibility,
    )
}
