package vera.jvm.lowering

import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmCheckCast
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmField
import vera.jvm.ir.JvmGetField
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
import vera.jvm.ir.JvmInstanceOf
import vera.jvm.ir.JvmInstanceCall
import vera.jvm.ir.JvmIntBinary
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmIntLiteral
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
import vera.jvm.ir.JvmUpcast
import vera.jvm.ir.JvmNoneType
import vera.jvm.ir.render
import vera.shared.model.Identifier

/**
 * Generated implementations of the built-in traits.
 *
 * `Eq` gives a structural `equals` and a matching `hashCode`; `Display` gives a `toString`. Methods are
 * only generated for classes that opt in by listing the trait after `has`.
 */
internal object JvmObjectMethods {

    val equalsName: Identifier = Identifier("equals")
    val toStringName: Identifier = Identifier("toString")
    val hashCodeName: Identifier = Identifier("hashCode")

    /** Member names reserved because they are `java.lang.Object` methods that Vera generates. */
    val reservedNames: Set<String> = setOf("toString", "equals", "hashCode")

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

    /** `equals(Object): Bool`, the signature trait-typed values compare through. */
    val objectEqualsSignature: JvmMethodSignature =
        JvmMethodSignature(listOf(JvmParameter(otherName, JvmType.OBJECT)), JvmType.BOOL)

    /** `toString(): String`. */
    val toStringSignature: JvmMethodSignature = JvmMethodSignature(emptyList(), JvmType.STRING)

    /** `hashCode(): Int`. */
    val hashCodeSignature: JvmMethodSignature = JvmMethodSignature(emptyList(), JvmType.INT)

    /**
     * Builds `equals(Object): Bool` for a class with `Eq`, overriding `Object.equals`: it is the
     * structural `equals` when the other object has the same class, and `False` otherwise. Values of a
     * trait that requires `Eq` compare through it.
     */
    fun objectEqualsMethod(className: Identifier): JvmMethod {
        val classType = JvmType.ClassType(className)
        val receiver = JvmLocal(thisName, classType)
        val other = JvmLocal(otherName, JvmType.OBJECT)
        val sameClass = JvmInstanceOf(JvmLocalRead(other), className)
        val structurallyEqual = equalsCall(JvmLocalRead(receiver), className, JvmCheckCast(JvmLocalRead(other), classType))
        return JvmMethod(
            equalsName,
            listOf(JvmParameter(otherName, JvmType.OBJECT)),
            JvmType.BOOL,
            JvmExplicitReturnBlock(listOf(JvmReturn(JvmLogicalAnd(sameClass, structurallyEqual)))),
            receiver,
        )
    }

    /**
     * Builds `hashCode(): Int` for a class with `Eq`, overriding `Object.hashCode` so that objects equal
     * by [objectEqualsMethod] have the same hash, as Java collections require. It combines the field
     * hashes as `31 * hash + field`, starting from the hash of the class name, so field-less cases of
     * one trait hash differently.
     */
    fun hashCodeMethod(className: Identifier, fields: List<JvmField>): JvmMethod {
        val receiver = JvmLocal(thisName, JvmType.ClassType(className))
        val seed: JvmExpression = JvmIntLiteral(className.display.hashCode())
        val result = fields.fold(seed) { hash, field ->
            val fieldHash = fieldHash(className, field, receiver) ?: return@fold hash
            JvmIntBinary(JvmIntBinaryOperator.ADD, JvmIntBinary(JvmIntBinaryOperator.MUL, JvmIntLiteral(31), hash), fieldHash)
        }
        return JvmMethod(
            hashCodeName,
            emptyList(),
            JvmType.INT,
            JvmExplicitReturnBlock(listOf(JvmReturn(result))),
            receiver,
        )
    }

    /**
     * Builds `name(): Type` returning the field [field], which implements the accessor a trait declares
     * for a required field. It is generated, so Vera name resolution never sees it.
     */
    fun fieldAccessorMethod(className: Identifier, field: JvmField): JvmMethod {
        val receiver = JvmLocal(thisName, JvmType.ClassType(className))
        return JvmMethod(
            field.name,
            emptyList(),
            field.type,
            JvmExplicitReturnBlock(listOf(JvmReturn(fieldRead(className, field, receiver)))),
            receiver,
        )
    }

    /** Compares two values of trait [trait] through the interface's `equals(Object)`. */
    fun traitEqualsCall(receiver: JvmExpression, trait: Identifier, argument: JvmExpression): JvmExpression =
        JvmInstanceCall(
            receiver = receiver,
            ownerClass = trait,
            methodName = equalsName,
            arguments = listOf(JvmUpcast(argument, JvmType.OBJECT)),
            signature = objectEqualsSignature,
            ownerIsInterface = true,
        )

    /** Renders a value of trait [trait] through the interface's `toString()`. */
    fun traitToStringCall(receiver: JvmExpression, trait: Identifier): JvmExpression = JvmInstanceCall(
        receiver = receiver,
        ownerClass = trait,
        methodName = toStringName,
        arguments = emptyList(),
        signature = toStringSignature,
        ownerIsInterface = true,
    )

    /** Builds `toString(): String`, rendering `ClassName(field: value, ...)`, or `ClassName` without fields. */
    fun toStringMethod(className: Identifier, fields: List<JvmField>): JvmMethod {
        // A field-less class is a singleton written by its name, so it prints as just the name.
        if (fields.isEmpty()) return constantToStringMethod(className, className.display)
        val receiver = JvmLocal(thisName, JvmType.ClassType(className))
        var builder = stringBuilder()
        builder = append(builder, JvmStringLiteral("${className.display}("))
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

    /** Builds `toString(): String` returning a fixed value, used for `None`. */
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

    /** Concatenates `Int` and `String` [pieces] in order with a `StringBuilder`. */
    fun concatenate(pieces: List<JvmExpression>): JvmExpression {
        val builder = pieces.fold(stringBuilder()) { builder, piece -> append(builder, piece) }
        return JvmInstanceCall(
            receiver = builder,
            ownerClass = stringBuilderType.name,
            methodName = toStringName,
            arguments = emptyList(),
            signature = JvmMethodSignature(emptyList(), JvmType.STRING),
        )
    }

    /**
     * Renders a `Bool` as `True` or `False`, the spelling of Vera's literals, rather than Java's
     * `true`/`false`.
     */
    fun boolToString(jvmBool: JvmExpression): JvmExpression = JvmIfExpression(
        condition = jvmBool,
        thenBlock = JvmImplicitReturnBlock(emptyList(), JvmStringLiteral("True")),
        elseBlock = JvmImplicitReturnBlock(emptyList(), JvmStringLiteral("False")),
    )

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
            JvmNoneType -> null
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

            is JvmType.InterfaceType -> traitEqualsCall(
                fieldRead(owner, field, receiver),
                type.name,
                fieldRead(owner, field, other),
            )

            else -> throw JvmLoweringException.InternalInvariant(
                "unsupported class field type ${type.render()}",
            )
        }
    }

    /** The hash of one field, as Java computes it for the same value; `None` fields add nothing. */
    private fun fieldHash(owner: Identifier, field: JvmField, receiver: JvmLocal): JvmExpression? {
        val value = fieldRead(owner, field, receiver)
        return when (val type = field.type) {
            JvmNoneType -> null
            JvmType.INT -> value
            // `Boolean.hashCode` values.
            JvmType.BOOL -> JvmIfExpression(
                condition = value,
                thenBlock = JvmImplicitReturnBlock(emptyList(), JvmIntLiteral(1231)),
                elseBlock = JvmImplicitReturnBlock(emptyList(), JvmIntLiteral(1237)),
            )
            JvmType.STRING -> hashCodeCall(value, Identifier("java.lang.String"), ownerIsInterface = false)
            is JvmType.ClassType -> hashCodeCall(value, type.name, ownerIsInterface = false)
            is JvmType.InterfaceType -> hashCodeCall(value, type.name, ownerIsInterface = true)
            else -> throw JvmLoweringException.InternalInvariant("unsupported class field type ${type.render()}")
        }
    }

    private fun hashCodeCall(receiver: JvmExpression, owner: Identifier, ownerIsInterface: Boolean): JvmExpression =
        JvmInstanceCall(
            receiver = receiver,
            ownerClass = owner,
            methodName = hashCodeName,
            arguments = emptyList(),
            signature = hashCodeSignature,
            ownerIsInterface = ownerIsInterface,
        )

    private fun appendField(
        builder: JvmExpression,
        owner: Identifier,
        field: JvmField,
        receiver: JvmLocal,
    ): JvmExpression {
        val type = field.type
        return when (type) {
            JvmNoneType -> append(builder, JvmStringLiteral("None"))
            JvmType.INT, JvmType.STRING -> append(builder, fieldRead(owner, field, receiver))
            JvmType.BOOL -> append(builder, boolToString(fieldRead(owner, field, receiver)))
            is JvmType.ClassType -> append(
                builder,
                toStringCall(fieldRead(owner, field, receiver), type.name),
            )

            is JvmType.InterfaceType -> append(
                builder,
                traitToStringCall(fieldRead(owner, field, receiver), type.name),
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
