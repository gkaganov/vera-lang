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

/** `match`: scrutinees, patterns, arms, exhaustiveness, and branch types. */
class MatchTests {
    @Nested
    inner class Scrutinee {
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
        fun `match on values that are not objects`() = assertCompilationFails(
            function("print(match 1 {", "    Point -> 1", "})"),
            JvmLoweringException.MatchScrutineeNotAnObject::class,
            "match needs an object of a class or trait, got Int",
        )
    }

    @Nested
    inner class Patterns {
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
        fun `empty parentheses in match patterns`() = assertCompilationFails(
            function("match value {", "    Empty() -> 0", "}"),
            VeraParseException.EmptyPatternParentheses::class,
            "a pattern that binds no fields has no parentheses: write `Empty`",
        )

        @Test
        fun `match patterns that are not cases`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                classDeclaration("Point", "val x: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Point(x) -> x\n}",
            JvmLoweringException.MatchPatternNotACase::class,
            "Point is not a case of Shape",
        )

        @Test
        fun `misspelled match patterns`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circel(radius) -> radius\n}",
            JvmLoweringException.MatchPatternNotACase::class,
            "Circel is not a case of Shape; did you mean `Circle`?",
        )

        @Test
        fun `match patterns with unknown fields`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circle(radus) -> radus\n}",
            JvmLoweringException.UnknownClassField::class,
            "class Circle has no field radus; did you mean `radius`?",
        )

        @Test
        fun `match bindings that shadow locals`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape, radius: Int): Int = match shape {\n    Circle(radius) -> radius\n}",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name radius is already in scope; shadowing is not allowed",
        )
    }

    @Nested
    inner class Arms {
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
        fun `match arms without an arrow`() = assertCompilationFails(
            function("match value {", "    Empty 0", "}"),
            VeraParseException.ExpectedToken::class,
            "expected `->` after the pattern, found '0'",
        )
    }

    @Nested
    inner class Exhaustiveness {
        @Test
        fun `match arms that miss cases`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                classDeclaration("Rect has Shape", "val width: Int") + classDeclaration("Empty has Shape") +
                "fn area(shape: Shape): Int = match shape {\n    Circle(radius) -> radius\n}",
            JvmLoweringException.MatchNotExhaustive::class,
            "match on Shape does not cover Rect, Empty",
        )

        @Test
        fun `match arms repeated`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circle -> 1\n    Circle(radius) -> radius\n}",
            JvmLoweringException.MatchArmRepeated::class,
            "Circle is matched more than once",
        )
    }

    @Nested
    inner class BranchTypes {
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
        fun `match arms of unrelated types`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                classDeclaration("Empty has Shape") +
                "fn label(shape: Shape) = print(match shape {\n    Circle(radius) -> radius\n    Empty -> \"empty\"\n})",
            JvmLoweringException.MatchArmTypeMismatch::class,
            "match arms must have the same type or share one trait, got Int and String",
        )
    }
}
