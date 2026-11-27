package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.compileProgram
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** Classes and objects: declarations, fields, methods, and singletons. */
class ClassTests {
    @Nested
    inner class Declarations {
        @Test
        fun `rejects fields without val`() = assertCompilationFails(
            "class Point {\n    x: Int\n}",
            VeraParseException.FieldWithoutVal::class,
            "declare fields with `val`: write `val x: ...`",
        )

        @Test
        fun `rejects mutable fields`() = assertCompilationFails(
            "class Account {\n    mut val balance: Int\n}",
            VeraParseException.MutableFieldNotSupported::class,
            "mutable fields need `mut fn` methods, which are not implemented yet; use `val`",
        )

        @Test
        fun `rejects statements in class bodies`() = assertCompilationFails(
            "class Point {\n    print(1)\n}",
            VeraParseException.ExpectedToken::class,
            "expected a field (`val`) or method (`fn`), found `print`",
        )

        @Test
        fun `rejects duplicate class fields`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "val x: String"),
            JvmLoweringException.DuplicateClassField::class,
            "class Point has duplicate field x",
        )

        @Test
        fun `rejects unknown class field types`() = assertCompilationFails(
            classDeclaration("Point", "val x: Missing"),
            JvmLoweringException.UnknownClassType::class,
            "type Missing is not defined",
        )

        @Test
        fun `rejects duplicate class types`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + classDeclaration("Point", "val y: Int"),
            JvmLoweringException.DuplicateClassType::class,
            "class Point is declared more than once",
        )

        @Test
        fun `rejects duplicate class methods`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "fn value(): Int {",
                "    return 1",
                "}",
                "fn value(): Int {",
                "    return 2",
                "}",
            ),
            JvmLoweringException.DuplicateClassMethod::class,
            "class Point has duplicate method value",
        )

        @Test
        fun `rejects class methods conflicting with fields`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val value: Int",
                "fn value(): Int {",
                "    return value",
                "}",
            ),
            JvmLoweringException.ClassMethodConflictsWithField::class,
            "class Point has both a field and a method named value",
        )
    }

    @Nested
    inner class Objects {
        @Test
        fun `objects can be constructed and their fields read`() = assertPrints(
            """
            class Message {
                val count: Int
                val text: String
                val active: Bool
                val unit: Unit
            }
            fn main() {
                val message = Message(count = 1, text = "Hello", active = False, unit = Unit)
                print(message.count)
                print(message.text)
                print(message.active)
                print(message.unit)
            }
            """,
            "1",
            "Hello",
            "False",
            "Unit",
        )

        @Test
        fun `fields can hold objects and field access chains`() = assertPrints(
            """
            class Point {
                val x: Int
                val y: Int
            }
            class Line {
                val start: Point
                val end: Point
            }
            fn main() {
                val line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                print(line.start.x + line.end.y)
            }
            """,
            "5",
        )

        @Test
        fun `fields can be read from call results and passed objects`() = assertPrints(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn makePoint(): Point = Point(x = 7, y = 8)
            fn add(left: Point, right: Point): Point = Point(x = left.x + right.x, y = left.y + right.y)
            fn main() {
                val point: Point = makePoint()
                print(point.x + makePoint().y)
                print(Point(x = 4, y = 5).y)
                print(add(left = Point(x = 1, y = 2), right = Point(x = 3, y = 4)).x)
            }
            """,
            "15",
            "5",
            "4",
        )

        @Test
        fun `rejects member access on non-object values`() = assertCompilationFails(
            function("val x = 1", "print(x.foo)"),
            JvmLoweringException.MemberAccessRequiresObject::class,
            "Int has no fields or methods yet",
        )

        @Test
        fun `rejects class constructors with the wrong arity`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point()"),
            JvmLoweringException.ClassConstructorArityMismatch::class,
            "class Point constructor requires 1 argument, got 0",
        )

        @Test
        fun `rejects class constructor arguments with the wrong type`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point(\"wrong\")"),
            JvmLoweringException.ClassConstructorArgumentTypeMismatch::class,
            "class Point field x requires Int, got String",
        )

        @Test
        fun `rejects unknown class fields`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1).y)"),
            JvmLoweringException.UnknownClassField::class,
            "class Point has no field y",
        )

        @Test
        fun `rejects class types used as values`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point"),
            JvmLoweringException.ClassTypeUsedAsValue::class,
            "class type Point cannot be used as a value",
        )

        @Test
        fun `rejects calling class fields`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1).x())"),
            JvmLoweringException.FieldCalled::class,
            "x is a field, not a method; remove `()`",
        )

        @Test
        fun `rejects calling the result of a call`() = assertCompilationFails(
            "fn one(): Int = 1\n" + function("print(one()())"),
            JvmLoweringException.ValueCallNotSupported::class,
            "calling a value is not supported yet",
        )
    }

    @Nested
    inner class Methods {
        @Test
        fun `methods take and return every type and read fields implicitly`() = assertPrints(
            """
            class Point has Display {
                val x: Int
                val y: Int
                fn sum(): Int = x + y
                fn offset(dx: Int, dy: Int): Point = Point(x = x + dx, y = y + dy)
                fn label(prefix: String): String = "\{prefix} \{x}"
                fn above(limit: Int): Bool = y > limit
                fn total(other: Point): Int = sum() + other.sum()
                fn sign(): Int = if x < 0 { -1 } else { 1 }
            }
            fn main() {
                val point = Point(x = 1, y = 2)
                print(point.sum())
                print(point.offset(dx = 3, dy = 4))
                print(point.label("x is"))
                print(point.above(1))
                print(point.total(Point(x = 3, y = 4)))
                print(point.sign())
            }
            """,
            "3",
            "Point(x: 4, y: 6)",
            "x is 1",
            "True",
            "10",
            "1",
        )

        @Test
        fun `methods call sibling methods, top-level functions, and print`() = assertPrints(
            """
            fn increment(value: Int): Int = value + 1
            class Counter {
                val value: Int
                fn doubled(): Int = value * 2
                fn quadrupled(): Int = doubled() * 2
                fn printNext() = print(increment(value))
            }
            fn main() {
                print(Counter(3).quadrupled())
                Counter(41).printNext()
            }
            """,
            "12",
            "42",
        )

        @Test
        fun `methods can be called on any object expression`() = assertPrints(
            """
            class Point {
                val x: Int
                fn withX(newX: Int): Point = Point(newX)
                fn value(): Int = x
            }
            class Box {
                val point: Point
                fn value(): Int = point.value()
                fn shifted(dx: Int): Int = point.withX(point.x + dx).value()
            }
            fn makeBox(): Box = Box(Point(1))
            fn main() {
                val box = Box(Point(9))
                print(box.value())
                print(box.point.value())
                print(Point(1).withX(7).x)
                print(makeBox().shifted(4))
            }
            """,
            "9",
            "9",
            "7",
            "5",
        )

        @Test
        fun `methods can call themselves recursively`() = assertPrints(
            """
            class Countdown {
                val from: Int
                fn total(n: Int): Int = if n == 0 { 0 } else { n + total(n - 1) }
                fn sum(): Int = total(from)
            }
            fn main() = print(Countdown(4).sum())
            """,
            "10",
        )

        @Test
        fun `rejects unknown class methods`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1).missing())"),
            JvmLoweringException.UnknownClassMethod::class,
            "class Point has no method missing",
        )

        @Test
        fun `rejects class methods called with the wrong arity`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn add(other: Int): Int {",
                "    return x + other",
                "}",
            ) + function("print(Point(1).add())"),
            JvmLoweringException.MethodArityMismatch::class,
            "class Point method add requires 1 argument, got 0",
        )

        @Test
        fun `rejects class method arguments with the wrong type`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn add(other: Int): Int {",
                "    return x + other",
                "}",
            ) + function("print(Point(1).add(\"wrong\"))"),
            JvmLoweringException.MethodArgumentTypeMismatch::class,
            "class Point method add parameter other requires Int, got String",
        )

        @Test
        fun `rejects class method return values with the wrong type`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn value(): Int {",
                "    return \"wrong\"",
                "}",
            ),
            JvmLoweringException.ReturnTypeMismatch::class,
            "return requires Int, got String",
        )

        @Test
        fun `rejects class methods used as values through member access`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn value(): Int {",
                "    return x",
                "}",
            ) + function("print(Point(1).value)"),
            JvmLoweringException.ClassMethodUsedAsValue::class,
            "class Point method value must be called",
        )

        @Test
        fun `rejects class methods used as bare values`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn value(): Int {",
                "    return x",
                "}",
                "fn other(): Int {",
                "    return value",
                "}",
            ),
            JvmLoweringException.ClassMethodUsedAsValue::class,
            "class Point method value must be called",
        )
    }

    @Nested
    inner class Singletons {
        @Test
        fun `classes without fields are singletons written by name`() = assertPrints(
            """
            class Empty has Display, Eq {}
            class Geometry {
                fn square(x: Int): Int = x * x
            }
            trait Shape {
                fn area(): Int
            }
            class NoShape has Shape {
                fn area(): Int = 0
            }
            fn describe(shape: Shape): Int = shape.area()
            fn main() {
                print(Empty)
                print(Empty == Empty)
                print("empty: \{Empty}")
                print(Geometry.square(3))
                print(describe(NoShape))
                val geometry = Geometry
                print(4 |> geometry.square())
            }
            """,
            "Empty",
            "True",
            "empty: Empty",
            "9",
            "0",
            "16",
        )

        @Test
        fun `singletons have one instance and a private constructor`() {
            val program = compileProgram(
                """
                class Empty {}
                fn first(): Empty = Empty
                fn main() {
                }
                """,
            )
            val empty = program.loadClass("Empty")
            val first = program.loadProgramClass().getMethod("first")
            Assertions.assertSame(first.invoke(null), first.invoke(null))
            Assertions.assertSame(empty.getField("INSTANCE").get(null), first.invoke(null))
            Assertions.assertTrue(empty.declaredConstructors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        }

        @Test
        fun `rejects constructing classes without fields`() = assertCompilationFails(
            classDeclaration("Empty") + function("val empty = Empty()"),
            JvmLoweringException.SingletonConstructed::class,
            "Empty has no fields, so it has a single value; write `Empty` instead of `Empty()`",
        )

        @Test
        fun `rejects forwarding values into classes without fields`() = assertCompilationFails(
            classDeclaration("Empty") + function("val empty = 1 |> Empty()"),
            JvmLoweringException.SingletonConstructed::class,
            "Empty has no fields, so it has a single value",
        )
    }
}
