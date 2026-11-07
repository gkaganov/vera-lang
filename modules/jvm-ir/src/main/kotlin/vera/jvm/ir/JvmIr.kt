package vera.jvm.ir

import vera.shared.model.Identifier

data class JvmProgram(val classes: List<JvmClass>)

data class JvmClass(val name: Identifier, val methods: List<JvmMethod>)

data class JvmMethod(
    val name: Identifier,
    val parameters: List<JvmParameter>,
    val returnType: JvmType,
    val body: JvmExplicitReturnBlock,
)

data class JvmMethodSignature(val parameters: List<JvmParameter>, val returnType: JvmType)

data class JvmParameter(val name: Identifier, val type: JvmType)

data class JvmLocal(val name: Identifier, val type: JvmType)

/** JVM-level types. Vera `Unit` is represented as [JvmType.VOID]. */
enum class JvmType { INT, STRING, BOOL, OBJECT, VOID }

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

data class JvmLocalRead(val local: JvmLocal) : JvmExpression {
    override val type = local.type
}

enum class JvmIntBinaryOperator { ADD, SUB, MUL, DIV, REM }
enum class JvmComparisonOperator { EQ, NEQ, LT, GT, LTE, GTE }

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

data class JvmStaticCall(
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
        JvmBuiltin.PRINT -> JvmType.VOID
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

/** A block that produces no value and returns implicitly as `Unit`. */
data class JvmUnitReturnBlock(override val statements: List<JvmStatement>) : JvmBlock {
    override val type = JvmType.VOID
}

data class JvmIfExpression(
    val condition: JvmExpression,
    val thenBlock: JvmBlock,
    val elseBlock: JvmBlock?,
) : JvmExpression {
    override val type = if (elseBlock != null) thenBlock.type else JvmType.VOID
}

sealed interface JvmStatement

data class JvmReturn(val expression: JvmExpression?) : JvmStatement

data class JvmVarDeclaration(val local: JvmLocal, val expression: JvmExpression) : JvmStatement

data class JvmRebind(val local: JvmLocal, val expression: JvmExpression) : JvmStatement

data class JvmExpressionStatement(val expression: JvmExpression) : JvmStatement

data class JvmWhile(val condition: JvmExpression, val body: JvmUnitReturnBlock) : JvmStatement
