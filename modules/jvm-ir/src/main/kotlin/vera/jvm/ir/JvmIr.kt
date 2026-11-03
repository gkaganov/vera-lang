package vera.jvm.ir

import vera.shared.model.Identifier

data class JvmProgram(val classes: List<JvmClass>)

data class JvmClass(val name: Identifier, val methods: List<JvmMethod>)

data class JvmMethod(
    val name: Identifier,
    val parameters: List<JvmParameter>,
    val returnType: JvmType,
    val body: List<JvmStatement>,
)

data class JvmMethodSignature(val parameters: List<JvmParameter>, val returnType: JvmType)

data class JvmParameter(val name: Identifier, val type: JvmType)

data class JvmLocal(val name: Identifier, val type: JvmType)

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

enum class IntBinaryOperator { ADD, SUB, MUL, DIV, REM }
enum class ComparisonOperator { EQ, NEQ, LT, GT, LTE, GTE }

data class JvmIntBinary(
    val operator: IntBinaryOperator,
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression {
    override val type = JvmType.INT
}

data class JvmComparison(
    val operator: ComparisonOperator,
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

data class JvmIfExpression(
    val condition: JvmExpression,
    val thenBlock: List<JvmStatement>,
    val elseBlock: List<JvmStatement>,
) : JvmExpression {
    override val type = JvmType.VOID
}

sealed interface JvmStatement

data class JvmReturn(val expression: JvmExpression?) : JvmStatement

data class JvmVarDeclaration(val local: JvmLocal, val expression: JvmExpression) : JvmStatement

data class JvmRebind(val local: JvmLocal, val expression: JvmExpression) : JvmStatement

data class JvmExpressionStatement(val expression: JvmExpression) : JvmStatement

data class JvmWhile(val condition: JvmExpression, val body: List<JvmStatement>) : JvmStatement
