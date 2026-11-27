package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.compileProgram
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** Structural interfaces and `implements`. */
class InterfaceTests {
    @Nested
    inner class Declarations {
        @Test
        fun `rejects cases in interfaces`() = assertCompilationFails(
            "interface Named { Red }",
            VeraParseException.InterfaceCase::class,
            "interfaces are open and cannot list cases; declare a `trait` for a closed set of cases",
        )

        @Test
        fun `rejects sealed interfaces`() = assertCompilationFails(
            "sealed interface Named {}",
            VeraParseException.JavaSyntax::class,
            "interfaces are open; declare a `trait` for a closed set of classes",
        )

        @Test
        fun `rejects duplicate interfaces`() = assertCompilationFails(
            "interface Named {}\ninterface Named {}",
            JvmLoweringException.DuplicateTrait::class,
            "interface Named is declared more than once",
        )
    }

    @Nested
    inner class StructuralFit {
        @Test
        fun `classes fit interfaces by their members without stating them`() = assertPrints(
            """
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
            fn main() {
                greet(Dog)
                greet(User("ada"))
            }
            """,
            "hello Rex",
            "hello ada",
        )

        @Test
        fun `interfaces read the fields they require`() = assertPrints(
            """
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
            fn main() {
                print(age(timed = Login(user = "ada", timestamp = 3), now = 10))
                print(age(timed = Tick(8), now = 10))
            }
            """,
            "7",
            "2",
        )

        @Test
        fun `trait values fit interfaces whose members their trait has`() = assertPrints(
            """
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
            fn main() {
                val pet: Pet = Dog
                greet(pet)
            }
            """,
            "hello Rex",
        )

        @Test
        fun `branches still share their trait when an interface fits them too`() = assertPrints(
            """
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
            fn main() {
                print(pick(True).area())
                val anything: Anything = pick(False)
            }
            """,
            "9",
        )

        @Test
        fun `parameter names are part of an interface's methods`() {
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
                fn main() {
                    val scaled: Scaled = Doubler
                    print(scaled.scale(4))
                }
                """
            assertPrints(source, "8")
            val program = compileProgram(source)
            val scaled = program.loadClass("Scaled")
            Assertions.assertEquals(listOf(scaled), program.loadClass("Doubler").interfaces.toList())
            Assertions.assertEquals(emptyList<Class<*>>(), program.loadClass("Counter").interfaces.toList())
        }

        @Test
        fun `rejects classes passed where an interface they do not fit is expected`() = assertCompilationFails(
            "interface Named {\n    fn name(): String\n}\nclass Dog {}\n" +
                "fn greet(named: Named) = print(named.name())\n" + function("greet(Dog)"),
            JvmLoweringException.FunctionArgumentTypeMismatch::class,
            "function greet parameter named requires Named, got Dog",
        )
    }

    @Nested
    inner class Implements {
        @Test
        fun `classes and trait cases can state the interfaces they implement`() = assertPrints(
            """
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
            fn main() {
                describe(Shape.Square(2))
                describe(Circle(1))
            }
            """,
            "square",
            "circle",
        )

        @Test
        fun `rejects has written after implements`() = assertCompilationFails(
            "class Square implements Named has Shape {\n}",
            VeraParseException.JavaSyntax::class,
            "write `has` before `implements`: `has Trait implements Interface`",
        )

        @Test
        fun `rejects trait cases with interfaces but no body`() = assertCompilationFails(
            "interface Named {}\ntrait Color { Red implements Named, Green }",
            VeraParseException.CaseTraitsWithoutBody::class,
            "a case with `implements` needs a body to end its list: write `Red implements ... { }`, found '}'",
        )

        @Test
        fun `rejects interfaces joined with has`() = assertCompilationFails(
            "interface Named {}\nclass Dog has Named {}",
            JvmLoweringException.InterfaceJoinedWithHas::class,
            "Named is an interface, which Dog fits by having its members; write `implements Named` to check that, not `has`",
        )

        @Test
        fun `rejects traits named with implements`() = assertCompilationFails(
            "trait Shape {}\nclass Square implements Shape {}",
            JvmLoweringException.TraitImplemented::class,
            "Shape is a trait, not an interface; write `Square has Shape`",
        )

        @Test
        fun `rejects built-in traits named with implements`() = assertCompilationFails(
            "class Square implements Eq {}",
            JvmLoweringException.TraitImplemented::class,
            "Eq is a trait, not an interface; write `Square has Eq`",
        )

        @Test
        fun `rejects unknown stated interfaces`() = assertCompilationFails(
            "interface Named {}\nclass Dog implements Nmaed {}",
            JvmLoweringException.UnknownInterface::class,
            "interface Nmaed is not defined; did you mean `Named`?",
        )

        @Test
        fun `rejects interfaces stated twice`() = assertCompilationFails(
            "interface Named {}\nclass Dog implements Named, Named {}",
            JvmLoweringException.DuplicateTraitImplementation::class,
            "class Dog implements interface Named more than once",
        )

        @Test
        fun `rejects stated interfaces without a required method`() = assertCompilationFails(
            "interface Named {\n    fn name(): String\n}\nclass Dog implements Named {}",
            JvmLoweringException.MissingTraitMethod::class,
            "class Dog implements interface Named but has no method name",
        )

        @Test
        fun `rejects stated interfaces without a required field`() = assertCompilationFails(
            "interface Timed {\n    val timestamp: Int\n}\nclass Tick implements Timed {\n    val time: Int\n}",
            JvmLoweringException.MissingTraitField::class,
            "class Tick implements interface Timed but has no field timestamp; declare `val timestamp: Int`",
        )

        @Test
        fun `rejects stated interfaces with renamed parameters`() = assertCompilationFails(
            "interface Scaled {\n    fn scale(by: Int): Int\n}\n" +
                "class Counter implements Scaled {\n    fn scale(factor: Int): Int = factor\n}",
            JvmLoweringException.TraitMethodParameterNameMismatch::class,
            "class Counter method scale names a parameter factor, but interface Scaled names it by; " +
                "parameter names are part of the interface",
        )

        @Test
        fun `rejects stated interfaces whose required built-in trait is missing`() = assertCompilationFails(
            "interface Named requires Display {}\nclass Dog implements Named {}",
            JvmLoweringException.MissingRequiredTrait::class,
            "Dog implements Named, which requires Display; add `has Display`",
        )
    }

    @Nested
    inner class Requirements {
        @Test
        fun `interfaces can require interfaces and built-in traits`() = assertPrints(
            """
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
            fn main() {
                val pet: Pet = Dog(3)
                introduce(pet)
                print(pet.sound())
                print(pet == Dog(3))
            }
            """,
            "Rex is Dog(age: 3)",
            "woof",
            "True",
        )

        @Test
        fun `rejects interfaces requiring traits`() = assertCompilationFails(
            "trait Shape {}\ninterface Named requires Shape {}",
            JvmLoweringException.InterfaceRequiresTrait::class,
            "interface Named cannot require trait Shape, because any class can fit an interface but only " +
                "Shape's classes have it; require an interface instead",
        )

        @Test
        fun `rejects traits requiring interfaces`() = assertCompilationFails(
            "interface Named {}\ntrait Shape requires Named {}",
            JvmLoweringException.TraitRequiresInterface::class,
            "trait Shape cannot require interface Named; Shape values fit Named automatically when Shape has its members",
        )

        @Test
        fun `rejects unknown required interfaces`() = assertCompilationFails(
            "interface Named {}\ninterface Pet requires Nmaed {}",
            JvmLoweringException.UnknownInterface::class,
            "interface Nmaed is not defined; did you mean `Named`?",
        )

        @Test
        fun `rejects interface requirement cycles`() = assertCompilationFails(
            "interface First requires Second {}\ninterface Second requires First {}",
            JvmLoweringException.RequirementCycle::class,
            "interface requirements form a cycle: First requires Second requires First",
        )
    }

    @Nested
    inner class Values {
        @Test
        fun `rejects match on interface values`() = assertCompilationFails(
            "interface Named {}\nclass Dog {}\nfn kind(named: Named): Int = match named {\n    Dog -> 1\n}",
            JvmLoweringException.MatchOnInterface::class,
            "interface Named cannot be matched, because any class with its members fits it; use its members, or match on a trait",
        )

        @Test
        fun `rejects printing interface values whose interface does not require Display`() = assertCompilationFails(
            "interface Named {}\nfn show(named: Named) = print(named)\n",
            JvmLoweringException.TraitRequirementMissing::class,
            "interface Named does not require Display, so its values cannot be printed; add `requires Display`",
        )

        @Test
        fun `rejects unknown methods on interface values`() = assertCompilationFails(
            "interface Named {\n    fn name(): String\n}\nfn read(named: Named): String = named.nmae()\n",
            JvmLoweringException.UnknownTraitMethod::class,
            "interface Named has no method nmae; did you mean `name`?",
        )
    }

    @Nested
    inner class Jvm {
        @Test
        fun `interfaces compile to open JVM interfaces implemented by every fitting class`() {
            val program = compileProgram(
                """
                interface Named {
                    fn name(): String
                }
                trait Pet {
                    fn name(): String
                }
                class Dog has Pet {
                    fn name(): String = "Rex"
                }
                fn main() {
                }
                """,
            )
            val named = program.loadClass("Named")
            val pet = program.loadClass("Pet")
            val dog = program.loadClass("Dog")
            Assertions.assertTrue(named.isInterface)
            Assertions.assertFalse(named.isSealed)
            Assertions.assertEquals(listOf(named), pet.interfaces.toList())
            Assertions.assertEquals(setOf(pet, named), dog.interfaces.toSet())
        }
    }
}
