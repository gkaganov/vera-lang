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

/** Lexical structure: tokens, newlines, line continuation, and comments. */
class LexicalTests {
    @Nested
    inner class Tokens {
        @Test
        fun `unexpected characters`() = assertCompilationFails(
            "fn $TEST_MAIN() { @ }",
            VeraLexException.UnexpectedCharacter::class,
            "unexpected character '@'",
        )

        @Test
        fun `a single pipe is not forward application`() = assertCompilationFails(
            function("print(1 | 2)"),
            VeraLexException.UnexpectedCharacter::class,
            "unexpected character '|'",
        )
    }

    @Nested
    inner class Newlines {
        @Test
        fun `blank lines inside if blocks are ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if True {

                                print("then")

                            } else {

                                print("else")

                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "then$EOL", testInfo)
        }

        @Test
        fun `blank lines before else are ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("then")
                            }


                            else {
                                print("else")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "else$EOL", testInfo)
        }

        @Test
        fun `newline before else is ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if True {
                                print("then")
                            }
                            else {
                                print("else")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "then$EOL", testInfo)
        }

        @Test
        fun `newline before else if is ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            if False {
                                print("if")
                            }
                            else if True {
                                print("else if")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "else if$EOL", testInfo)
        }

        @Test
        fun `blank lines between statements are ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(1)


                            print(2)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "1${EOL}2${EOL}", testInfo)
        }

        @Test
        fun `blank lines at function block edges are ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {

                            print(1)

                        }
                    """.trimIndent()
            assertProgramPrints(source, "1$EOL", testInfo)
        }

        @Test
        fun `blank lines between function declarations are ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print1()

                            print2()
                        }

                        fn print1() {
                            print(1)
                        }


                        fn print2() {
                            print(2)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "1${EOL}2${EOL}", testInfo)
        }

        @Test
        fun `blank lines with crlf line endings are ignored`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {

                            print(1)

                        }
                    """.trimIndent().replace("\n", "\r\n")
            assertProgramPrints(source, "1$EOL", testInfo)
        }

        @Test
        fun `blocks can be written on one line`(testInfo: TestInfo) {
            val source = """
                        class Point { val x: Int }
                        fn one(): Int { return 1 }
                        fn $TEST_MAIN() {
                            mut val counter = 0
                            while counter < 3 { counter = counter + 1 }
                            print(if counter == 3 { Point(5).x } else { 0 })
                            print(one())
                        }
                    """.trimIndent()
            assertProgramPrints(source, "5${EOL}1$EOL", testInfo)
        }

        @Test
        fun `while blocks allow blank lines`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            while True {

                                print("once")

                                return

                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "once$EOL", testInfo)
        }

        @Test
        fun `semicolons`() = assertCompilationFails(
            function("print(1);"),
            VeraLexException.UnexpectedSemicolon::class,
            "Vera does not use semicolons; end the statement with a newline at line 2, column 13",
        )

        @Test
        fun `missing statement terminators`() = assertCompilationFails(
            function("print(1) print(2)"),
            VeraParseException.ExpectedEndOfLine::class,
            "expected end of line",
        )
    }

    @Nested
    inner class LineContinuation {
        @Test
        fun `newlines inside parentheses continue the statement`(testInfo: TestInfo) {
            val source = """
                        fn add(a: Int, b: Int): Int {
                            return a + b
                        }
                        fn $TEST_MAIN() {
                            print(add(
                                a = 1,
                                b = add(a = 2, b = 3)
                            ))
                            print((1
                                + 2))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "6${EOL}3$EOL", testInfo)
        }

        @Test
        fun `blocks inside parentheses keep newline-terminated statements`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(if True {
                                val value = 2
                                value * 3
                            } else {
                                0
                            })
                        }
                    """.trimIndent()
            assertProgramPrints(source, "6$EOL", testInfo)
        }

        @Test
        fun `a trailing operator continues the statement`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            val total =
                                1 +
                                2 *
                                3
                            val both = True and

                                False
                            print(total)
                            print(both)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "7${EOL}False$EOL", testInfo)
        }

        @Test
        fun `a leading forward application or member access continues the statement`(testInfo: TestInfo) {
            val source = """
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
                        fn $TEST_MAIN() {
                            val box = Box(Inner(20))
                            box
                                .inner
                                .doubled()
                                |> increment
                                |> print
                        }
                    """.trimIndent()
            assertProgramPrints(source, "41$EOL", testInfo)
        }

        @Test
        fun `a leading minus starts a new statement`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val value = 1
                            -2
                            return value
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }
    }

    @Nested
    inner class Comments {
        @Test
        fun `line and doc comments are ignored`(testInfo: TestInfo) {
            val source = """
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
                        fn $TEST_MAIN() {
                            // A comment-only line.
                            print(Square(3).area()) // trailing comment
                            print("not // a comment")
                            print(10 / 2)
                        }
                        // A comment at the end of the file without a newline.
                    """.trimIndent()
            assertProgramPrints(source, "9${EOL}not // a comment${EOL}5$EOL", testInfo)
        }

        @Test
        fun `comments do not change line continuation`(testInfo: TestInfo) {
            val source = """
                        fn increment(value: Int): Int {
                            return value + 1
                        }
                        fn $TEST_MAIN() {
                            val total = 1 + // continues after a trailing operator
                                2
                            print(total)
                            total
                                // a comment between pipeline stages
                                |> increment

                                // and after a blank line
                                |> print
                            print(4) // a trailing comment still ends the statement
                        }
                    """.trimIndent()
            assertProgramPrints(source, "3${EOL}4${EOL}4$EOL", testInfo)
        }

        @Test
        fun `comments work with crlf line endings`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(1) // first
                            // second
                            print(2)
                        }
                    """.trimIndent().replace("\n", "\r\n")
            assertProgramPrints(source, "1${EOL}2$EOL", testInfo)
        }
    }
}
