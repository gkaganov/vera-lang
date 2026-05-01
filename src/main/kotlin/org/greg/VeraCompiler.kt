package org.greg

import org.antlr.v4.kotlinruntime.BufferedTokenStream
import org.antlr.v4.kotlinruntime.CharStreams
import org.greg.antlr.VeraLexer
import org.greg.antlr.VeraParser
import java.lang.classfile.ClassBuilder
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

class VeraCompiler(private val mainClassName: String) {

    fun compile(inputFile: Path, outputFile: Path) {
        val bytecode = compile(inputFile.readText())
        outputFile.createParentDirectories()
        outputFile.writeBytes(bytecode)
    }

    fun compile(code: String): ByteArray {
        val lexer = VeraLexer(CharStreams.fromString(code))
        val parser = VeraParser(BufferedTokenStream(lexer))
        val program = parser.program()

        val customAstProgram = AstMapper().mapProgram(program)

        val mainClassEmitter = processProgram(customAstProgram)
        return ClassFile.of().build(ClassDesc.of(mainClassName), mainClassEmitter)
    }

    private fun processProgram(program: Program): ClassBuilder.() -> Unit {
        // first pass - collect function declarations by name
        val fnDeclarations = mutableMapOf<String, FunctionDeclaration>()
        for (declaration in program.declarations) {
            if (declaration is FunctionDeclaration) {
                fnDeclarations[declaration.name] = declaration
            }
        }

        // second pass - compile program
        var classEmitter: ClassBuilder.() -> Unit = {}
        for (declaration in program.declarations) {
            if (declaration is FunctionDeclaration) {
                classEmitter = FunctionEmitter(mainClassName, fnDeclarations)
                    .processFunction(declaration, classEmitter)
            }
        }

        return {
            classEmitter()
            withFlags(AccessFlag.PUBLIC)
        }
    }
}
