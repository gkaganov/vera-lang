package vera.jvm.lowering

import vera.ast.VeraType
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitType
import vera.shared.model.Identifier

internal object JvmTypeLowering {

    fun lowerReturn(veraType: VeraType, classNames: Set<Identifier>): JvmType =
        if (veraType == VeraType.UNKNOWN) JvmUnitType else lower(veraType, classNames)

    fun lower(veraType: VeraType, classNames: Set<Identifier>): JvmType = when (veraType) {
        VeraType.INT -> JvmType.INT
        VeraType.STRING -> JvmType.STRING
        VeraType.BOOL -> JvmType.BOOL
        VeraType.UNIT -> JvmUnitType
        VeraType.UNKNOWN -> JvmType.VOID
        is VeraType.ClassType -> {
            if (veraType.name !in classNames) {
                throw JvmLoweringException.UnknownClassType(veraType.name)
            }
            JvmType.ClassType(veraType.name)
        }
    }
}
