package vera.parser

import vera.ast.VeraArgument
import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBlock
import vera.ast.VeraBlockBody
import vera.ast.VeraBoolLiteral
import vera.ast.VeraBreakStatement
import vera.ast.VeraChainedExpression
import vera.ast.VeraChainedExpressionData
import vera.ast.VeraClassDeclaration
import vera.ast.VeraContinueStatement
import vera.ast.VeraDeclaration
import vera.ast.VeraExplicitReturnBlock
import vera.ast.VeraExpression
import vera.ast.VeraExpressionBody
import vera.ast.VeraField
import vera.ast.VeraFieldPattern
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
import vera.ast.VeraImplicitReturnBlock
import vera.ast.VeraIntLiteral
import vera.ast.VeraMatchArm
import vera.ast.VeraMatchExpression
import vera.ast.VeraMemberAccess
import vera.ast.VeraMutableParameter
import vera.ast.VeraOperator
import vera.ast.VeraParameter
import vera.ast.VeraPattern
import vera.ast.VeraPrimaryExpression
import vera.ast.VeraProgram
import vera.ast.VeraRebindStatement
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraStringTemplate
import vera.ast.VeraTemplatePart
import vera.ast.VeraTemplateText
import vera.ast.VeraTemplateValue
import vera.ast.VeraTraitDeclaration
import vera.ast.VeraTraitKind
import vera.ast.VeraTraitMethod
import vera.ast.VeraType
import vera.ast.VeraUnaryOperation
import vera.ast.VeraUnaryOperator
import vera.ast.VeraNoneLiteral
import vera.ast.VeraNoneReturnBlock
import vera.ast.VeraWhileStatement
import vera.ast.render
import vera.shared.model.Identifier
import vera.shared.model.SourceSpan

object VeraParser {
    fun parse(tokens: List<VeraToken>): VeraProgram = VeraParserState(tokens).parse()
}

/** Mutable state for a single parse run. */
private class VeraParserState(private val tokens: List<VeraToken>) {

    private var position = 0

    fun parse(): VeraProgram {
        val declarations = mutableListOf<VeraDeclaration>()
        skipEols()
        while (!at(VeraTokenType.EOF)) {
            declarations += parseDeclaration()
            skipEols()
        }
        return VeraProgram(declarations)
    }

    private fun parseDeclaration(): VeraDeclaration {
        return when {
            at(VeraTokenType.FN) -> parseFunctionDeclaration()
            at(VeraTokenType.CLASS) -> parseClassDeclaration()
            at(VeraTokenType.TRAIT) -> parseTraitDeclaration(advance(), VeraTraitKind.TRAIT)
            at(VeraTokenType.INTERFACE) -> parseTraitDeclaration(advance(), VeraTraitKind.INTERFACE)
            else -> {
                val token = current()
                rejectJavaModifier(token)
                rejectTopLevelHabit(token)
                throw VeraParseException.ExpectedDeclaration(token)
            }
        }
    }

    private fun parseFunctionDeclaration(): VeraFunctionDeclaration {
        val fnToken = expect(VeraTokenType.FN)
        val name = expect(VeraTokenType.IDENTIFIER, "function name")
        val (parameters, returnType) = parseSignature()
        val body = if (match(VeraTokenType.BIND)) {
            VeraExpressionBody(parseExpression())
        } else {
            VeraBlockBody(VeraExplicitReturnBlock(parseBlock()))
        }
        return VeraFunctionDeclaration(Identifier(name.text), parameters, returnType, body, spanOf(fnToken, name))
    }

    private fun parseClassDeclaration(): VeraClassDeclaration {
        val classToken = expect(VeraTokenType.CLASS)
        val name = expect(VeraTokenType.IDENTIFIER, "class name")
        rejectClassHeaderHabit()
        val traits = parseNameList(VeraTokenType.HAS, "trait name")
        val interfaces = parseNameList(VeraTokenType.IMPLEMENTS, "interface name")
        rejectJavaInheritance()
        val (fields, methods) = parseClassBody(name.text)
        return VeraClassDeclaration(
            name = Identifier(name.text),
            fields = fields,
            methods = methods,
            traits = traits,
            span = spanOf(classToken, name),
            interfaces = interfaces,
        )
    }

    /** Parses an optional clause such as `has Trait, Trait` or `implements Interface`, led by [keyword]. */
    private fun parseNameList(keyword: VeraTokenType, what: String): List<Identifier> {
        val names = mutableListOf<Identifier>()
        if (match(keyword)) {
            names += Identifier(expect(VeraTokenType.IDENTIFIER, what).text)
            while (match(VeraTokenType.COMMA)) {
                names += Identifier(expect(VeraTokenType.IDENTIFIER, what).text)
            }
        }
        return names
    }

    /** Parses `{ ... }` with `val` fields and `fn` methods, one per line. */
    private fun parseClassBody(className: String): Pair<List<VeraField>, List<VeraFunctionDeclaration>> {
        expect(VeraTokenType.LBRACE)
        skipEols()
        val fields = mutableListOf<VeraField>()
        val methods = mutableListOf<VeraFunctionDeclaration>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            when {
                at(VeraTokenType.FN) -> methods += parseFunctionDeclaration()
                at(VeraTokenType.VAL) -> fields += parseField()
                at(VeraTokenType.MUT) -> throw VeraParseException.MutableFieldNotSupported(current())
                at(VeraTokenType.IDENTIFIER) && peek().type == VeraTokenType.COLON ->
                    throw VeraParseException.FieldWithoutVal(current())
                else -> {
                    rejectJavaModifier(current())
                    rejectMemberHabit(className)
                    throw VeraParseException.ExpectedToken("a field (`val`) or method (`fn`)", current())
                }
            }
            parseStatementTerminator()
            skipEols()
        }
        expect(VeraTokenType.RBRACE)
        return fields to methods
    }

    /** Parses `val name: Type` in a class body. */
    private fun parseField(): VeraField {
        val keyword = expect(VeraTokenType.VAL)
        val name = expect(VeraTokenType.IDENTIFIER, "field name")
        expect(VeraTokenType.COLON)
        val type = parseTypeRef()
        if (at(VeraTokenType.BIND)) {
            throw VeraParseException.JavaSyntax(current(), "fields have no default values; pass the value to the constructor")
        }
        return VeraField(Identifier(name.text), type, spanFrom(keyword))
    }

    /** Parses a trait or interface after its keyword, [startToken]. */
    private fun parseTraitDeclaration(startToken: VeraToken, kind: VeraTraitKind): VeraTraitDeclaration {
        val name = expect(VeraTokenType.IDENTIFIER, "${kind.keyword} name")
        rejectTraitHeaderHabit(kind)
        val owner = Identifier(name.text)
        val requirements = parseNameList(VeraTokenType.REQUIRES, "required ${kind.keyword}")
        expect(VeraTokenType.LBRACE)
        skipEols()
        val methods = mutableListOf<VeraTraitMethod>()
        val cases = mutableListOf<VeraClassDeclaration>()
        val fields = mutableListOf<VeraField>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            if (at(VeraTokenType.VAL)) {
                fields += parseField()
                parseStatementTerminator()
            } else if (at(VeraTokenType.MUT)) {
                throw VeraParseException.MutableFieldNotSupported(current())
            } else if (at(VeraTokenType.IDENTIFIER) && peek().type == VeraTokenType.COLON) {
                throw VeraParseException.FieldWithoutVal(current())
            } else if (at(VeraTokenType.IDENTIFIER) && current().text in memberHabitWords) {
                rejectMemberHabit(className = null)
            } else if (at(VeraTokenType.IDENTIFIER) && kind == VeraTraitKind.INTERFACE) {
                throw VeraParseException.InterfaceCase(current())
            } else if (at(VeraTokenType.IDENTIFIER)) {
                cases += parseTraitCase(owner)
                // Cases are separated by commas or newlines: `trait Color { Red, Green, Blue }`.
                if (!match(VeraTokenType.COMMA)) {
                    parseStatementTerminator()
                }
            } else {
                val expected = when (kind) {
                    VeraTraitKind.TRAIT -> "a trait method (`fn`), field (`val`), or case"
                    VeraTraitKind.INTERFACE -> "an interface method (`fn`) or field (`val`)"
                }
                val fnToken = expect(VeraTokenType.FN, expected)
                methods += parseTraitMethod(fnToken)
                rejectTraitMethodBody(kind)
                parseStatementTerminator()
            }
            skipEols()
        }
        expect(VeraTokenType.RBRACE)
        return VeraTraitDeclaration(owner, methods, spanOf(startToken, name), cases, requirements, fields, kind)
    }

    /**
     * Parses a case in a trait's block: `Name [has Traits] [implements Interfaces] [{ fields and methods }]`.
     * The case is a class owned by the trait, named `Owner$Case` and displayed as `Owner.Case`.
     */
    private fun parseTraitCase(owner: Identifier): VeraClassDeclaration {
        val name = expect(VeraTokenType.IDENTIFIER, "case name")
        val traits = parseNameList(VeraTokenType.HAS, "trait name")
        val interfaces = parseNameList(VeraTokenType.IMPLEMENTS, "interface name")
        // Commas also separate cases, so a `has` or `implements` list must end at a body to stay unambiguous.
        if ((traits.isNotEmpty() || interfaces.isNotEmpty()) && !at(VeraTokenType.LBRACE)) {
            val keyword = if (interfaces.isNotEmpty()) "implements" else "has"
            throw VeraParseException.CaseTraitsWithoutBody(current(), name.text, keyword)
        }
        val (fields, methods) = if (at(VeraTokenType.LBRACE)) parseClassBody(name.text) else emptyList<VeraField>() to emptyList()
        return VeraClassDeclaration(
            name = Identifier("${owner.value}$${name.text}"),
            fields = fields,
            methods = methods,
            traits = listOf(owner) + traits,
            span = name.span(),
            owner = owner,
            interfaces = interfaces,
        )
    }

    private fun parseTraitMethod(fnToken: VeraToken): VeraTraitMethod {
        val name = expect(VeraTokenType.IDENTIFIER, "method name")
        val (parameters, returnType) = parseSignature()
        return VeraTraitMethod(Identifier(name.text), parameters, returnType, spanFrom(fnToken))
    }

    /** Parses `(params)` and an optional `: Type`; an omitted return type is [VeraType.UNKNOWN]. */
    private fun parseSignature(): Pair<List<VeraParameter>, VeraType> {
        expect(VeraTokenType.LPAREN)
        val parameters = mutableListOf<VeraParameter>()
        if (!at(VeraTokenType.RPAREN)) {
            parameters += parseParameter()
            while (match(VeraTokenType.COMMA) && !at(VeraTokenType.RPAREN)) {
                parameters += parseParameter()
            }
        }
        expect(VeraTokenType.RPAREN)
        val returnType = if (match(VeraTokenType.COLON)) {
            if (at(VeraTokenType.NONE)) {
                throw VeraParseException.ExplicitNoneReturnType(current())
            }
            parseTypeRef()
        } else {
            VeraType.UNKNOWN
        }
        return parameters to returnType
    }

    private fun parseParameter(): VeraParameter {
        val name = expect(VeraTokenType.IDENTIFIER, "parameter name")
        if (at(VeraTokenType.RPAREN) || at(VeraTokenType.COMMA)) {
            throw VeraParseException.JavaSyntax(current(), "parameter ${name.text} needs a type: write `${name.text}: Type`")
        }
        if (at(VeraTokenType.IDENTIFIER)) {
            throw VeraParseException.JavaSyntax(
                name,
                "Vera writes the type after the name: `${current().text}: ${veraTypeName(name.text)}`",
            )
        }
        expect(VeraTokenType.COLON)
        val type = parseTypeRef()
        if (at(VeraTokenType.BIND)) {
            throw VeraParseException.JavaSyntax(current(), "Vera has no default parameter values; callers pass every argument")
        }
        return VeraParameter(Identifier(name.text), type, spanFrom(name))
    }

    private fun parseTypeRef(): VeraType {
        return when (current().type) {
            VeraTokenType.INT_TYPE -> {
                advance()
                VeraType.INT
            }

            VeraTokenType.STRING_TYPE -> {
                advance()
                VeraType.STRING
            }

            VeraTokenType.BOOL_TYPE -> {
                advance()
                VeraType.BOOL
            }

            VeraTokenType.NONE -> {
                advance()
                VeraType.NONE
            }

            VeraTokenType.IDENTIFIER -> VeraType.ClassType(parseQualifiedName(advance()))

            else -> {
                val token = current()
                throw VeraParseException.ExpectedType(token)
            }
        }
    }

    private fun parseBlock(): List<VeraStatement> = parseBlockStatements()

    private fun parseBranchBlock(): VeraBlock {
        val statements = parseBlockStatements().toMutableList()
        val result = (statements.lastOrNull() as? VeraExpression)?.expression
        return if (result != null) {
            statements.removeLast()
            VeraImplicitReturnBlock(statements, result)
        } else {
            VeraNoneReturnBlock(statements)
        }
    }

    private fun parseBlockStatements(): List<VeraStatement> {
        expect(VeraTokenType.LBRACE)
        skipEols()
        val statements = mutableListOf<VeraStatement>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            statements += parseStatement()
        }
        expect(VeraTokenType.RBRACE)
        return statements
    }

    private fun parseStatement(): VeraStatement {
        rejectJavaStatement()
        val statement = when {
            at(VeraTokenType.VAL) -> parseBindStatement(advance(), mutable = false)
            at(VeraTokenType.MUT) -> {
                val keyword = advance()
                expect(VeraTokenType.VAL, "`val` after `mut`")
                if (at(VeraTokenType.IDENTIFIER) && peek().type in statementEnds) {
                    val name = advance()
                    VeraMutableParameter(Identifier(name.text), spanFrom(keyword))
                } else {
                    parseBindStatement(keyword, mutable = true)
                }
            }
            at(VeraTokenType.IDENTIFIER) && peek().type == VeraTokenType.BIND -> parseRebindStatement()
            at(VeraTokenType.RETURN) -> parseReturnStatement()
            at(VeraTokenType.WHILE) -> parseWhileStatement()
            at(VeraTokenType.BREAK) -> VeraBreakStatement(advance().span()).also { rejectLabeledJump() }
            at(VeraTokenType.CONTINUE) -> VeraContinueStatement(advance().span()).also { rejectLabeledJump() }
            at(VeraTokenType.FN) -> throw VeraParseException.NestedFunction(current())
            else -> VeraExpression(parseExpression()).also { veraExpression -> rejectMemberRebinding(veraExpression) }
        }
        parseStatementTerminator()
        return statement
    }

    /** Parses a binding after its `val` or `mut val`; [keyword] is the first keyword token. */
    private fun parseBindStatement(keyword: VeraToken, mutable: Boolean): VeraBindStatement {
        val name = expect(VeraTokenType.IDENTIFIER, "variable name")
        val type = if (match(VeraTokenType.COLON)) parseTypeRef() else VeraType.UNKNOWN
        if (type != VeraType.UNKNOWN && current().type in statementEnds) {
            throw VeraParseException.JavaSyntax(
                current(),
                "a `val` needs a value: write `val ${name.text}: ${type.render()} = ...`, or choose it with " +
                    "`val ${name.text} = if ... { ... } else { ... }`",
            )
        }
        expect(VeraTokenType.BIND)
        val expression = parseExpression()
        rejectChainedRebinding()
        return VeraBindStatement(Identifier(name.text), type, VeraExpression(expression), mutable, spanFrom(keyword))
    }

    private fun parseRebindStatement(): VeraRebindStatement {
        val name = expect(VeraTokenType.IDENTIFIER, "variable name")
        expect(VeraTokenType.BIND)
        if (at(VeraTokenType.LBRACE)) {
            // The AST has no representation for update blocks yet.
            throw VeraParseException.UnsupportedRebindUpdateBlock(current())
        }
        val expression = parseExpression()
        rejectChainedRebinding()
        return VeraRebindStatement(Identifier(name.text), VeraExpression(expression), spanFrom(name))
    }

    private fun parseReturnStatement(): VeraReturnStatement {
        val keyword = expect(VeraTokenType.RETURN)
        val expression = if (at(VeraTokenType.EOL) || at(VeraTokenType.EOF) || at(VeraTokenType.RBRACE)) {
            null
        } else {
            VeraExpression(parseExpression())
        }
        return VeraReturnStatement(expression, spanFrom(keyword))
    }

    private fun parseWhileStatement(): VeraWhileStatement {
        val keyword = expect(VeraTokenType.WHILE)
        val condition = parseExpression()
        val body = parseBlock()
        return VeraWhileStatement(condition, VeraNoneReturnBlock(body), spanFrom(keyword))
    }

    /** Ends a statement or member at a newline, at the end of input, or before a closing `}`. */
    private fun parseStatementTerminator() {
        when {
            at(VeraTokenType.EOL) -> skipEols()
            at(VeraTokenType.EOF) || at(VeraTokenType.RBRACE) -> Unit
            else -> {
                val token = current()
                throw VeraParseException.ExpectedEndOfLine(token)
            }
        }
    }

    // Precedence is encoded by this recursive-descent ladder: each parse function handles one
    // precedence level and delegates tighter-binding operands to the next function. `parseBinary`
    // consumes repeated operators at its level, making them left-associative. The ordering lives
    // in the call chain rather than in a separate precedence table.
    private fun parseExpression(): VeraPrimaryExpression = parseForwardApplication()

    private fun parseForwardApplication(): VeraPrimaryExpression =
        parseBinary(
            ::parseLogicalOr,
            mapOf(VeraTokenType.FORWARD_APPLICATION to VeraOperator.FORWARD_APPLICATION),
        )

    private fun parseLogicalOr(): VeraPrimaryExpression =
        parseBinary(::parseLogicalAnd, mapOf(VeraTokenType.OR to VeraOperator.OR))

    private fun parseLogicalAnd(): VeraPrimaryExpression =
        parseBinary(::parseEquality, mapOf(VeraTokenType.AND to VeraOperator.AND))

    private fun parseEquality(): VeraPrimaryExpression =
        parseBinary(
            ::parseComparison,
            mapOf(
                VeraTokenType.EQ to VeraOperator.EQUALS,
                VeraTokenType.NEQ to VeraOperator.NOT_EQUALS,
            ),
        )

    private fun parseComparison(): VeraPrimaryExpression =
        parseBinary(
            ::parseAdditive,
            mapOf(
                VeraTokenType.LT to VeraOperator.LESS,
                VeraTokenType.GT to VeraOperator.GREATER,
                VeraTokenType.LTE to VeraOperator.LESS_EQ,
                VeraTokenType.GTE to VeraOperator.GREATER_EQ,
            ),
        )

    private fun parseAdditive(): VeraPrimaryExpression =
        parseBinary(
            ::parseMultiplicative,
            mapOf(
                VeraTokenType.PLUS to VeraOperator.ADD,
                VeraTokenType.MINUS to VeraOperator.SUBTRACT,
            ),
        )

    private fun parseMultiplicative(): VeraPrimaryExpression =
        parseBinary(
            ::parseUnary,
            mapOf(
                VeraTokenType.STAR to VeraOperator.MULTIPLY,
                VeraTokenType.SLASH to VeraOperator.DIVIDE,
                VeraTokenType.PERCENT to VeraOperator.PERCENT,
            ),
        )

    private fun parseUnary(): VeraPrimaryExpression {
        val operatorToken = current()
        if (match(VeraTokenType.MINUS)) {
            if (at(VeraTokenType.INT_LITERAL)) {
                // Fold the sign into the literal so `-2147483648` (Int.MIN_VALUE) is representable.
                val token = advance()
                val value = "-${token.text}".toIntOrNull()
                    ?: throw VeraParseException.InvalidIntLiteral(token, negative = true)
                return VeraIntLiteral(value, spanOf(operatorToken, token))
            }
            val operand = parseUnary()
            return VeraUnaryOperation(VeraUnaryOperator.NEGATE, operand, spanFrom(operatorToken))
        }
        if (match(VeraTokenType.NOT)) {
            val operand = parseUnary()
            return VeraUnaryOperation(VeraUnaryOperator.NOT, operand, spanFrom(operatorToken))
        }
        if (at(VeraTokenType.PLUS)) {
            throw VeraParseException.JavaSyntax(current(), "Vera has no unary `+`; write the number itself")
        }
        return parseChainedPrimary()
    }

    private fun parseBinary(
        operand: () -> VeraPrimaryExpression,
        operators: Map<VeraTokenType, VeraOperator>,
    ): VeraPrimaryExpression {
        var left = operand()
        while (true) {
            val operator = operators[current().type] ?: break
            advance()
            val right = operand()
            left = VeraBinaryOperation(left, operator, right, SourceSpan(left.span.start, right.span.end))
        }
        return left
    }

    private fun parseChainedPrimary(): VeraPrimaryExpression {
        val base = parsePrimary()
        val data = mutableListOf<VeraChainedExpressionData>()
        while (true) {
            when {
                match(VeraTokenType.DOT) -> {
                    val name = expect(VeraTokenType.IDENTIFIER, "member name")
                    data += VeraMemberAccess(Identifier(name.text), name.span())
                }

                at(VeraTokenType.LPAREN) -> {
                    data += parseArgumentList()
                }

                else -> break
            }
        }
        if (data.isEmpty()) return base
        return VeraChainedExpression(base, data, SourceSpan(base.span.start, data.last().span.end))
    }

    private fun parseArgumentList(): VeraArguments {
        val openParenthesis = expect(VeraTokenType.LPAREN)
        val arguments = mutableListOf<VeraArgument>()
        if (!at(VeraTokenType.RPAREN)) {
            arguments += parseArgument()
            while (match(VeraTokenType.COMMA) && !at(VeraTokenType.RPAREN)) {
                arguments += parseArgument()
            }
        }
        expect(VeraTokenType.RPAREN)
        return VeraArguments(arguments, spanFrom(openParenthesis))
    }

    /** Parses `name = value` or a bare `value`. */
    private fun parseArgument(): VeraArgument {
        if (at(VeraTokenType.IDENTIFIER) && peek().type == VeraTokenType.BIND) {
            val name = advance()
            expect(VeraTokenType.BIND)
            val expression = parseExpression()
            return VeraArgument(Identifier(name.text), expression, spanFrom(name))
        }
        val expression = parseExpression()
        return VeraArgument(null, expression, expression.span)
    }

    private fun parsePrimary(): VeraPrimaryExpression {
        return when (current().type) {
            VeraTokenType.INT_LITERAL -> {
                val token = advance()
                val value = token.text.toIntOrNull()
                    ?: throw VeraParseException.InvalidIntLiteral(token)
                VeraIntLiteral(value, token.span())
            }

            VeraTokenType.STRING_LITERAL -> {
                val token = advance()
                VeraStringLiteral(token.text, token.span())
            }

            VeraTokenType.TEMPLATE_START -> parseStringTemplate()

            VeraTokenType.BOOL_LITERAL -> {
                val token = advance()
                VeraBoolLiteral(token.text == "True", token.span())
            }

            VeraTokenType.NONE -> VeraNoneLiteral(advance().span())

            VeraTokenType.IDENTIFIER if current().text == "new" && peek().type == VeraTokenType.IDENTIFIER ->
                throw javaNew()

            VeraTokenType.IDENTIFIER -> {
                val token = advance()
                VeraIdentifierAsExpression(Identifier(token.text), token.span())
            }

            VeraTokenType.LPAREN -> {
                advance()
                val expression = parseExpression()
                expect(VeraTokenType.RPAREN)
                expression
            }

            VeraTokenType.IF -> parseIfExpression()

            VeraTokenType.MATCH -> parseMatchExpression()

            else -> {
                val token = current()
                throw VeraParseException.ExpectedExpression(token)
            }
        }
    }

    /** Parses `TEMPLATE_START expression { TEMPLATE_MIDDLE expression } TEMPLATE_END`. */
    private fun parseStringTemplate(): VeraStringTemplate {
        val startToken = advance()
        val parts = mutableListOf<VeraTemplatePart>(VeraTemplateText(startToken.text))
        while (true) {
            if (at(VeraTokenType.TEMPLATE_MIDDLE) || at(VeraTokenType.TEMPLATE_END)) {
                throw VeraParseException.EmptyTemplatePlaceholder(current())
            }
            parts += VeraTemplateValue(parseExpression())
            val token = current()
            when (token.type) {
                VeraTokenType.TEMPLATE_MIDDLE -> parts += VeraTemplateText(advance().text)
                VeraTokenType.TEMPLATE_END -> {
                    parts += VeraTemplateText(advance().text)
                    return VeraStringTemplate(parts, spanFrom(startToken))
                }
                else -> throw VeraParseException.ExpectedToken("`}` to close the template placeholder", token)
            }
        }
    }

    private fun parseIfExpression(): VeraIfExpression {
        val keyword = expect(VeraTokenType.IF)
        val condition = parseExpression()
        requireBraces()
        val thenBlock = parseBranchBlock()
        val elseBlock = if (matchElseAfterOptionalEols()) {
            if (at(VeraTokenType.IF)) {
                VeraImplicitReturnBlock(
                    emptyList(),
                    parseIfExpression(),
                )
            } else {
                requireBraces()
                parseBranchBlock()
            }
        } else {
            null
        }
        return VeraIfExpression(
            condition = condition,
            thenBlock = thenBlock,
            elseBlock = elseBlock,
            span = spanFrom(keyword),
        )
    }

    // Java habits: these checks only run where the parser would otherwise fail, and turn the generic
    // error into one that says what to write in Vera instead.

    private fun rejectJavaModifier(token: VeraToken) {
        if (token.type == VeraTokenType.IDENTIFIER && token.text == "sealed" && peek().type == VeraTokenType.TRAIT) {
            throw VeraParseException.JavaSyntax(token, "traits are closed by default; remove `sealed`")
        }
        if (token.type == VeraTokenType.IDENTIFIER && token.text == "sealed" && peek().type == VeraTokenType.INTERFACE) {
            throw VeraParseException.JavaSyntax(token, "interfaces are open; declare a `trait` for a closed set of classes")
        }
        if (token.type == VeraTokenType.IDENTIFIER && token.text in javaModifiers) {
            throw VeraParseException.JavaSyntax(
                token,
                "Vera has no `${token.text}` modifier; declarations are written without modifiers",
            )
        }
    }

    /** `a = b = 3`: a rebinding produces no value, so it cannot be chained. */
    private fun rejectChainedRebinding() {
        if (at(VeraTokenType.BIND)) {
            throw VeraParseException.JavaSyntax(
                current(),
                "rebinding is a statement, not an expression; rebind each variable on its own line",
            )
        }
    }

    /** `point.x = 3`: fields are immutable, so only a whole variable can be rebound. */
    private fun rejectMemberRebinding(veraExpression: VeraExpression) {
        val veraChained = veraExpression.expression as? VeraChainedExpression ?: return
        val veraMember = veraChained.data.lastOrNull() as? VeraMemberAccess ?: return
        if (at(VeraTokenType.BIND)) {
            throw VeraParseException.JavaSyntax(
                current(),
                "field ${veraMember.member.display} cannot be rebound; objects are immutable, so create a new object " +
                    "with the new value",
            )
        }
    }

    /** `break outer` or `continue outer`: Vera jumps only out of the innermost loop. */
    private fun rejectLabeledJump() {
        if (at(VeraTokenType.IDENTIFIER)) {
            throw VeraParseException.JavaSyntax(
                current(),
                "Vera has no labeled jumps; `break` and `continue` target the innermost `while`",
            )
        }
    }

    /** `if (c) print(x)`: Vera always needs a block. */
    private fun requireBraces() {
        if (!at(VeraTokenType.LBRACE)) {
            throw VeraParseException.JavaSyntax(current(), "Vera requires braces: write `if condition { ... } else { ... }`")
        }
    }

    /** Java and Kotlin pattern forms in a `match` arm, at the token where the pattern is expected. */
    private fun rejectPatternHabit() {
        val token = current()
        val next = peek()
        val hint = when {
            token.type == VeraTokenType.ELSE || token.type == VeraTokenType.IDENTIFIER && token.text == "_" ->
                "Vera has no catch-all arm; list every case, so adding a case later is a compile error"
            token.type == VeraTokenType.IDENTIFIER && (token.text == "is" || token.text == "case") &&
                next.type == VeraTokenType.IDENTIFIER ->
                "write the class as the pattern: `${next.text} ->`, or `${next.text}(field) ->` to bind fields"
            else -> return
        }
        throw VeraParseException.JavaSyntax(token, hint)
    }

    /** Java and Kotlin declarations that Vera writes differently, at a token that starts no declaration. */
    private fun rejectTopLevelHabit(token: VeraToken) {
        val next = peek()
        val hint = when {
            token.type == VeraTokenType.VAL || token.type == VeraTokenType.MUT ||
                token.text == "const" && next.type == VeraTokenType.VAL ->
                "Vera has no top-level values; use a function such as `fn limit(): Int = 1`"
            (token.type == VeraTokenType.IDENTIFIER && token.text != "record" || token.type in builtinTypeTokens) &&
                next.type == VeraTokenType.IDENTIFIER && tokens.getOrNull(position + 2)?.type == VeraTokenType.LPAREN ->
                "Vera declares functions with `fn`: write `fn ${next.text}(...): Type`"
            token.type != VeraTokenType.IDENTIFIER -> null
            token.text == "fun" -> "Vera declares functions with `fn`"
            token.text == "enum" -> "Vera writes enums as a trait with cases: `trait Color { Red, Green }`"
            token.text == "data" && next.type == VeraTokenType.CLASS ->
                "every Vera class is a data class: write `class`, and add `has Eq, Display` for equality and printing"
            token.text == "record" ->
                "Vera declares fields in the class body: `class Point { val x: Int }`; the constructor is generated"
            token.text == "object" -> "a class without fields is a singleton: write `class ${next.text} { }`"
            token.text == "sealed" && next.type == VeraTokenType.CLASS ->
                "Vera has no inheritance; a closed set of classes is a trait with cases: `trait Shape { Circle { ... } }`"
            else -> null
        } ?: return
        throw VeraParseException.JavaSyntax(token, hint)
    }

    /** Kotlin primary constructors, supertype lists, and generics after a class name. */
    private fun rejectClassHeaderHabit() {
        val token = current()
        val hint = when (token.type) {
            VeraTokenType.LPAREN ->
                "Vera declares fields in the class body: `class Point { val x: Int }`; the constructor is generated"
            VeraTokenType.COLON -> "Vera lists traits with `has` (`class Point has Eq`) and interfaces with `implements`"
            VeraTokenType.LT -> "Vera has no generic types yet"
            else -> return
        }
        throw VeraParseException.JavaSyntax(token, hint)
    }

    /** Kotlin supertype lists and generics after a trait or interface name. */
    private fun rejectTraitHeaderHabit(kind: VeraTraitKind) {
        val token = current()
        val hint = when (token.type) {
            VeraTokenType.COLON -> "${if (kind == VeraTraitKind.INTERFACE) "an" else "a"} ${kind.keyword} lists what it " +
                "requires with `requires`: `requires Named`"
            VeraTokenType.LT -> "Vera has no generic types yet"
            else -> return
        }
        throw VeraParseException.JavaSyntax(token, hint)
    }

    /** Words that start a Java or Kotlin member Vera writes differently. */
    private val memberHabitWords = setOf("fun", "override", "constructor", "init", "default")

    /** Java and Kotlin member forms in a class, trait, or interface body. */
    private fun rejectMemberHabit(className: String?) {
        val token = current()
        if (token.type != VeraTokenType.IDENTIFIER) return
        val next = peek()
        val hint = when {
            token.text == "fun" -> "Vera declares methods with `fn`"
            token.text == "override" || token.text == "default" ->
                "Vera has no `${token.text}`; a method with the trait's signature implements it"
            token.text == "constructor" || token.text == "init" ||
                token.text == className && next.type == VeraTokenType.LPAREN ->
                "the constructor is generated from the fields; Vera has no constructor bodies"
            else -> return
        }
        throw VeraParseException.JavaSyntax(token, hint)
    }

    /** A Java `default` or Kotlin interface method body after a trait or interface method signature. */
    private fun rejectTraitMethodBody(kind: VeraTraitKind) {
        if (at(VeraTokenType.BIND) || at(VeraTokenType.LBRACE)) {
            throw VeraParseException.JavaSyntax(
                current(),
                "${kind.keyword} methods have no bodies; each class implements the method itself, because Vera has no inheritance",
            )
        }
    }

    private fun rejectJavaInheritance() {
        val token = current()
        if (token.type == VeraTokenType.IDENTIFIER && token.text == "extends") {
            throw VeraParseException.JavaSyntax(
                token,
                "Vera has no inheritance; share behaviour through traits with `has` and interfaces with `implements`, or compose classes",
            )
        }
        if (token.type == VeraTokenType.HAS) {
            throw VeraParseException.JavaSyntax(token, "write `has` before `implements`: `has Trait implements Interface`")
        }
    }

    /** Rejects Java statement forms: `int x = 1`, `Point p = ...`, `new Point()`, `final`, `for (...)`. */
    private fun rejectJavaStatement() {
        val token = current()
        val next = peek()
        val startsWithType = token.type in builtinTypeTokens || token.type == VeraTokenType.IDENTIFIER
        when {
            token.type == VeraTokenType.IDENTIFIER && token.text == "new" && next.type == VeraTokenType.IDENTIFIER ->
                throw javaNew()

            token.type == VeraTokenType.IDENTIFIER && token.text == "var" && next.type == VeraTokenType.IDENTIFIER ->
                throw VeraParseException.JavaSyntax(
                    token,
                    "Vera has no `var`; declare with `val`, or `mut val` for a binding you rebind",
                )

            token.type == VeraTokenType.IDENTIFIER && token.text == "final" ->
                throw VeraParseException.JavaSyntax(
                    token,
                    "Vera has no `final`; bindings are immutable by default, so write `val`",
                )

            token.type == VeraTokenType.IDENTIFIER && token.text in javaModifiers &&
                next.type == VeraTokenType.IDENTIFIER -> rejectJavaModifier(token)

            token.type == VeraTokenType.IDENTIFIER && token.text == "for" && next.type == VeraTokenType.LPAREN ->
                throw VeraParseException.JavaSyntax(token, "Vera has no `for` loop; use `while`")

            token.type == VeraTokenType.IDENTIFIER && token.text == "do" && next.type == VeraTokenType.LBRACE ->
                throw VeraParseException.JavaSyntax(token, "Vera has no `do`-`while`; use `while True { ... }` with a `break`")

            token.type == VeraTokenType.IDENTIFIER && (token.text == "when" || token.text == "switch") &&
                (next.type == VeraTokenType.LPAREN || next.type == VeraTokenType.LBRACE) ->
                throw VeraParseException.JavaSyntax(
                    token,
                    "Vera has no `${token.text}`; use `match` on a trait, or an `if` / `else if` chain",
                )

            token.type == VeraTokenType.IDENTIFIER &&
                (next.type == VeraTokenType.VAL || next.type == VeraTokenType.IDENTIFIER && next.text == "var") ->
                throw VeraParseException.JavaSyntax(
                    token,
                    "Vera has no `${token.text}` modifier; declare the binding with its value: `val name = ...`",
                )

            startsWithType && next.type == VeraTokenType.IDENTIFIER ->
                throw VeraParseException.JavaSyntax(
                    token,
                    "Vera declares variables with `val`: write `val ${next.text}: ${veraTypeName(token.text)} = ...`",
                )
        }
    }

    private fun veraTypeName(javaName: String): String = javaTypeNames[javaName] ?: javaName

    /** `new Point(...)` at the current token. */
    private fun javaNew(): VeraParseException.JavaSyntax =
        VeraParseException.JavaSyntax(
            current(),
            "Vera has no `new`; call the class directly: `${peek().text}(...)`",
        )

    /** Parses `match scrutinee { Pattern -> body ... }`, one arm per line. */
    private fun parseMatchExpression(): VeraMatchExpression {
        val keyword = expect(VeraTokenType.MATCH)
        val scrutinee = parseExpression()
        expect(VeraTokenType.LBRACE, "`{` to start the match arms")
        skipEols()
        val arms = mutableListOf<VeraMatchArm>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            arms += parseMatchArm()
            parseStatementTerminator()
        }
        expect(VeraTokenType.RBRACE)
        if (arms.isEmpty()) {
            throw VeraParseException.ExpectedToken("a match arm", tokens[position - 1])
        }
        return VeraMatchExpression(scrutinee, arms, spanFrom(keyword))
    }

    /** Parses `Pattern -> expression`, `Pattern -> { block }`, or `Pattern -> return / break / continue`. */
    private fun parseMatchArm(): VeraMatchArm {
        val pattern = parsePattern()
        if (at(VeraTokenType.COMMA)) {
            throw VeraParseException.JavaSyntax(current(), "a match arm has one pattern; write a separate arm for each case")
        }
        expect(VeraTokenType.ARROW, "`->` after the pattern")
        val body = when {
            at(VeraTokenType.LBRACE) -> parseBranchBlock()
            // `Quit -> return "quit"`: a jump on its own needs no braces.
            at(VeraTokenType.RETURN) -> VeraNoneReturnBlock(listOf(parseReturnStatement()))
            at(VeraTokenType.BREAK) -> VeraNoneReturnBlock(listOf(VeraBreakStatement(advance().span())))
            at(VeraTokenType.CONTINUE) -> VeraNoneReturnBlock(listOf(VeraContinueStatement(advance().span())))
            else -> VeraImplicitReturnBlock(emptyList(), parseExpression())
        }
        return VeraMatchArm(pattern, body, SourceSpan(pattern.span.start, tokens[position - 1].span().end))
    }

    /** Parses `ClassName` or `ClassName(field, field = binding)`. */
    private fun parsePattern(): VeraPattern {
        rejectPatternHabit()
        val className = expect(VeraTokenType.IDENTIFIER, "a class name in the pattern")
        val patternName = parseQualifiedName(className)
        val fields = mutableListOf<VeraFieldPattern>()
        if (match(VeraTokenType.LPAREN)) {
            if (at(VeraTokenType.RPAREN)) {
                throw VeraParseException.EmptyPatternParentheses(current(), patternName.display)
            }
            fields += parseFieldPattern()
            while (match(VeraTokenType.COMMA) && !at(VeraTokenType.RPAREN)) {
                fields += parseFieldPattern()
            }
            expect(VeraTokenType.RPAREN)
        }
        return VeraPattern(patternName, fields, spanFrom(className))
    }

    /**
     * Completes a name started by [first]: `Name`, or `Owner.Case` for a case nested in a trait, which
     * is named `Owner$Case`. Used where a type or pattern names a class.
     */
    private fun parseQualifiedName(first: VeraToken): Identifier {
        if (at(VeraTokenType.DOT) && peek().type == VeraTokenType.IDENTIFIER) {
            advance()
            val case = advance()
            return Identifier("${first.text}\$${case.text}")
        }
        return Identifier(first.text)
    }

    /** Parses `field` (binds the field to a local of the same name) or `field = binding`. */
    private fun parseFieldPattern(): VeraFieldPattern {
        if ((at(VeraTokenType.IDENTIFIER) || current().type in builtinTypeTokens) && peek().type == VeraTokenType.IDENTIFIER) {
            throw VeraParseException.JavaSyntax(current(), "patterns bind fields by name, without a type: write `${peek().text}`")
        }
        val field = expect(VeraTokenType.IDENTIFIER, "a field name")
        val binding = if (match(VeraTokenType.BIND)) expect(VeraTokenType.IDENTIFIER, "a binding name") else field
        return VeraFieldPattern(Identifier(field.text), Identifier(binding.text), spanFrom(field))
    }

    private fun matchElseAfterOptionalEols(): Boolean {
        val positionBeforeEols = position
        skipEols()
        if (at(VeraTokenType.ELSE)) {
            advance()
            return true
        }
        position = positionBeforeEols
        return false
    }

    private fun skipEols() {
        while (at(VeraTokenType.EOL)) {
            advance()
        }
    }

    /** The span from the start of [first] to the end of [last]. */
    private fun spanOf(first: VeraToken, last: VeraToken): SourceSpan = SourceSpan(first.span().start, last.span().end)

    /** The span from the start of [first] to the end of the most recently consumed token. */
    private fun spanFrom(first: VeraToken): SourceSpan = spanOf(first, tokens[maxOf(position - 1, 0)])

    private fun current(): VeraToken = tokens[position]

    private fun peek(): VeraToken = tokens[minOf(position + 1, tokens.lastIndex)]

    private fun at(type: VeraTokenType): Boolean = current().type == type

    private fun advance(): VeraToken {
        val token = current()
        if (token.type != VeraTokenType.EOF) {
            position++
        }
        return token
    }

    private fun match(type: VeraTokenType): Boolean {
        if (!at(type)) return false
        advance()
        return true
    }

    private fun expect(type: VeraTokenType, what: String? = null): VeraToken {
        if (!at(type)) {
            val token = current()
            throw VeraParseException.ExpectedToken(what ?: type.describeExpected(), token)
        }
        return advance()
    }

    private companion object {
        val javaModifiers = setOf("public", "private", "protected", "static", "final", "abstract")
        val javaTypeNames = mapOf("int" to "Int", "Integer" to "Int", "boolean" to "Bool", "Boolean" to "Bool")
        /** Tokens that can follow a complete statement. */
        val statementEnds = setOf(VeraTokenType.EOL, VeraTokenType.EOF, VeraTokenType.RBRACE)
        val builtinTypeTokens = setOf(
            VeraTokenType.INT_TYPE,
            VeraTokenType.STRING_TYPE,
            VeraTokenType.BOOL_TYPE,
            VeraTokenType.NONE,
        )
    }

    /** How an expected token reads in an error message: a backticked `{` rather than `LBRACE`. */
    private fun VeraTokenType.describeExpected(): String = when (this) {
        VeraTokenType.LPAREN -> "`(`"
        VeraTokenType.RPAREN -> "`)`"
        VeraTokenType.LBRACE -> "`{`"
        VeraTokenType.RBRACE -> "`}`"
        VeraTokenType.COLON -> "`:`"
        VeraTokenType.COMMA -> "`,`"
        VeraTokenType.BIND -> "`=`"
        VeraTokenType.IDENTIFIER -> "a name"
        else -> "`${name.lowercase()}`"
    }
}
