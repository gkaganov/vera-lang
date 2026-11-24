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

/** String literals and string templates. */
class StringTests {
    @Nested
    inner class Literals {
        @Test
        fun `strings support punctuation unicode and escapes`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print("Hello, world!")
                            print("punctuation: (yes) [ok] {braces} #%&*+-=;:'/@~`")
                            print("quote: \" backslash: \\")
                            print("line\nbreak")
                            print("tab\tend")
                            print("unicode: é 日本 😀")
                        }
                    """.trimIndent()
            val expected = "Hello, world!$EOL" +
                "punctuation: (yes) [ok] {braces} #%&*+-=;:'/@~`$EOL" +
                "quote: \" backslash: \\$EOL" +
                "line\nbreak$EOL" +
                "tab\tend$EOL" +
                "unicode: é 日本 😀$EOL"
            assertProgramPrints(source, expected, testInfo)
        }

        @Test
        fun `invalid string escapes`() = assertCompilationFails(
            function("print(\"bad \\q\")"),
            VeraLexException.InvalidStringEscape::class,
            "invalid string escape '\\q'",
        )

        @Test
        fun `newlines in strings`() = assertCompilationFails(
            "fn $TEST_MAIN() { print(\"a\nb\") }",
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
        )

        @Test
        fun `unterminated strings`() = assertCompilationFails(
            "fn $TEST_MAIN() { print(\"abc",
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
        )
    }

    @Nested
    inner class Templates {
        @Test
        fun `string templates insert values`(testInfo: TestInfo) {
            val source = """
                        class Point has Display {
                            val x: Int
                            val y: Int
                            fn sum(): Int {
                                return x + y
                            }
                        }
                        fn $TEST_MAIN() {
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
                    """.trimIndent()
            assertProgramPrints(
                source,
                "Hello, Vera!${EOL}2 + 3 = 5${EOL}42${EOL}ok: True, unit: Unit${EOL}point Point(x: 1, y: 2) sums to 3$EOL",
                testInfo,
            )
        }

        @Test
        fun `string template placeholders can hold nested strings, templates, and blocks`(testInfo: TestInfo) {
            val source = """
                        fn shout(text: String): String {
                            return "\{text}!"
                        }
                        fn $TEST_MAIN() {
                            val ready = True
                            print("\{shout("hi")} and \{"inner \{1 + 1}"}")
                            print("status: \{if ready { "go" } else { "wait" }}")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "hi! and inner 2${EOL}status: go$EOL", testInfo)
        }

        @Test
        fun `plain braces and escaped placeholders stay literal`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            val value = 7
                            print("{\"key\": \{value}}")
                            print("\\{value}")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "{\"key\": 7}${EOL}\\{value}$EOL", testInfo)
        }

        @Test
        fun `string template placeholders are evaluated left to right`(testInfo: TestInfo) {
            val source = """
                        fn first(): Int {
                            print("first")
                            return 1
                        }
                        fn second(): Int {
                            print("second")
                            return 2
                        }
                        fn $TEST_MAIN() {
                            val text = "\{first()}-\{second()}"
                            print(text)
                            text |> print
                        }
                    """.trimIndent()
            assertProgramPrints(source, "first${EOL}second${EOL}1-2${EOL}1-2$EOL", testInfo)
        }

        @Test
        fun `line breaks inside template placeholders`() = assertCompilationFails(
            function("print(\"sum: \\{1 +", "    2}\")"),
            VeraLexException.UnterminatedString::class,
            "unterminated string literal at line 2, column 11",
        )

        @Test
        fun `unclosed template placeholders`() = assertCompilationFails(
            function("print(\"value: \\{1\")"),
            VeraLexException.UnterminatedString::class,
            "unterminated string literal",
        )

        @Test
        fun `empty template placeholders`() = assertCompilationFails(
            function("print(\"value: \\{}\")"),
            VeraParseException.ExpectedExpression::class,
            "expected an expression",
        )

        @Test
        fun `template placeholders with two expressions`() = assertCompilationFails(
            function("val a = 1", "val b = 2", "print(\"\\{a b}\")"),
            VeraParseException.ExpectedToken::class,
            "expected `}` to close the template placeholder",
        )
    }
}
