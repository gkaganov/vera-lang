package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** Variables and rebinding: `val`, `mut val`, and block scopes. */
class BindingTests {
    @Nested
    inner class Val {
        @Test
        fun `val with explicit type declaration can be bound and accessed`() = assertReturns(
            """
            fn main(): Int {
                val myVar1: Int = 50
                val myVar2: Int = 51
                return myVar1 + myVar2 + 52
            }
            """,
            153,
        )

        @Test
        fun `val types can be inferred when bound directly`() = assertPrints(
            """
            fn main() {
                val inferredString = "I am a String"
                val inferredInt = 1000
                print(inferredString)
                print(inferredInt)
            }
            """,
            "I am a String",
            "1000",
        )

        @Test
        fun `rejects bindings that contradict their declared type`() = assertCompilationFails(
            function("val value: Int = \"text\""),
            JvmLoweringException.BindTypeMismatch::class,
            "variable value is declared as Int but bound to a value of type String",
        )
    }

    @Nested
    inner class Rebinding {
        @Test
        fun `mut val bindings can be rebound`() = assertPrints(
            """
            class Point has Display {
                val x: Int
                val y: Int
            }
            fn main() {
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
            """,
            "3",
            "Point(x: 6, y: 0)",
        )

        @Test
        fun `mut val without a value makes a parameter rebindable`() = assertPrints(
            """
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
            fn main() {
                countdown(3)
                print(Counter(10).countUp(2))
            }
            """,
            "3",
            "2",
            "1",
            "12",
        )

        @Test
        fun `variables can be rebound with equals`() = assertReturns(
            """
            fn main(): Int {
                mut val value = 1
                value = 42
                return value
            }
            """,
            42,
        )

        @Test
        fun `rebinding can read the previous value`() = assertReturns(
            """
            fn main(): Int {
                mut val value = 1
                value = value + 2
                return value
            }
            """,
            3,
        )

        @Test
        fun `rejects mut without val`() = assertCompilationFails(
            function("mut count = 0"),
            VeraParseException.ExpectedToken::class,
            "expected `val` after `mut`, found 'count'",
        )

        @Test
        fun `rejects rebind update blocks`() = assertCompilationFails(
            function("value = {", "    1", "}"),
            VeraParseException.UnsupportedRebindUpdateBlock::class,
            "rebind update blocks are not supported",
        )

        @Test
        fun `rejects rebinding val bindings`() = assertCompilationFails(
            function("val count = 0", "count = 1"),
            JvmLoweringException.ImmutableBindingRebound::class,
            "count is immutable; declare it with `mut val` to rebind it",
        )

        @Test
        fun `rejects rebinding parameters`() = assertCompilationFails(
            "fn next(value: Int): Int {\n    value = value + 1\n    return value\n}",
            JvmLoweringException.ParameterRebound::class,
            "parameter value cannot be rebound; write `mut val value` first to make it rebindable",
        )

        @Test
        fun `rejects mut val without a value for a local`() = assertCompilationFails(
            function("val count = 0", "mut val count"),
            JvmLoweringException.MutableParameterRequiresParameter::class,
            "`mut val count` without a value makes a parameter rebindable, but count is not a parameter",
        )

        @Test
        fun `rejects rebinding a parameter before it is made rebindable`() = assertCompilationFails(
            "fn next(value: Int): Int {\n    value = value + 1\n    mut val value\n    return value\n}",
            JvmLoweringException.ParameterRebound::class,
            "parameter value cannot be rebound; write `mut val value` first to make it rebindable",
            line = 2,
            column = 5,
        )

        @Test
        fun `rejects mut val without a value repeated`() = assertCompilationFails(
            "fn next(value: Int): Int {\n    mut val value\n    mut val value\n    return value\n}",
            JvmLoweringException.MutableParameterRepeated::class,
            "parameter value is already rebindable",
        )

        @Test
        fun `rejects mut val without a value inside a block`() = assertCompilationFails(
            "fn next(value: Int) {\n    if True {\n        mut val value\n    }\n}",
            JvmLoweringException.MutableParameterOutsideFunctionBody::class,
            "`mut val value` must be written directly in the function body, not inside a block",
        )

        @Test
        fun `rejects rebinding unknown variables`() = assertCompilationFails(
            function("missing = 1"),
            JvmLoweringException.UnknownVariable::class,
            "variable missing is not defined",
        )

        @Test
        fun `rejects rebinding to a different type`() = assertCompilationFails(
            function("mut val value = 1", "value = \"text\""),
            JvmLoweringException.RebindTypeMismatch::class,
            "cannot rebind variable value",
        )
    }

    @Nested
    inner class Scopes {
        @Test
        fun `sibling blocks can reuse local names`() = assertPrints(
            """
            fn pick(flag: Bool): Int {
                return if flag {
                    val value = 1
                    value
                } else {
                    val value = 2
                    value
                }
            }
            fn main() {
                print(pick(True))
                print(pick(False))
                mut val round = 0
                while round < 2 {
                    val doubled = round * 2
                    print(doubled)
                    round = round + 1
                }
            }
            """,
            "1",
            "2",
            "0",
            "2",
        )

        @Test
        fun `rejects locals used after the if branch that declares them`() = assertCompilationFails(
            function("if True {", "    val inner = 1", "}", "print(inner)"),
            JvmLoweringException.UnknownVariable::class,
            "variable inner is not defined",
        )

        @Test
        fun `rejects locals used after the loop body that declares them`() = assertCompilationFails(
            function("while False {", "    val inner = 1", "}", "print(inner)"),
            JvmLoweringException.UnknownVariable::class,
            "variable inner is not defined",
        )
    }
}
