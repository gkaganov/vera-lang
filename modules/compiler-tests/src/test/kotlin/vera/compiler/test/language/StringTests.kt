package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.function
import vera.parser.VeraLexException
import vera.parser.VeraParseException

/** String literals and string templates. */
class StringTests {
    @Nested
    inner class Literals {
        @Test
        fun `strings support punctuation unicode and escapes`() = assertPrints(
            """
            fn main() {
                print("Hello, world!")
                print("punctuation: (yes) [ok] {braces} #%&*+-=;:'/@~`")
                print("quote: \" backslash: \\")
                print("line\nbreak")
                print("tab\tend")
                print("carriage\rreturn")
                print("unicode: é 日本 😀")
            }
            """,
            "Hello, world!",
            "punctuation: (yes) [ok] {braces} #%&*+-=;:'/@~`",
            "quote: \" backslash: \\",
            "line\nbreak",
            "tab\tend",
            "carriage\rreturn",
            "unicode: é 日本 😀",
        )

        @Test
        fun `unicode escapes take four hex digits`() = assertPrints(
            """
            fn main() {
                print("\u0041\u00e9\u00C9!")
            }
            """,
            "A\u00e9\u00c9!",
        )

        @Test
        fun `rejects unicode escapes without four hex digits`() = assertCompilationFails(
            function("print(\"\\u00G1\")"),
            VeraLexException.InvalidUnicodeEscape::class,
            "`\\u` needs exactly four hex digits, as in `\\u00E9`",
        )

        @Test
        fun `rejects unicode escapes cut short by the end of the string`() = assertCompilationFails(
            function("print(\"\\u12\")"),
            VeraLexException.InvalidUnicodeEscape::class,
            "`\\u` needs exactly four hex digits",
        )

        @Test
        fun `rejects invalid string escapes`() = assertCompilationFails(
            function("print(\"bad \\q\")"),
            VeraLexException.InvalidStringEscape::class,
            "invalid string escape `\\q`",
        )

        @Test
        fun `rejects newlines in strings`() = assertCompilationFails(
            "fn main() { print(\"a\nb\") }",
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
        )

        @Test
        fun `rejects unterminated strings`() = assertCompilationFails(
            "fn main() { print(\"abc",
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
        )
    }

    @Nested
    inner class Templates {
        @Test
        fun `string templates insert values`() = assertPrints(
            """
            class Point has Display {
                val x: Int
                val y: Int
                fn sum(): Int {
                    return x + y
                }
            }
            fn main() {
                val name = "Vera"
                val a = 2
                val b = 3
                val point = Point(x = 1, y = 2)
                print("Hello, \{name}!")
                print("\{a} + \{b} = \{a + b}")
                print("\{42}")
                print("ok: \{a < b}, unit: \{Unit}")
                print("point \{point} sums to \{point.sum()}")
            }
            """,
            "Hello, Vera!",
            "2 + 3 = 5",
            "42",
            "ok: True, unit: Unit",
            "point Point(x: 1, y: 2) sums to 3",
        )

        @Test
        fun `string template placeholders can hold nested strings, templates, and blocks`() = assertPrints(
            """
            fn shout(text: String): String {
                return "\{text}!"
            }
            fn main() {
                val ready = True
                print("\{shout("hi")} and \{"inner \{1 + 1}"}")
                print("status: \{if ready { "go" } else { "wait" }}")
            }
            """,
            "hi! and inner 2",
            "status: go",
        )

        @Test
        fun `placeholders can be adjacent or end the string`() = assertPrints(
            """
            fn main() {
                val a = 1
                val b = 2
                print("\{a}\{b}")
                print("\{a} items")
                print("total: \{a + b}")
            }
            """,
            "12",
            "1 items",
            "total: 3",
        )

        @Test
        fun `plain braces and escaped placeholders stay literal`() = assertPrints(
            """
            fn main() {
                val value = 7
                print("{\"key\": \{value}}")
                print("\\{value}")
            }
            """,
            "{\"key\": 7}",
            "\\{value}",
        )

        @Test
        fun `string template placeholders are evaluated left to right`() = assertPrints(
            """
            fn first(): Int {
                print("first")
                return 1
            }
            fn second(): Int {
                print("second")
                return 2
            }
            fn main() {
                val text = "\{first()}-\{second()}"
                print(text)
                text |> print()
            }
            """,
            "first",
            "second",
            "1-2",
            "1-2",
        )

        @Test
        fun `rejects line breaks inside template placeholders`() = assertCompilationFails(
            function("print(\"sum: \\{1 +", "    2}\")"),
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
            line = 2,
            column = 11,
        )

        @Test
        fun `rejects unclosed template placeholders`() = assertCompilationFails(
            function("print(\"value: \\{1\")"),
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
        )

        @Test
        fun `rejects empty template placeholders`() = assertCompilationFails(
            function("print(\"value: \\{}\")"),
            VeraParseException.EmptyTemplatePlaceholder::class,
            "empty template placeholder; write an expression inside `\\{...}`",
        )

        @Test
        fun `rejects template placeholders with two expressions`() = assertCompilationFails(
            function("val a = 1", "val b = 2", "print(\"\\{a b}\")"),
            VeraParseException.ExpectedToken::class,
            "expected `}` to close the template placeholder",
        )
    }
}
