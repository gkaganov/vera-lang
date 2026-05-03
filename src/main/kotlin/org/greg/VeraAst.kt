package org.greg

import arrow.core.NonEmptyList
import org.greg.antlr.VeraParser

class VeraAst {

    data class Program(val declarations: List<Declaration>)

    sealed interface Declaration
    data class FunctionDeclaration(val name: String, val params: List<Parameter>, val returnType: Type, val statements: List<Statement>) : Declaration

    sealed interface Statement
    data class BindStatement(val name: String, val type: Type, val expression: Expression) : Statement
    data class RebindStatement(val name: String, val expression: Expression) : Statement
    data class ReturnStatement(val expression: Expression?) : Statement
    data class Expression(
        val chainedExpressions: NonEmptyList<ChainedExpression>,
        val chainOperators: List<InfixOperator>,
    ) : Statement, PrimaryExpression

    data class ChainedExpression(val primaryExpression: PrimaryExpression, val data: List<ChainedExpressionData> = emptyList())

    sealed interface ChainedExpressionData
    data class MemberAccess(val member: String) : ChainedExpressionData
    data class Arguments(val expressions: List<Expression>) : ChainedExpressionData

    enum class InfixOperator { PLUS, MINUS, MUL, DIV }

    sealed interface PrimaryExpression
    sealed interface Literal : PrimaryExpression
    data class IntLiteral(val value: Int) : Literal
    data class StringLiteral(val value: String) : Literal
    enum class BoolLiteral : Literal { TRUE, FALSE }
    data class ExpressionIdentifier(val identifier: String) : PrimaryExpression

    data class Parameter(val name: String, val type: Type)
    enum class Type { INT, STRING, BOOL, UNIT }

    fun mapProgram(ctx: VeraParser.ProgramContext): Program {
        return Program(ctx.declaration().map { decl -> mapDeclaration(decl) })
    }

    private fun mapDeclaration(ctx: VeraParser.DeclarationContext): Declaration {
        @Suppress("SENSELESS_COMPARISON")
        return if (ctx.functionDeclaration() != null) {
            val decl = ctx.functionDeclaration()
            val name = decl.name?.text ?: error("fnName null")
            val params = decl.parameterClause().parameters()?.parameter()
                ?.map { param -> mapParam(param) }
                ?: emptyList()
            val returnType = mapType(decl.returnType()?.typeRef())
            val statements = decl.block().statement().map { stmt -> mapStatement(stmt) }
            FunctionDeclaration(name, params, returnType, statements)
        } else {
            error("unhandled declaration type")
        }
    }

    private fun mapParam(ctx: VeraParser.ParameterContext): Parameter {
        val name = ctx.name?.text ?: error("no paramName")
        val type = mapType(ctx.typeRef())
        return Parameter(name, type)
    }

    private fun mapType(ctx: VeraParser.TypeRefContext?): Type {
        return if (ctx == null) {
            Type.UNIT
        } else if (ctx.STRING_TYPE() != null) {
            Type.STRING
        } else if (ctx.INT_TYPE() != null) {
            Type.INT
        } else {
            error("unknown typeRef ${ctx.text}")
        }
    }

    private fun mapStatement(ctx: VeraParser.StatementContext): Statement {
        val bindStatement = ctx.bindStatement()
        val rebindStatement = ctx.rebindStatement()
        val returnStatement = ctx.returnStatement()
        val expression = ctx.expression()
        return if (bindStatement != null) {
            val name = bindStatement.name?.text ?: error("no bindStatement name")
            val type = mapType(bindStatement.typeRef())
            val expression = mapExpression(bindStatement.expression())
            BindStatement(name, type, expression)
        } else if (rebindStatement != null) {
            val name = rebindStatement.name?.text ?: error("no rebindStatement name")
            val expression = rebindStatement.rebindRhs().expression() ?: error("no expressinon in rebindStatement")
            RebindStatement(name, mapExpression(expression))
        } else if (returnStatement != null) {
            // expression can legally be null if the rturn value is Unit
            val expression = returnStatement.expression()?.let { mapExpression(it) }
            ReturnStatement(expression)
        } else if (expression != null) {
            val chainedExpression = ChainedExpression(mapExpression(expression))
            val chainOperators = expression.infixOperator().map(::mapInfixOperator)
            Expression(NonEmptyList.of(chainedExpression), chainOperators)
        } else {
            error("unknown statement")
        }
    }

    private fun mapInfixOperator(ctx: VeraParser.InfixOperatorContext): InfixOperator {
        return when (ctx.text) {
            ctx.PLUS()?.text -> InfixOperator.PLUS
            ctx.MINUS()?.text -> InfixOperator.MINUS
            ctx.MUL()?.text -> InfixOperator.MUL
            ctx.DIV()?.text -> InfixOperator.DIV
            else -> error("unknown infix operator ${ctx.text}")
        }
    }

    private fun mapExpression(ctx: VeraParser.ExpressionContext): Expression {
        val headChainedExpression = mapChainedExpression(ctx.chainedExpression(0) ?: error("firstChainedExpression is always present"))
        val tailChainedExpressions = ctx.chainedExpression().drop(1).map(::mapChainedExpression)
        val chainedExpressions = NonEmptyList.of(headChainedExpression, *tailChainedExpressions.toTypedArray())
        val operators = ctx.infixOperator().map(::mapInfixOperator)
        return Expression(chainedExpressions, operators)
    }

    private fun mapChainedExpression(ctx: VeraParser.ChainedExpressionContext): ChainedExpression {
        val primaryExpression = mapPrimaryExpression(ctx.primaryExpression())
        val data = ctx.children?.mapNotNull { child ->
            when (child) {
                is VeraParser.MemberAccessContext -> MemberAccess(child.name?.text ?: error("member access name null"))
                is VeraParser.ArgumentListContext -> Arguments(child.arguments()?.expression()?.map { expr -> mapExpression(expr) }.orEmpty())
                else -> null
            }
        }.orEmpty()
        return ChainedExpression(primaryExpression, data)
    }

    private fun mapPrimaryExpression(ctx: VeraParser.PrimaryExpressionContext): PrimaryExpression {
        val literal = ctx.literal()
        val identifier = ctx.IDENTIFIER()
        val expression = ctx.expression()
        return if (literal != null) {
            mapLiteral(literal)
        } else if (identifier != null) {
            ExpressionIdentifier(identifier.text)
        } else if (expression != null) {
            mapExpression(expression)
        } else {
            error("unknown chainedExpression")
        }
    }

    private fun mapLiteral(ctx: VeraParser.LiteralContext): Literal {
        val intLiteral = ctx.INT_LITERAL()
        val stringLiteral = ctx.STRING_LITERAL()
        val boolLiteral = ctx.BOOL_LITERAL()
        return if (intLiteral != null) {
            IntLiteral(intLiteral.text.toInt())
        } else if (stringLiteral != null) {
            val raw = ctx.text.trim('"')
            StringLiteral(raw)
        } else if (boolLiteral != null) {
            if (boolLiteral.text == "True") BoolLiteral.TRUE else BoolLiteral.FALSE
        } else {
            error("unknown literal")
        }
    }
}
