package vera.compiler.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import vera.compiler.VeraCompiler
import vera.shared.model.Identifier
import vera.shared.model.VeraException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes

class VeraCompilerTests {

    companion object {
        private val EOL: String = System.lineSeparator()
        private val CLASS_ID = AtomicLong()
        private const val TEST_MAIN = "testMain"
    }

    @Test
    fun `print function prints to system-out`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(65)
                    }
                """.trimIndent()
        assertProgramPrints(source, "65$EOL", testInfo)
    }

    @Test
    fun `print evaluates arithmetic arguments`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(1 + 2 * 3)
                    }
                """.trimIndent()
        assertProgramPrints(source, "7$EOL", testInfo)
    }

    @Test
    fun `functions can be called`(testInfo: TestInfo) {
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
    fun `val with explicit type declaration can be bound and accessed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val myVar1: Int = 50
                        val myVar2: Int = 51
                        return myVar1 + myVar2 + 52
                    }
                """.trimIndent()
        assertProgramReturns(source, 153, testInfo)
    }

    @Test
    fun `Int can be returned`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val numberResult: Int = getInt() + 0
                        return numberResult
                    }
                    fn getInt(): Int {
                        return 698
                    }
                """.trimIndent()
        assertProgramReturns(source, 698, testInfo)
    }

    @Test
    fun `one Int parameter can be passed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val numberResult: Int = receiveAndReturn(369)
                        return numberResult
                    }
                    fn receiveAndReturn(int: Int): Int {
                        return int
                    }
                """.trimIndent()
        assertProgramReturns(source, 369, testInfo)
    }

    @Test
    fun `multiple Int parameters can be passed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return add3Ints(int1 = 99, int2 = 98, int3 = 2 * 48 + 1)
                    }
                    fn add3Ints(int1: Int, int2: Int, int3: Int): Int {
                        return int1 + int2 + int3
                    }
                """.trimIndent()
        assertProgramReturns(source, 294, testInfo)
    }

    @Test
    fun `a String can be printed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print("Hello World!")
                    }
                """.trimIndent()
        assertProgramPrints(source, "Hello World!$EOL", testInfo)
    }

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
    fun `val types can be inferred when bound directly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        val inferredString = "I am a String"
                        val inferredInt = 1000
                        print(inferredString)
                        print(inferredInt)
                    }
                """.trimIndent()
        assertProgramPrints(source, "I am a String${EOL}1000${EOL}", testInfo)
    }

    @Test
    fun `Ints can be added`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val sum = 2 + 2
                        return sum
                    }
                """.trimIndent()
        assertProgramReturns(source, 4, testInfo)
    }

    @Test
    fun `Ints can be subtracted`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val difference = 100 - 37
                        return difference
                    }
                """.trimIndent()
        assertProgramReturns(source, 63, testInfo)
    }

    @Test
    fun `unary minus negates Ints`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(-1)
                        val value = 5
                        print(-value)
                        print(-2 * 3)
                        print(1 - -2)
                        print(--4)
                    }
                """.trimIndent()
        assertProgramPrints(source, "-1${EOL}-5${EOL}-6${EOL}3${EOL}4$EOL", testInfo)
    }

    @Test
    fun `Ints can be multiplied`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val product = 6 * 8
                        return product
                    }
                """.trimIndent()
        assertProgramReturns(source, 48, testInfo)
    }

    @Test
    fun `Ints can be divided if the result is an Int`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        val quotient = 16 / 4
                        return quotient
                    }
                """.trimIndent()
        assertProgramReturns(source, 4, testInfo)
    }

    @Test
    fun `Bools can be declared and bound to True and False`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        val trueValue = True
                        val falseValue = False
                        print(trueValue)
                        print(falseValue)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}", testInfo)
    }

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
    fun `sibling blocks can reuse local names`(testInfo: TestInfo) {
        val source = """
                    fn pick(flag: Bool): Int {
                        return if flag {
                            val value = 1
                            value
                        } else {
                            val value = 2
                            value
                        }
                    }
                    fn $TEST_MAIN() {
                        print(pick(True))
                        print(pick(False))
                        mut val round = 0
                        while round < 2 {
                            val doubled = round * 2
                            print(doubled)
                            round = round + 1
                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "1${EOL}2${EOL}0${EOL}2$EOL", testInfo)
    }

    @Test
    fun `returning from both if branches ends a function`(testInfo: TestInfo) {
        val source = """
                    fn sign(value: Int): Int {
                        if value > 0 {
                            return 1
                        } else if value < 0 {
                            return -1
                        } else {
                            return 0
                        }
                    }
                    fn $TEST_MAIN() {
                        print(sign(5))
                        print(sign(-5))
                        print(sign(0))
                    }
                """.trimIndent()
        assertProgramPrints(source, "1$EOL-1${EOL}0$EOL", testInfo)
    }

    @Test
    fun `functions and methods can have expression bodies`(testInfo: TestInfo) {
        val source = """
                    trait Shape {
                        fn area(): Int
                    }
                    class Square has Shape {
                        val side: Int
                        fn area(): Int = side * side
                    }
                    fn double(value: Int): Int = value * 2
                    fn greet(name: String): String = "Hello, \{name}!"
                    fn sign(value: Int): Int = if value > 0 { 1 } else if value < 0 { -1 } else { 0 }
                    fn total(first: Int, second: Int): Int =
                        first + second
                    fn log(text: String) = print(text)
                    fn $TEST_MAIN() {
                        print(Square(3).area())
                        print(4 |> double)
                        print(greet("Vera"))
                        print(sign(-7))
                        print(total(first = 2, second = 3))
                        log("done")
                    }
                """.trimIndent()
        assertProgramPrints(source, "9${EOL}8${EOL}Hello, Vera!${EOL}-1${EOL}5${EOL}done$EOL", testInfo)
    }

    @Test
    fun `mut val bindings can be rebound`(testInfo: TestInfo) {
        val source = """
                    class Point has Display {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN() {
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
                """.trimIndent()
        assertProgramPrints(source, "3${EOL}Point(x: 6, y: 0)$EOL", testInfo)
    }

    @Test
    fun `mut val without a value makes a parameter rebindable`(testInfo: TestInfo) {
        val source = """
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
                    fn $TEST_MAIN() {
                        countdown(3)
                        print(Counter(10).countUp(2))
                    }
                """.trimIndent()
        assertProgramPrints(source, "3${EOL}2${EOL}1${EOL}12$EOL", testInfo)
    }

    @Test
    fun `the smallest Int literal can be written`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(-2147483648)
                        print(-2147483648 + 1)
                        print(-(5))
                    }
                """.trimIndent()
        assertProgramPrints(source, "-2147483648$EOL-2147483647$EOL-5$EOL", testInfo)
    }

    @Test
    fun `variables can be rebound with equals`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        mut val value = 1
                        value = 42
                        return value
                    }
                """.trimIndent()
        assertProgramReturns(source, 42, testInfo)
    }

    @Test
    fun `rebinding can read the previous value`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        mut val value = 1
                        value = value + 2
                        return value
                    }
                """.trimIndent()
        assertProgramReturns(source, 3, testInfo)
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

    @Test
    fun `equals compares Ints correctly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(3 == 3)
                        print(4 == 5)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `not-equals compares Ints correctly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(3 != 52)
                        print(4 != 4)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `Bool equality compares values`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(True == True)
                        print(True == False)
                        print(True != False)
                        print(False != False)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False$EOL", testInfo)
    }

    @Test
    fun `String equality compares contents`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print("hello" == "hello")
                        print("hello" == "world")
                        print("hello" != "world")
                        print("hello" != "hello")
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False$EOL", testInfo)
    }

    @Test
    fun `Ints can be compared with less-than`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(3 < 5)
                        print(5 < 3)
                        print(3 < 3)
                        print(1 + 1 < 3)
                        print(3 < 1 + 1)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}False${EOL}True${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `Ints can be compared with greater-than`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(5 > 3)
                        print(3 > 5)
                        print(3 > 3)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `Ints can be compared with less-or-equal and greater-or-equal`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(3 <= 3)
                        print(4 <= 3)
                        print(3 >= 3)
                        print(3 >= 4)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `Ints support modulo`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return 17 % 5
                    }
                """.trimIndent()
        assertProgramReturns(source, 2, testInfo)
    }

    @Test
    fun `multiplication has precedence over addition`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return 2 + 3 * 4
                    }
                """.trimIndent()
        assertProgramReturns(source, 14, testInfo)
    }

    @Test
    fun `mixed addition and subtraction are left-associative`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return 8 - 2 + 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 7, testInfo)
    }

    @Test
    fun `mixed multiplication, division, and modulo are left-associative`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return 8 / 2 * 3 % 5
                    }
                """.trimIndent()
        assertProgramReturns(source, 2, testInfo)
    }

    @Test
    fun `additive expressions have precedence over equality`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(1 + 1 == 2)
                        print(2 == 1 + 1)
                        print(1 + 1 == 3 - 1)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}True${EOL}True$EOL", testInfo)
    }

    @Test
    fun `parentheses override arithmetic precedence`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return (1 + 2) * 3
                    }
                """.trimIndent()
        assertProgramReturns(source, 9, testInfo)
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
    fun `logical and evaluates booleans`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(True and True)
                        print(True and False)
                        print(False and True)
                        print(False and False)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}False${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `logical or evaluates booleans`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(True or True)
                        print(True or False)
                        print(False or True)
                        print(False or False)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}True${EOL}True${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `not negates booleans`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        val done = False
                        print(not True)
                        print(not done)
                        print(not not True)
                        print(not (1 < 2))
                    }
                """.trimIndent()
        assertProgramPrints(source, "False${EOL}True${EOL}True${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `not binds tighter than comparison and logical operators`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(not True == False)
                        print(not False and False)
                        print(not True or True)
                        print(True == not False)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}True${EOL}", testInfo)
    }

    @Test
    fun `not applies to whole member call chains`(testInfo: TestInfo) {
        val source = """
                    class Task {
                        val finished: Bool
                        fn isFinished(): Bool {
                            return finished
                        }
                    }
                    fn $TEST_MAIN() {
                        val task = Task(False)
                        if not task.isFinished() and not task.finished {
                            print("pending")
                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "pending$EOL", testInfo)
    }

    @Test
    fun `logical operators short-circuit`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(False and fail())
                        print(True or fail())
                    }
                    fn fail(): Bool {
                        print("should not run")
                        return True
                    }
                """.trimIndent()
        assertProgramPrints(source, "False${EOL}True${EOL}", testInfo)
    }

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
    fun `forward application works with builtins`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        2 |> print
                    }
                """.trimIndent()
        assertProgramPrints(source, "2$EOL", testInfo)
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

    @Test
    fun `named arguments work for functions, constructors, and methods`(testInfo: TestInfo) {
        val source = """
                    class Rect {
                        val width: Int
                        val height: Int
                        fn scaled(factor: Int, extra: Int): Int {
                            return width * height * factor + extra
                        }
                    }
                    fn area(width: Int, height: Int): Int {
                        return width * height
                    }
                    fn $TEST_MAIN() {
                        print(area(width = 3, height = 4))
                        val rect = Rect(width = 2, height = 5)
                        print(rect.scaled(factor = 2, extra = 1))
                        print(Rect(
                            width = 1,
                            height = 1
                        ).width)
                    }
                """.trimIndent()
        assertProgramPrints(source, "12${EOL}21${EOL}1$EOL", testInfo)
    }

    @Test
    fun `bare locals with matching names count as named arguments`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        val x = 3
                        val y = 4
                        val point = Point(x, y)
                        val mixed = Point(x, y = 10)
                        return point.x + point.y + mixed.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 17, testInfo)
    }

    @Test
    fun `only written arguments count when naming forwarded calls`(testInfo: TestInfo) {
        val source = """
                    fn add(left: Int, right: Int): Int {
                        return left + right
                    }
                    fn clamp(value: Int, low: Int, high: Int): Int {
                        return if value < low { low } else if value > high { high } else { value }
                    }
                    fn $TEST_MAIN() {
                        1 |> add(2) |> print
                        42 |> clamp(low = 0, high = 10) |> print
                    }
                """.trimIndent()
        assertProgramPrints(source, "3${EOL}10$EOL", testInfo)
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
    fun `objects can be constructed and fields read`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        val point = Point(x = 1, y = 2)
                        return point.x + point.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 3, testInfo)
    }

    @Test
    fun `class fields can hold objects and chained field access works`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    class Line {
                        val start: Point
                        val end: Point
                    }
                    fn $TEST_MAIN(): Int {
                        val line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                        return line.start.x + line.end.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 5, testInfo)
    }

    @Test
    fun `objects can be passed to and returned from functions`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    fn add(left: Point, right: Point): Point {
                        return Point(x = left.x + right.x, y = left.y + right.y)
                    }
                    fn $TEST_MAIN(): Int {
                        val result = add(left = Point(x = 1, y = 2), right = Point(x = 3, y = 4))
                        return result.x * 10 + result.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 46, testInfo)
    }

    @Test
    fun `class fields can hold String and Bool values`(testInfo: TestInfo) {
        val source = """
                    class Message {
                        val text: String
                        val active: Bool
                    }
                    fn $TEST_MAIN() {
                        val message = Message(text = "Hello", active = False)
                        print(message.text)
                        print(message.active)
                    }
                """.trimIndent()
        assertProgramPrints(source, "Hello${EOL}False${EOL}", testInfo)
    }

    @Test
    fun `field access can follow a constructor call`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        return Point(x = 4, y = 5).y
                    }
                """.trimIndent()
        assertProgramReturns(source, 5, testInfo)
    }

    @Test
    fun `class types work in local annotations and field access follows function calls`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    fn makePoint(): Point {
                        return Point(x = 7, y = 8)
                    }
                    fn $TEST_MAIN(): Int {
                        val point: Point = makePoint()
                        return point.x + makePoint().y
                    }
                """.trimIndent()
        assertProgramReturns(source, 15, testInfo)
    }

    @Test
    fun `class methods can read fields implicitly`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int

                        fn sum(): Int {
                            return x + y
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Point(x = 1, y = 2).sum()
                    }
                """.trimIndent()
        assertProgramReturns(source, 3, testInfo)
    }

    @Test
    fun `class methods accept parameters and return objects`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int

                        fn offset(dx: Int, dy: Int): Point {
                            return Point(x = x + dx, y = y + dy)
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        val point = Point(x = 1, y = 2).offset(dx = 3, dy = 4)
                        return point.x * 10 + point.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 46, testInfo)
    }

    @Test
    fun `class methods can call sibling methods`(testInfo: TestInfo) {
        val source = """
                    class Counter {
                        val value: Int

                        fn doubled(): Int {
                            return value * 2
                        }

                        fn quadrupled(): Int {
                            return doubled() * 2
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Counter(3).quadrupled()
                    }
                """.trimIndent()
        assertProgramReturns(source, 12, testInfo)
    }

    @Test
    fun `class methods can call top-level functions and print`(testInfo: TestInfo) {
        val source = """
                    fn increment(value: Int): Int {
                        return value + 1
                    }
                    class Counter {
                        val value: Int

                        fn printNext() {
                            print(increment(value))
                        }
                    }
                    fn $TEST_MAIN() {
                        Counter(41).printNext()
                    }
                """.trimIndent()
        assertProgramPrints(source, "42$EOL", testInfo)
    }

    @Test
    fun `class methods can be called on nested object values`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int

                        fn sum(): Int {
                            return x + y
                        }
                    }
                    class Line {
                        val start: Point
                        val end: Point
                    }
                    fn $TEST_MAIN(): Int {
                        val line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                        return line.start.sum() + line.end.sum()
                    }
                """.trimIndent()
        assertProgramReturns(source, 10, testInfo)
    }

    @Test
    fun `class methods can call methods on field values`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int

                        fn value(): Int {
                            return x
                        }
                    }
                    class Box {
                        val point: Point

                        fn value(): Int {
                            return point.value()
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Box(Point(9)).value()
                    }
                """.trimIndent()
        assertProgramReturns(source, 9, testInfo)
    }

    @Test
    fun `class methods can be called on constructor results`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int

                        fn withX(newX: Int): Point {
                            return Point(x = newX, y = y)
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Point(x = 1, y = 2).withX(7).x
                    }
                """.trimIndent()
        assertProgramReturns(source, 7, testInfo)
    }

    @Test
    fun `class methods can use if expressions`(testInfo: TestInfo) {
        val source = """
                    class Number {
                        val value: Int

                        fn sign(): Int {
                            return if value < 0 {
                                0
                            } else {
                                1
                            }
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Number(5).sign()
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `class methods support string parameters and returns`(testInfo: TestInfo) {
        val source = """
                    class Label {
                        val text: String

                        fn value(): String {
                            return text
                        }

                        fn echo(message: String): String {
                            return message
                        }
                    }
                    fn $TEST_MAIN() {
                        print(Label("hello").value())
                        print(Label("unused").echo("world"))
                    }
                """.trimIndent()
        assertProgramPrints(source, "hello${EOL}world${EOL}", testInfo)
    }

    @Test
    fun `class methods support bool parameters and returns`(testInfo: TestInfo) {
        val source = """
                    class Flag {
                        val value: Bool

                        fn select(useValue: Bool): Bool {
                            return if useValue {
                                value
                            } else {
                                False
                            }
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return if Flag(True).select(True) and Flag(False).select(True) {
                            0
                        } else {
                            1
                        }
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `class methods on field values accept arguments`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int

                        fn offset(dx: Int, dy: Int): Point {
                            return Point(x = x + dx, y = y + dy)
                        }
                    }
                    class Box {
                        val point: Point

                        fn shifted(dx: Int, dy: Int): Int {
                            val moved = point.offset(dx, dy)
                            return moved.x * 10 + moved.y
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Box(Point(x = 1, y = 2)).shifted(dx = 3, dy = 4)
                    }
                """.trimIndent()
        assertProgramReturns(source, 46, testInfo)
    }

    @Test
    fun `class methods accept object parameters`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int

                        fn total(other: Point): Int {
                            return x + y + other.x + other.y
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        return Point(x = 1, y = 2).total(Point(x = 3, y = 4))
                    }
                """.trimIndent()
        assertProgramReturns(source, 10, testInfo)
    }

    @Test
    fun `Unit values can be stored in objects and read back`(testInfo: TestInfo) {
        val source = """
                    class Signal {
                        val flag: Unit
                    }
                    fn $TEST_MAIN(): Int {
                        val signal = Signal(Unit)
                        val flag: Unit = signal.flag
                        return 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `Unit values can be passed and returned`(testInfo: TestInfo) {
        val source = """
                    fn identity(unit: Unit) {
                        return unit
                    }
                    fn $TEST_MAIN(): Int {
                        val unit = identity(Unit)
                        return 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `Unit can be returned explicitly`(testInfo: TestInfo) {
        val source = """
                    fn explicitUnit() {
                        return Unit
                    }
                    fn $TEST_MAIN(): Int {
                        val unit = explicitUnit()
                        return 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
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
    fun `trait methods must be implemented and can be called`(testInfo: TestInfo) {
        val source = """
                    trait Shape {
                        fn area(): Int
                    }
                    class Square has Shape {
                        val side: Int
                        fn area(): Int {
                            return side * side
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        val square = Square(3)
                        return square.area()
                    }
                """.trimIndent()
        assertProgramReturns(source, 9, testInfo)
    }

    @Test
    fun `traits can be declared on one line`(testInfo: TestInfo) {
        val source = """
                    trait Shape { fn area(): Int }
                    trait Marker {}
                    class Square has Shape, Marker {
                        val side: Int
                        fn area(): Int {
                            return side * side
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        val square = Square(4)
                        return square.area()
                    }
                """.trimIndent()
        assertProgramReturns(source, 16, testInfo)
    }

    @Test
    fun `trait-typed parameters call the implementing class's method`(testInfo: TestInfo) {
        val source = """
                    trait Shape {
                        fn area(): Int
                    }
                    class Square has Shape {
                        val side: Int
                        fn area(): Int = side * side
                    }
                    class Rect has Shape {
                        val width: Int
                        val height: Int
                        fn area(): Int = width * height
                    }
                    fn describe(shape: Shape): Int = shape.area()
                    fn $TEST_MAIN() {
                        print(describe(Square(3)))
                        print(describe(Rect(width = 2, height = 5)))
                    }
                """.trimIndent()
        assertProgramPrints(source, "9${EOL}10$EOL", testInfo)
    }

    @Test
    fun `trait types work for locals, rebinding, returns, and fields`(testInfo: TestInfo) {
        val source = """
                    trait Shape {
                        fn area(): Int
                    }
                    class Square has Shape {
                        val side: Int
                        fn area(): Int = side * side
                    }
                    class Rect has Shape {
                        val width: Int
                        val height: Int
                        fn area(): Int = width * height
                    }
                    class Holder {
                        val shape: Shape
                    }
                    fn pick(big: Bool): Shape {
                        if big {
                            return Square(10)
                        }
                        return Rect(width = 1, height = 2)
                    }
                    fn $TEST_MAIN() {
                        mut val current: Shape = Square(2)
                        print(current.area())
                        current = Rect(width = 1, height = 3)
                        print(current.area())
                        print(pick(True).area())
                        print(pick(False).area())
                        print(Holder(Square(4)).shape.area())
                    }
                """.trimIndent()
        assertProgramPrints(source, "4${EOL}3${EOL}100${EOL}2${EOL}16$EOL", testInfo)
    }

    @Test
    fun `trait method calls use named arguments and forward application`(testInfo: TestInfo) {
        val source = """
                    trait Scaler {
                        fn scale(value: Int, offset: Int): Int
                    }
                    class Doubler has Scaler {
                        fn scale(value: Int, offset: Int): Int = value * 2 + offset
                    }
                    fn apply(scaler: Scaler): Int = scaler.scale(value = 5, offset = 1)
                    fn $TEST_MAIN() {
                        print(apply(Doubler))
                        val scaler: Scaler = Doubler
                        3 |> scaler.scale(10) |> print
                    }
                """.trimIndent()
        assertProgramPrints(source, "11${EOL}16$EOL", testInfo)
    }

    @Test
    fun `traits compile to JVM interfaces implemented by their classes`() {
        val source = """
                    trait Shape {
                        fn area(): Int
                    }
                    class Square has Shape, Eq {
                        val side: Int
                        fn area(): Int = side * side
                    }
                    fn $TEST_MAIN() {
                    }
                """.trimIndent()
        val bytecode = VeraCompiler.compile(Identifier("TraitInterfaceTest"), source)
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val shape = classLoader.loadClass("Shape")
        val square = classLoader.loadClass("Square")
        Assertions.assertTrue(shape.isInterface)
        Assertions.assertEquals(listOf(shape), square.interfaces.toList())
        Assertions.assertEquals(listOf("area"), shape.declaredMethods.map { it.name })
    }

    @Test
    fun `match selects the arm for the object's class and binds its fields`(testInfo: TestInfo) {
        val source = """
                    trait Shape {}
                    class Circle has Shape {
                        val radius: Int
                    }
                    class Rect has Shape {
                        val width: Int
                        val height: Int
                    }
                    class Empty has Shape {}
                    fn area(shape: Shape): Int = match shape {
                        Circle(radius) -> 3 * radius * radius
                        Rect(width = w, height) -> w * height
                        Empty -> 0
                    }
                    fn $TEST_MAIN() {
                        print(area(Circle(2)))
                        print(area(Rect(width = 2, height = 3)))
                        print(area(Empty))
                    }
                """.trimIndent()
        assertProgramPrints(source, "12${EOL}6${EOL}0$EOL", testInfo)
    }

    @Test
    fun `match arms can be blocks, statements, and return from the function`(testInfo: TestInfo) {
        val source = """
                    trait Event {}
                    class Click has Event {
                        val x: Int
                    }
                    class Quit has Event {}
                    fn describe(event: Event): String {
                        match event {
                            Click(x) -> {
                                val doubled = x * 2
                                return "click \{doubled}"
                            }
                            Quit -> return "quit"
                        }
                    }
                    fn $TEST_MAIN() {
                        print(describe(Click(4)))
                        print(describe(Quit))
                    }
                """.trimIndent()
        assertProgramPrints(source, "click 8${EOL}quit$EOL", testInfo)
    }

    @Test
    fun `branches of different classes unify to their shared trait`(testInfo: TestInfo) {
        val source = """
                    trait Shape {
                        fn size(): Int
                    }
                    class Circle has Shape {
                        val radius: Int
                        fn size(): Int = radius
                    }
                    class Empty has Shape {
                        fn size(): Int = 0
                    }
                    fn make(big: Bool): Shape = if big { Circle(5) } else { Empty }
                    fn grow(shape: Shape): Shape = match shape {
                        Circle(radius) -> Circle(radius + 1)
                        Empty -> Circle(1)
                    }
                    fn $TEST_MAIN() {
                        print(make(True).size())
                        print(make(False).size())
                        print(grow(make(True)).size())
                        print(grow(make(False)).size())
                    }
                """.trimIndent()
        assertProgramPrints(source, "5${EOL}0${EOL}6${EOL}1$EOL", testInfo)
    }

    @Test
    fun `match destructures a single class and evaluates its scrutinee once`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        val x: Int
                        val y: Int
                    }
                    fn origin(): Point {
                        print("origin")
                        return Point(x = 3, y = 4)
                    }
                    fn $TEST_MAIN() {
                        val sum = match origin() {
                            Point(x, y) -> x + y
                        }
                        print(sum)
                        mut val count = 0
                        while count < 3 {
                            count = count + 1
                            match Point(x = count, y = 0) {
                                Point(x) -> if x == 2 { break }
                            }
                        }
                        print(count)
                    }
                """.trimIndent()
        assertProgramPrints(source, "origin${EOL}7${EOL}2$EOL", testInfo)
    }

    @Test
    fun `traits compile to sealed JVM interfaces`() {
        val source = """
                    trait Shape {}
                    class Circle has Shape {
                        val radius: Int
                    }
                    class Empty has Shape {}
                    fn $TEST_MAIN() {
                    }
                """.trimIndent()
        val bytecode = VeraCompiler.compile(Identifier("SealedInterfaceTest"), source)
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val shape = classLoader.loadClass("Shape")
        Assertions.assertTrue(shape.isSealed)
        Assertions.assertEquals(setOf("Circle", "Empty"), shape.permittedSubclasses.map { it.name }.toSet())
        Assertions.assertTrue(shape.isAssignableFrom(classLoader.loadClass("Circle")))
    }

    @Test
    fun `classes without fields are singletons written by name`(testInfo: TestInfo) {
        val source = """
                    class Empty has Display, Eq {}
                    class Geometry {
                        fn square(x: Int): Int = x * x
                    }
                    trait Shape {
                        fn area(): Int
                    }
                    class Nothing has Shape {
                        fn area(): Int = 0
                    }
                    fn describe(shape: Shape): Int = shape.area()
                    fn $TEST_MAIN() {
                        print(Empty)
                        print(Empty == Empty)
                        print("empty: \{Empty}")
                        print(Geometry.square(3))
                        print(describe(Nothing))
                        val geometry = Geometry
                        print(4 |> geometry.square())
                    }
                """.trimIndent()
        assertProgramPrints(source, "Empty${EOL}True${EOL}empty: Empty${EOL}9${EOL}0${EOL}16$EOL", testInfo)
    }

    @Test
    fun `singletons have one instance and a private constructor`() {
        val source = """
                    class Empty {}
                    fn first(): Empty = Empty
                    fn $TEST_MAIN() {
                    }
                """.trimIndent()
        val className = Identifier("SingletonTest")
        val bytecode = VeraCompiler.compile(className, source)
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val empty = classLoader.loadClass("Empty")
        val first = classLoader.loadClass(className.value).getMethod("first")
        Assertions.assertSame(first.invoke(null), first.invoke(null))
        Assertions.assertSame(empty.getField("INSTANCE").get(null), first.invoke(null))
        Assertions.assertTrue(empty.declaredConstructors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
    }

    @Test
    fun `trait blocks declare constant cases that match unqualified`(testInfo: TestInfo) {
        val source = """
                    trait Color { Red, Green, Blue }
                    fn name(color: Color): String = match color {
                        Red -> "red"
                        Green -> "green"
                        Blue -> "blue"
                    }
                    fn $TEST_MAIN() {
                        print(name(Color.Green))
                        val color: Color = Color.Red
                        print(name(color))
                    }
                """.trimIndent()
        assertProgramPrints(source, "green${EOL}red$EOL", testInfo)
    }

    @Test
    fun `trait cases can have fields, methods, and traits`(testInfo: TestInfo) {
        val source = """
                    trait Shape {
                        fn area(): Int
                        Circle has Display {
                            val radius: Int
                            fn area(): Int = 3 * radius * radius
                        }
                        Empty has Display {
                            fn area(): Int = 0
                        }
                    }
                    fn radiusOf(shape: Shape): Int = match shape {
                        Circle(radius) -> radius
                        Empty -> 0
                    }
                    fn $TEST_MAIN() {
                        val circle: Shape.Circle = Shape.Circle(2)
                        print(circle)
                        print(circle.area())
                        print(Shape.Empty)
                        print(radiusOf(circle))
                        val shape: Shape = Shape.Empty
                        print(shape.area())
                        print(3 |> Shape.Circle() |> radiusOf)
                    }
                """.trimIndent()
        assertProgramPrints(
            source,
            "Shape.Circle(radius: 2)${EOL}12${EOL}Shape.Empty${EOL}2${EOL}0${EOL}3$EOL",
            testInfo,
        )
    }

    @Test
    fun `owned cases can join other traits and match qualified there`(testInfo: TestInfo) {
        val source = """
                    trait Printable {
                        fn label(): String
                    }
                    trait Color {
                        Red has Printable {
                            fn label(): String = "red"
                        }
                        Green
                    }
                    class Invoice has Printable {
                        val total: Int
                        fn label(): String = "invoice \{total}"
                    }
                    fn kind(item: Printable): String = match item {
                        Color.Red -> "a color"
                        Invoice(total) -> "an invoice of \{total}"
                    }
                    fn $TEST_MAIN() {
                        print(kind(Color.Red))
                        print(kind(Invoice(7)))
                        val item: Printable = Color.Red
                        print(item.label())
                    }
                """.trimIndent()
        assertProgramPrints(source, "a color${EOL}an invoice of 7${EOL}red$EOL", testInfo)
    }

    @Test
    fun `recursive trait cases coexist with a top-level class of the same name`(testInfo: TestInfo) {
        val source = """
                    class Leaf {
                        val color: String
                    }
                    trait Tree {
                        Leaf
                        Node {
                            val left: Tree
                            val right: Tree
                        }
                    }
                    fn count(tree: Tree): Int = match tree {
                        Leaf -> 1
                        Node(left, right) -> count(left) + count(right)
                    }
                    fn $TEST_MAIN() {
                        val tree = Tree.Node(left = Tree.Leaf, right = Tree.Node(left = Tree.Leaf, right = Tree.Leaf))
                        print(count(tree))
                        print(Leaf("green").color)
                    }
                """.trimIndent()
        assertProgramPrints(source, "3${EOL}green$EOL", testInfo)
    }

    @Test
    fun `trait cases compile to nested JVM classes`() {
        val source = """
                    trait Shape {
                        Circle {
                            val radius: Int
                        }
                        Empty
                    }
                    fn $TEST_MAIN() {
                    }
                """.trimIndent()
        val bytecode = VeraCompiler.compile(Identifier("NestedCaseTest"), source)
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val shape = classLoader.loadClass("Shape")
        val circle = classLoader.loadClass("Shape\$Circle")
        Assertions.assertEquals("Circle", circle.simpleName)
        Assertions.assertEquals(shape, circle.declaringClass)
        Assertions.assertTrue(shape.isSealed)
        Assertions.assertEquals(setOf("Shape\$Circle", "Shape\$Empty"), shape.permittedSubclasses.map { it.name }.toSet())
    }

    @Test
    fun `trait methods with omitted return types match Unit methods`(testInfo: TestInfo) {
        val source = """
                    trait Greeter {
                        fn greet()
                    }
                    class Host has Greeter {
                        val name: String
                        fn greet() {
                            print(name)
                        }
                    }
                    fn $TEST_MAIN() {
                        val host = Host("vera")
                        host.greet()
                    }
                """.trimIndent()
        assertProgramPrints(source, "vera$EOL", testInfo)
    }

    @Test
    fun `trait methods with parameters are implemented with the same names`(testInfo: TestInfo) {
        val source = """
                    trait Scaled {
                        fn scale(by: Int): Int
                    }
                    class Counter has Scaled {
                        val value: Int
                        fn scale(by: Int): Int {
                            return value * by
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        val counter = Counter(2)
                        return counter.scale(3)
                    }
                """.trimIndent()
        assertProgramReturns(source, 6, testInfo)
    }

    @Test
    fun `classes can implement multiple traits`(testInfo: TestInfo) {
        val source = """
                    trait Named {
                        fn name(): String
                    }
                    trait Valued {
                        fn value(): Int
                    }
                    class Entry has Named, Valued {
                        val label: String
                        val amount: Int
                        fn name(): String {
                            return label
                        }
                        fn value(): Int {
                            return amount
                        }
                    }
                    fn $TEST_MAIN() {
                        val entry = Entry(label = "item", amount = 7)
                        print(entry.name())
                        print(entry.value())
                    }
                """.trimIndent()
        assertProgramPrints(source, "item${EOL}7$EOL", testInfo)
    }

    @Test
    fun `objects with Eq compare structurally`(testInfo: TestInfo) {
        val source = """
                    class Point has Eq {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN() {
                        print(Point(x = 1, y = 2) == Point(x = 1, y = 2))
                        print(Point(x = 1, y = 2) == Point(x = 1, y = 3))
                        print(Point(x = 1, y = 2) != Point(x = 1, y = 3))
                        print(Point(x = 1, y = 2) != Point(x = 1, y = 2))
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False$EOL", testInfo)
    }

    @Test
    fun `nested objects with Eq compare structurally`(testInfo: TestInfo) {
        val source = """
                    class Point has Eq {
                        val x: Int
                        val y: Int
                    }
                    class Line has Eq {
                        val start: Point
                        val end: Point
                    }
                    fn $TEST_MAIN() {
                        print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) == Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)))
                        print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) == Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 5)))
                        print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) != Line(start = Point(x = 0, y = 2), end = Point(x = 3, y = 4)))
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True$EOL", testInfo)
    }

    @Test
    fun `Eq compares String and Bool fields by value`(testInfo: TestInfo) {
        val source = """
                    class Label has Eq {
                        val text: String
                        val active: Bool
                    }
                    fn $TEST_MAIN() {
                        print(Label(text = "same", active = True) == Label(text = "same", active = True))
                        print(Label(text = "same", active = True) == Label(text = "other", active = True))
                        print(Label(text = "same", active = True) != Label(text = "same", active = False))
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False${EOL}True$EOL", testInfo)
    }

    @Test
    fun `objects with Eq and no fields are equal`(testInfo: TestInfo) {
        val source = """
                    class Empty has Eq {
                    }
                    fn $TEST_MAIN() {
                        print(Empty == Empty)
                        print(Empty != Empty)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False$EOL", testInfo)
    }

    @Test
    fun `Unit equality always holds`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(Unit == Unit)
                        print(Unit != Unit)
                    }
                """.trimIndent()
        assertProgramPrints(source, "True${EOL}False$EOL", testInfo)
    }

    @Test
    fun `Unit equality evaluates both operands in order`(testInfo: TestInfo) {
        val source = """
                    fn first() {
                        print("first")
                    }
                    fn second() {
                        print("second")
                    }
                    fn $TEST_MAIN() {
                        print(first() == second())
                        print(first() != second())
                    }
                """.trimIndent()
        assertProgramPrints(
            source,
            "first${EOL}second${EOL}True${EOL}first${EOL}second${EOL}False$EOL",
            testInfo,
        )
    }

    @Test
    fun `Display prints objects with their fields`(testInfo: TestInfo) {
        val source = """
                    class Point has Display {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN() {
                        print(Point(x = 1, y = 2))
                    }
                """.trimIndent()
        assertProgramPrints(source, "Point(x: 1, y: 2)$EOL", testInfo)
    }

    @Test
    fun `Display objects can be forwarded to print`(testInfo: TestInfo) {
        val source = """
                    class Point has Display {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN() {
                        Point(x = 1, y = 2) |> print
                        Point(x = 3, y = 4) |> print()
                    }
                """.trimIndent()
        assertProgramPrints(source, "Point(x: 1, y: 2)${EOL}Point(x: 3, y: 4)$EOL", testInfo)
    }

    @Test
    fun `Display prints nested objects recursively`(testInfo: TestInfo) {
        val source = """
                    class Point has Display {
                        val x: Int
                        val y: Int
                    }
                    class Line has Display {
                        val start: Point
                        val end: Point
                    }
                    fn $TEST_MAIN() {
                        print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)))
                    }
                """.trimIndent()
        assertProgramPrints(
            source,
            "Line(start: Point(x: 1, y: 2), end: Point(x: 3, y: 4))$EOL",
            testInfo,
        )
    }

    @Test
    fun `Display prints String Bool and Unit fields`(testInfo: TestInfo) {
        val source = """
                    class Message has Display {
                        val text: String
                        val active: Bool
                    }
                    class Signal has Display {
                        val flag: Unit
                    }
                    fn $TEST_MAIN() {
                        print(Message(text = "hello", active = True))
                        print(Signal(Unit))
                    }
                """.trimIndent()
        assertProgramPrints(source, "Message(text: hello, active: True)${EOL}Signal(flag: Unit)$EOL", testInfo)
    }

    @Test
    fun `Unit can be printed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(Unit)
                    }
                """.trimIndent()
        assertProgramPrints(source, "Unit$EOL", testInfo)
    }

    @Test
    fun `Unit-returning calls can be printed`(testInfo: TestInfo) {
        val source = """
                    fn doNothing() {
                    }
                    fn $TEST_MAIN() {
                        print(doNothing())
                    }
                """.trimIndent()
        assertProgramPrints(source, "Unit$EOL", testInfo)
    }

    @Test
    fun `object equality works on variables`(testInfo: TestInfo) {
        val source = """
                    class Point has Eq {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        val first = Point(x = 1, y = 2)
                        val second = Point(x = 1, y = 2)
                        if first == second {
                            return 1
                        }
                        return 0
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `Eq and Display can be combined`(testInfo: TestInfo) {
        val source = """
                    class Point has Eq, Display {
                        val x: Int
                        val y: Int
                    }
                    fn $TEST_MAIN() {
                        print(Point(x = 1, y = 2))
                        print(Point(x = 1, y = 2) == Point(x = 1, y = 2))
                        print(Point(x = 1, y = 2) != Point(x = 2, y = 2))
                    }
                """.trimIndent()
        assertProgramPrints(source, "Point(x: 1, y: 2)${EOL}True${EOL}True$EOL", testInfo)
    }

    @Test
    fun `zero-parameter Unit main gets a void JVM entry point`() {
        val source = """
                    fn main() {
                        print("entry")
                    }
                """.trimIndent()
        assertJvmMainPrints(source, "entry$EOL")
    }

    @Test
    fun `zero-parameter value-returning main gets a void JVM entry point`() {
        val source = """
                    fn main(): Int {
                        print("entry")
                        return 1
                    }
                """.trimIndent()
        assertJvmMainPrints(source, "entry$EOL")
    }

    private fun assertJvmMainPrints(source: String, expected: String) {
        val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
        val bytecode = compileOrFail(className, source)
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val entryPoint = classLoader.loadClass(className.value)
            .getMethod("main", Array<String>::class.java)

        val oldOut = System.out
        val baos = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(baos))
            val arguments: Any = emptyArray<String>()
            entryPoint.invoke(null, arguments)
        } finally {
            System.setOut(oldOut)
        }

        Assertions.assertEquals(expected, baos.toString())
    }

    private fun assertProgramReturns(
        source: String,
        expected: Any,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
        val bytecode = compileOrFail(className, source)
        val mainBytecode = bytecode[className] ?: throw AssertionError("no bytecode emitted for ${className.value}")
        if (compileToOut) Path("out", testInfo.testMethod.get().name, "${className.value}.class").createParentDirectories().writeBytes(mainBytecode)
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val testMethod = classLoader.loadClass(className.value).getMethod(TEST_MAIN)
        val actual = testMethod.invoke(null)
        Assertions.assertEquals(expected, actual)
    }

    private fun assertProgramPrints(
        source: String,
        expected: String,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
        val bytecode = compileOrFail(className, source)
        val mainBytecode = bytecode[className] ?: throw AssertionError("no bytecode emitted for ${className.value}")
        if (compileToOut) Path("out", testInfo.testMethod.get().name, "${className.value}.class").createParentDirectories().writeBytes(mainBytecode)
        val oldOut = System.out
        val baos = ByteArrayOutputStream()
        val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
        val testMethod = classLoader.loadClass(className.value).getMethod(TEST_MAIN)
        try {
            System.setOut(PrintStream(baos))
            testMethod.invoke(null)
        } finally {
            System.setOut(oldOut)
        }
        Assertions.assertEquals(expected, baos.toString())
    }

    private fun compileOrFail(className: Identifier, source: String): Map<Identifier, ByteArray> {
        return try {
            VeraCompiler.compile(className, source)
        } catch (exception: VeraException) {
            throw AssertionError("compilation failed: ${exception.render()}")
        }
    }
}
