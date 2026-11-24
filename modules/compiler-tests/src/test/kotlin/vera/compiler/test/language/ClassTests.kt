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
        fun `fields without val`() = assertCompilationFails(
            "class Point {\n    x: Int\n}",
            VeraParseException.FieldWithoutVal::class,
            "declare fields with `val`: write `val x: ...`",
        )

        @Test
        fun `mutable fields`() = assertCompilationFails(
            "class Account {\n    mut val balance: Int\n}",
            VeraParseException.MutableFieldNotSupported::class,
            "mutable fields need `mut fn` methods, which are not implemented yet; use `val`",
        )

        @Test
        fun `statements in class bodies`() = assertCompilationFails(
            "class Point {\n    print(1)\n}",
            VeraParseException.ExpectedToken::class,
            "expected a field (`val`) or method (`fn`), found 'print'",
        )

        @Test
        fun `duplicate class fields`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "val x: String"),
            JvmLoweringException.DuplicateClassField::class,
            "class Point has duplicate field x",
        )

        @Test
        fun `unknown class field types`() = assertCompilationFails(
            classDeclaration("Point", "val x: Missing"),
            JvmLoweringException.UnknownClassType::class,
            "class type Missing is not defined",
        )

        @Test
        fun `duplicate class types`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + classDeclaration("Point", "val y: Int"),
            JvmLoweringException.DuplicateClassType::class,
            "class Point is declared more than once",
        )

        @Test
        fun `duplicate class methods`() = assertCompilationFails(
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
        fun `class methods conflicting with fields`() = assertCompilationFails(
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
        fun `objects can be constructed and fields read`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn main(): Int {
                val point = Point(x = 1, y = 2)
                return point.x + point.y
            }
            """,
            3,
        )

        @Test
        fun `class fields can hold objects and chained field access works`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            class Line {
                val start: Point
                val end: Point
            }
            fn main(): Int {
                val line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                return line.start.x + line.end.y
            }
            """,
            5,
        )

        @Test
        fun `objects can be passed to and returned from functions`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn add(left: Point, right: Point): Point {
                return Point(x = left.x + right.x, y = left.y + right.y)
            }
            fn main(): Int {
                val result = add(left = Point(x = 1, y = 2), right = Point(x = 3, y = 4))
                return result.x * 10 + result.y
            }
            """,
            46,
        )

        @Test
        fun `class fields can hold String and Bool values`() = assertPrints(
            """
            class Message {
                val text: String
                val active: Bool
            }
            fn main() {
                val message = Message(text = "Hello", active = False)
                print(message.text)
                print(message.active)
            }
            """,
            "Hello",
            "False",
        )

        @Test
        fun `field access can follow a constructor call`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn main(): Int {
                return Point(x = 4, y = 5).y
            }
            """,
            5,
        )

        @Test
        fun `class types work in local annotations and field access follows function calls`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn makePoint(): Point {
                return Point(x = 7, y = 8)
            }
            fn main(): Int {
                val point: Point = makePoint()
                return point.x + makePoint().y
            }
            """,
            15,
        )

        @Test
        fun `member access on non-object values`() = assertCompilationFails(
            function("val x = 1", "print(x.foo)"),
            JvmLoweringException.MemberAccessRequiresObject::class,
            "member access requires an object",
        )

        @Test
        fun `class constructors with the wrong arity`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point()"),
            JvmLoweringException.ClassConstructorArityMismatch::class,
            "class Point constructor requires 1 argument, got 0",
        )

        @Test
        fun `class constructor arguments with the wrong type`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point(\"wrong\")"),
            JvmLoweringException.ClassConstructorArgumentTypeMismatch::class,
            "class Point field x requires Int, got String",
        )

        @Test
        fun `unknown class fields`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1).y)"),
            JvmLoweringException.UnknownClassField::class,
            "class Point has no field y",
        )

        @Test
        fun `class types used as values`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point"),
            JvmLoweringException.ClassTypeUsedAsValue::class,
            "class type Point cannot be used as a value",
        )

        @Test
        fun `calling class fields`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1).x())"),
            JvmLoweringException.ValueCallNotSupported::class,
            "calling a value is not supported yet",
        )
    }

    @Nested
    inner class Methods {
        @Test
        fun `class methods can read fields implicitly`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int

                fn sum(): Int {
                    return x + y
                }
            }
            fn main(): Int {
                return Point(x = 1, y = 2).sum()
            }
            """,
            3,
        )

        @Test
        fun `class methods accept parameters and return objects`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int

                fn offset(dx: Int, dy: Int): Point {
                    return Point(x = x + dx, y = y + dy)
                }
            }
            fn main(): Int {
                val point = Point(x = 1, y = 2).offset(dx = 3, dy = 4)
                return point.x * 10 + point.y
            }
            """,
            46,
        )

        @Test
        fun `class methods can call sibling methods`() = assertReturns(
            """
            class Counter {
                val value: Int

                fn doubled(): Int {
                    return value * 2
                }

                fn quadrupled(): Int {
                    return doubled() * 2
                }
            }
            fn main(): Int {
                return Counter(3).quadrupled()
            }
            """,
            12,
        )

        @Test
        fun `class methods can call top-level functions and print`() = assertPrints(
            """
            fn increment(value: Int): Int {
                return value + 1
            }
            class Counter {
                val value: Int

                fn printNext() {
                    print(increment(value))
                }
            }
            fn main() {
                Counter(41).printNext()
            }
            """,
            "42",
        )

        @Test
        fun `class methods can be called on nested object values`() = assertReturns(
            """
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
            fn main(): Int {
                val line = Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4))
                return line.start.sum() + line.end.sum()
            }
            """,
            10,
        )

        @Test
        fun `class methods can call methods on field values`() = assertReturns(
            """
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
            fn main(): Int {
                return Box(Point(9)).value()
            }
            """,
            9,
        )

        @Test
        fun `class methods can be called on constructor results`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int

                fn withX(newX: Int): Point {
                    return Point(x = newX, y = y)
                }
            }
            fn main(): Int {
                return Point(x = 1, y = 2).withX(7).x
            }
            """,
            7,
        )

        @Test
        fun `class methods can use if expressions`() = assertReturns(
            """
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
            fn main(): Int {
                return Number(5).sign()
            }
            """,
            1,
        )

        @Test
        fun `class methods support string parameters and returns`() = assertPrints(
            """
            class Label {
                val text: String

                fn value(): String {
                    return text
                }

                fn echo(message: String): String {
                    return message
                }
            }
            fn main() {
                print(Label("hello").value())
                print(Label("unused").echo("world"))
            }
            """,
            "hello",
            "world",
        )

        @Test
        fun `class methods support bool parameters and returns`() = assertReturns(
            """
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
            fn main(): Int {
                return if Flag(True).select(True) and Flag(False).select(True) {
                    0
                } else {
                    1
                }
            }
            """,
            1,
        )

        @Test
        fun `class methods on field values accept arguments`() = assertReturns(
            """
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
            fn main(): Int {
                return Box(Point(x = 1, y = 2)).shifted(dx = 3, dy = 4)
            }
            """,
            46,
        )

        @Test
        fun `class methods accept object parameters`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int

                fn total(other: Point): Int {
                    return x + y + other.x + other.y
                }
            }
            fn main(): Int {
                return Point(x = 1, y = 2).total(Point(x = 3, y = 4))
            }
            """,
            10,
        )

        @Test
        fun `unknown class methods`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1).missing())"),
            JvmLoweringException.UnknownClassMethod::class,
            "class Point has no method missing",
        )

        @Test
        fun `class methods called with the wrong arity`() = assertCompilationFails(
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
        fun `class method arguments with the wrong type`() = assertCompilationFails(
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
        fun `class method return values with the wrong type`() = assertCompilationFails(
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
        fun `class methods used as values through member access`() = assertCompilationFails(
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
        fun `class methods used as bare values`() = assertCompilationFails(
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
            class Nothing has Shape {
                fn area(): Int = 0
            }
            fn describe(shape: Shape): Int = shape.area()
            fn main() {
                print(Empty)
                print(Empty == Empty)
                print("empty: \{Empty}")
                print(Geometry.square(3))
                print(describe(Nothing))
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
            val source = """
                        class Empty {}
                        fn first(): Empty = Empty
                        fn main() {
                        }
                    """.trimIndent()
            val program = compileProgram(source)
            val empty = program.loadClass("Empty")
            val first = program.loadProgramClass().getMethod("first")
            Assertions.assertSame(first.invoke(null), first.invoke(null))
            Assertions.assertSame(empty.getField("INSTANCE").get(null), first.invoke(null))
            Assertions.assertTrue(empty.declaredConstructors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        }

        @Test
        fun `constructing classes without fields`() = assertCompilationFails(
            classDeclaration("Empty") + function("val empty = Empty()"),
            JvmLoweringException.SingletonConstructed::class,
            "Empty has no fields, so it has a single value; write `Empty` instead of `Empty()`",
        )

        @Test
        fun `forwarding values into classes without fields`() = assertCompilationFails(
            classDeclaration("Empty") + function("val empty = 1 |> Empty"),
            JvmLoweringException.SingletonConstructed::class,
            "Empty has no fields, so it has a single value",
        )
    }
}
