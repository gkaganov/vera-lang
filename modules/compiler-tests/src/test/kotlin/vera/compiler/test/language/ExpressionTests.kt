package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException

/** Expressions: arithmetic, comparisons, equality, Boolean logic, and precedence. */
class ExpressionTests {
    @Nested
    inner class Arithmetic {
        @Test
        fun `Ints can be added`() = assertReturns(
            """
            fn main(): Int {
                val sum = 2 + 2
                return sum
            }
            """,
            4,
        )

        @Test
        fun `Ints can be subtracted`() = assertReturns(
            """
            fn main(): Int {
                val difference = 100 - 37
                return difference
            }
            """,
            63,
        )

        @Test
        fun `unary minus negates Ints`() = assertPrints(
            """
            fn main() {
                print(-1)
                val value = 5
                print(-value)
                print(-2 * 3)
                print(1 - -2)
                print(-(-4))
            }
            """,
            "-1",
            "-5",
            "-6",
            "3",
            "4",
        )

        @Test
        fun `Ints can be multiplied`() = assertReturns(
            """
            fn main(): Int {
                val product = 6 * 8
                return product
            }
            """,
            48,
        )

        @Test
        fun `Ints can be divided if the result is an Int`() = assertReturns(
            """
            fn main(): Int {
                val quotient = 16 / 4
                return quotient
            }
            """,
            4,
        )

        @Test
        fun `Ints support modulo`() = assertReturns(
            """
            fn main(): Int {
                return 17 % 5
            }
            """,
            2,
        )

        @Test
        fun `rejects arithmetic with a non-Int operand`() = assertCompilationFails(
            function("val value = 1 * \"x\""),
            JvmLoweringException.IntOperandsRequired::class,
            "operator `*` is only defined for Int operands, got String",
        )

        @Test
        fun `rejects chained comparisons`() = assertCompilationFails(
            function("print(1 < 2 < 3)"),
            JvmLoweringException.IntOperandsRequired::class,
            "operator `<` is only defined for Int operands, got Bool; comparisons cannot be chained; " +
                "write `a < b and b < c`",
        )

        @Test
        fun `rejects non-Int binary operands`() = assertCompilationFails(
            function("val value = \"x\" * \"y\""),
            JvmLoweringException.IntOperandsRequired::class,
            "operator `*` is only defined for Int operands",
        )

        @Test
        fun `rejects non-Int unary operands`() = assertCompilationFails(
            function("print(-\"text\")"),
            JvmLoweringException.IntOperandsRequired::class,
            "operator `-` is only defined for Int operands",
        )
    }

    @Nested
    inner class Comparisons {
        @Test
        fun `Ints can be compared with less-than`() = assertPrints(
            """
            fn main() {
                print(3 < 5)
                print(5 < 3)
                print(3 < 3)
                print(1 + 1 < 3)
                print(3 < 1 + 1)
            }
            """,
            "True",
            "False",
            "False",
            "True",
            "False",
        )

        @Test
        fun `Ints can be compared with greater-than`() = assertPrints(
            """
            fn main() {
                print(5 > 3)
                print(3 > 5)
                print(3 > 3)
            }
            """,
            "True",
            "False",
            "False",
        )

        @Test
        fun `Ints can be compared with less-or-equal and greater-or-equal`() = assertPrints(
            """
            fn main() {
                print(3 <= 3)
                print(4 <= 3)
                print(3 >= 3)
                print(3 >= 4)
            }
            """,
            "True",
            "False",
            "True",
            "False",
        )

        @Test
        fun `rejects ordering Bool operands`() = assertCompilationFails(
            function("print(True < False)"),
            JvmLoweringException.IntOperandsRequired::class,
            "operator `<` is only defined for Int operands, got Bool",
        )
    }

    @Nested
    inner class Equality {
        @Test
        fun `equals compares Ints correctly`() = assertPrints(
            """
            fn main() {
                print(3 == 3)
                print(4 == 5)
            }
            """,
            "True",
            "False",
        )

        @Test
        fun `not-equals compares Ints correctly`() = assertPrints(
            """
            fn main() {
                print(3 != 52)
                print(4 != 4)
            }
            """,
            "True",
            "False",
        )

        @Test
        fun `Bool equality compares values`() = assertPrints(
            """
            fn main() {
                print(True == True)
                print(True == False)
                print(True != False)
                print(False != False)
            }
            """,
            "True",
            "False",
            "True",
            "False",
        )

        @Test
        fun `String equality compares contents`() = assertPrints(
            """
            fn main() {
                print("hello" == "hello")
                print("hello" == "world")
                print("hello" != "world")
                print("hello" != "hello")
            }
            """,
            "True",
            "False",
            "True",
            "False",
        )

        @Test
        fun `rejects equality operands with different types`() = assertCompilationFails(
            function("print(1 == True)"),
            JvmLoweringException.OperandTypesDiffer::class,
            "operator `==` requires both operands to have the same type",
        )
    }

    @Nested
    inner class BooleanLogic {
        @Test
        fun `logical and evaluates booleans`() = assertPrints(
            """
            fn main() {
                print(True and True)
                print(True and False)
                print(False and True)
                print(False and False)
            }
            """,
            "True",
            "False",
            "False",
            "False",
        )

        @Test
        fun `logical or evaluates booleans`() = assertPrints(
            """
            fn main() {
                print(True or True)
                print(True or False)
                print(False or True)
                print(False or False)
            }
            """,
            "True",
            "True",
            "True",
            "False",
        )

        @Test
        fun `not negates booleans`() = assertPrints(
            """
            fn main() {
                val done = False
                print(not True)
                print(not done)
                print(not not True)
                print(not (1 < 2))
            }
            """,
            "False",
            "True",
            "True",
            "False",
        )

        @Test
        fun `not applies to whole member call chains`() = assertPrints(
            """
            class Task {
                val finished: Bool
                fn isFinished(): Bool {
                    return finished
                }
            }
            fn main() {
                val task = Task(False)
                if not task.isFinished() and not task.finished {
                    print("pending")
                }
            }
            """,
            "pending",
        )

        @Test
        fun `logical operators short-circuit`() = assertPrints(
            """
            fn main() {
                print(False and fail())
                print(True or fail())
            }
            fn fail(): Bool {
                print("should not run")
                return True
            }
            """,
            "False",
            "True",
        )

        @Test
        fun `rejects non-Bool logical operands`() = assertCompilationFails(
            function("val value = 1 and True"),
            JvmLoweringException.BoolOperandsRequired::class,
            "operator `and` is only defined for Bool operands",
        )

        @Test
        fun `rejects non-Bool not operands`() = assertCompilationFails(
            function("val value = not 1"),
            JvmLoweringException.BoolOperandsRequired::class,
            "operator `not` is only defined for Bool operands",
        )
    }

    @Nested
    inner class Precedence {
        @Test
        fun `multiplication has precedence over addition`() = assertReturns(
            """
            fn main(): Int {
                return 2 + 3 * 4
            }
            """,
            14,
        )

        @Test
        fun `mixed addition and subtraction are left-associative`() = assertReturns(
            """
            fn main(): Int {
                return 8 - 2 + 1
            }
            """,
            7,
        )

        @Test
        fun `mixed multiplication, division, and modulo are left-associative`() = assertReturns(
            """
            fn main(): Int {
                return 8 / 2 * 3 % 5
            }
            """,
            2,
        )

        @Test
        fun `additive expressions have precedence over equality`() = assertPrints(
            """
            fn main() {
                print(1 + 1 == 2)
                print(2 == 1 + 1)
                print(1 + 1 == 3 - 1)
            }
            """,
            "True",
            "True",
            "True",
        )

        @Test
        fun `parentheses override arithmetic precedence`() = assertReturns(
            """
            fn main(): Int {
                return (1 + 2) * 3
            }
            """,
            9,
        )

        @Test
        fun `not binds tighter than comparison and logical operators`() = assertPrints(
            """
            fn main() {
                print(not True == False)
                print(not False and False)
                print(not True or True)
                print(True == not False)
            }
            """,
            "True",
            "False",
            "True",
            "True",
        )

        @Test
        fun `rejects not applied to an Int comparison operand`() = assertCompilationFails(
            function("val value = not 1 == 2"),
            JvmLoweringException.BoolOperandsRequired::class,
            "operator `not` is only defined for Bool operands",
        )
    }
}
