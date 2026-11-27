package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.function
import vera.parser.VeraLexException
import vera.parser.VeraParseException

/** Lexical structure: tokens, newlines, line continuation, and comments. */
class LexicalTests {
    @Nested
    inner class Tokens {
        @Test
        fun `rejects keywords used as names`() = assertCompilationFails(
            function("val match = 1"),
            VeraParseException.ExpectedToken::class,
            "expected variable name, found `match`",
        )

        @Test
        fun `rejects keywords used as function names`() = assertCompilationFails(
            "fn has() {\n}",
            VeraParseException.ExpectedToken::class,
            "expected function name, found `has`",
        )

        @Test
        fun `rejects unexpected characters`() = assertCompilationFails(
            "fn main() { # }",
            VeraLexException.UnexpectedCharacter::class,
            "unexpected character `#`",
        )
    }

    @Nested
    inner class BlankLines {
        @Test
        fun `blank lines inside if blocks are ignored`() = assertPrints(
            """
            fn main() {
                if True {

                    print("then")

                } else {

                    print("else")

                }
            }
            """,
            "then",
        )

        @Test
        fun `blank lines before else are ignored`() = assertPrints(
            """
            fn main() {
                if False {
                    print("then")
                }


                else {
                    print("else")
                }
            }
            """,
            "else",
        )

        @Test
        fun `newline before else is ignored`() = assertPrints(
            """
            fn main() {
                if True {
                    print("then")
                }
                else {
                    print("else")
                }
            }
            """,
            "then",
        )

        @Test
        fun `newline before else if is ignored`() = assertPrints(
            """
            fn main() {
                if False {
                    print("if")
                }
                else if True {
                    print("else if")
                }
            }
            """,
            "else if",
        )

        @Test
        fun `blank lines between statements are ignored`() = assertPrints(
            """
            fn main() {
                print(1)


                print(2)
            }
            """,
            "1",
            "2",
        )

        @Test
        fun `blank lines at function block edges are ignored`() = assertPrints(
            """
            fn main() {

                print(1)

            }
            """,
            "1",
        )

        @Test
        fun `blank lines between function declarations are ignored`() = assertPrints(
            """
            fn main() {
                print1()

                print2()
            }

            fn print1() {
                print(1)
            }


            fn print2() {
                print(2)
            }
            """,
            "1",
            "2",
        )

        @Test
        fun `blank lines with crlf line endings are ignored`() = assertPrints(
            """
            fn main() {

                print(1)

            }
            """.trimIndent().replace("\n", "\r\n"),
            "1",
        )
    }

    @Nested
    inner class StatementEnds {
        @Test
        fun `blocks can be written on one line`() = assertPrints(
            """
            class Point { val x: Int }
            fn one(): Int { return 1 }
            fn main() {
                mut val counter = 0
                while counter < 3 { counter = counter + 1 }
                print(if counter == 3 { Point(5).x } else { 0 })
                print(one())
            }
            """,
            "5",
            "1",
        )

        @Test
        fun `while blocks allow blank lines`() = assertPrints(
            """
            fn main() {
                while True {

                    print("once")

                    return

                }
            }
            """,
            "once",
        )

        @Test
        fun `rejects semicolons`() = assertCompilationFails(
            function("print(1);"),
            VeraLexException.UnexpectedSemicolon::class,
            "Vera does not use semicolons; end the statement with a newline",
            line = 2,
            column = 13,
        )

        @Test
        fun `rejects missing statement terminators`() = assertCompilationFails(
            function("print(1) print(2)"),
            VeraParseException.ExpectedEndOfLine::class,
            "expected end of line",
        )
    }

    @Nested
    inner class LineContinuation {
        @Test
        fun `newlines inside parentheses continue the statement`() = assertPrints(
            """
            fn add(a: Int, b: Int): Int {
                return a + b
            }
            fn main() {
                print(add(
                    a = 1,
                    b = add(a = 2, b = 3)
                ))
                print((1
                    + 2))
            }
            """,
            "6",
            "3",
        )

        @Test
        fun `blocks inside parentheses keep newline-terminated statements`() = assertPrints(
            """
            fn main() {
                print(if True {
                    val value = 2
                    value * 3
                } else {
                    0
                })
            }
            """,
            "6",
        )

        @Test
        fun `a trailing operator continues the statement`() = assertPrints(
            """
            fn main() {
                val total =
                    1 +
                    2 *
                    3
                val both = True and

                    False
                print(total)
                print(both)
            }
            """,
            "7",
            "False",
        )

        @Test
        fun `a leading forward application or member access continues the statement`() = assertPrints(
            """
            class Box {
                val inner: Inner
            }
            class Inner {
                val value: Int
                fn doubled(): Int {
                    return value * 2
                }
            }
            fn increment(value: Int): Int {
                return value + 1
            }
            fn main() {
                val box = Box(Inner(20))
                box
                    .inner
                    .doubled()
                    |> increment()
                    |> print()
            }
            """,
            "41",
        )

        @Test
        fun `a leading minus starts a new statement`() = assertReturns(
            """
            fn main(): Int {
                val value = 1
                -2
                return value
            }
            """,
            1,
        )

        @Test
        fun `a leading not starts a new statement`() = assertReturns(
            """
            fn main(): Int {
                val value = 1
                not True
                return value
            }
            """,
            1,
        )

        @Test
        fun `a trailing not, dot, or arrow continues the statement`() = assertPrints(
            """
            class Box {
                val size: Int
            }
            trait Shape { Square, Round }
            fn main() {
                val flag = not
                    True
                print(flag)
                print(Box(3).
                    size)
                val shape: Shape = Shape.Square
                print(match shape {
                    Square ->
                        "square"
                    Round -> "round"
                })
            }
            """,
            "False",
            "3",
            "square",
        )
    }

    @Nested
    inner class Comments {
        @Test
        fun `line and doc comments are ignored`() = assertPrints(
            """
            // A leading comment.
            /// Documents the trait.
            trait Shape {
                /// Documents a trait method.
                fn area(): Int // trailing comment on a signature
            }
            /// Documents the class.
            class Square has Shape {
                val side: Int // trailing comment on a field

                /// Documents a method.
                fn area(): Int {
                    return side * side
                }
            }
            /// Documents the function.
            fn main() {
                // A comment-only line.
                print(Square(3).area()) // trailing comment
                print("not // a comment")
                print(10 / 2)
            }
            // A comment at the end of the file without a newline.
            """,
            "9",
            "not // a comment",
            "5",
        )

        @Test
        fun `comments do not change line continuation`() = assertPrints(
            """
            fn increment(value: Int): Int {
                return value + 1
            }
            fn main() {
                val total = 1 + // continues after a trailing operator
                    2
                print(total)
                total
                    // a comment between pipeline stages
                    |> increment()

                    // and after a blank line
                    |> print()
                print(4) // a trailing comment still ends the statement
            }
            """,
            "3",
            "4",
            "4",
        )

        @Test
        fun `comments work with crlf line endings`() = assertPrints(
            """
            fn main() {
                print(1) // first
                // second
                print(2)
            }
            """.trimIndent().replace("\n", "\r\n"),
            "1",
            "2",
        )
    }
}
