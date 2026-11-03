package vera.ast

import arrow.core.NonEmptyList
import vera.shared.model.Identifier

data class VeraProgram(val declarations: List<VeraDeclaration>)

sealed interface VeraDeclaration
data class VeraFunctionDeclaration(val name: Identifier, val parameters: List<VeraParameter>, val returnType: VeraType, val statements: List<VeraStatement>) : VeraDeclaration

sealed interface VeraStatement
data class VeraBindStatement(val name: Identifier, val type: VeraType, val expression: VeraExpression) : VeraStatement
data class VeraRebindStatement(val name: Identifier, val expression: VeraExpression) : VeraStatement
data class VeraReturnStatement(val expression: VeraExpression?) : VeraStatement
data class VeraWhileStatement(val condition: VeraPrimaryExpression, val body: List<VeraStatement>) : VeraStatement
data class VeraExpression(val expression: VeraPrimaryExpression) : VeraStatement

data class VeraChainedExpression(val primaryExpression: VeraPrimaryExpression, val data: List<VeraChainedExpressionData> = emptyList()) : VeraPrimaryExpression

sealed interface VeraChainedExpressionData
data class VeraMemberAccess(val member: Identifier) : VeraChainedExpressionData
data class VeraArguments(val expressions: List<VeraPrimaryExpression>) : VeraChainedExpressionData

enum class VeraOperator(val symbol: String) {
    PIPE("|"),
    OR("or"),
    AND("and"),
    EQUALS("=="), NOT_EQUALS("!="),
    LESS("<"), GREATER(">"), LESS_EQ("<="), GREATER_EQ(">="),
    ADD("+"), SUBTRACT("-"),
    MULTIPLY("*"), DIVIDE("/"), PERCENT("%")
}

sealed interface VeraPrimaryExpression
data class VeraBinaryOperation(
    val left: VeraPrimaryExpression,
    val operator: VeraOperator,
    val right: VeraPrimaryExpression,
) : VeraPrimaryExpression
sealed interface VeraLiteral : VeraPrimaryExpression
data class VeraIntLiteral(val value: Int) : VeraLiteral
data class VeraStringLiteral(val value: String) : VeraLiteral
enum class VeraBoolLiteral : VeraLiteral { TRUE, FALSE }
data class VeraIdentifierAsExpression(val identifier: Identifier) : VeraPrimaryExpression
data class VeraIfExpression(val condition: VeraPrimaryExpression, val thenBlock: NonEmptyList<VeraStatement>, val elseBlock: List<VeraStatement>) : VeraPrimaryExpression

data class VeraParameter(val name: Identifier, val type: VeraType)
enum class VeraType { INT, STRING, BOOL, UNIT, UNKNOWN }
