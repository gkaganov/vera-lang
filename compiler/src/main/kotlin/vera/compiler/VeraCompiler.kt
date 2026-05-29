package vera.compiler

import vera.ast.VeraFunctionDeclaration
import vera.ast.Program
import vera.jvm.bytecode.emitter.JvmBytecodeEmitter
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmProgram
import vera.jvm.lowering.JvmLowering
import vera.parser.VeraParser

class VeraCompiler(private val mainClassName: String) {

    fun compile(code: String) : ByteArray {
        val astProgram = VeraParser().parseProgram(code)
        val jvmProgram = processProgram(astProgram)
        val bytecode = JvmBytecodeEmitter().emit(jvmProgram)

        return bytecode
    }

    private fun processProgram(program: Program) : JvmProgram {
        // first pass - collect function declarations by name
        val fnDeclarations = mutableMapOf<String, VeraFunctionDeclaration>()
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

        return JvmProgram(jvmMethods)
    }
}
