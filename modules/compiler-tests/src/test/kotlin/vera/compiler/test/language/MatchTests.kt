package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** `match`: scrutinees, patterns, arms, exhaustiveness, and branch types. */
class MatchTests {
    @Nested
    inner class Scrutinee {
        @Test
        fun `match destructures a single class and evaluates its scrutinee once`() = assertPrints(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn origin(): Point {
                print("origin")
                return Point(x = 3, y = 4)
            }
            fn main() {
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
            """,
            "origin",
            "7",
            "2",
        )

        @Test
        fun `rejects match on values that are not objects`() = assertCompilationFails(
            function("print(match 1 {", "    Point -> 1", "})"),
            JvmLoweringException.MatchScrutineeNotAnObject::class,
            "match needs an object of a class or trait, got Int",
        )
    }

    @Nested
    inner class Patterns {
        @Test
        fun `match selects the arm for the object's class and binds its fields`() = assertPrints(
            """
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
            fn main() {
                print(area(Circle(2)))
                print(area(Rect(width = 2, height = 3)))
                print(area(Empty))
            }
            """,
            "12",
            "6",
            "0",
        )

        @Test
        fun `rejects empty parentheses in match patterns`() = assertCompilationFails(
            function("match value {", "    Empty() -> 0", "}"),
            VeraParseException.EmptyPatternParentheses::class,
            "a pattern that binds no fields has no parentheses: write `Empty`",
        )

        @Test
        fun `rejects match patterns that are not cases`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                classDeclaration("Point", "val x: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Point(x) -> x\n}",
            JvmLoweringException.MatchPatternNotACase::class,
            "Point is not a case of Shape",
        )

        @Test
        fun `rejects misspelled match patterns`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circel(radius) -> radius\n}",
            JvmLoweringException.MatchPatternNotACase::class,
            "Circel is not a case of Shape; did you mean `Circle`?",
        )

        @Test
        fun `rejects match patterns with unknown fields`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circle(radus) -> radus\n}",
            JvmLoweringException.UnknownClassField::class,
            "class Circle has no field radus; did you mean `radius`?",
        )

        @Test
        fun `rejects pattern bindings named differently from the field`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circle(r) -> r\n}",
            JvmLoweringException.UnknownClassField::class,
            "class Circle has no field r; patterns bind fields by name; to use another name, write `radius = r`",
        )

        @Test
        fun `rejects match bindings that shadow locals`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape, radius: Int): Int = match shape {\n    Circle(radius) -> radius\n}",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name radius is already used by parameter radius; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects patterns that bind a field twice`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Rect has Shape", "val width: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Rect(width, width = w) -> w\n}",
            JvmLoweringException.PatternFieldBoundTwice::class,
            "field width is bound more than once in the pattern",
            line = 6,
            column = 17,
        )
    }

    @Nested
    inner class Arms {
        @Test
        fun `match arms can be blocks, statements, and return from the function`() = assertPrints(
            """
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
            fn main() {
                print(describe(Click(4)))
                print(describe(Quit))
            }
            """,
            "click 8",
            "quit",
        )

        @Test
        fun `rejects match arms without an arrow`() = assertCompilationFails(
            function("match value {", "    Empty 0", "}"),
            VeraParseException.ExpectedToken::class,
            "expected `->` after the pattern, found '0'",
        )
    }

    @Nested
    inner class Exhaustiveness {
        @Test
        fun `rejects match arms that miss cases`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                classDeclaration("Rect has Shape", "val width: Int") + classDeclaration("Empty has Shape") +
                "fn area(shape: Shape): Int = match shape {\n    Circle(radius) -> radius\n}",
            JvmLoweringException.MatchNotExhaustive::class,
            "match on Shape does not cover Rect, Empty",
        )

        @Test
        fun `rejects match arms repeated`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                "fn area(shape: Shape): Int = match shape {\n    Circle -> 1\n    Circle(radius) -> radius\n}",
            JvmLoweringException.MatchArmRepeated::class,
            "Circle is matched more than once",
        )
    }

    @Nested
    inner class BranchTypes {
        @Test
        fun `branches of different classes unify to their shared trait`() = assertPrints(
            """
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
            fn main() {
                print(make(True).size())
                print(make(False).size())
                print(grow(make(True)).size())
                print(grow(make(False)).size())
            }
            """,
            "5",
            "0",
            "6",
            "1",
        )

        @Test
        fun `rejects match arms of unrelated types`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
                classDeclaration("Empty has Shape") +
                "fn label(shape: Shape) = print(match shape {\n    Circle(radius) -> radius\n    Empty -> \"empty\"\n})",
            JvmLoweringException.MatchArmTypeMismatch::class,
            "match arms must have the same type or share one trait, got Int and String",
        )
    }
}
