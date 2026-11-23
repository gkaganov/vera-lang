package vera.parser

import vera.ast.VeraArgument
import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBlock
import vera.ast.VeraBoolLiteral
import vera.ast.VeraBreakStatement
import vera.ast.VeraChainedExpression
import vera.ast.VeraChainedExpressionData
import vera.ast.VeraClassDeclaration
import vera.ast.VeraContinueStatement
import vera.ast.VeraDeclaration
import vera.ast.VeraExplicitReturnBlock
import vera.ast.VeraExpression
import vera.ast.VeraField
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
import vera.ast.VeraImplicitReturnBlock
import vera.ast.VeraIntLiteral
import vera.ast.VeraMemberAccess
import vera.ast.VeraOperator
import vera.ast.VeraParameter
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
import vera.ast.VeraTraitMethod
import vera.ast.VeraType
import vera.ast.VeraUnaryOperation
import vera.ast.VeraUnaryOperator
import vera.ast.VeraUnitLiteral
import vera.ast.VeraUnitReturnBlock
import vera.ast.VeraWhileStatement
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
            at(VeraTokenType.TRAIT) -> parseTraitDeclaration()
            else -> {
                val token = current()
                throw VeraParseException.ExpectedDeclaration(token)
            }
        }
    }

    private fun parseFunctionDeclaration(): VeraFunctionDeclaration {
        val fnToken = expect(VeraTokenType.FN)
        val name = expect(VeraTokenType.IDENTIFIER, "function name")
        val (parameters, returnType) = parseSignature()
        val body = parseBlock()
        return VeraFunctionDeclaration(
            Identifier(name.text),
            parameters,
            returnType,
            VeraExplicitReturnBlock(body),
            spanOf(fnToken, name),
        )
    }

    private fun parseClassDeclaration(): VeraClassDeclaration {
        val classToken = expect(VeraTokenType.CLASS)
        val name = expect(VeraTokenType.IDENTIFIER, "class name")
        val traits = mutableListOf<Identifier>()
        if (match(VeraTokenType.HAS)) {
            traits += Identifier(expect(VeraTokenType.IDENTIFIER, "trait name").text)
            while (match(VeraTokenType.COMMA)) {
                traits += Identifier(expect(VeraTokenType.IDENTIFIER, "trait name").text)
            }
        }
        expect(VeraTokenType.LBRACE)
        skipEols()
        val fields = mutableListOf<VeraField>()
        val methods = mutableListOf<VeraFunctionDeclaration>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            if (at(VeraTokenType.FN)) {
                methods += parseFunctionDeclaration()
            } else {
                val fieldName = expect(VeraTokenType.IDENTIFIER, "field name")
                expect(VeraTokenType.COLON)
                val fieldType = parseTypeRef()
                fields += VeraField(Identifier(fieldName.text), fieldType, spanFrom(fieldName))
            }
            parseStatementTerminator()
            skipEols()
        }
        expect(VeraTokenType.RBRACE)
        return VeraClassDeclaration(Identifier(name.text), fields, methods, traits, spanOf(classToken, name))
    }

    private fun parseTraitDeclaration(): VeraTraitDeclaration {
        val traitToken = expect(VeraTokenType.TRAIT)
        val name = expect(VeraTokenType.IDENTIFIER, "trait name")
        expect(VeraTokenType.LBRACE)
        skipEols()
        val methods = mutableListOf<VeraTraitMethod>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            val fnToken = expect(VeraTokenType.FN, "trait method")
            methods += parseTraitMethod(fnToken)
            parseStatementTerminator()
            skipEols()
        }
        expect(VeraTokenType.RBRACE)
        return VeraTraitDeclaration(Identifier(name.text), methods, spanOf(traitToken, name))
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
            while (match(VeraTokenType.COMMA)) {
                parameters += parseParameter()
            }
        }
        expect(VeraTokenType.RPAREN)
        val returnType = if (match(VeraTokenType.COLON)) parseTypeRef() else VeraType.UNKNOWN
        return parameters to returnType
    }

    private fun parseParameter(): VeraParameter {
        val name = expect(VeraTokenType.IDENTIFIER, "parameter name")
        expect(VeraTokenType.COLON)
        val type = parseTypeRef()
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

            VeraTokenType.UNIT_TYPE -> {
                advance()
                VeraType.UNIT
            }

            VeraTokenType.IDENTIFIER -> {
                val token = advance()
                VeraType.ClassType(Identifier(token.text))
            }

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
            VeraUnitReturnBlock(statements)
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
        val statement = when {
            at(VeraTokenType.VAR) || at(VeraTokenType.VAL) -> parseBindStatement()
            at(VeraTokenType.IDENTIFIER) && peek().type == VeraTokenType.BIND -> parseRebindStatement()
            at(VeraTokenType.RETURN) -> parseReturnStatement()
            at(VeraTokenType.WHILE) -> parseWhileStatement()
            at(VeraTokenType.BREAK) -> VeraBreakStatement(advance().span())
            at(VeraTokenType.CONTINUE) -> VeraContinueStatement(advance().span())
            else -> VeraExpression(parseExpression())
        }
        parseStatementTerminator()
        return statement
    }

    private fun parseBindStatement(): VeraBindStatement {
        val keyword = advance() // var | val
        val name = expect(VeraTokenType.IDENTIFIER, "variable name")
        val type = if (match(VeraTokenType.COLON)) parseTypeRef() else VeraType.UNKNOWN
        expect(VeraTokenType.BIND)
        val expression = parseExpression()
        return VeraBindStatement(Identifier(name.text), type, VeraExpression(expression), spanFrom(keyword))
    }

    private fun parseRebindStatement(): VeraRebindStatement {
        val name = expect(VeraTokenType.IDENTIFIER, "variable name")
        expect(VeraTokenType.BIND)
        if (at(VeraTokenType.LBRACE)) {
            // The AST has no representation for update blocks yet.
            throw VeraParseException.UnsupportedRebindUpdateBlock(current())
        }
        val expression = parseExpression()
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
        return VeraWhileStatement(condition, VeraUnitReturnBlock(body), spanFrom(keyword))
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
                    ?: throw VeraParseException.InvalidIntLiteral(token)
                return VeraIntLiteral(value, spanOf(operatorToken, token))
            }
            val operand = parseUnary()
            return VeraUnaryOperation(VeraUnaryOperator.NEGATE, operand, spanFrom(operatorToken))
        }
        if (match(VeraTokenType.NOT)) {
            val operand = parseUnary()
            return VeraUnaryOperation(VeraUnaryOperator.NOT, operand, spanFrom(operatorToken))
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
            while (match(VeraTokenType.COMMA)) {
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

            VeraTokenType.UNIT_TYPE -> VeraUnitLiteral(advance().span())

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
        val thenBlock = parseBranchBlock()
        val elseBlock = if (matchElseAfterOptionalEols()) {
            if (at(VeraTokenType.IF)) {
                VeraImplicitReturnBlock(
                    emptyList(),
                    parseIfExpression(),
                )
            } else {
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

    /** How an expected token reads in an error message: `'{'` rather than `LBRACE`. */
    private fun VeraTokenType.describeExpected(): String = when (this) {
        VeraTokenType.LPAREN -> "'('"
        VeraTokenType.RPAREN -> "')'"
        VeraTokenType.LBRACE -> "'{'"
        VeraTokenType.RBRACE -> "'}'"
        VeraTokenType.COLON -> "':'"
        VeraTokenType.COMMA -> "','"
        VeraTokenType.BIND -> "'='"
        VeraTokenType.IDENTIFIER -> "a name"
        else -> "'${name.lowercase()}'"
    }
}
