package vera.jvm.ir

import vera.shared.model.Identifier

data class JvmProgram(val classes: List<JvmClass>)

data class JvmClass(
    val name: Identifier,
    val methods: List<JvmMethod>,
    val fields: List<JvmField> = emptyList(),
    val constructors: List<JvmConstructor> = emptyList(),
)

data class JvmField(val name: Identifier, val type: JvmType, val isStatic: Boolean = false)

data class JvmConstructor(val parameters: List<JvmParameter>)

data class JvmMethod(
    val name: Identifier,
    val parameters: List<JvmParameter>,
    val returnType: JvmType,
    val body: JvmExplicitReturnBlock,
    val receiver: JvmLocal? = null,
)

data class JvmMethodSignature(val parameters: List<JvmParameter>, val returnType: JvmType)

data class JvmParameter(val name: Identifier, val type: JvmType)

data class JvmLocal(val name: Identifier, val type: JvmType)

/** JVM-level types. Vera `Unit` is represented by [JvmUnitType]. */
sealed interface JvmType {
    data object INT : JvmType
    data object STRING : JvmType
    data object BOOL : JvmType
    data object OBJECT : JvmType
    data object VOID : JvmType
    data class ClassType(val name: Identifier) : JvmType
    data class Array(val elementType: JvmType) : JvmType
}

/** The generated built-in `Unit` class. */
val JvmUnitType: JvmType.ClassType = JvmType.ClassType(Identifier("Unit"))

fun JvmType.render(): String = when (this) {
    is JvmType.ClassType -> name.value
    is JvmType.Array -> "${elementType.render()}[]"
    else -> toString()
}

sealed interface JvmExpression {
    val type: JvmType
}

data class JvmIntLiteral(val value: Int) : JvmExpression {
    override val type = JvmType.INT
}

data class JvmStringLiteral(val value: String) : JvmExpression {
    override val type = JvmType.STRING
}

data class JvmBoolLiteral(val value: Boolean) : JvmExpression {
    override val type = JvmType.BOOL
}

data object JvmUnitLiteral : JvmExpression {
    override val type = JvmUnitType
}

data class JvmLocalRead(val local: JvmLocal) : JvmExpression {
    override val type = local.type
}

enum class JvmIntBinaryOperator { ADD, SUB, MUL, DIV, REM }
enum class JvmIntUnaryOperator { NEGATE }
enum class JvmComparisonOperator { EQ, NEQ, LT, GT, LTE, GTE }

data class JvmIntUnary(
    val operator: JvmIntUnaryOperator,
    val operand: JvmExpression,
) : JvmExpression {
    override val type = JvmType.INT
}

data class JvmIntBinary(
    val operator: JvmIntBinaryOperator,
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression {
    override val type = JvmType.INT
}

data class JvmComparison(
    val operator: JvmComparisonOperator,
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression {
    override val type = JvmType.BOOL
}

data class JvmLogicalAnd(
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression {
    override val type = JvmType.BOOL
}

data class JvmLogicalOr(
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression {
    override val type = JvmType.BOOL
}

data class JvmRecordConstruction(
    val recordName: Identifier,
    val arguments: List<JvmExpression>,
    val signature: JvmMethodSignature,
) : JvmExpression {
    override val type = JvmType.ClassType(recordName)
}

data class JvmGetField(
    val receiver: JvmExpression,
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
) : JvmExpression {
    override val type = fieldType
}

data class JvmStaticCall(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val arguments: List<JvmExpression>,
    val signature: JvmMethodSignature,
) : JvmExpression {
    override val type = signature.returnType
}

data class JvmInstanceCall(
    val receiver: JvmExpression,
    val ownerClass: Identifier,
    val methodName: Identifier,
    val arguments: List<JvmExpression>,
    val signature: JvmMethodSignature,
) : JvmExpression {
    override val type = signature.returnType
}

enum class JvmBuiltin { PRINT }

data class JvmBuiltinCall(
    val builtin: JvmBuiltin,
    val arguments: List<JvmExpression>,
) : JvmExpression {
    override val type = when (builtin) {
        JvmBuiltin.PRINT -> JvmUnitType
    }
}

sealed interface JvmBlock {
    val statements: List<JvmStatement>
    val type: JvmType
}

/** A method body whose statements use explicit `return` statements. */
data class JvmExplicitReturnBlock(override val statements: List<JvmStatement>) : JvmBlock {
    override val type = JvmType.VOID
}

/** A branch body whose value is its final expression; it returns implicitly. */
data class JvmImplicitReturnBlock(
    override val statements: List<JvmStatement>,
    val result: JvmExpression,
) : JvmBlock {
    override val type = result.type
}

/** A branch body with no final expression; its value is `Unit`. */
data class JvmUnitReturnBlock(override val statements: List<JvmStatement>) : JvmBlock {
    override val type = JvmUnitType
}

data class JvmIfExpression(
    val condition: JvmExpression,
    val thenBlock: JvmBlock,
    val elseBlock: JvmBlock?,
) : JvmExpression {
    override val type = if (elseBlock != null) thenBlock.type else JvmUnitType
}

sealed interface JvmStatement

data class JvmReturn(val expression: JvmExpression?) : JvmStatement

data class JvmVarDeclaration(val local: JvmLocal, val expression: JvmExpression) : JvmStatement

data class JvmRebind(val local: JvmLocal, val expression: JvmExpression) : JvmStatement

data class JvmExpressionStatement(val expression: JvmExpression) : JvmStatement

data class JvmPutStatic(
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
    val fieldDescriptor: Identifier,
    val value: JvmExpression,
) : JvmStatement

data class JvmWhile(val condition: JvmExpression, val body: JvmUnitReturnBlock) : JvmStatement
data object JvmBreak : JvmStatement
data object JvmContinue : JvmStatement
