package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.VeraCompiler
import vera.compiler.test.PROGRAM_CLASS
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertCompilerError
import vera.compiler.test.assertJvmMainPrints
import vera.compiler.test.classDeclaration
import vera.compiler.test.compileProgram
import vera.compiler.test.function
import vera.compiler.test.traitDeclaration
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException
import vera.shared.model.Identifier

/** Program structure: declarations, the program class, and the JVM entry point. */
class ProgramTests {
    @Nested
    inner class Syntax {
        @Test
        fun `rejects non-function declarations`() = assertCompilationFails(
            "val value = 1",
            VeraParseException.ExpectedDeclaration::class,
            "expected a function, class, or trait declaration",
        )

        @Test
        fun `rejects missing tokens`() = assertCompilationFails(
            "fn main( { }",
            VeraParseException.ExpectedToken::class,
            "expected parameter name",
        )

        @Test
        fun `rejects missing expressions`() = assertCompilationFails(
            function("val value ="),
            VeraParseException.ExpectedExpression::class,
            "expected an expression",
        )

        @Test
        fun `rejects missing types`() = assertCompilationFails(
            "fn scale(by: 2): Int = by",
            VeraParseException.ExpectedType::class,
            "expected a type, found '2'",
        )
    }

    @Nested
    inner class EntryPoint {
        @Test
        fun `zero-parameter Unit main gets a void JVM entry point`() = assertJvmMainPrints(
            """
            fn main() {
                print("entry")
            }
            """,
            "entry",
        )

        @Test
        fun `zero-parameter value-returning main gets a void JVM entry point`() = assertJvmMainPrints(
            """
            fn main(): Int {
                print("entry")
                return 1
            }
            """,
            "entry",
        )

        @Test
        fun `main with parameters gets no JVM entry point`() {
            val program = compileProgram(
                """
                fn main(times: Int) {
                    print(times)
                }
                """,
            ).loadProgramClass()
            Assertions.assertEquals(
                listOf(Int::class.javaPrimitiveType),
                program.methods.filter { method -> method.name == "main" }.map { method -> method.parameterTypes.single() },
            )
        }
    }

    @Nested
    inner class ProgramClass {
        @Test
        fun `rejects traits named like the program class`() = assertCompilationFails(
            traitDeclaration(PROGRAM_CLASS),
            JvmLoweringException.TraitNameConflictsWithProgramClass::class,
            "trait $PROGRAM_CLASS conflicts with the program class $PROGRAM_CLASS",
        )

        @Test
        fun `rejects class names conflicting with the program class`() = assertCompilationFails(
            classDeclaration(PROGRAM_CLASS, "val x: Int"),
            JvmLoweringException.ClassNameConflictsWithProgramClass::class,
            "class $PROGRAM_CLASS conflicts with the generated program class",
        )

        @Test
        fun `rejects Unit as the program class name`() {
            assertCompilerError<JvmLoweringException.ProgramClassConflictsWithUnit>(
                "program class Unit conflicts with the built-in Unit class",
            ) {
                VeraCompiler.compile(Identifier("Unit"), "fn main() { }")
            }
        }
    }
}
