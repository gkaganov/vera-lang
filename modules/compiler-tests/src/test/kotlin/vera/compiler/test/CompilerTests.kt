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
    fun `var with explicit type declaration can be bound and accessed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var myVar1: Int = 50
                        var myVar2: Int = 51
                        return myVar1 + myVar2 + 52
                    }
                """.trimIndent()
        assertProgramReturns(source, 153, testInfo)
    }

    @Test
    fun `Int can be returned`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var numberResult: Int = getInt() + 0
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
                        var numberResult: Int = receiveAndReturn(369)
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
                        x: Int
                        y: Int
                        fn sum(): Int {
                            return x + y
                        }
                    }
                    fn $TEST_MAIN() {
                        var name = "Vera"
                        var a = 2
                        var b = 3
                        var point = Point(x = 1, y = 2)
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
                        var ready = True
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
                        var value = 7
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
                        var text = "\{first()}-\{second()}"
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
    fun `var types can be inferred when bound directly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        var inferredString = "I am a String"
                        var inferredInt = 1000
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
                        var value = 5
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
                            var value = 40
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
                        var value = if False {
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
                            var value = 2
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
                        var total =
                            1 +
                            2 *
                            3
                        var both = True and

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
                        inner: Inner
                    }
                    class Inner {
                        value: Int
                        fn doubled(): Int {
                            return value * 2
                        }
                    }
                    fn increment(value: Int): Int {
                        return value + 1
                    }
                    fn $TEST_MAIN() {
                        var box = Box(Inner(20))
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
                        var value = 1
                        -2
                        return value
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `blocks can be written on one line`(testInfo: TestInfo) {
        val source = """
                    class Point { x: Int }
                    fn one(): Int { return 1 }
                    fn $TEST_MAIN() {
                        var counter = 0
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
                        side: Int // trailing comment on a field

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
                        var total = 1 + // continues after a trailing operator
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
                        var count = 0
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
                        var count = 0
                        var total = 0
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
                        var outer = 0
                        var total = 0
                        while outer < 3 {
                            outer = outer + 1
                            var inner = 0
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
                        var counter = 0
                        var last = 0
                        while True {
                            counter = counter + 1
                            var value = if counter < 10 {
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
                            var count = 0
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
                            var value = 1
                            value
                        } else {
                            var value = 2
                            value
                        }
                    }
                    fn $TEST_MAIN() {
                        print(pick(True))
                        print(pick(False))
                        var round = 0
                        while round < 2 {
                            var doubled = round * 2
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
                        var value = 1
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
                        var value = 1
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
                        var counter = 0
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
                        var count = 0
                        var total = 0
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
                        var done = False
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
                        finished: Bool
                        fn isFinished(): Bool {
                            return finished
                        }
                    }
                    fn $TEST_MAIN() {
                        var task = Task(False)
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
                        x: Int
                        y: Int
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
                        x: Int
                        y: Int
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
                        base: Int

                        fn process(input: Int): Int {
                            return input + base
                        }
                    }
                    class Processor {
                        runtime: Runtime
                    }
                    fn $TEST_MAIN(): Int {
                        var processor = Processor(Runtime(10))
                        return 5 |> processor.runtime.process()
                    }
                """.trimIndent()
        assertProgramReturns(source, 15, testInfo)
    }

    @Test
    fun `forward application passes the left value before explicit method arguments`(testInfo: TestInfo) {
        val source = """
                    class Calculator {
                        offset: Int

                        fn add(value: Int, extra: Int): Int {
                            return value + extra + offset
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        var calculator = Calculator(100)
                        return 1 |> calculator.add(20)
                    }
                """.trimIndent()
        assertProgramReturns(source, 121, testInfo)
    }

    @Test
    fun `forward application chains member calls`(testInfo: TestInfo) {
        val source = """
                    class Runtime {
                        base: Int

                        fn process(input: Int): Int {
                            return input + base
                        }
                    }
                    class Processor {
                        runtime: Runtime
                    }
                    fn $TEST_MAIN() {
                        var processor = Processor(Runtime(10))
                        1 |> processor.runtime.process() |> print()
                    }
                """.trimIndent()
        assertProgramPrints(source, "11$EOL", testInfo)
    }

    @Test
    fun `forward application evaluates the left value before a member chain`(testInfo: TestInfo) {
        val source = """
                    class Box {
                        base: Int
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
                        width: Int
                        height: Int
                        fn scaled(factor: Int, extra: Int): Int {
                            return width * height * factor + extra
                        }
                    }
                    fn area(width: Int, height: Int): Int {
                        return width * height
                    }
                    fn $TEST_MAIN() {
                        print(area(width = 3, height = 4))
                        var rect = Rect(width = 2, height = 5)
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
                        x: Int
                        y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        var x = 3
                        var y = 4
                        var point = Point(x, y)
                        var mixed = Point(x, y = 10)
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
                        value: Int

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
                        x: Int
                        y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        var point = 2 |> Point(1)
                        return point.x * 10 + point.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 21, testInfo)
    }

    @Test
    fun `objects can be constructed and fields read`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        x: Int
                        y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        var point = Point(x = 1, y = 2)
                        return point.x + point.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 3, testInfo)
    }

    @Test
    fun `class fields can hold objects and chained field access works`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        x: Int
                        y: Int
                    }
                    class Line {
                        start: Point
                        end: Point
                    }
                    fn $TEST_MAIN(): Int {
                        var line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                        return line.start.x + line.end.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 5, testInfo)
    }

    @Test
    fun `objects can be passed to and returned from functions`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        x: Int
                        y: Int
                    }
                    fn add(left: Point, right: Point): Point {
                        return Point(x = left.x + right.x, y = left.y + right.y)
                    }
                    fn $TEST_MAIN(): Int {
                        var result = add(left = Point(x = 1, y = 2), right = Point(x = 3, y = 4))
                        return result.x * 10 + result.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 46, testInfo)
    }

    @Test
    fun `class fields can hold String and Bool values`(testInfo: TestInfo) {
        val source = """
                    class Message {
                        text: String
                        active: Bool
                    }
                    fn $TEST_MAIN() {
                        var message = Message(text = "Hello", active = False)
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
                        x: Int
                        y: Int
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
                        x: Int
                        y: Int
                    }
                    fn makePoint(): Point {
                        return Point(x = 7, y = 8)
                    }
                    fn $TEST_MAIN(): Int {
                        var point: Point = makePoint()
                        return point.x + makePoint().y
                    }
                """.trimIndent()
        assertProgramReturns(source, 15, testInfo)
    }

    @Test
    fun `class methods can read fields implicitly`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        x: Int
                        y: Int

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
                        x: Int
                        y: Int

                        fn offset(dx: Int, dy: Int): Point {
                            return Point(x = x + dx, y = y + dy)
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        var point = Point(x = 1, y = 2).offset(dx = 3, dy = 4)
                        return point.x * 10 + point.y
                    }
                """.trimIndent()
        assertProgramReturns(source, 46, testInfo)
    }

    @Test
    fun `class methods can call sibling methods`(testInfo: TestInfo) {
        val source = """
                    class Counter {
                        value: Int

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
                        value: Int

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
                        x: Int
                        y: Int

                        fn sum(): Int {
                            return x + y
                        }
                    }
                    class Line {
                        start: Point
                        end: Point
                    }
                    fn $TEST_MAIN(): Int {
                        var line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                        return line.start.sum() + line.end.sum()
                    }
                """.trimIndent()
        assertProgramReturns(source, 10, testInfo)
    }

    @Test
    fun `class methods can call methods on field values`(testInfo: TestInfo) {
        val source = """
                    class Point {
                        x: Int

                        fn value(): Int {
                            return x
                        }
                    }
                    class Box {
                        point: Point

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
                        x: Int
                        y: Int

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
                        value: Int

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
                        text: String

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
                        value: Bool

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
                        x: Int
                        y: Int

                        fn offset(dx: Int, dy: Int): Point {
                            return Point(x = x + dx, y = y + dy)
                        }
                    }
                    class Box {
                        point: Point

                        fn shifted(dx: Int, dy: Int): Int {
                            var moved = point.offset(dx, dy)
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
                        x: Int
                        y: Int

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
                        flag: Unit
                    }
                    fn $TEST_MAIN(): Int {
                        var signal = Signal(Unit)
                        var flag: Unit = signal.flag
                        return 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `Unit values can be passed and returned`(testInfo: TestInfo) {
        val source = """
                    fn identity(unit: Unit): Unit {
                        return unit
                    }
                    fn $TEST_MAIN(): Int {
                        var unit = identity(Unit)
                        return 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `Unit can be returned explicitly`(testInfo: TestInfo) {
        val source = """
                    fn explicitUnit(): Unit {
                        return Unit
                    }
                    fn $TEST_MAIN(): Int {
                        var unit = explicitUnit()
                        return 1
                    }
                """.trimIndent()
        assertProgramReturns(source, 1, testInfo)
    }

    @Test
    fun `if without else produces Unit`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var result: Unit = if False {
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
                        var result: Unit = if False {
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
                        side: Int
                        fn area(): Int {
                            return side * side
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        var square = Square(3)
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
                        side: Int
                        fn area(): Int {
                            return side * side
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        var square = Square(4)
                        return square.area()
                    }
                """.trimIndent()
        assertProgramReturns(source, 16, testInfo)
    }

    @Test
    fun `trait methods with omitted return types match Unit methods`(testInfo: TestInfo) {
        val source = """
                    trait Greeter {
                        fn greet()
                    }
                    class Host has Greeter {
                        name: String
                        fn greet() {
                            print(name)
                        }
                    }
                    fn $TEST_MAIN() {
                        var host = Host("vera")
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
                        value: Int
                        fn scale(by: Int): Int {
                            return value * by
                        }
                    }
                    fn $TEST_MAIN(): Int {
                        var counter = Counter(2)
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
                        label: String
                        amount: Int
                        fn name(): String {
                            return label
                        }
                        fn value(): Int {
                            return amount
                        }
                    }
                    fn $TEST_MAIN() {
                        var entry = Entry(label = "item", amount = 7)
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
                        x: Int
                        y: Int
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
                        x: Int
                        y: Int
                    }
                    class Line has Eq {
                        start: Point
                        end: Point
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
                        text: String
                        active: Bool
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
                        print(Empty() == Empty())
                        print(Empty() != Empty())
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
                        x: Int
                        y: Int
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
                        x: Int
                        y: Int
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
                        x: Int
                        y: Int
                    }
                    class Line has Display {
                        start: Point
                        end: Point
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
                        text: String
                        active: Bool
                    }
                    class Signal has Display {
                        flag: Unit
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
                        x: Int
                        y: Int
                    }
                    fn $TEST_MAIN(): Int {
                        var first = Point(x = 1, y = 2)
                        var second = Point(x = 1, y = 2)
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
                        x: Int
                        y: Int
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
