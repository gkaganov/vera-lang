package vera.parser

import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBlock
import vera.ast.VeraBoolLiteral
import vera.ast.VeraChainedExpression
import vera.ast.VeraChainedExpressionData
import vera.ast.VeraDeclaration
import vera.ast.VeraExplicitReturnBlock
import vera.ast.VeraExpression
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
import vera.ast.VeraRecordDeclaration
import vera.ast.VeraRecordField
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraType
import vera.ast.VeraUnitLiteral
import vera.ast.VeraUnitReturnBlock
import vera.ast.VeraWhileStatement
import vera.shared.model.Identifier

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
            at(VeraTokenType.RECORD) -> parseRecordDeclaration()
            else -> {
                val token = current()
                throw VeraParseException.ExpectedDeclaration(token, token.location())
            }
        }
    }

    private fun parseFunctionDeclaration(): VeraFunctionDeclaration {
        expect(VeraTokenType.FN)
        val name = expect(VeraTokenType.IDENTIFIER, "function name")
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
        val body = parseBlock()
        return VeraFunctionDeclaration(
            Identifier(name.text),
            parameters,
            returnType,
            VeraExplicitReturnBlock(body),
        )
    }

    private fun parseRecordDeclaration(): VeraRecordDeclaration {
        expect(VeraTokenType.RECORD)
        val name = expect(VeraTokenType.IDENTIFIER, "record name")
        expect(VeraTokenType.LBRACE)
        skipEols()
        val fields = mutableListOf<VeraRecordField>()
        while (!at(VeraTokenType.RBRACE) && !at(VeraTokenType.EOF)) {
            val fieldName = expect(VeraTokenType.IDENTIFIER, "field name")
            expect(VeraTokenType.COLON)
            fields += VeraRecordField(Identifier(fieldName.text), parseTypeRef())
            parseStatementTerminator()
            skipEols()
        }
        expect(VeraTokenType.RBRACE)
        return VeraRecordDeclaration(Identifier(name.text), fields)
    }

    private fun parseParameter(): VeraParameter {
        val name = expect(VeraTokenType.IDENTIFIER, "parameter name")
        expect(VeraTokenType.COLON)
        return VeraParameter(Identifier(name.text), parseTypeRef())
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
                VeraType.Record(Identifier(token.text))
            }

            else -> {
                val token = current()
                throw VeraParseException.ExpectedType(token, token.location())
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
            else -> VeraExpression(parseExpression())
        }
        parseStatementTerminator()
        return statement
    }

    private fun parseBindStatement(): VeraBindStatement {
        advance() // var | val
        val name = expect(VeraTokenType.IDENTIFIER, "variable name")
        val type = if (match(VeraTokenType.COLON)) parseTypeRef() else VeraType.UNKNOWN
        expect(VeraTokenType.BIND)
        val expression = parseExpression()
        return VeraBindStatement(Identifier(name.text), type, VeraExpression(expression))
    }

    private fun parseRebindStatement(): VeraRebindStatement {
        val name = expect(VeraTokenType.IDENTIFIER, "variable name")
        expect(VeraTokenType.BIND)
        if (at(VeraTokenType.LBRACE)) {
            // The AST has no representation for update blocks yet.
            throw VeraParseException.UnsupportedRebindUpdateBlock(current().location())
        }
        val expression = parseExpression()
        return VeraRebindStatement(Identifier(name.text), VeraExpression(expression))
    }

    private fun parseReturnStatement(): VeraReturnStatement {
        expect(VeraTokenType.RETURN)
        val expression = if (at(VeraTokenType.EOL) || at(VeraTokenType.EOF) || at(VeraTokenType.RBRACE)) {
            null
        } else {
            VeraExpression(parseExpression())
        }
        return VeraReturnStatement(expression)
    }

    private fun parseWhileStatement(): VeraWhileStatement {
        expect(VeraTokenType.WHILE)
        val condition = parseExpression()
        val body = parseBlock()
        return VeraWhileStatement(condition, VeraUnitReturnBlock(body))
    }

    private fun parseStatementTerminator() {
        when {
            at(VeraTokenType.EOL) -> skipEols()
            at(VeraTokenType.EOF) -> Unit
            else -> {
                val token = current()
                throw VeraParseException.ExpectedEndOfLine(token, token.location())
            }
        }
    }

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
            ::parseChainedPrimary,
            mapOf(
                VeraTokenType.STAR to VeraOperator.MULTIPLY,
                VeraTokenType.SLASH to VeraOperator.DIVIDE,
                VeraTokenType.PERCENT to VeraOperator.PERCENT,
            ),
        )

    private fun parseBinary(
        operand: () -> VeraPrimaryExpression,
        operators: Map<VeraTokenType, VeraOperator>,
    ): VeraPrimaryExpression {
        var left = operand()
        while (true) {
            val operator = operators[current().type] ?: break
            advance()
            val right = operand()
            left = VeraBinaryOperation(left, operator, right)
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
                    data += VeraMemberAccess(Identifier(name.text))
                }

                at(VeraTokenType.LPAREN) -> {
                    data += VeraArguments(parseArgumentList())
                }

                else -> break
            }
        }
        return if (data.isEmpty()) base else VeraChainedExpression(base, data)
    }

    private fun parseArgumentList(): List<VeraPrimaryExpression> {
        expect(VeraTokenType.LPAREN)
        val arguments = mutableListOf<VeraPrimaryExpression>()
        if (!at(VeraTokenType.RPAREN)) {
            arguments += parseExpression()
            while (match(VeraTokenType.COMMA)) {
                arguments += parseExpression()
            }
        }
        expect(VeraTokenType.RPAREN)
        return arguments
    }

    private fun parsePrimary(): VeraPrimaryExpression {
        return when (current().type) {
            VeraTokenType.INT_LITERAL -> {
                val token = advance()
                val value = token.text.toIntOrNull()
                    ?: throw VeraParseException.InvalidIntLiteral(token, token.location())
                VeraIntLiteral(value)
            }

            VeraTokenType.STRING_LITERAL -> {
                val token = advance()
                VeraStringLiteral(token.text.removeSurrounding("\""))
            }

            VeraTokenType.BOOL_LITERAL -> {
                val token = advance()
                if (token.text == "True") VeraBoolLiteral.TRUE else VeraBoolLiteral.FALSE
            }

            VeraTokenType.UNIT_TYPE -> {
                advance()
                VeraUnitLiteral
            }

            VeraTokenType.IDENTIFIER -> {
                val token = advance()
                VeraIdentifierAsExpression(Identifier(token.text))
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
                throw VeraParseException.ExpectedExpression(token, token.location())
            }
        }
    }

    private fun parseIfExpression(): VeraIfExpression {
        expect(VeraTokenType.IF)
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
            throw VeraParseException.ExpectedToken(what ?: type.name, token, token.location())
        }
        return advance()
    }
}
