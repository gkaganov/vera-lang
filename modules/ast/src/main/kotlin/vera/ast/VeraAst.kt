package vera.ast

import arrow.core.NonEmptyList
import vera.shared.model.Identifier

data class Program(val declarations: List<Declaration>)

sealed interface Declaration
data class VeraFunctionDeclaration(val name: Identifier, val parameters: List<VeraParameter>, val returnType: VeraType, val statements: List<VeraStatement>) : Declaration

sealed interface VeraStatement
data class BindStatement(val name: Identifier, val type: VeraType, val expression: Expression) : VeraStatement
data class RebindStatement(val name: Identifier, val expression: Expression) : VeraStatement
data class ReturnStatement(val expression: Expression?) : VeraStatement
data class Expression(
    val chainedExpressions: NonEmptyList<ChainedExpression>,
    val chainOperators: List<InfixOperator>,
) : VeraStatement, PrimaryExpression

data class ChainedExpression(val primaryExpression: PrimaryExpression, val data: List<ChainedExpressionData> = emptyList())

sealed interface ChainedExpressionData
data class VeraMemberAccess(val member: Identifier) : ChainedExpressionData
data class VeraArguments(val expressions: List<Expression>) : ChainedExpressionData

enum class InfixOperator { ADD, SUBTRACT, MULTIPLY, DIVIDE, EQUALS, NOT_EQUALS }

sealed interface PrimaryExpression
sealed interface Literal : PrimaryExpression
data class IntLiteral(val value: Int) : Literal
data class StringLiteral(val value: String) : Literal
enum class BoolLiteral : Literal { TRUE, FALSE }
data class IdentifierAsExpression(val identifier: Identifier) : PrimaryExpression
data class IfExpression(val condition: Expression, val thenBlock: NonEmptyList<VeraStatement>, val elseBlock: List<VeraStatement>) : PrimaryExpression

data class VeraParameter(val name: Identifier, val type: VeraType)
enum class VeraType { INT, STRING, BOOL, UNIT, UNKNOWN }
