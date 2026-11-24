package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.VeraCompiler
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertCompilerError
import vera.compiler.test.assertJvmMainPrints
import vera.compiler.test.classDeclaration
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
        fun `non-function declarations`() = assertCompilationFails(
            "val value = 1",
            VeraParseException.ExpectedDeclaration::class,
            "expected a function, class, or trait declaration",
        )

        @Test
        fun `missing tokens`() = assertCompilationFails(
            "fn main( { }",
            VeraParseException.ExpectedToken::class,
            "expected parameter name",
        )

        @Test
        fun `missing expressions`() = assertCompilationFails(
            function("val value ="),
            VeraParseException.ExpectedExpression::class,
            "expected an expression",
        )

        @Test
        fun `missing types`() = assertCompilationFails(
            "fn scale(by: 2): Int = by",
            VeraParseException.ExpectedType::class,
            "expected a type, found '2'",
        )
    }

    @Nested
    inner class EntryPoint {
        @Test
        fun `zero-parameter Unit main gets a void JVM entry point`() {
            val source = """
                        fn main() {
                            print("entry")
                        }
                    """.trimIndent()
            assertJvmMainPrints(source, "entry")
        }

        @Test
        fun `zero-parameter value-returning main gets a void JVM entry point`() {
            val source = """
                        fn main(): Int {
                            print("entry")
                            return 1
                        }
                    """.trimIndent()
            assertJvmMainPrints(source, "entry")
        }
    }

    @Nested
    inner class ProgramClass {
        @Test
        fun `traits named like the program class`() = assertCompilationFails(
            traitDeclaration("ErrorTest"),
            JvmLoweringException.TraitNameConflictsWithProgramClass::class,
            "trait ErrorTest conflicts with the program class ErrorTest",
        )

        @Test
        fun `class names conflicting with the program class`() = assertCompilationFails(
            classDeclaration("ErrorTest", "val x: Int"),
            JvmLoweringException.ClassNameConflictsWithProgramClass::class,
            "class ErrorTest conflicts with the generated program class",
        )

        @Test
        fun `program class name cannot collide with Unit`() {
            assertCompilerError<JvmLoweringException.ProgramClassConflictsWithUnit>(
                "program class Unit conflicts with the built-in Unit class",
            ) {
                VeraCompiler.compile(Identifier("Unit"), "fn main() { }")
            }
        }
    }
}
