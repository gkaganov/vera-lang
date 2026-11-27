package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.compiler.test.traitDeclaration
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
                val line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                val same = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                val movedEnd = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 5))
                val movedStart = Line(start = Point(x = 0, y = 2), end = Point(x = 3, y = 4))
                print(line == same)
                print(line == movedEnd)
                print(line != movedStart)
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
        fun `classes without fields have Eq and Display without declaring them`() = assertPrints(
            """
            class Empty {}
            class Other {}
            trait Color { Red, Green }
            trait Named requires Display {}
            class Anonymous has Named {}
            class Holder has Eq, Display {
                val empty: Empty
            }
            fn show(named: Named) = print(named)
            fn main() {
                print(Empty == Empty)
                print(Empty != Empty)
                print(Empty)
                print("in a template: \{Other}")
                print(Color.Red == Color.Red)
                print(Color.Green)
                show(Anonymous)
                print(Holder(Empty) == Holder(Empty))
                print(Holder(Empty))
            }
            """,
            "True",
            "False",
            "Empty",
            "in a template: Other",
            "True",
            "Color.Green",
            "Anonymous",
            "True",
            "Holder(empty: Empty)",
        )

        @Test
        fun `rejects comparing cases whose trait does not require Eq`() = assertCompilationFails(
            "trait Shape {\n    Circle {\n        val radius: Int\n    }\n}\n" + function("print(Shape.Circle(1) == Shape.Circle(1))"),
            JvmLoweringException.ObjectEqualityRequiresTrait::class,
            "class Shape.Circle does not have the Eq trait, so its objects cannot be compared; add `requires Eq` " +
                "to trait Shape",
        )

        @Test
        fun `rejects comparing trait values of singleton cases without requires Eq`() = assertCompilationFails(
            "trait Color { Red, Green }\nfn same(first: Color, second: Color): Bool = first == second\n",
            JvmLoweringException.TraitRequirementMissing::class,
            "trait Color does not require Eq, so its values cannot be compared",
        )

        @Test
        fun `Eq compares Unit fields`() = assertPrints(
            """
            class Signal has Eq {
                val flag: Unit
                val level: Int
            }
            fn main() {
                print(Signal(flag = Unit, level = 1) == Signal(flag = Unit, level = 1))
                print(Signal(flag = Unit, level = 1) == Signal(flag = Unit, level = 2))
            }
            """,
            "True",
            "False",
        )

        @Test
        fun `object equality evaluates both operands in order`() = assertPrints(
            """
            class Point has Eq {
                val x: Int
            }
            fn left(): Point {
                print("left")
                return Point(1)
            }
            fn right(): Point {
                print("right")
                return Point(1)
            }
            fn main() {
                print(left() == right())
                print(left() != right())
            }
            """,
            "left",
            "right",
            "True",
            "left",
            "right",
            "False",
        )

        @Test
        fun `rejects object equality without the Eq trait`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1) == Point(1))"),
            JvmLoweringException.ObjectEqualityRequiresTrait::class,
            "class Point does not have the Eq trait, so its objects cannot be compared; declare `class Point has Eq`",
        )

        @Test
        fun `rejects Eq fields whose types lack Eq`() = assertCompilationFails(
            "class Point {\n    val x: Int\n}\nclass Line has Eq {\n    val start: Point\n}",
            JvmLoweringException.ClassFieldRequiresTrait::class,
            "class Line field start has type Point, which does not have the Eq trait; declare `class Point has Eq`",
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
        fun `rejects forwarding objects without the Display trait to print`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("Point(1) |> print()"),
            JvmLoweringException.PrintRequiresDisplayTrait::class,
            "class Point does not have the Display trait",
        )
    }

    /** `toString`, `equals`, and `hashCode` are generated `Object` methods, so members cannot take them. */
    @Nested
    inner class ReservedNames {
        @Test
        fun `rejects equals methods`() = assertCompilationFails(
            classDeclaration("Point has Eq", "val x: Int", "fn equals(other: Point): Bool = True"),
            JvmLoweringException.ReservedMemberName::class,
            "equals is reserved for the method that Vera generates; `==` comes from the `Eq` trait, so add `has Eq`",
        )

        @Test
        fun `rejects toString methods even without Display`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "fn toString(): String = \"point\""),
            JvmLoweringException.ReservedMemberName::class,
            "toString is reserved for the method that Vera generates; printing comes from the `Display` trait, " +
                "so add `has Display`",
        )

        @Test
        fun `rejects hashCode methods`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "fn hashCode(): Int = 7"),
            JvmLoweringException.ReservedMemberName::class,
            "hashCode is reserved for the method that Vera generates; it belongs with the `Eq` trait",
        )

        @Test
        fun `rejects trait methods with reserved names`() = assertCompilationFails(
            traitDeclaration("Named", "fn toString(): String"),
            JvmLoweringException.ReservedMemberName::class,
            "toString is reserved",
        )

        @Test
        fun `rejects required fields with reserved names`() = assertCompilationFails(
            "interface Hashed {\n    val hashCode: Int\n}",
            JvmLoweringException.ReservedMemberName::class,
            "hashCode is reserved",
        )
    }
}
