package vera.ast

import arrow.core.NonEmptyList

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
data class IfExpression(val condition: Expression, val thenBlock: NonEmptyList<Statement>, val elseBlock: List<Statement>) : PrimaryExpression

data class Parameter(val name: String, val type: Type)
enum class Type { INT, STRING, BOOL, UNIT }
