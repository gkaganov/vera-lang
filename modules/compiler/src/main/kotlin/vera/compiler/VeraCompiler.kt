package vera.compiler

import vera.ast.Program
import vera.ast.VeraFunctionDeclaration
import vera.jvm.bytecode.emitter.JvmBytecodeEmitter
import vera.jvm.ir.JvmClass
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmProgram
import vera.jvm.lowering.JvmLowering
import vera.parser.VeraParser
import vera.shared.model.Identifier

class VeraCompiler(private val mainClassName: Identifier) {

    fun compile(code: String): Map<Identifier, ByteArray> {
        val astProgram = VeraParser().parseProgram(code)
        val jvmProgram = processProgram(astProgram)
        val bytecode = JvmBytecodeEmitter().emit(jvmProgram)

        return bytecode
    }

    private fun processProgram(program: Program): JvmProgram {
        // first pass - collect function declarations by name
        val fnDeclarations = mutableMapOf<Identifier, VeraFunctionDeclaration>()
        for (declaration in program.declarations) {
            if (declaration is VeraFunctionDeclaration) {
                fnDeclarations[declaration.name] = declaration
            }
        }

        // second pass - compile program
        val jvmMethods = mutableListOf<JvmMethod>()
        for (declaration in program.declarations) {
            if (declaration is VeraFunctionDeclaration) {
                jvmMethods.add(
                    JvmLowering(mainClassName, fnDeclarations).lowerFunction(declaration)
                )
            }
        }

        return JvmProgram(listOf(JvmClass(mainClassName, jvmMethods)))
    }
}
