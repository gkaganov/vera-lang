package vera.jvm.lowering

import vera.ast.VeraType
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitType
import vera.shared.model.Identifier

/** The class and user trait names a type annotation may refer to. Built-in traits are not types. */
internal data class JvmTypeNames(val classes: Set<Identifier>, val traits: Set<Identifier>)

internal object JvmTypeLowering {

    private val builtinTypeNames = listOf("Int", "String", "Bool", "Unit").map(::Identifier)

    fun lowerReturn(veraType: VeraType, typeNames: JvmTypeNames): JvmType =
        if (veraType == VeraType.UNKNOWN) JvmUnitType else lower(veraType, typeNames)

    fun lower(veraType: VeraType, typeNames: JvmTypeNames): JvmType = when (veraType) {
        VeraType.INT -> JvmType.INT
        VeraType.STRING -> JvmType.STRING
        VeraType.BOOL -> JvmType.BOOL
        VeraType.UNIT -> JvmUnitType
        VeraType.UNKNOWN -> JvmType.VOID
        is VeraType.ClassType -> {
            val name = veraType.name
            when (name) {
                in typeNames.classes -> JvmType.ClassType(name)
                in typeNames.traits -> JvmType.InterfaceType(name)
                in JvmBuiltinTraits.names -> throw JvmLoweringException.BuiltinTraitUsedAsType(name)
                else -> throw JvmLoweringException.UnknownClassType(
                    name,
                    JvmNameHints.closest(name, typeNames.classes + typeNames.traits + builtinTypeNames),
                    JvmNameHints.forJavaType(name),
                )
            }
        }
    }
}
