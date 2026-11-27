package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException

/** Control flow: `if`, `while`, `break`/`continue`, and unreachable statements. */
class ControlFlowTests {
    @Nested
    inner class If {
        @Test
        fun `if statements execute only then-block if True`() = assertPrints(
            """
            fn main() {
                if 1 + 1 < 3 {
                    print("So true")
                } else {
                    print("Never gonna happen")
                }
            }
            """,
            "So true",
        )

        @Test
        fun `if statements execute only else-block if False`() = assertPrints(
            """
            fn main() {
                if False {
                    print("So true")
                } else {
                    print("Absolutely false!")
                }
            }
            """,
            "Absolutely false!",
        )

        @Test
        fun `else if executes the matching branch`() = assertPrints(
            """
            fn main() {
                if False {
                    print("if")
                } else if True {
                    print("else if")
                } else {
                    print("else")
                }
            }
            """,
            "else if",
        )

        @Test
        fun `else if falls through to else when no branch matches`() = assertPrints(
            """
            fn main() {
                if False {
                    print("if")
                } else if False {
                    print("else if")
                } else {
                    print("else")
                }
            }
            """,
            "else",
        )

        @Test
        fun `else if chains select the first matching branch`() = assertPrints(
            """
            fn main() {
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
            """,
            "third",
        )

        @Test
        fun `else if without a final else terminates before following statement`() = assertPrints(
            """
            fn main() {
                if False {
                    print("if")
                } else if False {
                    print("else if")
                }

                print("after")
            }
            """,
            "after",
        )

        @Test
        fun `if without else still terminates before following statement`() = assertPrints(
            """
            fn main() {
                if False {
                    print("then")
                }

                print("after")
            }
            """,
            "after",
        )

        @Test
        fun `if and while conditions may use grouping parentheses`() = assertReturns(
            """
            fn main(): Int {
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
            """,
            3,
        )

        @Test
        fun `rejects non-Bool conditions`() = assertCompilationFails(
            function("if 1 {", "}"),
            JvmLoweringException.ConditionNotBool::class,
            "conditions must result in a bool",
        )
    }

    @Nested
    inner class IfExpressions {
        @Test
        fun `if expressions evaluate to Unit when returned`() = assertPrints(
            """
            fn main() {
                return if False {
                    print("if")
                } else {
                    print("else")
                }
            }
            """,
            "else",
        )

        @Test
        fun `if expressions return the matching branch value`() = assertPrints(
            """
            fn main() {
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
            """,
            "1",
            "4",
        )

        @Test
        fun `if expressions return the last expression in a branch`() = assertPrints(
            """
            fn main() {
                print(if True {
                    val value = 40
                    value + 2
                } else {
                    0
                })
            }
            """,
            "42",
        )

        @Test
        fun `if branch expression statements are discarded`() = assertPrints(
            """
            fn main() {
                print(if True {
                    1 + 1
                    3
                } else {
                    4
                })
            }
            """,
            "3",
        )

        @Test
        fun `else if expressions return the matching branch value`() = assertPrints(
            """
            fn main() {
                print(if False {
                    1
                } else if True {
                    2
                } else {
                    3
                })
            }
            """,
            "2",
        )

        @Test
        fun `if expressions can be bound to a variable`() = assertPrints(
            """
            fn main() {
                val value = if False {
                    1
                } else {
                    2
                }
                print(value)
            }
            """,
            "2",
        )

        @Test
        fun `if expressions can be returned`() = assertReturns(
            """
            fn main(): Int {
                return if False {
                    1
                } else {
                    2
                }
            }
            """,
            2,
        )

        @Test
        fun `if expressions used as statements discard their value`() = assertPrints(
            """
            fn main() {
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
            """,
            "after",
        )

        @Test
        fun `if without else produces Unit`() = assertReturns(
            """
            fn main(): Int {
                val result: Unit = if False {
                    print("no")
                }
                return 1
            }
            """,
            1,
        )

        @Test
        fun `if without else discards a non-Unit branch value`() = assertReturns(
            """
            fn main(): Int {
                val result: Unit = if False {
                    1
                }
                return 1
            }
            """,
            1,
        )

        @Test
        fun `rejects if branches with different types`() = assertCompilationFails(
            function("val value = if True {", "    1", "} else {", "    \"no\"", "}"),
            JvmLoweringException.IfBranchTypeMismatch::class,
            "if branches must have the same type",
        )
    }

    @Nested
    inner class While {
        @Test
        fun `while with a false condition skips its body`() = assertPrints(
            """
            fn main() {
                while False {
                    print("body")
                }
                print("after")
            }
            """,
            "after",
        )

        @Test
        fun `while executes its body when the condition is true`() = assertPrints(
            """
            fn main() {
                while 1 + 1 < 3 {
                    print("once")
                    return
                }
                print("after")
            }
            """,
            "once",
        )

        @Test
        fun `while loops can terminate through rebinding`() = assertPrints(
            """
            fn main() {
                mut val counter = 0
                while counter <= 2 {
                    print(counter)
                    counter = counter + 1
                }
            }
            """,
            "0",
            "1",
            "2",
        )
    }

    @Nested
    inner class BreakAndContinue {
        @Test
        fun `break exits the innermost while loop`() = assertReturns(
            """
            fn main(): Int {
                mut val count = 0
                while True {
                    count = count + 1
                    if count == 3 {
                        break
                    }
                }
                return count
            }
            """,
            3,
        )

        @Test
        fun `continue skips the rest of the loop body`() = assertReturns(
            """
            fn main(): Int {
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
            """,
            12,
        )

        @Test
        fun `break only exits the innermost loop`() = assertReturns(
            """
            fn main(): Int {
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
            """,
            6,
        )

        @Test
        fun `break works inside an if that forms a whole statement`() = assertReturns(
            """
            fn main(): Int {
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
            """,
            20,
        )

        @Test
        fun `break works in a loop nested inside an expression`() = assertReturns(
            """
            fn add(a: Int, b: Int): Int {
                return a + b
            }
            fn main(): Int {
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
            """,
            5,
        )

        @Test
        fun `rejects break outside loops`() = assertCompilationFails(
            function("break"),
            JvmLoweringException.BreakOutsideLoop::class,
            "break is only allowed inside a loop",
        )

        @Test
        fun `rejects continue outside loops`() = assertCompilationFails(
            function("continue"),
            JvmLoweringException.ContinueOutsideLoop::class,
            "continue is only allowed inside a loop",
        )

        @Test
        fun `rejects break inside call arguments`() = assertCompilationFails(
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
        fun `rejects continue inside operands`() = assertCompilationFails(
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
        fun `rejects statements after return`() = assertCompilationFails(
            function("return", "print(1)"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after return",
        )

        @Test
        fun `rejects statements after break`() = assertCompilationFails(
            function("while True {", "    break", "    print(1)", "}"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after break",
        )

        @Test
        fun `rejects statements after continue`() = assertCompilationFails(
            function("while True {", "    continue", "    print(1)", "}"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after continue",
        )

        @Test
        fun `rejects block results after break`() = assertCompilationFails(
            function("while True {", "    val value = if True {", "        break", "        0", "    } else {", "        1", "    }", "}"),
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after break",
        )

        @Test
        fun `rejects statements after an if that returns from every branch`() = assertCompilationFails(
            "fn pick(flag: Bool): Int {\n    if flag {\n        return 1\n    } else {\n        return 2\n    }\n" +
                "    return 3\n}",
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after an if whose branches all return",
        )

        @Test
        fun `rejects statements after a match whose arms all return`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Empty has Shape") +
                "fn area(shape: Shape): Int {\n    match shape {\n        Empty -> return 0\n    }\n    return 1\n}",
            JvmLoweringException.UnreachableStatement::class,
            "unreachable statement after a match whose arms all return",
        )
    }
}
