package vera.compiler

import vera.jvm.bytecode.emitter.JvmBytecodeEmitter
import vera.jvm.instruction.lowering.JvmInstructionLowering
import vera.jvm.lowering.JvmProgramLowering
import vera.parser.VeraLexer
import vera.parser.VeraParser
import vera.shared.model.Identifier

object VeraCompiler {

    fun compile(mainClassName: Identifier, code: String): Map<Identifier, ByteArray> {
        val tokens = VeraLexer.tokenize(code)
        val ast = VeraParser.parse(tokens)
        val jvmProgram = JvmProgramLowering.lower(mainClassName, ast)
        val jvmInstructionProgram = JvmInstructionLowering.lower(jvmProgram)
        return JvmBytecodeEmitter.emit(jvmInstructionProgram)
    }
}
