package vera.parser

import arrow.core.NonEmptyList
import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBoolLiteral
import vera.ast.VeraChainedExpression
import vera.ast.VeraChainedExpressionData
import vera.ast.VeraDeclaration
import vera.ast.VeraExpression
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
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
import vera.ast.VeraType
import vera.shared.model.Identifier

object VeraParser {
    fun parse(tokens: List<Token>): VeraProgram = ParserState(tokens).parse()
}

/** Mutable state for a single parse run. */
private class ParserState(private val tokens: List<Token>) {

    private var position = 0

    fun parse(): VeraProgram {
        val declarations = mutableListOf<VeraDeclaration>()
        skipEols()
        while (!at(TokenType.EOF)) {
            declarations += parseDeclaration()
            skipEols()
        }
        return VeraProgram(declarations)
    }

    private fun parseDeclaration(): VeraDeclaration {
        return when {
            at(TokenType.FN) -> parseFunctionDeclaration()
            else -> error("expected a function declaration")
        }
    }

    private fun parseFunctionDeclaration(): VeraFunctionDeclaration {
        expect(TokenType.FN)
        val name = expect(TokenType.IDENTIFIER, "function name")
        expect(TokenType.LPAREN)
        val parameters = mutableListOf<VeraParameter>()
        if (!at(TokenType.RPAREN)) {
            parameters += parseParameter()
            while (match(TokenType.COMMA)) {
                parameters += parseParameter()
            }
        }
        expect(TokenType.RPAREN)
        val returnType = if (match(TokenType.COLON)) parseTypeRef() else VeraType.UNKNOWN
        val statements = parseBlock()
        return VeraFunctionDeclaration(Identifier(name.text), parameters, returnType, statements)
    }

    private fun parseParameter(): VeraParameter {
        val name = expect(TokenType.IDENTIFIER, "parameter name")
        expect(TokenType.COLON)
        return VeraParameter(Identifier(name.text), parseTypeRef())
    }

    private fun parseTypeRef(): VeraType {
        return when (current().type) {
            TokenType.INT_TYPE -> {
                advance()
                VeraType.INT
            }
            TokenType.STRING_TYPE -> {
                advance()
                VeraType.STRING
            }
            TokenType.BOOL_TYPE -> {
                advance()
                VeraType.BOOL
            }
            TokenType.UNIT_TYPE -> {
                advance()
                VeraType.UNIT
            }
            else -> error("expected a type")
        }
    }

    private fun parseBlock(): List<VeraStatement> {
        expect(TokenType.LBRACE)
        skipEols()
        val statements = mutableListOf<VeraStatement>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            statements += parseStatement()
        }
        expect(TokenType.RBRACE)
        return statements
    }

    private fun parseStatement(): VeraStatement {
        val statement = when {
            at(TokenType.VAR) || at(TokenType.VAL) -> parseBindStatement()
            at(TokenType.IDENTIFIER) && peek(1).type == TokenType.REBIND -> parseRebindStatement()
            at(TokenType.RETURN) -> parseReturnStatement()
            else -> VeraExpression(parseExpression())
        }
        parseStatementTerminator()
        return statement
    }

    private fun parseBindStatement(): VeraBindStatement {
        advance() // var | val
        val name = expect(TokenType.IDENTIFIER, "variable name")
        val type = if (match(TokenType.COLON)) parseTypeRef() else VeraType.UNKNOWN
        expect(TokenType.BIND)
        val expression = parseExpression()
        return VeraBindStatement(Identifier(name.text), type, VeraExpression(expression))
    }

    private fun parseRebindStatement(): VeraRebindStatement {
        val name = expect(TokenType.IDENTIFIER, "variable name")
        expect(TokenType.REBIND)
        if (at(TokenType.LBRACE)) {
            // The AST has no representation for update blocks yet.
            error("rebind update blocks are not supported")
        }
        return VeraRebindStatement(Identifier(name.text), VeraExpression(parseExpression()))
    }

    private fun parseReturnStatement(): VeraReturnStatement {
        expect(TokenType.RETURN)
        val expression = if (at(TokenType.EOL) || at(TokenType.EOF) || at(TokenType.RBRACE)) {
            null
        } else {
            VeraExpression(parseExpression())
        }
        return VeraReturnStatement(expression)
    }

    private fun parseStatementTerminator() {
        when {
            at(TokenType.EOL) -> skipEols()
            at(TokenType.EOF) -> Unit
            else -> error("expected end of line")
        }
    }

    private fun parseExpression(): VeraPrimaryExpression = parseForwardApplication()

    private fun parseForwardApplication(): VeraPrimaryExpression =
        parseBinary(::parseLogicalOr, mapOf(TokenType.PIPE to VeraOperator.PIPE))

    private fun parseLogicalOr(): VeraPrimaryExpression =
        parseBinary(::parseLogicalAnd, mapOf(TokenType.OR to VeraOperator.OR))

    private fun parseLogicalAnd(): VeraPrimaryExpression =
        parseBinary(::parseEquality, mapOf(TokenType.AND to VeraOperator.AND))

    private fun parseEquality(): VeraPrimaryExpression =
        parseBinary(
            ::parseComparison,
            mapOf(
                TokenType.EQ to VeraOperator.EQUALS,
                TokenType.NEQ to VeraOperator.NOT_EQUALS,
            ),
        )

    private fun parseComparison(): VeraPrimaryExpression =
        parseBinary(
            ::parseAdditive,
            mapOf(
                TokenType.LT to VeraOperator.LESS,
                TokenType.GT to VeraOperator.GREATER,
                TokenType.LTE to VeraOperator.LESS_EQ,
                TokenType.GTE to VeraOperator.GREATER_EQ,
            ),
        )

    private fun parseAdditive(): VeraPrimaryExpression =
        parseBinary(
            ::parseMultiplicative,
            mapOf(
                TokenType.PLUS to VeraOperator.ADD,
                TokenType.MINUS to VeraOperator.SUBTRACT,
            ),
        )

    private fun parseMultiplicative(): VeraPrimaryExpression =
        parseBinary(
            ::parseChainedPrimary,
            mapOf(
                TokenType.STAR to VeraOperator.MULTIPLY,
                TokenType.SLASH to VeraOperator.DIVIDE,
                TokenType.PERCENT to VeraOperator.PERCENT,
            ),
        )

    private fun parseBinary(
        operand: () -> VeraPrimaryExpression,
        operators: Map<TokenType, VeraOperator>,
    ): VeraPrimaryExpression {
        var left = operand()
        while (true) {
            val operator = operators[current().type] ?: break
            advance()
            left = VeraBinaryOperation(left, operator, operand())
        }
        return left
    }

    private fun parseChainedPrimary(): VeraPrimaryExpression {
        val base = parsePrimary()
        val data = mutableListOf<VeraChainedExpressionData>()
        while (true) {
            when {
                match(TokenType.DOT) -> {
                    val name = expect(TokenType.IDENTIFIER, "member name")
                    data += VeraMemberAccess(Identifier(name.text))
                }
                at(TokenType.LPAREN) -> {
                    data += VeraArguments(parseArgumentList())
                }
                else -> break
            }
        }
        return if (data.isEmpty()) base else VeraChainedExpression(base, data)
    }

    private fun parseArgumentList(): List<VeraPrimaryExpression> {
        expect(TokenType.LPAREN)
        val arguments = mutableListOf<VeraPrimaryExpression>()
        if (!at(TokenType.RPAREN)) {
            arguments += parseExpression()
            while (match(TokenType.COMMA)) {
                arguments += parseExpression()
            }
        }
        expect(TokenType.RPAREN)
        return arguments
    }

    private fun parsePrimary(): VeraPrimaryExpression {
        return when (current().type) {
            TokenType.INT_LITERAL -> {
                val token = advance()
                VeraIntLiteral(token.text.toInt())
            }
            TokenType.STRING_LITERAL -> {
                val token = advance()
                VeraStringLiteral(token.text.removeSurrounding("\""))
            }
            TokenType.BOOL_LITERAL -> {
                val token = advance()
                if (token.text == "True") VeraBoolLiteral.TRUE else VeraBoolLiteral.FALSE
            }
            TokenType.IDENTIFIER -> {
                val token = advance()
                VeraIdentifierAsExpression(Identifier(token.text))
            }
            TokenType.LPAREN -> {
                advance()
                val expression = parseExpression()
                expect(TokenType.RPAREN)
                expression
            }
            TokenType.IF -> parseIfExpression()
            else -> error("expected an expression")
        }
    }

    private fun parseIfExpression(): VeraIfExpression {
        expect(TokenType.IF)
        val condition = parseExpression()
        val thenBlock = parseBlock()
        val elseBlock = if (matchElseAfterOptionalEols()) parseBlock() else emptyList()
        return VeraIfExpression(
            condition = condition,
            thenBlock = NonEmptyList.of(thenBlock),
            elseBlock = elseBlock,
        )
    }

    private fun matchElseAfterOptionalEols(): Boolean {
        val positionBeforeEols = position
        skipEols()
        if (at(TokenType.ELSE)) {
            advance()
            return true
        }
        position = positionBeforeEols
        return false
    }

    private fun skipEols() {
        while (at(TokenType.EOL)) {
            advance()
        }
    }

    private fun current(): Token = tokens[position]

    private fun peek(offset: Int): Token = tokens[minOf(position + offset, tokens.lastIndex)]

    private fun at(type: TokenType): Boolean = current().type == type

    private fun advance(): Token {
        val token = current()
        if (token.type != TokenType.EOF) {
            position++
        }
        return token
    }

    private fun match(type: TokenType): Boolean {
        if (!at(type)) return false
        advance()
        return true
    }

    private fun expect(type: TokenType, what: String? = null): Token {
        if (!at(type)) {
            error("expected ${what ?: type}")
        }
        return advance()
    }

    private fun error(message: String): Nothing {
        val token = current()
        throw IllegalStateException(
            "$message at line ${token.line}, column ${token.column} (found '${token.text}')"
        )
    }
}
