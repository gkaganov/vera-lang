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

/** Structural interfaces and `implements`. */
class InterfaceTests {
    @Nested
    inner class Declarations {
        @Test
        fun `cases in interfaces`() = assertCompilationFails(
            "interface Named { Red }",
            VeraParseException.InterfaceCase::class,
            "interfaces are open and cannot list cases; declare a `trait` for a closed set of cases",
        )

        @Test
        fun `sealed interfaces`() = assertCompilationFails(
            "sealed interface Named {}",
            VeraParseException.JavaSyntax::class,
            "interfaces are open; declare a `trait` for a closed set of classes",
        )

        @Test
        fun `duplicate interfaces`() = assertCompilationFails(
            "interface Named {}\ninterface Named {}",
            JvmLoweringException.DuplicateTrait::class,
            "interface Named is declared more than once",
        )
    }

    @Nested
    inner class StructuralFit {
        @Test
        fun `classes fit interfaces by their members without stating them`(testInfo: TestInfo) {
            val source = """
                        interface Named {
                            fn name(): String
                        }
                        class Dog {
                            fn name(): String = "Rex"
                        }
                        class User {
                            val login: String
                            fn name(): String = login
                        }
                        fn greet(named: Named) = print("hello \{named.name()}")
                        fn $TEST_MAIN() {
                            greet(Dog)
                            greet(User("ada"))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "hello Rex${EOL}hello ada$EOL", testInfo)
        }

        @Test
        fun `interfaces read the fields they require`(testInfo: TestInfo) {
            val source = """
                        interface Timed {
                            val timestamp: Int
                        }
                        class Login {
                            val user: String
                            val timestamp: Int
                        }
                        class Tick {
                            val timestamp: Int
                        }
                        fn age(timed: Timed, now: Int): Int = now - timed.timestamp
                        fn $TEST_MAIN() {
                            print(age(timed = Login(user = "ada", timestamp = 3), now = 10))
                            print(age(timed = Tick(8), now = 10))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "7${EOL}2$EOL", testInfo)
        }

        @Test
        fun `trait values fit interfaces whose members their trait has`(testInfo: TestInfo) {
            val source = """
                        interface Named {
                            fn name(): String
                        }
                        trait Labeled {
                            fn name(): String
                        }
                        trait Pet requires Labeled {
                            fn sound(): String
                        }
                        class Dog has Pet, Labeled {
                            fn name(): String = "Rex"
                            fn sound(): String = "woof"
                        }
                        fn greet(named: Named) = print("hello \{named.name()}")
                        fn $TEST_MAIN() {
                            val pet: Pet = Dog
                            greet(pet)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "hello Rex$EOL", testInfo)
        }

        @Test
        fun `branches still share their trait when an interface fits them too`(testInfo: TestInfo) {
            val source = """
                        interface Anything {}
                        trait Shape {
                            fn area(): Int
                        }
                        class Square has Shape {
                            val side: Int
                            fn area(): Int = side * side
                        }
                        class Empty has Shape {
                            fn area(): Int = 0
                        }
                        fn pick(big: Bool): Shape = if big { Square(3) } else { Empty }
                        fn $TEST_MAIN() {
                            print(pick(True).area())
                            val anything: Anything = pick(False)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "9$EOL", testInfo)
        }

        @Test
        fun `parameter names are part of an interface's methods`(testInfo: TestInfo) {
            val source = """
                        interface Scaled {
                            fn scale(by: Int): Int
                        }
                        class Doubler {
                            fn scale(by: Int): Int = by * 2
                        }
                        class Counter {
                            fn scale(factor: Int): Int = factor
                        }
                        fn $TEST_MAIN() {
                            val scaled: Scaled = Doubler
                            print(scaled.scale(4))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "8$EOL", testInfo)
            val bytecode = VeraCompiler.compile(Identifier("ParameterNameTest"), source)
            val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
            val scaled = classLoader.loadClass("Scaled")
            Assertions.assertEquals(listOf(scaled), classLoader.loadClass("Doubler").interfaces.toList())
            Assertions.assertEquals(emptyList<Class<*>>(), classLoader.loadClass("Counter").interfaces.toList())
        }

        @Test
        fun `classes passed where an interface they do not fit is expected`() = assertCompilationFails(
            "interface Named {\n    fn name(): String\n}\nclass Dog {}\n" +
                "fn greet(named: Named) = print(named.name())\n" + function("greet(Dog)"),
            JvmLoweringException.FunctionArgumentTypeMismatch::class,
            "function greet parameter named requires Named, got Dog",
        )
    }

    @Nested
    inner class Implements {
        @Test
        fun `classes and trait cases can state the interfaces they implement`(testInfo: TestInfo) {
            val source = """
                        interface Named {
                            fn name(): String
                        }
                        trait Shape {
                            fn area(): Int
                            Square implements Named {
                                val side: Int
                                fn area(): Int = side * side
                                fn name(): String = "square"
                            }
                        }
                        trait Round {}
                        class Circle has Round implements Named {
                            val radius: Int
                            fn name(): String = "circle"
                        }
                        fn describe(named: Named) = print(named.name())
                        fn $TEST_MAIN() {
                            describe(Shape.Square(2))
                            describe(Circle(1))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "square${EOL}circle$EOL", testInfo)
        }

        @Test
        fun `has written after implements`() = assertCompilationFails(
            "class Square implements Named has Shape {\n}",
            VeraParseException.JavaSyntax::class,
            "write `has` before `implements`: `has Trait implements Interface`",
        )

        @Test
        fun `trait cases with interfaces but no body`() = assertCompilationFails(
            "interface Named {}\ntrait Color { Red implements Named, Green }",
            VeraParseException.CaseTraitsWithoutBody::class,
            "a case with `implements` needs a body to end its list: write `Red implements ... { }`, found '}'",
        )

        @Test
        fun `interfaces joined with has`() = assertCompilationFails(
            "interface Named {}\nclass Dog has Named {}",
            JvmLoweringException.InterfaceJoinedWithHas::class,
            "Named is an interface, which Dog fits by having its members; write `implements Named` to check that, not `has`",
        )

        @Test
        fun `traits named with implements`() = assertCompilationFails(
            "trait Shape {}\nclass Square implements Shape {}",
            JvmLoweringException.TraitImplemented::class,
            "Shape is a trait, not an interface; write `Square has Shape`",
        )

        @Test
        fun `built-in traits named with implements`() = assertCompilationFails(
            "class Square implements Eq {}",
            JvmLoweringException.TraitImplemented::class,
            "Eq is a trait, not an interface; write `Square has Eq`",
        )

        @Test
        fun `unknown stated interfaces`() = assertCompilationFails(
            "interface Named {}\nclass Dog implements Nmaed {}",
            JvmLoweringException.UnknownInterface::class,
            "interface Nmaed is not defined; did you mean `Named`?",
        )

        @Test
        fun `interfaces stated twice`() = assertCompilationFails(
            "interface Named {}\nclass Dog implements Named, Named {}",
            JvmLoweringException.DuplicateTraitImplementation::class,
            "class Dog implements interface Named more than once",
        )

        @Test
        fun `stated interfaces without a required method`() = assertCompilationFails(
            "interface Named {\n    fn name(): String\n}\nclass Dog implements Named {}",
            JvmLoweringException.MissingTraitMethod::class,
            "class Dog implements interface Named but has no method name",
        )

        @Test
        fun `stated interfaces without a required field`() = assertCompilationFails(
            "interface Timed {\n    val timestamp: Int\n}\nclass Tick implements Timed {\n    val time: Int\n}",
            JvmLoweringException.MissingTraitField::class,
            "class Tick implements interface Timed but has no field timestamp; declare `val timestamp: Int`",
        )

        @Test
        fun `stated interfaces with renamed parameters`() = assertCompilationFails(
            "interface Scaled {\n    fn scale(by: Int): Int\n}\n" +
                "class Counter implements Scaled {\n    fn scale(factor: Int): Int = factor\n}",
            JvmLoweringException.TraitMethodParameterNameMismatch::class,
            "class Counter method scale names a parameter factor, but interface Scaled names it by; " +
                "parameter names are part of the interface",
        )

        @Test
        fun `stated interfaces whose required built-in trait is missing`() = assertCompilationFails(
            "interface Named requires Display {}\nclass Dog implements Named {}",
            JvmLoweringException.MissingRequiredTrait::class,
            "Dog implements Named, which requires Display; add `has Display`",
        )
    }

    @Nested
    inner class Requirements {
        @Test
        fun `interfaces can require interfaces and built-in traits`(testInfo: TestInfo) {
            val source = """
                        interface Named requires Display {
                            fn name(): String
                        }
                        interface Pet requires Named, Eq {
                            fn sound(): String
                        }
                        class Dog has Display, Eq {
                            val age: Int
                            fn name(): String = "Rex"
                            fn sound(): String = "woof"
                        }
                        fn introduce(named: Named) = print("\{named.name()} is \{named}")
                        fn $TEST_MAIN() {
                            val pet: Pet = Dog(3)
                            introduce(pet)
                            print(pet.sound())
                            print(pet == Dog(3))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Rex is Dog(age: 3)${EOL}woof${EOL}True$EOL", testInfo)
        }

        @Test
        fun `interfaces requiring traits`() = assertCompilationFails(
            "trait Shape {}\ninterface Named requires Shape {}",
            JvmLoweringException.InterfaceRequiresTrait::class,
            "interface Named cannot require trait Shape, because any class can fit an interface but only " +
                "Shape's classes have it; require an interface instead",
        )

        @Test
        fun `traits requiring interfaces`() = assertCompilationFails(
            "interface Named {}\ntrait Shape requires Named {}",
            JvmLoweringException.TraitRequiresInterface::class,
            "trait Shape cannot require interface Named; Shape values fit Named automatically when Shape has its members",
        )

        @Test
        fun `unknown required interfaces`() = assertCompilationFails(
            "interface Named {}\ninterface Pet requires Nmaed {}",
            JvmLoweringException.UnknownInterface::class,
            "interface Nmaed is not defined; did you mean `Named`?",
        )

        @Test
        fun `interface requirement cycles`() = assertCompilationFails(
            "interface First requires Second {}\ninterface Second requires First {}",
            JvmLoweringException.RequirementCycle::class,
            "interface requirements form a cycle: First requires Second requires First",
        )
    }

    @Nested
    inner class Values {
        @Test
        fun `match on interface values`() = assertCompilationFails(
            "interface Named {}\nclass Dog {}\nfn kind(named: Named): Int = match named {\n    Dog -> 1\n}",
            JvmLoweringException.MatchOnInterface::class,
            "interface Named cannot be matched, because any class with its members fits it; use its members, or match on a trait",
        )

        @Test
        fun `printing interface values whose interface does not require Display`() = assertCompilationFails(
            "interface Named {}\nfn show(named: Named) = print(named)\n",
            JvmLoweringException.TraitRequirementMissing::class,
            "interface Named does not require Display, so its values cannot be printed; add `requires Display`",
        )

        @Test
        fun `unknown methods on interface values`() = assertCompilationFails(
            "interface Named {\n    fn name(): String\n}\nfn read(named: Named): String = named.nmae()\n",
            JvmLoweringException.UnknownTraitMethod::class,
            "interface Named has no method nmae; did you mean `name`?",
        )
    }

    @Nested
    inner class Jvm {
        @Test
        fun `interfaces compile to open JVM interfaces implemented by every fitting class`() {
            val source = """
                        interface Named {
                            fn name(): String
                        }
                        trait Pet {
                            fn name(): String
                        }
                        class Dog has Pet {
                            fn name(): String = "Rex"
                        }
                        fn $TEST_MAIN() {
                        }
                    """.trimIndent()
            val bytecode = VeraCompiler.compile(Identifier("InterfaceTest"), source)
            val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
            val named = classLoader.loadClass("Named")
            val pet = classLoader.loadClass("Pet")
            val dog = classLoader.loadClass("Dog")
            Assertions.assertTrue(named.isInterface)
            Assertions.assertFalse(named.isSealed)
            Assertions.assertEquals(listOf(named), pet.interfaces.toList())
            Assertions.assertEquals(setOf(pet, named), dog.interfaces.toSet())
        }
    }
}
