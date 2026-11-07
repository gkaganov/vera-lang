package vera.jvm.lowering

import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraProgram
import vera.jvm.ir.JvmClass
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmProgram
import vera.shared.model.Identifier

object JvmProgramLowering {

    fun lower(mainClassName: Identifier, program: VeraProgram): JvmProgram {
        // First pass - collect function declarations by name for call resolution.
        val functionDeclarations = program.declarations
            .filterIsInstance<VeraFunctionDeclaration>()
            .associateBy { it.name }

        // Second pass - lower each function.
        val jvmMethods = mutableListOf<JvmMethod>()
        program.declarations
            .filterIsInstance<VeraFunctionDeclaration>()
            .forEach { declaration ->
                jvmMethods += JvmFunctionLowering
                    .lower(mainClassName, functionDeclarations, declaration)
            }

        return JvmProgram(listOf(JvmClass(mainClassName, jvmMethods)))
    }
}
