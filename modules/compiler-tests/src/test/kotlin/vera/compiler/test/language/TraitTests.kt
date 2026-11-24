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

/** Traits: declarations, implementing classes, trait types, requirements, and required fields. */
class TraitTests {
    @Nested
    inner class Declarations {
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
        fun `explicit Unit return types on trait methods`() = assertCompilationFails(
            traitDeclaration("Resettable", "fn reset(): Unit"),
            VeraParseException.ExplicitUnitReturnType::class,
            "leave out the return type for a function that returns `Unit`",
        )

        @Test
        fun `Kotlin sealed traits`() = assertCompilationFails(
            "sealed trait Shape {}",
            VeraParseException.JavaSyntax::class,
            "traits are closed by default; remove `sealed`",
        )

        @Test
        fun `trait methods sharing a line`() = assertCompilationFails(
            "trait Shape { fn area(): Int fn perimeter(): Int }",
            VeraParseException.ExpectedEndOfLine::class,
            "expected end of line",
        )

        @Test
        fun `duplicate trait declarations`() = assertCompilationFails(
            traitDeclaration("Shape") + traitDeclaration("Shape"),
            JvmLoweringException.DuplicateTrait::class,
            "trait Shape is declared more than once",
        )

        @Test
        fun `traits conflicting with builtin traits`() = assertCompilationFails(
            traitDeclaration("Eq"),
            JvmLoweringException.NameConflictsWithBuiltinTrait::class,
            "name Eq is reserved for a built-in trait",
        )

        @Test
        fun `duplicate trait method parameters`() = assertCompilationFails(
            traitDeclaration("Shape", "fn scale(by: Int, by: Int): Int"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name by is already in scope",
        )

        @Test
        fun `duplicate trait methods`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int", "fn area(): Bool"),
            JvmLoweringException.DuplicateTraitMethod::class,
            "trait Shape has duplicate method area",
        )

        @Test
        fun `unknown trait method parameter types`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(value: Missing): Int"),
            JvmLoweringException.UnknownClassType::class,
            "class type Missing is not defined",
        )
    }

    @Nested
    inner class Implementing {
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
        fun `trait methods with renamed parameters`() = assertCompilationFails(
            traitDeclaration("Scaled", "fn scale(by: Int): Int") +
                "\nclass Counter has Scaled {\n    val value: Int\n" +
                "    fn scale(amount: Int): Int {\n        return value * amount\n    }\n}",
            JvmLoweringException.TraitMethodParameterNameMismatch::class,
            "class Counter method scale names a parameter amount, but trait Scaled names it by",
        )

        @Test
        fun `unknown traits`() = assertCompilationFails(
            "class Point has Missing {\n    val x: Int\n}",
            JvmLoweringException.UnknownTrait::class,
            "trait Missing is not defined",
        )

        @Test
        fun `trait methods that are not implemented`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int") +
                "\nclass Square has Shape {\n    val side: Int\n}",
            JvmLoweringException.MissingTraitMethod::class,
            "class Square has trait Shape but no method area",
        )

        @Test
        fun `trait methods with the wrong signature`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int") +
                "\nclass Square has Shape {\n    val side: Int\n" +
                "    fn area(): String {\n        return \"x\"\n    }\n}",
            JvmLoweringException.TraitMethodSignatureMismatch::class,
            "class Square method area does not match trait Shape: expected () -> Int, got () -> String",
        )

        @Test
        fun `traits listed more than once`() = assertCompilationFails(
            "class Point has Eq, Eq {\n    val x: Int\n}",
            JvmLoweringException.DuplicateTraitImplementation::class,
            "class Point has trait Eq more than once",
        )

        @Test
        fun `trailing commas in has clauses`() = assertCompilationFails(
            "class Point has Eq, {\n    val x: Int\n}",
            VeraParseException.ExpectedToken::class,
            "expected trait name",
        )

        @Test
        fun `empty has clauses`() = assertCompilationFails(
            "class Point has {\n    val x: Int\n}",
            VeraParseException.ExpectedToken::class,
            "expected trait name",
        )
    }

    @Nested
    inner class TraitTypes {
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
        fun `classes without the trait passed as a trait type`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int") + classDeclaration("Point", "val x: Int") +
                "fn describe(shape: Shape): Int = shape.area()\n" + function("print(describe(Point(1)))"),
            JvmLoweringException.FunctionArgumentTypeMismatch::class,
            "function describe parameter shape requires Shape, got Point",
        )

        @Test
        fun `members that the trait does not declare`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int") +
                "fn describe(shape: Shape): Int = shape.aera()\n",
            JvmLoweringException.UnknownTraitMethod::class,
            "trait Shape has no method aera; did you mean `area`?",
        )

        @Test
        fun `built-in traits used as types`() = assertCompilationFails(
            "fn show(value: Display) {\n}",
            JvmLoweringException.BuiltinTraitUsedAsType::class,
            "built-in trait Display cannot be used as a type",
        )
    }

    @Nested
    inner class Requirements {
        @Test
        fun `trait requirements let trait values be printed and compared`(testInfo: TestInfo) {
            val source = """
                        trait Shape requires Display, Eq {
                            fn area(): Int
                        }
                        class Square has Shape, Display, Eq {
                            val side: Int
                            fn area(): Int = side * side
                        }
                        class Rect has Shape, Display, Eq {
                            val width: Int
                            val height: Int
                            fn area(): Int = width * height
                        }
                        fn $TEST_MAIN() {
                            val first: Shape = Square(2)
                            val second: Shape = Rect(width = 1, height = 4)
                            print(first)
                            print("second: \{second}")
                            print(first == second)
                            print(first == Square(2))
                            print(first != second)
                        }
                    """.trimIndent()
            assertProgramPrints(
                source,
                "Square(side: 2)${EOL}second: Rect(width: 1, height: 4)${EOL}False${EOL}True${EOL}True$EOL",
                testInfo,
            )
        }

        @Test
        fun `trait cases get their trait's requirements automatically`(testInfo: TestInfo) {
            val source = """
                        trait Color requires Display, Eq { Red, Green }
                        fn $TEST_MAIN() {
                            print(Color.Red)
                            print(Color.Red == Color.Red)
                            val color: Color = Color.Green
                            print(color == Color.Red)
                            print("color: \{color}")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Color.Red${EOL}True${EOL}False${EOL}color: Color.Green$EOL", testInfo)
        }

        @Test
        fun `a trait value is a value of the traits it requires`(testInfo: TestInfo) {
            val source = """
                        trait Named {
                            fn name(): String
                        }
                        trait Pet requires Named {
                            fn sound(): String
                        }
                        class Dog has Pet, Named {
                            fn name(): String = "Rex"
                            fn sound(): String = "woof"
                        }
                        fn greet(named: Named) = print("hello \{named.name()}")
                        fn $TEST_MAIN() {
                            val pet: Pet = Dog
                            greet(pet)
                            print(pet.name())
                            print(pet.sound())
                        }
                    """.trimIndent()
            assertProgramPrints(source, "hello Rex${EOL}Rex${EOL}woof$EOL", testInfo)
        }

        @Test
        fun `Eq and Display classes can hold trait values whose trait requires them`(testInfo: TestInfo) {
            val source = """
                        trait Shape requires Display, Eq {}
                        class Square has Shape, Display, Eq {
                            val side: Int
                        }
                        class Holder has Display, Eq {
                            val shape: Shape
                        }
                        fn $TEST_MAIN() {
                            print(Holder(Square(2)))
                            print(Holder(Square(2)) == Holder(Square(2)))
                            print(Holder(Square(2)) == Holder(Square(3)))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Holder(shape: Square(side: 2))${EOL}True${EOL}False$EOL", testInfo)
        }

        @Test
        fun `trait-typed fields in Eq classes`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int") + "class Holder has Eq {\n    val shape: Shape\n}",
            JvmLoweringException.ClassFieldTraitTypeNotSupported::class,
            "class Holder has Eq, but field shape has trait type Shape, which does not require Eq",
        )

        @Test
        fun `printing trait-typed values`() = assertCompilationFails(
            traitDeclaration("Shape", "fn area(): Int") + "fn show(shape: Shape) = print(shape)\n",
            JvmLoweringException.TraitRequirementMissing::class,
            "trait Shape does not require Display, so its values cannot be printed; add `requires Display`",
        )

        @Test
        fun `classes missing a required trait`() = assertCompilationFails(
            "trait Shape requires Display {}\nclass Square has Shape {\n    val side: Int\n}",
            JvmLoweringException.MissingRequiredTrait::class,
            "Square has Shape, which requires Display; add `has Display`",
        )

        @Test
        fun `repeated requirements`() = assertCompilationFails(
            "trait Shape requires Display, Display {}",
            JvmLoweringException.DuplicateRequirement::class,
            "trait Shape requires Display more than once",
        )

        @Test
        fun `requirement cycles`() = assertCompilationFails(
            "trait First requires Second {}\ntrait Second requires First {}",
            JvmLoweringException.RequirementCycle::class,
            "trait requirements form a cycle: First requires Second requires First",
        )

        @Test
        fun `unknown required traits`() = assertCompilationFails(
            "trait Shape requires Displya {}",
            JvmLoweringException.UnknownTrait::class,
            "trait Displya is not defined; did you mean `Display`?",
        )

        @Test
        fun `comparing trait values whose trait does not require Eq`() = assertCompilationFails(
            traitDeclaration("Shape") + "fn same(first: Shape, second: Shape): Bool = first == second\n",
            JvmLoweringException.TraitRequirementMissing::class,
            "trait Shape does not require Eq, so its values cannot be compared; add `requires Eq`",
        )

        @Test
        fun `interpolating trait values whose trait does not require Display`() = assertCompilationFails(
            traitDeclaration("Shape") + "fn label(shape: Shape): String = \"\\{shape}\"\n",
            JvmLoweringException.TraitRequirementMissing::class,
            "trait Shape does not require Display, so its values cannot be used in string templates",
        )
    }

    @Nested
    inner class RequiredFields {
        @Test
        fun `trait values read the fields their trait requires`(testInfo: TestInfo) {
            val source = """
                        trait Event {
                            val timestamp: Int
                            fn describe(): String
                        }
                        class Login has Event {
                            val user: String
                            val timestamp: Int
                            fn describe(): String = "login \{user}"
                        }
                        class Logout has Event {
                            val timestamp: Int
                            fn describe(): String = "logout"
                        }
                        fn later(first: Event, second: Event): Event = if first.timestamp > second.timestamp {
                            first
                        } else {
                            second
                        }
                        fn $TEST_MAIN() {
                            val event = later(first = Login(user = "ada", timestamp = 3), second = Logout(5))
                            print(event.timestamp)
                            print(event.describe())
                        }
                    """.trimIndent()
            assertProgramPrints(source, "5${EOL}logout$EOL", testInfo)
        }

        @Test
        fun `trait cases declare the fields their trait requires`(testInfo: TestInfo) {
            val source = """
                        trait Shape {
                            val name: String
                            Circle {
                                val name: String
                                val radius: Int
                            }
                            Square {
                                val name: String
                                val side: Int
                            }
                        }
                        fn area(shape: Shape): Int = match shape {
                            Circle(radius) -> 3 * radius * radius
                            Square(name, side) -> side * side
                        }
                        fn $TEST_MAIN() {
                            val shape: Shape = Shape.Square(name = "tile", side = 2)
                            print("\{shape.name}: \{area(shape)}")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "tile: 4$EOL", testInfo)
        }

        @Test
        fun `trait values read the fields of the traits their trait requires`(testInfo: TestInfo) {
            val source = """
                        trait Named {
                            val name: String
                        }
                        trait Pet requires Named {
                            val age: Int
                        }
                        class Dog has Pet, Named {
                            val name: String
                            val age: Int
                        }
                        fn $TEST_MAIN() {
                            val pet: Pet = Dog(name = "Rex", age = 3)
                            print("\{pet.name} is \{pet.age}")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Rex is 3$EOL", testInfo)
        }

        @Test
        fun `trait fields without val`() = assertCompilationFails(
            "trait Event {\n    timestamp: Int\n}",
            VeraParseException.FieldWithoutVal::class,
            "declare fields with `val`: write `val timestamp: ...`",
        )

        @Test
        fun `mutable trait fields`() = assertCompilationFails(
            "trait Account {\n    mut val balance: Int\n}",
            VeraParseException.MutableFieldNotSupported::class,
            "mutable fields need `mut fn` methods, which are not implemented yet; use `val`",
        )

        @Test
        fun `classes missing a field their trait requires`() = assertCompilationFails(
            "trait Event {\n    val timestamp: Int\n}\nclass Login has Event {\n    val user: String\n}",
            JvmLoweringException.MissingTraitField::class,
            "class Login has trait Event but no field timestamp; declare `val timestamp: Int`",
        )

        @Test
        fun `trait cases without a field their trait requires`() = assertCompilationFails(
            "trait Shape {\n    val name: String\n    Empty\n}",
            JvmLoweringException.MissingTraitField::class,
            "class Shape.Empty has trait Shape but no field name; declare `val name: String`",
        )

        @Test
        fun `required fields declared with another type`() = assertCompilationFails(
            "trait Event {\n    val timestamp: Int\n}\nclass Login has Event {\n    val timestamp: String\n}",
            JvmLoweringException.TraitFieldTypeMismatch::class,
            "class Login field timestamp has type String, but trait Event requires Int",
        )

        @Test
        fun `duplicate trait fields`() = assertCompilationFails(
            "trait Event {\n    val timestamp: Int\n    val timestamp: Int\n}",
            JvmLoweringException.DuplicateTraitField::class,
            "trait Event has duplicate field timestamp",
        )

        @Test
        fun `trait fields named like trait methods`() = assertCompilationFails(
            "trait Event {\n    val timestamp: Int\n    fn timestamp(): Int\n}",
            JvmLoweringException.TraitFieldConflictsWithMethod::class,
            "trait Event has both a field and a method named timestamp",
        )

        @Test
        fun `unknown fields on trait values`() = assertCompilationFails(
            "trait Event {\n    val timestamp: Int\n}\n" + "fn read(event: Event): Int = event.timestmap\n",
            JvmLoweringException.UnknownTraitField::class,
            "trait Event has no field timestmap; did you mean `timestamp`?",
        )

        @Test
        fun `required fields called like methods`() = assertCompilationFails(
            "trait Event {\n    val timestamp: Int\n}\n" + "fn read(event: Event): Int = event.timestamp()\n",
            JvmLoweringException.ValueCallNotSupported::class,
            "calling a value is not supported yet",
        )
    }

    @Nested
    inner class Jvm {
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
        fun `required traits become superinterfaces on the JVM`() {
            val source = """
                        trait Named {
                            fn name(): String
                        }
                        trait Pet requires Named, Display {}
                        class Dog has Pet, Named, Display {
                            fn name(): String = "Rex"
                        }
                        fn $TEST_MAIN() {
                        }
                    """.trimIndent()
            val bytecode = VeraCompiler.compile(Identifier("RequirementTest"), source)
            val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
            val named = classLoader.loadClass("Named")
            val pet = classLoader.loadClass("Pet")
            Assertions.assertEquals(listOf(named), pet.interfaces.toList())
            Assertions.assertEquals(setOf("Pet", "Dog"), named.permittedSubclasses.map { it.name }.toSet())
            Assertions.assertTrue(pet.declaredMethods.any { it.name == "toString" })
        }

        @Test
        fun `required fields compile to accessor methods on the JVM`() {
            val source = """
                        trait Event {
                            val timestamp: Int
                        }
                        class Login has Event {
                            val timestamp: Int
                        }
                        fn $TEST_MAIN() {
                        }
                    """.trimIndent()
            val bytecode = VeraCompiler.compile(Identifier("FieldAccessorTest"), source)
            val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
            val event = classLoader.loadClass("Event")
            val login = classLoader.loadClass("Login")
            Assertions.assertEquals(Int::class.javaPrimitiveType, event.getMethod("timestamp").returnType)
            val constructor = login.declaredConstructors.single()
            val loginObject = constructor.newInstance(7)
            Assertions.assertEquals(7, login.getMethod("timestamp").invoke(loginObject))
        }
    }
}
