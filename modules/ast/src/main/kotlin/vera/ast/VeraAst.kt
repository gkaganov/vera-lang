package vera.ast

import vera.shared.model.Identifier

data class VeraProgram(val declarations: List<VeraDeclaration>)

sealed interface VeraDeclaration
data class VeraFunctionDeclaration(val name: Identifier, val parameters: List<VeraParameter>, val returnType: VeraType, val body: VeraExplicitReturnBlock) : VeraDeclaration
data class VeraRecordDeclaration(
    val name: Identifier,
    val fields: List<VeraRecordField>,
    val methods: List<VeraFunctionDeclaration> = emptyList(),
) : VeraDeclaration
data class VeraRecordField(val name: Identifier, val type: VeraType)

sealed interface VeraStatement
data class VeraBindStatement(val name: Identifier, val type: VeraType, val expression: VeraExpression) : VeraStatement
data class VeraRebindStatement(val name: Identifier, val expression: VeraExpression) : VeraStatement
data class VeraReturnStatement(val expression: VeraExpression?) : VeraStatement
data class VeraWhileStatement(val condition: VeraPrimaryExpression, val body: VeraUnitReturnBlock) : VeraStatement
data class VeraExpression(val expression: VeraPrimaryExpression) : VeraStatement
data object VeraBreakStatement : VeraStatement
data object VeraContinueStatement : VeraStatement

data class VeraChainedExpression(val primaryExpression: VeraPrimaryExpression, val data: List<VeraChainedExpressionData> = emptyList()) : VeraPrimaryExpression

sealed interface VeraChainedExpressionData
data class VeraMemberAccess(val member: Identifier) : VeraChainedExpressionData
data class VeraArguments(val expressions: List<VeraPrimaryExpression>) : VeraChainedExpressionData

enum class VeraOperator(val symbol: String) {
    FORWARD_APPLICATION("|>"),
    OR("or"),
    AND("and"),
    EQUALS("=="), NOT_EQUALS("!="),
    LESS("<"), GREATER(">"), LESS_EQ("<="), GREATER_EQ(">="),
    ADD("+"), SUBTRACT("-"),
    MULTIPLY("*"), DIVIDE("/"), PERCENT("%")
}

enum class VeraUnaryOperator(val symbol: String) {
    NEGATE("-")
}

sealed interface VeraPrimaryExpression
data class VeraUnaryOperation(
    val operator: VeraUnaryOperator,
    val operand: VeraPrimaryExpression,
) : VeraPrimaryExpression
data class VeraBinaryOperation(
    val left: VeraPrimaryExpression,
    val operator: VeraOperator,
    val right: VeraPrimaryExpression,
) : VeraPrimaryExpression
sealed interface VeraLiteral : VeraPrimaryExpression
data class VeraIntLiteral(val value: Int) : VeraLiteral
data class VeraStringLiteral(val value: String) : VeraLiteral
enum class VeraBoolLiteral : VeraLiteral { TRUE, FALSE }
data object VeraUnitLiteral : VeraLiteral
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

sealed interface VeraType {
    data object INT : VeraType
    data object STRING : VeraType
    data object BOOL : VeraType
    data object UNIT : VeraType
    data object UNKNOWN : VeraType
    data class Record(val name: Identifier) : VeraType
}

fun VeraType.render(): String = when (this) {
    is VeraType.Record -> name.value
    else -> toString()
}
