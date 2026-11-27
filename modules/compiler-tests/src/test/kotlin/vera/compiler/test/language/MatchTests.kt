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
            }
            """,
            "origin",
            "7",
        )

        @Test
        fun `rejects match on values that are not objects`() = assertCompilationFails(
            function("print(match 1 {", "    Point -> 1", "})"),
            JvmLoweringException.MatchScrutineeNotAnObject::class,
            "match needs a Bool or an object of a class or trait, got Int",
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
        fun `match arms can break, continue, or jump from inside an if`() = assertPrints(
            """
            trait Step { Skip, Stop, Show }
            class Point {
                val x: Int
            }
            fn step(n: Int): Step = if n == 2 { Step.Skip } else if n == 4 { Step.Stop } else { Step.Show }
            fn main() {
                mut val n = 0
                while n < 10 {
                    n = n + 1
                    match step(n) {
                        Skip -> continue
                        Stop -> break
                        Show -> print(n)
                    }
                }
                mut val count = 0
                while count < 3 {
                    count = count + 1
                    match Point(count) {
                        Point(x) -> if x == 2 { break }
                    }
                }
                print(count)
            }
            """,
            "1",
            "3",
            "2",
        )

        @Test
        fun `match bindings are local to their arm`() = assertPrints(
            """
            trait Shape {
                Circle {
                    val size: Int
                }
                Square {
                    val size: Int
                }
            }
            fn measure(shape: Shape): Int = match shape {
                Circle(size) -> size
                Square(size) -> size * 2
            }
            fn main() {
                print(measure(Shape.Circle(4)))
                print(measure(Shape.Square(3)))
            }
            """,
            "4",
            "6",
        )

        @Test
        fun `rejects match bindings used after the match`() = assertCompilationFails(
            "trait Shape {\n    Circle {\n        val radius: Int\n    }\n}\n" +
                "fn area(shape: Shape): Int {\n    match shape {\n        Circle(radius) -> print(radius)\n    }\n    return radius\n}",
            JvmLoweringException.UnknownVariable::class,
            "variable radius is not defined",
        )

        @Test
        fun `rejects match arms without an arrow`() = assertCompilationFails(
            function("match value {", "    Empty 0", "}"),
            VeraParseException.ExpectedToken::class,
            "expected `->` after the pattern, found `0`",
        )
    }

    @Nested
    inner class Bool {
        @Test
        fun `match on a Bool selects the True or False arm in any order`() = assertPrints(
            """
            fn check(label: String, value: Bool): Bool {
                print(label)
                return value
            }
            fn describe(flag: Bool): String = match flag {
                False -> "off"
                True -> "on"
            }
            fn main() {
                print(describe(True))
                print(describe(False))
                val size = match check(label = "evaluated once", value = 1 < 2) {
                    True -> 10
                    False -> 20
                }
                print(size)
                match size > 15 {
                    True -> print("large")
                    False -> {
                        print("small")
                    }
                }
            }
            """,
            "on",
            "off",
            "evaluated once",
            "10",
            "small",
        )

        @Test
        fun `match arms on a Bool can return, break, and continue`() = assertPrints(
            """
            fn sign(value: Int): String {
                match value < 0 {
                    True -> return "negative"
                    False -> return "not negative"
                }
            }
            fn main() {
                print(sign(-1))
                print(sign(1))
                mut val count = 0
                while True {
                    count = count + 1
                    match count % 2 == 0 {
                        True -> continue
                        False -> print(count)
                    }
                    match count > 4 {
                        True -> break
                        False -> None
                    }
                }
            }
            """,
            "negative",
            "not negative",
            "1",
            "3",
            "5",
        )

        @Test
        fun `rejects a match on a Bool that misses a case`() = assertCompilationFails(
            "fn describe(flag: Bool): String = match flag {\n    True -> \"on\"\n}",
            JvmLoweringException.MatchNotExhaustive::class,
            "match on Bool does not cover False",
            line = 1,
            column = 35,
        )

        @Test
        fun `rejects a Bool case matched twice`() = assertCompilationFails(
            "fn describe(flag: Bool): String = match flag {\n    True -> \"on\"\n    False -> \"off\"\n    True -> \"again\"\n}",
            JvmLoweringException.MatchArmRepeated::class,
            "True is matched more than once",
            line = 4,
            column = 5,
        )

        @Test
        fun `rejects patterns that are not Bool cases`() = assertCompilationFails(
            "fn describe(flag: Bool): String = match flag {\n    True -> \"on\"\n    Off -> \"off\"\n}",
            JvmLoweringException.MatchPatternNotACase::class,
            "Off is not a case of Bool",
            line = 3,
            column = 5,
        )

        @Test
        fun `rejects Bool cases in a match on a trait`() = assertCompilationFails(
            "trait Light { On, Off }\nfn describe(light: Light): String = match light {\n    True -> \"on\"\n    Off -> \"off\"\n}",
            JvmLoweringException.MatchPatternNotACase::class,
            "True is not a case of Light",
        )

        @Test
        fun `rejects Bool match arms of different types`() = assertCompilationFails(
            function("val value = match True {", "    True -> 1", "    False -> \"one\"", "}"),
            JvmLoweringException.MatchArmTypeMismatch::class,
            "match arms must have the same type or share one trait, got Int and String",
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
        fun `branches convert to the type expected where the value goes`() = assertPrints(
            """
            fn main() {
                val named: Named = if True { Dog } else { User("Ann") }
                print(named.name())
                print(pick(False).name())
                print(pickWithReturn(True).name())
                describe(if False { Dog } else { User("Bob") })
                print(Greeter("hi").greet(match 1 < 2 {
                    True -> User("Cy")
                    False -> Dog
                }))
                print(Holder(if True { Dog } else { User("Di") }).named.name())
                mut val current: Named = Dog
                current = if False {
                    Dog
                } else if True {
                    User("Ed")
                } else {
                    Dog
                }
                print(current.name())
            }
            interface Named {
                fn name(): String
            }
            class Dog {
                fn name(): String = "dog"
            }
            class User {
                val label: String
                fn name(): String = label
            }
            class Greeter {
                val greeting: String
                fn greet(named: Named): String = "\{greeting} \{named.name()}"
            }
            class Holder {
                val named: Named
            }
            fn pick(dog: Bool): Named = if dog { Dog } else { User("Flo") }
            fn pickWithReturn(dog: Bool): Named {
                return match dog {
                    True -> Dog
                    False -> User("Gus")
                }
            }
            fn describe(named: Named) = print(named.name())
            """,
            "dog",
            "Flo",
            "dog",
            "Bob",
            "hi Cy",
            "dog",
            "Ed",
        )

        @Test
        fun `rejects a branch that does not convert to the expected type at that branch`() = assertCompilationFails(
            function("val named: Named = if True { Dog } else { 5 }") +
                "interface Named {\n    fn name(): String\n}\nclass Dog {\n    fn name(): String = \"dog\"\n}",
            JvmLoweringException.BindTypeMismatch::class,
            "variable named is declared as Named but bound to a value of type Int; built-in type Int cannot fit an interface yet",
            line = 2,
            column = 47,
        )

        @Test
        fun `rejects an argument branch that does not fit the parameter's interface`() = assertCompilationFails(
            function("describe(match True {", "    True -> Dog", "    False -> Cat", "})") +
                "interface Named {\n    fn name(): String\n}\nclass Dog {\n    fn name(): String = \"dog\"\n}\n" +
                "class Cat {\n    fn sound(): String = \"meow\"\n}\nfn describe(named: Named) = print(named.name())",
            JvmLoweringException.FunctionArgumentTypeMismatch::class,
            "function describe parameter named requires Named, got Cat; Cat does not fit Named",
            line = 4,
            column = 18,
        )

        @Test
        fun `rejects a returned branch that does not convert, inside a nested if`() = assertCompilationFails(
            "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") + classDeclaration("Box") +
                "fn make(round: Bool, big: Bool): Shape = if round {\n    Circle(1)\n} else if big {\n    Box\n} else {\n    Circle(2)\n}",
            JvmLoweringException.ReturnTypeMismatch::class,
            "return requires Shape, got Box",
            line = 10,
            column = 5,
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
