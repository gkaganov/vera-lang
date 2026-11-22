package vera.jvm.lowering

import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmField
import vera.jvm.ir.JvmGetField
import vera.jvm.ir.JvmInstanceCall
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmLocalRead
import vera.jvm.ir.JvmLogicalAnd
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmObjectConstruction
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStringLiteral
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitType
import vera.jvm.ir.render
import vera.shared.model.Identifier

/**
 * Generated implementations of the built-in traits.
 *
 * `Eq` gives a structural `equals`; `Display` gives a `toString`. Methods are only generated for
 * classes that opt in by listing the trait after `has`.
 */
internal object JvmObjectMethods {

    val equalsName: Identifier = Identifier("equals")
    val toStringName: Identifier = Identifier("toString")

    private val thisName = Identifier("<this>")
    private val otherName = Identifier("other")
    private val valueName = Identifier("value")
    private val appendName = Identifier("append")
    private val stringBuilderType = JvmType.ClassType(Identifier("java.lang.StringBuilder"))

    /** Builds `equals(other): Bool`, comparing all fields structurally. */
    fun equalsMethod(className: Identifier, fields: List<JvmField>): JvmMethod {
        val receiver = JvmLocal(thisName, JvmType.ClassType(className))
        val other = JvmLocal(otherName, JvmType.ClassType(className))
        val comparisons = fields.mapNotNull { field -> fieldEquality(className, field, receiver, other) }
        val result = comparisons.reduceOrNull { left, right -> JvmLogicalAnd(left, right) }
            ?: JvmBoolLiteral(true)
        return JvmMethod(
            equalsName,
            listOf(JvmParameter(otherName, other.type)),
            JvmType.BOOL,
            JvmExplicitReturnBlock(listOf(JvmReturn(result))),
            receiver,
        )
    }

    /** Builds a call to the generated structural `equals` method. */
    fun equalsCall(
        receiver: JvmExpression,
        className: Identifier,
        argument: JvmExpression,
    ): JvmExpression = JvmInstanceCall(
        receiver = receiver,
        ownerClass = className,
        methodName = equalsName,
        arguments = listOf(argument),
        signature = JvmMethodSignature(
            listOf(JvmParameter(otherName, JvmType.ClassType(className))),
            JvmType.BOOL,
        ),
    )

    /** Builds `toString(): String`, rendering `ClassName(field: value, ...)`. */
    fun toStringMethod(className: Identifier, fields: List<JvmField>): JvmMethod {
        val receiver = JvmLocal(thisName, JvmType.ClassType(className))
        var builder = stringBuilder()
        builder = append(builder, JvmStringLiteral("${className.value}("))
        fields.forEachIndexed { index, field ->
            if (index > 0) builder = append(builder, JvmStringLiteral(", "))
            builder = append(builder, JvmStringLiteral("${field.name.value}: "))
            builder = appendField(builder, className, field, receiver)
        }
        builder = append(builder, JvmStringLiteral(")"))
        val result = JvmInstanceCall(
            receiver = builder,
            ownerClass = stringBuilderType.name,
            methodName = toStringName,
            arguments = emptyList(),
            signature = JvmMethodSignature(emptyList(), JvmType.STRING),
        )
        return JvmMethod(
            toStringName,
            emptyList(),
            JvmType.STRING,
            JvmExplicitReturnBlock(listOf(JvmReturn(result))),
            receiver,
        )
    }

    /** Builds `toString(): String` returning a fixed value, used for `Unit`. */
    fun constantToStringMethod(className: Identifier, value: String): JvmMethod {
        val receiver = JvmLocal(thisName, JvmType.ClassType(className))
        return JvmMethod(
            toStringName,
            emptyList(),
            JvmType.STRING,
            JvmExplicitReturnBlock(listOf(JvmReturn(JvmStringLiteral(value)))),
            receiver,
        )
    }

    /** Builds a call to the generated `toString` method. */
    fun toStringCall(receiver: JvmExpression, className: Identifier): JvmExpression = JvmInstanceCall(
        receiver = receiver,
        ownerClass = className,
        methodName = toStringName,
        arguments = emptyList(),
        signature = JvmMethodSignature(emptyList(), JvmType.STRING),
    )

    private fun fieldEquality(
        owner: Identifier,
        field: JvmField,
        receiver: JvmLocal,
        other: JvmLocal,
    ): JvmExpression? {
        val type = field.type
        return when (type) {
            JvmUnitType -> null
            JvmType.INT, JvmType.BOOL, JvmType.STRING -> JvmComparison(
                JvmComparisonOperator.EQ,
                fieldRead(owner, field, receiver),
                fieldRead(owner, field, other),
            )

            is JvmType.ClassType -> equalsCall(
                fieldRead(owner, field, receiver),
                type.name,
                fieldRead(owner, field, other),
            )

            else -> throw JvmLoweringException.InternalInvariant(
                "unsupported class field type ${type.render()}",
            )
        }
    }

    private fun appendField(
        builder: JvmExpression,
        owner: Identifier,
        field: JvmField,
        receiver: JvmLocal,
    ): JvmExpression {
        val type = field.type
        return when (type) {
            JvmUnitType -> append(builder, JvmStringLiteral("Unit"))
            JvmType.INT, JvmType.BOOL, JvmType.STRING -> append(builder, fieldRead(owner, field, receiver))
            is JvmType.ClassType -> append(
                builder,
                toStringCall(fieldRead(owner, field, receiver), type.name),
            )

            else -> throw JvmLoweringException.InternalInvariant(
                "unsupported class field type ${type.render()}",
            )
        }
    }

    private fun fieldRead(owner: Identifier, field: JvmField, receiver: JvmLocal): JvmExpression =
        JvmGetField(
            receiver = JvmLocalRead(receiver),
            ownerClass = owner,
            fieldName = field.name,
            fieldType = field.type,
        )

    private fun stringBuilder(): JvmExpression = JvmObjectConstruction(
        className = stringBuilderType.name,
        arguments = emptyList(),
        signature = JvmMethodSignature(emptyList(), JvmType.VOID),
    )

    private fun append(builder: JvmExpression, value: JvmExpression): JvmExpression =
        JvmInstanceCall(
            receiver = builder,
            ownerClass = stringBuilderType.name,
            methodName = appendName,
            arguments = listOf(value),
            signature = JvmMethodSignature(
                listOf(JvmParameter(valueName, value.type)),
                stringBuilderType,
            ),
        )
}
