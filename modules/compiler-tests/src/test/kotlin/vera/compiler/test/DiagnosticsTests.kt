package vera.compiler.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import vera.compiler.VeraCompiler
import vera.compiler.VeraDiagnostics
import vera.jvm.lowering.JvmLoweringException
import vera.shared.model.Identifier

/** Rendering of compiler errors with their source location. */
class DiagnosticsTests {
    @Test
    fun `diagnostics underline the expression that caused a lowering error`() {
        assertRendersAs(
            "fn main() {\n    print(1 + True)\n}\n",
            """
            error: operator `+` is only defined for Int operands, got Bool
             --> main.vera:2:11
              |
            2 |     print(1 + True)
              |           ^^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics underline the offending argument`() {
        assertRendersAs(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                "fn main() {\n    print(add(1, right = 2))\n}\n",
            """
            error: argument 1 of function add must be named: write `left = ...`
             --> main.vera:5:15
              |
            5 |     print(add(1, right = 2))
              |               ^
            """,
        )
    }

    @Test
    fun `diagnostics point at the unexpected token of a parse error`() {
        assertRendersAs(
            "fn main() {\n    val value =\n}\n",
            """
            error: expected an expression, found `}`
             --> main.vera:3:1
              |
            3 | }
              | ^
            """,
        )
    }

    @Test
    fun `diagnostics point at the character of a lexer error`() {
        assertRendersAs(
            "fn main() {\n    print(1);\n}\n",
            """
            error: Vera does not use semicolons; end the statement with a newline
             --> main.vera:2:13
              |
            2 |     print(1);
              |             ^
            """,
        )
    }

    @Test
    fun `diagnostics underline the header of a conflicting declaration`() {
        assertRendersAs(
            "fn helper() {\n}\nfn helper() {\n}\n",
            """
            error: function helper is declared more than once; Vera has no overloading, so give each function its own name
             --> main.vera:3:1
              |
            3 | fn helper() {
              | ^^^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics underline a multi-line span to the end of its first line`() {
        assertRendersAs(
            "fn main() {\n    val value = if True {\n        1\n    } else {\n        \"a\"\n    }\n}\n",
            """
            error: if branches must have the same type, got Int and String
             --> main.vera:2:17
              |
            2 |     val value = if True {
              |                 ^^^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics keep tab indentation so the underline lines up`() {
        assertRendersAs(
            "fn main() {\n\tprint(missing)\n}\n",
            "error: variable missing is not defined\n" +
                " --> main.vera:2:8\n" +
                "  |\n" +
                "2 | \tprint(missing)\n" +
                "  | \t      ^^^^^^^",
        )
    }

    @Test
    fun `diagnostics show CRLF source lines without the carriage return`() {
        assertRendersAs(
            "fn main() {\r\n    print(missing)\r\n}\r\n",
            """
            error: variable missing is not defined
             --> main.vera:2:11
              |
            2 |     print(missing)
              |           ^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics widen the gutter for multi-digit line numbers`() {
        assertRendersAs(
            "fn main() {\n" + "    print(1)\n".repeat(8) + "    print(missing)\n}\n",
            """
            error: variable missing is not defined
              --> main.vera:10:11
               |
            10 |     print(missing)
               |           ^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics without a source location show only the message`() {
        val error = Assertions.assertThrows(JvmLoweringException.ProgramClassConflictsWithUnit::class.java) {
            VeraCompiler.compile(Identifier("Unit"), "fn main() {\n}\n")
        }
        Assertions.assertEquals(
            "error: program class Unit conflicts with the built-in Unit class",
            VeraDiagnostics.render(error, "fn main() {\n}\n", "main.vera"),
        )
    }

    private fun assertRendersAs(source: String, expected: String) {
        val error = compilationError(source)
        Assertions.assertEquals(expected.trimIndent(), VeraDiagnostics.render(error, source, "main.vera"))
    }
}
