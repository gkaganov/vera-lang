package vera.ast

import vera.shared.model.Identifier

data class VeraProgram(val declarations: List<VeraDeclaration>)

sealed interface VeraDeclaration
data class VeraFunctionDeclaration(val name: Identifier, val parameters: List<VeraParameter>, val returnType: VeraType, val body: VeraExplicitReturnBlock) : VeraDeclaration

sealed interface VeraStatement
data class VeraBindStatement(val name: Identifier, val type: VeraType, val expression: VeraExpression) : VeraStatement
data class VeraRebindStatement(val name: Identifier, val expression: VeraExpression) : VeraStatement
data class VeraReturnStatement(val expression: VeraExpression?) : VeraStatement
data class VeraWhileStatement(val condition: VeraPrimaryExpression, val body: VeraUnitReturnBlock) : VeraStatement
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

sealed interface VeraBlock {
    val statements: List<VeraStatement>
}

/** A block whose statements use explicit `return` statements. */
data class VeraExplicitReturnBlock(override val statements: List<VeraStatement>) : VeraBlock

/** A block whose value is its final expression; it returns implicitly. */
data class VeraImplicitReturnBlock(
    override val statements: List<VeraStatement>,
    val result: VeraPrimaryExpression,
) : VeraBlock

/** A block that produces no value and returns implicitly as `Unit`. */
data class VeraUnitReturnBlock(override val statements: List<VeraStatement>) : VeraBlock

data class VeraIfExpression(
    val condition: VeraPrimaryExpression,
    val thenBlock: VeraBlock,
    val elseBlock: VeraBlock?,
) : VeraPrimaryExpression

data class VeraParameter(val name: Identifier, val type: VeraType)
enum class VeraType { INT, STRING, BOOL, UNIT, UNKNOWN }
