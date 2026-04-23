package org.greg

import arrow.core.NonEmptyList

data class Program(val declarations: List<Declaration>) {}

sealed interface Declaration
data class FunctionDeclaration(val name: String, val params: List<Parameter>, val returnType: Type, val statements: List<Statement>) : Declaration {}

sealed interface Statement
data class BindStatement(val name: String, val type: Type, val expression: Expression) : Statement
data class RebindStatement(val name: String, val expression: Expression) : Statement
data class ReturnStatement(val expression: Expression?): Statement
data class Expression(val chainedExpressions: NonEmptyList<Expression>) : Statement, PrimaryExpression

data class ChainedExpression(val primaryExpression: PrimaryExpression, val data: List<ChainedExpressionData>)

sealed interface ChainedExpressionData
data class MemberAccess(val member: String) : ChainedExpressionData
data class ArgumentList(val arguments: List<Expression>) : ChainedExpressionData

sealed interface PrimaryExpression
data class ExpressionLiteral(val literal: Literal) : PrimaryExpression
data class ExpressionIdentifier(val identifier: String): PrimaryExpression

data class Literal(val symbol: String) { }
data class Parameter(val name: String, val type: Type) {}
enum class Type() { INT, STRING, VOID }

class AstMapper {
}
