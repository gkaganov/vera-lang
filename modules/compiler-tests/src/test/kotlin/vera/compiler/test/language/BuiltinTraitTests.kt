package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException

/** The built-in traits `Eq` and `Display`. */
class BuiltinTraitTests {
    @Nested
    inner class Eq {
        @Test
        fun `objects with Eq compare structurally`() = assertPrints(
            """
            class Point has Eq {
                val x: Int
                val y: Int
            }
            fn main() {
                print(Point(x = 1, y = 2) == Point(x = 1, y = 2))
                print(Point(x = 1, y = 2) == Point(x = 1, y = 3))
                print(Point(x = 1, y = 2) != Point(x = 1, y = 3))
                print(Point(x = 1, y = 2) != Point(x = 1, y = 2))
            }
            """,
            "True",
            "False",
            "True",
            "False",
        )

        @Test
        fun `nested objects with Eq compare structurally`() = assertPrints(
            """
            class Point has Eq {
                val x: Int
                val y: Int
            }
            class Line has Eq {
                val start: Point
                val end: Point
            }
            fn main() {
                print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) == Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)))
                print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) == Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 5)))
                print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) != Line(start = Point(x = 0, y = 2), end = Point(x = 3, y = 4)))
            }
            """,
            "True",
            "False",
            "True",
        )

        @Test
        fun `Eq compares String and Bool fields by value`() = assertPrints(
            """
            class Label has Eq {
                val text: String
                val active: Bool
            }
            fn main() {
                print(Label(text = "same", active = True) == Label(text = "same", active = True))
                print(Label(text = "same", active = True) == Label(text = "other", active = True))
                print(Label(text = "same", active = True) != Label(text = "same", active = False))
            }
            """,
            "True",
            "False",
            "True",
        )

        @Test
        fun `objects with Eq and no fields are equal`() = assertPrints(
            """
            class Empty has Eq {
            }
            fn main() {
                print(Empty == Empty)
                print(Empty != Empty)
            }
            """,
            "True",
            "False",
        )

        @Test
        fun `object equality works on variables`() = assertReturns(
            """
            class Point has Eq {
                val x: Int
                val y: Int
            }
            fn main(): Int {
                val first = Point(x = 1, y = 2)
                val second = Point(x = 1, y = 2)
                if first == second {
                    return 1
                }
                return 0
            }
            """,
            1,
        )

        @Test
        fun `rejects object equality without the Eq trait`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1) == Point(1))"),
            JvmLoweringException.ObjectEqualityRequiresTrait::class,
            "class Point does not have the Eq trait",
        )

        @Test
        fun `rejects Eq fields whose types lack Eq`() = assertCompilationFails(
            "class Point {\n    val x: Int\n}\nclass Line has Eq {\n    val start: Point\n}",
            JvmLoweringException.ClassFieldRequiresTrait::class,
            "class Line field start has type Point, which does not have the Eq trait",
        )

        @Test
        fun `rejects class methods conflicting with generated trait methods`() = assertCompilationFails(
            "class Point has Eq {\n    val x: Int\n" +
                "    fn equals(other: Point): Bool {\n        return True\n    }\n}",
            JvmLoweringException.ClassMethodConflictsWithGeneratedMethod::class,
            "class Point method equals conflicts with the generated method for trait Eq",
        )
    }

    @Nested
    inner class Display {
        @Test
        fun `Display prints objects with their fields`() = assertPrints(
            """
            class Point has Display {
                val x: Int
                val y: Int
            }
            fn main() {
                print(Point(x = 1, y = 2))
            }
            """,
            "Point(x: 1, y: 2)",
        )

        @Test
        fun `Display objects can be forwarded to print`() = assertPrints(
            """
            class Point has Display {
                val x: Int
                val y: Int
            }
            fn main() {
                Point(x = 1, y = 2) |> print
                Point(x = 3, y = 4) |> print()
            }
            """,
            "Point(x: 1, y: 2)",
            "Point(x: 3, y: 4)",
        )

        @Test
        fun `Display prints nested objects recursively`() = assertPrints(
            """
            class Point has Display {
                val x: Int
                val y: Int
            }
            class Line has Display {
                val start: Point
                val end: Point
            }
            fn main() {
                print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)))
            }
            """,
            "Line(start: Point(x: 1, y: 2), end: Point(x: 3, y: 4))",
        )

        @Test
        fun `Display prints String Bool and Unit fields`() = assertPrints(
            """
            class Message has Display {
                val text: String
                val active: Bool
            }
            class Signal has Display {
                val flag: Unit
            }
            fn main() {
                print(Message(text = "hello", active = True))
                print(Signal(Unit))
            }
            """,
            "Message(text: hello, active: True)",
            "Signal(flag: Unit)",
        )

        @Test
        fun `Eq and Display can be combined`() = assertPrints(
            """
            class Point has Eq, Display {
                val x: Int
                val y: Int
            }
            fn main() {
                print(Point(x = 1, y = 2))
                print(Point(x = 1, y = 2) == Point(x = 1, y = 2))
                print(Point(x = 1, y = 2) != Point(x = 2, y = 2))
            }
            """,
            "Point(x: 1, y: 2)",
            "True",
            "True",
        )

        @Test
        fun `rejects objects without the Display trait in string templates`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(\"at \\{Point(1)}\")"),
            JvmLoweringException.TemplateValueRequiresDisplayTrait::class,
            "class Point does not have the Display trait, so its objects cannot be used in a string template",
        )

        @Test
        fun `rejects printing objects without the Display trait`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1))"),
            JvmLoweringException.PrintRequiresDisplayTrait::class,
            "class Point does not have the Display trait",
        )

        @Test
        fun `rejects Display fields whose types lack Display`() = assertCompilationFails(
            "class Point {\n    val x: Int\n}\nclass Line has Display {\n    val start: Point\n}",
            JvmLoweringException.ClassFieldRequiresTrait::class,
            "class Line field start has type Point, which does not have the Display trait",
        )

        @Test
        fun `rejects class methods conflicting with generated Display methods`() = assertCompilationFails(
            "class Point has Display {\n    val x: Int\n" +
                "    fn toString(): String {\n        return \"point\"\n    }\n}",
            JvmLoweringException.ClassMethodConflictsWithGeneratedMethod::class,
            "class Point method toString conflicts with the generated method for trait Display",
        )

        @Test
        fun `rejects forwarding objects without the Display trait to print`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("Point(1) |> print"),
            JvmLoweringException.PrintRequiresDisplayTrait::class,
            "class Point does not have the Display trait",
        )
    }
}
