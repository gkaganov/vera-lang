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

/** Forward application with `|>`. */
class ForwardApplicationTests {
    @Nested
    inner class Basics {
        @Test
        fun `forward application passes the left value as the first argument`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(2 |> double)
                            print(1 |> add(2))
                        }
                        fn double(value: Int): Int {
                            return value * 2
                        }
                        fn add(left: Int, right: Int): Int {
                            return left + right
                        }
                    """.trimIndent()
            assertProgramPrints(source, "4${EOL}3${EOL}", testInfo)
        }

        @Test
        fun `forward application works without surrounding spaces`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(2|>double)
                        }
                        fn double(value: Int): Int {
                            return value * 2
                        }
                    """.trimIndent()
            assertProgramPrints(source, "4$EOL", testInfo)
        }

        @Test
        fun `forward application chains left to right`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(3 |> add(4) |> double)
                        }
                        fn double(value: Int): Int {
                            return value * 2
                        }
                        fn add(left: Int, right: Int): Int {
                            return left + right
                        }
                    """.trimIndent()
            assertProgramPrints(source, "14$EOL", testInfo)
        }

        @Test
        fun `forward application accepts object values`(testInfo: TestInfo) {
            val source = """
                        class Point {
                            val x: Int
                            val y: Int
                        }
                        fn sum(point: Point): Int {
                            return point.x + point.y
                        }
                        fn $TEST_MAIN(): Int {
                            return Point(x = 1, y = 2) |> sum
                        }
                    """.trimIndent()
            assertProgramReturns(source, 3, testInfo)
        }

        @Test
        fun `forward application works inside arguments`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return 1 |> add(2 |> double)
                        }
                        fn double(value: Int): Int {
                            return value * 2
                        }
                        fn add(left: Int, right: Int): Int {
                            return left + right
                        }
                    """.trimIndent()
            assertProgramReturns(source, 5, testInfo)
        }
    }

    @Nested
    inner class Precedence {
        @Test
        fun `forward application binds looser than arithmetic`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(1 + 2 |> double)
                        }
                        fn double(value: Int): Int {
                            return value * 2
                        }
                    """.trimIndent()
            assertProgramPrints(source, "6$EOL", testInfo)
        }

        @Test
        fun `forward application binds looser than or`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(True or False |> negate)
                        }
                        fn negate(value: Bool): Bool {
                            return if value {
                                False
                            } else {
                                True
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "False$EOL", testInfo)
        }

        @Test
        fun `forward application binds looser than and`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(False and True |> negate)
                        }
                        fn negate(value: Bool): Bool {
                            return if value {
                                False
                            } else {
                                True
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True$EOL", testInfo)
        }

        @Test
        fun `forward application binds looser than equality`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(1 == 1 |> identityBool)
                        }
                        fn identityBool(value: Bool): Bool {
                            return value
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True$EOL", testInfo)
        }

        @Test
        fun `forward application groups an or-expression on the right`() = assertCompilationFails(
            function("print(1 |> f or True)"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' must be a callable name or member call",
        )
    }

    @Nested
    inner class Targets {
        @Test
        fun `forward application works with builtins`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            2 |> print
                        }
                    """.trimIndent()
            assertProgramPrints(source, "2$EOL", testInfo)
        }

        @Test
        fun `forward application calls sibling class methods`(testInfo: TestInfo) {
            val source = """
                        class Counter {
                            val value: Int

                            fn add(amount: Int): Int {
                                return value + amount
                            }

                            fn viaForward(amount: Int): Int {
                                return amount |> add
                            }
                        }
                        fn $TEST_MAIN(): Int {
                            return Counter(10).viaForward(5)
                        }
                    """.trimIndent()
            assertProgramReturns(source, 15, testInfo)
        }

        @Test
        fun `forward application accepts class constructors on the right`(testInfo: TestInfo) {
            val source = """
                        class Point {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN(): Int {
                            val point = 2 |> Point(1)
                            return point.x * 10 + point.y
                        }
                    """.trimIndent()
            assertProgramReturns(source, 21, testInfo)
        }

        @Test
        fun `forward application to a non-callable value`() = assertCompilationFails(
            function("print(1 |> 2)"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' must be a callable name or member call",
        )

        @Test
        fun `forward application to a member access without a call`() = assertCompilationFails(
            function("print(1 |> value.name)"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' must be a callable name or member call",
        )

        @Test
        fun `forward application calling a call result`() = assertCompilationFails(
            function("print(1 |> f()())"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' cannot call the result of another call",
        )
    }

    @Nested
    inner class MemberChains {
        @Test
        fun `forward application accepts field access on the left`(testInfo: TestInfo) {
            val source = """
                        class Point {
                            val x: Int
                            val y: Int
                        }
                        fn double(value: Int): Int {
                            return value * 2
                        }
                        fn $TEST_MAIN(): Int {
                            return Point(x = 3, y = 4).x |> double
                        }
                    """.trimIndent()
            assertProgramReturns(source, 6, testInfo)
        }

        @Test
        fun `forward application calls methods on member chains`(testInfo: TestInfo) {
            val source = """
                        class Runtime {
                            val base: Int

                            fn process(input: Int): Int {
                                return input + base
                            }
                        }
                        class Processor {
                            val runtime: Runtime
                        }
                        fn $TEST_MAIN(): Int {
                            val processor = Processor(Runtime(10))
                            return 5 |> processor.runtime.process()
                        }
                    """.trimIndent()
            assertProgramReturns(source, 15, testInfo)
        }

        @Test
        fun `forward application passes the left value before explicit method arguments`(testInfo: TestInfo) {
            val source = """
                        class Calculator {
                            val offset: Int

                            fn add(value: Int, extra: Int): Int {
                                return value + extra + offset
                            }
                        }
                        fn $TEST_MAIN(): Int {
                            val calculator = Calculator(100)
                            return 1 |> calculator.add(20)
                        }
                    """.trimIndent()
            assertProgramReturns(source, 121, testInfo)
        }

        @Test
        fun `forward application chains member calls`(testInfo: TestInfo) {
            val source = """
                        class Runtime {
                            val base: Int

                            fn process(input: Int): Int {
                                return input + base
                            }
                        }
                        class Processor {
                            val runtime: Runtime
                        }
                        fn $TEST_MAIN() {
                            val processor = Processor(Runtime(10))
                            1 |> processor.runtime.process() |> print()
                        }
                    """.trimIndent()
            assertProgramPrints(source, "11$EOL", testInfo)
        }

        @Test
        fun `forward application evaluates the left value before a member chain`(testInfo: TestInfo) {
            val source = """
                        class Box {
                            val base: Int
                            fn plus(a: Int, b: Int): Int {
                                return a + b + base
                            }
                        }
                        fn left(): Int {
                            print("left")
                            return 1
                        }
                        fn box(): Box {
                            print("box")
                            return Box(10)
                        }
                        fn two(): Int {
                            print("two")
                            return 2
                        }
                        fn $TEST_MAIN() {
                            print(left() |> box().plus(two()))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "left${EOL}box${EOL}two${EOL}13$EOL", testInfo)
        }
    }
}
