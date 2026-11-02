package vera.parser

import arrow.core.NonEmptyList
import org.antlr.v4.kotlinruntime.BufferedTokenStream
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.ParserRuleContext
import org.antlr.v4.kotlinruntime.tree.TerminalNode
import vera.antlr.VeraLexer
import vera.antlr.VeraParser
import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBoolLiteral
import vera.ast.VeraChainedExpression
import vera.ast.VeraDeclaration
import vera.ast.VeraExpression
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
import vera.ast.VeraIntLiteral
import vera.ast.VeraLiteral
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

class VeraParser {

    fun parseProgram(code: String): VeraProgram {
        val lexer = VeraLexer(CharStreams.fromString(code))
        val parser = VeraParser(BufferedTokenStream(lexer))
        return mapProgram(parser.program())
    }

    private fun mapProgram(ctx: VeraParser.ProgramContext): VeraProgram {
        return VeraProgram(ctx.declaration().map { decl -> mapDeclaration(decl) })
    }

    private fun mapDeclaration(ctx: VeraParser.DeclarationContext): VeraDeclaration {
        @Suppress("SENSELESS_COMPARISON")
        return if (ctx.functionDeclaration() != null) {
            val decl = ctx.functionDeclaration()
            val name = decl.name?.text ?: error("fnName null")
            val params = decl.parameterClause().parameters()?.parameter()
                ?.map { param -> mapParam(param) }
                ?: emptyList()
            val returnType = mapType(decl.returnType()?.typeRef())
            val statements = decl.block().statement().map { stmt -> mapStatement(stmt) }
            VeraFunctionDeclaration(Identifier(name), params, returnType, statements)
        } else {
            error("unhandled declaration type")
        }
    }

    private fun mapParam(ctx: VeraParser.ParameterContext): VeraParameter {
        val name = ctx.name?.text ?: error("no paramName")
        val type = mapType(ctx.typeRef())
        return VeraParameter(Identifier(name), type)
    }

    private fun mapType(ctx: VeraParser.TypeRefContext?): VeraType {
        return if (ctx == null) {
            VeraType.UNKNOWN
        } else if (ctx.UNIT_TYPE() != null) {
            VeraType.UNIT
        } else if (ctx.STRING_TYPE() != null) {
            VeraType.STRING
        } else if (ctx.INT_TYPE() != null) {
            VeraType.INT
        } else {
            error("unknown typeRef ${ctx.text}")
        }
    }

    private fun mapStatement(ctx: VeraParser.StatementContext): VeraStatement {
        val bindStatement = ctx.bindStatement()
        val rebindStatement = ctx.rebindStatement()
        val returnStatement = ctx.returnStatement()
        val expression = ctx.expression()
        return if (bindStatement != null) {
            val name = bindStatement.name?.text ?: error("no bindStatement name")
            val type = mapType(bindStatement.typeRef())
            val expression = mapExpression(bindStatement.expression())
            VeraBindStatement(Identifier(name), type, expression)
        } else if (rebindStatement != null) {
            val name = rebindStatement.name?.text ?: error("no rebindStatement name")
            val expression = rebindStatement.rebindRhs().expression() ?: error("no expressinon in rebindStatement")
            VeraRebindStatement(Identifier(name), mapExpression(expression))
        } else if (returnStatement != null) {
            // expression can legally be null if the rturn value is Unit
            val expression = returnStatement.expression()?.let { mapExpression(it) }
            VeraReturnStatement(expression)
        } else if (expression != null) {
            VeraExpression(mapForwardApplication(expression.forwardApplication()))
        } else {
            error("unknown statement")
        }
    }

    private fun mapForwardApplication(ctx: VeraParser.ForwardApplicationContext): VeraPrimaryExpression {
        val head = mapLogicalOr(ctx.logicalOr(0) ?: error("logicalOr required"))
        val tails = ctx.logicalOr().drop(1)
        val ops = ctx.PIPE().map { VeraOperator.PIPE }
        return buildBinaryTree(head, ops, tails) { mapLogicalOr(it) }
    }

    private fun mapLogicalOr(ctx: VeraParser.LogicalOrContext): VeraPrimaryExpression {
        val head = mapLogicalAnd(ctx.logicalAnd(0) ?: error("logicalAnd required"))
        val tails = ctx.logicalAnd().drop(1)
        val ops = ctx.OR().map { VeraOperator.OR }
        return buildBinaryTree(head, ops, tails) { mapLogicalAnd(it) }
    }

    private fun mapLogicalAnd(ctx: VeraParser.LogicalAndContext): VeraPrimaryExpression {
        val head = mapEquality(ctx.equality(0) ?: error("equality required"))
        val tails = ctx.equality().drop(1)
        val ops = ctx.AND().map { VeraOperator.AND }
        return buildBinaryTree(head, ops, tails) { mapEquality(it) }
    }

    private fun mapEquality(ctx: VeraParser.EqualityContext): VeraPrimaryExpression {
        val head = mapComparison(ctx.comparison(0) ?: error("comparison required"))
        val tails = ctx.comparison().drop(1)
        val ops = operatorsInSourceOrder(ctx)
        return buildBinaryTree(head, ops, tails) { mapComparison(it) }
    }

    private fun mapComparison(ctx: VeraParser.ComparisonContext): VeraPrimaryExpression {
        val head = mapAdditive(ctx.additive(0) ?: error("additive required"))
        val tails = ctx.additive().drop(1)
        val ops = operatorsInSourceOrder(ctx)
        return buildBinaryTree(head, ops, tails) { mapAdditive(it) }
    }

    private fun mapAdditive(ctx: VeraParser.AdditiveContext): VeraPrimaryExpression {
        val head = mapMultiplicative(ctx.multiplicative(0) ?: error("multiplicative required"))
        val tails = ctx.multiplicative().drop(1)
        val ops = operatorsInSourceOrder(ctx)
        return buildBinaryTree(head, ops, tails) { mapMultiplicative(it) }
    }

    private fun mapMultiplicative(ctx: VeraParser.MultiplicativeContext): VeraPrimaryExpression {
        val head = mapChainedPrimary(ctx.chainedPrimary(0) ?: error("chainedPrimary required"))
        val tails = ctx.chainedPrimary().drop(1)
        val ops = operatorsInSourceOrder(ctx)
        return buildBinaryTree(head, ops, tails) { mapChainedPrimary(it) }
    }

    /**
     * Operators of a binary-expression context in source order. Each precedence level's
     * context contains only that level's operator tokens interleaved with operands, so
     * filtering direct terminal children preserves the original left-to-right order
     * (collecting per-token-kind lists would lose it).
     */
    private fun operatorsInSourceOrder(ctx: ParserRuleContext): List<VeraOperator> {
        return ctx.children.orEmpty()
            .filterIsInstance<TerminalNode>()
            .mapNotNull { tokenOperators[it.symbol.type] }
    }

    private val tokenOperators: Map<Int, VeraOperator> = mapOf(
        VeraParser.Tokens.EQ to VeraOperator.EQUALS,
        VeraParser.Tokens.NEQ to VeraOperator.NOT_EQUALS,
        VeraParser.Tokens.LT to VeraOperator.LESS,
        VeraParser.Tokens.GT to VeraOperator.GREATER,
        VeraParser.Tokens.LTE to VeraOperator.LESS_EQ,
        VeraParser.Tokens.GTE to VeraOperator.GREATER_EQ,
        VeraParser.Tokens.PLUS to VeraOperator.ADD,
        VeraParser.Tokens.MINUS to VeraOperator.SUBTRACT,
        VeraParser.Tokens.STAR to VeraOperator.MULTIPLY,
        VeraParser.Tokens.SLASH to VeraOperator.DIVIDE,
        VeraParser.Tokens.PERCENT to VeraOperator.PERCENT,
    )

    private fun <T> buildBinaryTree(
        head: VeraPrimaryExpression,
        operators: List<VeraOperator>,
        tails: List<T>,
        mapTail: (T) -> VeraPrimaryExpression,
    ): VeraPrimaryExpression {
        if (operators.size != tails.size) {
            error("the number of operators (${operators.size}) and operands (${tails.size + 1}) must match")
        }
        return tails.foldIndexed(head) { index, acc, tail ->
            VeraBinaryOperation(
                left = acc,
                operator = operators[index],
                right = mapTail(tail)
            )
        }
    }

    private fun mapPrimary(ctx: VeraParser.PrimaryContext): VeraPrimaryExpression {
        val literal = ctx.literal()
        val identifier = ctx.IDENTIFIER()
        val ifExpression = ctx.ifExpression()
        val parenExpr = ctx.expression()
        return when {
            literal != null -> mapLiteral(literal)
            identifier != null -> VeraIdentifierAsExpression(Identifier(identifier.text))
            parenExpr != null -> mapForwardApplication(parenExpr.forwardApplication())
            ifExpression != null -> VeraIfExpression(
                condition = mapForwardApplication(
                    ifExpression.condition?.forwardApplication() ?: error("condition in if expression must be present")
                ),
                thenBlock = NonEmptyList.of(ifExpression.thenBranch?.statement()?.map(::mapStatement) ?: error("thenBlock must exist")),
                elseBlock = ifExpression.elseBranch?.statement()?.map(::mapStatement).orEmpty(),
            )
            else -> error("unknown primary expression")
        }
    }

    private fun mapChainedPrimary(ctx: VeraParser.ChainedPrimaryContext): VeraPrimaryExpression {
        val base = mapPrimary(ctx.primary())
        val data = ctx.children?.mapNotNull { child ->
            when (child) {
                is VeraParser.MemberAccessContext -> VeraMemberAccess(Identifier(child.name?.text ?: error("member access name null")))
                is VeraParser.ArgumentListContext -> VeraArguments(
                    child.arguments()?.expression()?.map { expr -> mapForwardApplication(expr.forwardApplication()) }.orEmpty()
                )
                else -> null
            }
        } ?: emptyList()
        return if (data.isEmpty()) {
            base
        } else {
            VeraChainedExpression(base, data)
        }
    }

    /** Wraps an expression parse-tree node in the statement-level [VeraExpression] AST node. */
    private fun mapExpression(ctx: VeraParser.ExpressionContext): VeraExpression {
        return VeraExpression(mapForwardApplication(ctx.forwardApplication()))
    }

    private fun mapLiteral(ctx: VeraParser.LiteralContext): VeraLiteral {
        val intLiteral = ctx.INT_LITERAL()
        val stringLiteral = ctx.STRING_LITERAL()
        val boolLiteral = ctx.BOOL_LITERAL()
        return if (intLiteral != null) {
            VeraIntLiteral(intLiteral.text.toInt())
        } else if (stringLiteral != null) {
            val raw = ctx.text.trim('"')
            VeraStringLiteral(raw)
        } else if (boolLiteral != null) {
            if (boolLiteral.text == "True") VeraBoolLiteral.TRUE else VeraBoolLiteral.FALSE
        } else {
            error("unknown literal")
        }
    }
}
