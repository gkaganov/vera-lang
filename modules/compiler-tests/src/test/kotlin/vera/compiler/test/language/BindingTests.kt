package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import vera.compiler.VeraCompiler
import vera.compiler.test.ByteArrayClassLoader
import vera.compiler.test.EOL
import vera.compiler.test.TEST_MAIN
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertCompilerError
import vera.compiler.test.assertJvmMainPrints
import vera.compiler.test.assertProgramPrints
import vera.compiler.test.assertProgramReturns
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.compiler.test.traitDeclaration
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraLexException
import vera.parser.VeraParseException
import vera.shared.model.Identifier

/** Variables and rebinding: `val`, `mut val`, and block scopes. */
class BindingTests {
    @Nested
    inner class Val {
        @Test
        fun `val with explicit type declaration can be bound and accessed`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val myVar1: Int = 50
                            val myVar2: Int = 51
                            return myVar1 + myVar2 + 52
                        }
                    """.trimIndent()
            assertProgramReturns(source, 153, testInfo)
        }

        @Test
        fun `val types can be inferred when bound directly`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            val inferredString = "I am a String"
                            val inferredInt = 1000
                            print(inferredString)
                            print(inferredInt)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "I am a String${EOL}1000${EOL}", testInfo)
        }

        @Test
        fun `bindings that contradict their declared type`() = assertCompilationFails(
            function("val value: Int = \"text\""),
            JvmLoweringException.BindTypeMismatch::class,
            "variable value is declared as Int but bound to a value of type String",
        )
    }

    @Nested
    inner class Rebinding {
        @Test
        fun `mut val bindings can be rebound`(testInfo: TestInfo) {
            val source = """
                        class Point has Display {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN() {
                            val limit = 3
                            mut val count = 0
                            mut val point = Point(x = 0, y = 0)
                            while count < limit {
                                count = count + 1
                                point = Point(x = point.x + count, y = point.y)
                            }
                            print(count)
                            print(point)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "3${EOL}Point(x: 6, y: 0)$EOL", testInfo)
        }

        @Test
        fun `mut val without a value makes a parameter rebindable`(testInfo: TestInfo) {
            val source = """
                        class Counter {
                            val start: Int
                            fn countUp(steps: Int): Int {
                                mut val steps
                                mut val total = start
                                while steps > 0 {
                                    total = total + 1
                                    steps = steps - 1
                                }
                                return total
                            }
                        }
                        fn countdown(from: Int) {
                            mut val from
                            while from > 0 {
                                print(from)
                                from = from - 1
                            }
                        }
                        fn $TEST_MAIN() {
                            countdown(3)
                            print(Counter(10).countUp(2))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "3${EOL}2${EOL}1${EOL}12$EOL", testInfo)
        }

        @Test
        fun `variables can be rebound with equals`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val value = 1
                            value = 42
                            return value
                        }
                    """.trimIndent()
            assertProgramReturns(source, 42, testInfo)
        }

        @Test
        fun `rebinding can read the previous value`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val value = 1
                            value = value + 2
                            return value
                        }
                    """.trimIndent()
            assertProgramReturns(source, 3, testInfo)
        }

        @Test
        fun `mut without val`() = assertCompilationFails(
            function("mut count = 0"),
            VeraParseException.ExpectedToken::class,
            "expected `val` after `mut`, found 'count'",
        )

        @Test
        fun `rebind update blocks`() = assertCompilationFails(
            function("value = {", "    1", "}"),
            VeraParseException.UnsupportedRebindUpdateBlock::class,
            "rebind update blocks are not supported",
        )

        @Test
        fun `rebinding val bindings`() = assertCompilationFails(
            function("val count = 0", "count = 1"),
            JvmLoweringException.ImmutableBindingRebound::class,
            "count is immutable; declare it with `mut val` to rebind it",
        )

        @Test
        fun `rebinding parameters`() = assertCompilationFails(
            "fn next(value: Int): Int {\n    value = value + 1\n    return value\n}",
            JvmLoweringException.ParameterRebound::class,
            "parameter value cannot be rebound; write `mut val value` first to make it rebindable",
        )

        @Test
        fun `mut val without a value for a local`() = assertCompilationFails(
            function("val count = 0", "mut val count"),
            JvmLoweringException.MutableParameterRequiresParameter::class,
            "`mut val count` without a value makes a parameter rebindable, but count is not a parameter",
        )

        @Test
        fun `rebinding a parameter before it is made rebindable`() = assertCompilationFails(
            "fn next(value: Int): Int {\n    value = value + 1\n    mut val value\n    return value\n}",
            JvmLoweringException.ParameterRebound::class,
            "parameter value cannot be rebound; write `mut val value` first to make it rebindable at line 2, column 5",
        )

        @Test
        fun `mut val without a value repeated`() = assertCompilationFails(
            "fn next(value: Int): Int {\n    mut val value\n    mut val value\n    return value\n}",
            JvmLoweringException.MutableParameterRepeated::class,
            "parameter value is already rebindable",
        )

        @Test
        fun `mut val without a value inside a block`() = assertCompilationFails(
            "fn next(value: Int) {\n    if True {\n        mut val value\n    }\n}",
            JvmLoweringException.MutableParameterOutsideFunctionBody::class,
            "`mut val value` must be written directly in the function body, not inside a block",
        )

        @Test
        fun `rebinding unknown variables`() = assertCompilationFails(
            function("missing = 1"),
            JvmLoweringException.UnknownVariable::class,
            "variable missing is not defined",
        )

        @Test
        fun `rebinding to a different type`() = assertCompilationFails(
            function("mut val value = 1", "value = \"text\""),
            JvmLoweringException.RebindTypeMismatch::class,
            "cannot rebind variable value",
        )
    }

    @Nested
    inner class Scopes {
        @Test
        fun `sibling blocks can reuse local names`(testInfo: TestInfo) {
            val source = """
                        fn pick(flag: Bool): Int {
                            return if flag {
                                val value = 1
                                value
                            } else {
                                val value = 2
                                value
                            }
                        }
                        fn $TEST_MAIN() {
                            print(pick(True))
                            print(pick(False))
                            mut val round = 0
                            while round < 2 {
                                val doubled = round * 2
                                print(doubled)
                                round = round + 1
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "1${EOL}2${EOL}0${EOL}2$EOL", testInfo)
        }

        @Test
        fun `locals used after the if branch that declares them`() = assertCompilationFails(
            function("if True {", "    val inner = 1", "}", "print(inner)"),
            JvmLoweringException.UnknownVariable::class,
            "variable inner is not defined",
        )

        @Test
        fun `locals used after the loop body that declares them`() = assertCompilationFails(
            function("while False {", "    val inner = 1", "}", "print(inner)"),
            JvmLoweringException.UnknownVariable::class,
            "variable inner is not defined",
        )
    }
}
