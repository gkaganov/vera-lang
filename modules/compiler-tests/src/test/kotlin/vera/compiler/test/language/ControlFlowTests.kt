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

/** Control flow: `if`, `while`, `break`/`continue`, and unreachable statements. */
class ControlFlowTests {
    @Nested
    inner class If {
        @Test
        fun `if statements execute only then-block if True`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if 1 + 1 < 3 {
                                print("So true")
                            } else {
                                print("Never gonna happen")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "So true$EOL", testInfo)
        }

        @Test
        fun `if statements execute only else-block if False`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("So true")
                            } else {
                                print("Absolutely false!")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Absolutely false!$EOL", testInfo)
        }

        @Test
        fun `else if executes the matching branch`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("if")
                            } else if True {
                                print("else if")
                            } else {
                                print("else")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "else if$EOL", testInfo)
        }

        @Test
        fun `else if falls through to else when no branch matches`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("if")
                            } else if False {
                                print("else if")
                            } else {
                                print("else")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "else$EOL", testInfo)
        }

        @Test
        fun `else if chains select the first matching branch`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("if")
                            } else if False {
                                print("second")
                            } else if True {
                                print("third")
                            } else {
                                print("else")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "third$EOL", testInfo)
        }

        @Test
        fun `else if without a final else terminates before following statement`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("if")
                            } else if False {
                                print("else if")
                            }

                            print("after")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "after$EOL", testInfo)
        }

        @Test
        fun `if without else still terminates before following statement`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("then")
                            }

                            print("after")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "after$EOL", testInfo)
        }

        @Test
        fun `if and while conditions may use grouping parentheses`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val count = 0
                            mut val total = 0
                            while (count < 3) {
                                if (count == 1) or (count == 2) {
                                    total = total + count
                                }
                                count = count + 1
                            }
                            return total
                        }
                    """.trimIndent()
            assertProgramReturns(source, 3, testInfo)
        }

        @Test
        fun `non-Bool conditions`() = assertCompilationFails(
            function("if 1 {", "}"),
            JvmLoweringException.ConditionNotBool::class,
            "conditions must result in a bool",
        )
    }

    @Nested
    inner class IfExpressions {
        @Test
        fun `if expressions evaluate to Unit when returned`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            return if False {
                                print("if")
                            } else {
                                print("else")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "else$EOL", testInfo)
        }

        @Test
        fun `if expressions return the matching branch value`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(if True {
                                1
                            } else {
                                2
                            })
                            print(if False {
                                3
                            } else {
                                4
                            })
                        }
                    """.trimIndent()
            assertProgramPrints(source, "1${EOL}4${EOL}", testInfo)
        }

        @Test
        fun `if expressions return the last expression in a branch`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(if True {
                                val value = 40
                                value + 2
                            } else {
                                0
                            })
                        }
                    """.trimIndent()
            assertProgramPrints(source, "42$EOL", testInfo)
        }

        @Test
        fun `if branch expression statements are discarded`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(if True {
                                1 + 1
                                3
                            } else {
                                4
                            })
                        }
                    """.trimIndent()
            assertProgramPrints(source, "3$EOL", testInfo)
        }

        @Test
        fun `else if expressions return the matching branch value`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(if False {
                                1
                            } else if True {
                                2
                            } else {
                                3
                            })
                        }
                    """.trimIndent()
            assertProgramPrints(source, "2$EOL", testInfo)
        }

        @Test
        fun `if expressions can be bound to a variable`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            val value = if False {
                                1
                            } else {
                                2
                            }
                            print(value)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "2$EOL", testInfo)
        }

        @Test
        fun `if expressions can be returned`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return if False {
                                1
                            } else {
                                2
                            }
                        }
                    """.trimIndent()
            assertProgramReturns(source, 2, testInfo)
        }

        @Test
        fun `if expressions used as statements discard their value`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if True {
                                1
                            } else {
                                2
                            }

                            if True {
                                3
                            }

                            print("after")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "after$EOL", testInfo)
        }

        @Test
        fun `if without else produces Unit`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val result: Unit = if False {
                                print("no")
                            }
                            return 1
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }

        @Test
        fun `if without else discards a non-Unit branch value`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val result: Unit = if False {
                                1
                            }
                            return 1
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }

        @Test
        fun `if branches with different types`() = assertCompilationFails(
            function("val value = if True {", "    1", "} else {", "    \"no\"", "}"),
            JvmLoweringException.IfBranchTypeMismatch::class,
            "if branches must have the same type",
        )
    }

    @Nested
    inner class While {
        @Test
        fun `while with a false condition skips its body`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            while False {
                                print("body")
                            }
                            print("after")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "after$EOL", testInfo)
        }

        @Test
        fun `while executes its body when the condition is true`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            while 1 + 1 < 3 {
                                print("once")
                                return
                            }
                            print("after")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "once$EOL", testInfo)
        }

        @Test
        fun `while loops can terminate through rebinding`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            mut val counter = 0
                            while counter <= 2 {
                                print(counter)
                                counter = counter + 1
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "0${EOL}1${EOL}2${EOL}", testInfo)
        }
    }

    @Nested
    inner class BreakAndContinue {
        @Test
        fun `break exits the innermost while loop`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val count = 0
                            while True {
                                count = count + 1
                                if count == 3 {
                                    break
                                }
                            }
                            return count
                        }
                    """.trimIndent()
            assertProgramReturns(source, 3, testInfo)
        }

        @Test
        fun `continue skips the rest of the loop body`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val count = 0
                            mut val total = 0
                            while count < 5 {
                                count = count + 1
                                if count == 3 {
                                    continue
                                }
                                total = total + count
                            }
                            return total
                        }
                    """.trimIndent()
            assertProgramReturns(source, 12, testInfo)
        }

        @Test
        fun `break only exits the innermost loop`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val outer = 0
                            mut val total = 0
                            while outer < 3 {
                                outer = outer + 1
                                mut val inner = 0
                                while True {
                                    inner = inner + 1
                                    if inner == 2 {
                                        break
                                    }
                                }
                                total = total + inner
                            }
                            return total
                        }
                    """.trimIndent()
            assertProgramReturns(source, 6, testInfo)
        }

        @Test
        fun `break works inside an if that forms a whole statement`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            mut val counter = 0
                            mut val last = 0
                            while True {
                                counter = counter + 1
                                val value = if counter < 10 {
                                    if counter == 3 {
                                        break
                                    }
                                    counter * 10
                                } else {
                                    0
                                }
                                last = value
                            }
                            return last
                        }
                    """.trimIndent()
            assertProgramReturns(source, 20, testInfo)
        }

        @Test
        fun `break works in a loop nested inside an expression`(testInfo: TestInfo) {
            val source = """
                        fn add(a: Int, b: Int): Int {
                            return a + b
                        }
                        fn $TEST_MAIN(): Int {
                            return add(a = 1, b = if True {
                                mut val count = 0
                                while True {
                                    count = count + 1
                                    if count == 4 {
                                        break
                                    }
                                }
                                count
                            } else {
                                0
                            })
                        }
                    """.trimIndent()
            assertProgramReturns(source, 5, testInfo)
        }

        @Test
        fun `break outside loops`() = assertCompilationFails(
            function("break"),
            JvmLoweringException.BreakOutsideLoop::class,
            "break is only allowed inside a loop",
        )

        @Test
        fun `continue outside loops`() = assertCompilationFails(
            function("continue"),
            JvmLoweringException.ContinueOutsideLoop::class,
            "continue is only allowed inside a loop",
        )

        @Test
        fun `break inside call arguments`() = assertCompilationFails(
            "fn add(a: Int, b: Int): Int {\n    return a + b\n}\n" + function(
                "while True {",
                "    print(add(1, if True {",
                "        break",
                "        0",
                "    } else {",
                "        1",
                "    }))",
                "}",
            ),
            JvmLoweringException.LoopJumpInsideExpression::class,
            "break cannot be used inside a nested expression",
        )

        @Test
        fun `continue inside operands`() = assertCompilationFails(
            function(
                "while True {",
                "    val value = 1 + if True {",
                "        continue",
                "        0",
                "    } else {",
                "        1",
                "    }",
                "}",
            ),
            JvmLoweringException.LoopJumpInsideExpression::class,
            "continue cannot be used inside a nested expression",
        )
    }

    @Nested
    inner class Unreachable {
        @Test
        fun `statements after return`() = assertCompilationFails(
            function("return", "print(1)"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after return",
        )

        @Test
        fun `statements after break`() = assertCompilationFails(
            function("while True {", "    break", "    print(1)", "}"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after break",
        )

        @Test
        fun `statements after continue`() = assertCompilationFails(
            function("while True {", "    continue", "    print(1)", "}"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after continue",
        )

        @Test
        fun `block results after break`() = assertCompilationFails(
            function("while True {", "    val value = if True {", "        break", "        0", "    } else {", "        1", "    }", "}"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after break",
        )

        @Test
        fun `statements after an if that returns from every branch`() = assertCompilationFails(
            "fn pick(flag: Bool): Int {\n    if flag {\n        return 1\n    } else {\n        return 2\n    }\n" +
                "    return 3\n}",
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after an if whose branches all return",
        )

        @Test
        fun `statements after a match whose arms all return`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Empty has Shape") +
                "fn area(shape: Shape): Int {\n    match shape {\n        Empty -> return 0\n    }\n    return 1\n}",
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after a match whose arms all return",
        )
    }
}
