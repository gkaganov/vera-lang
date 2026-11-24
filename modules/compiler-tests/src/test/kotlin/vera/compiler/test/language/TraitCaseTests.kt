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

/** Trait cases declared in a trait's block. */
class TraitCaseTests {
    @Nested
    inner class Cases {
        @Test
        fun `trait blocks declare constant cases that match unqualified`(testInfo: TestInfo) {
            val source = """
                        trait Color { Red, Green, Blue }
                        fn name(color: Color): String = match color {
                            Red -> "red"
                            Green -> "green"
                            Blue -> "blue"
                        }
                        fn $TEST_MAIN() {
                            print(name(Color.Green))
                            val color: Color = Color.Red
                            print(name(color))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "green${EOL}red$EOL", testInfo)
        }

        @Test
        fun `trait cases can have fields, methods, and traits`(testInfo: TestInfo) {
            val source = """
                        trait Shape {
                            fn area(): Int
                            Circle has Display {
                                val radius: Int
                                fn area(): Int = 3 * radius * radius
                            }
                            Empty has Display {
                                fn area(): Int = 0
                            }
                        }
                        fn radiusOf(shape: Shape): Int = match shape {
                            Circle(radius) -> radius
                            Empty -> 0
                        }
                        fn $TEST_MAIN() {
                            val circle: Shape.Circle = Shape.Circle(2)
                            print(circle)
                            print(circle.area())
                            print(Shape.Empty)
                            print(radiusOf(circle))
                            val shape: Shape = Shape.Empty
                            print(shape.area())
                            print(3 |> Shape.Circle() |> radiusOf)
                        }
                    """.trimIndent()
            assertProgramPrints(
                source,
                "Shape.Circle(radius: 2)${EOL}12${EOL}Shape.Empty${EOL}2${EOL}0${EOL}3$EOL",
                testInfo,
            )
        }

        @Test
        fun `recursive trait cases coexist with a top-level class of the same name`(testInfo: TestInfo) {
            val source = """
                        class Leaf {
                            val color: String
                        }
                        trait Tree {
                            Leaf
                            Node {
                                val left: Tree
                                val right: Tree
                            }
                        }
                        fn count(tree: Tree): Int = match tree {
                            Leaf -> 1
                            Node(left, right) -> count(left) + count(right)
                        }
                        fn $TEST_MAIN() {
                            val tree = Tree.Node(left = Tree.Leaf, right = Tree.Node(left = Tree.Leaf, right = Tree.Leaf))
                            print(count(tree))
                            print(Leaf("green").color)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "3${EOL}green$EOL", testInfo)
        }

        @Test
        fun `trait cases with traits but no body`() = assertCompilationFails(
            "trait Printable {\n    fn label(): String\n}\ntrait Color { Red has Printable, Green }",
            VeraParseException.CaseTraitsWithoutBody::class,
            "a case with `has` needs a body to end its list: write `Red has ... { }`, found '}'",
        )

        @Test
        fun `classes joining a trait that lists its cases`() = assertCompilationFails(
            "trait Color { Red }\nclass Teal has Color {}",
            JvmLoweringException.BlockTraitJoined::class,
            "Color lists its cases inside it; add Teal there instead of joining with `has`",
        )

        @Test
        fun `unknown trait cases`() = assertCompilationFails(
            "trait Color { Red, Green }\n" + function("val color = Color.Rde"),
            JvmLoweringException.UnknownTraitCase::class,
            "trait Color has no case Rde; did you mean `Red`?",
        )

        @Test
        fun `unqualified trait cases`() = assertCompilationFails(
            "trait Color { Red, Green }\n" + function("val color = Red"),
            JvmLoweringException.UnknownVariable::class,
            "variable Red is not defined; a case is written with its trait: `Color.Red`",
        )

        @Test
        fun `unqualified trait case construction`() = assertCompilationFails(
            "trait Shape {\n    Circle {\n        val radius: Int\n    }\n}\n" + function("val shape = Circle(1)"),
            JvmLoweringException.UnknownFunction::class,
            "function Circle is not defined; a case is written with its trait: `Shape.Circle`",
        )

        @Test
        fun `trait cases missing a trait method`() = assertCompilationFails(
            "trait Shape {\n    fn area(): Int\n    Empty\n}",
            JvmLoweringException.MissingTraitMethod::class,
            "class Shape.Empty has trait Shape but no method area",
        )

        @Test
        fun `duplicate trait cases`() = assertCompilationFails(
            "trait Color { Red, Red }",
            JvmLoweringException.DuplicateClassType::class,
            "Color.Red is declared more than once",
        )

        @Test
        fun `trait cases with fields used as values`() = assertCompilationFails(
            "trait Shape {\n    Circle {\n        val radius: Int\n    }\n}\n" + function("val shape = Shape.Circle"),
            JvmLoweringException.ClassTypeUsedAsValue::class,
            "class type Shape.Circle cannot be used as a value",
        )
    }

    @Nested
    inner class Matching {
        @Test
        fun `owned cases can join other traits and match qualified there`(testInfo: TestInfo) {
            val source = """
                        trait Printable {
                            fn label(): String
                        }
                        trait Color {
                            Red has Printable {
                                fn label(): String = "red"
                            }
                            Green
                        }
                        class Invoice has Printable {
                            val total: Int
                            fn label(): String = "invoice \{total}"
                        }
                        fn kind(item: Printable): String = match item {
                            Color.Red -> "a color"
                            Invoice(total) -> "an invoice of \{total}"
                        }
                        fn $TEST_MAIN() {
                            print(kind(Color.Red))
                            print(kind(Invoice(7)))
                            val item: Printable = Color.Red
                            print(item.label())
                        }
                    """.trimIndent()
            assertProgramPrints(source, "a color${EOL}an invoice of 7${EOL}red$EOL", testInfo)
        }

        @Test
        fun `match on a trait's cases that misses one`() = assertCompilationFails(
            "trait Color { Red, Blue }\n" + "fn name(color: Color): Int = match color {\n    Red -> 1\n}",
            JvmLoweringException.MatchNotExhaustive::class,
            "match on Color does not cover Color.Blue",
        )
    }

    @Nested
    inner class Jvm {
        @Test
        fun `trait cases compile to nested JVM classes`() {
            val source = """
                        trait Shape {
                            Circle {
                                val radius: Int
                            }
                            Empty
                        }
                        fn $TEST_MAIN() {
                        }
                    """.trimIndent()
            val bytecode = VeraCompiler.compile(Identifier("NestedCaseTest"), source)
            val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
            val shape = classLoader.loadClass("Shape")
            val circle = classLoader.loadClass("Shape\$Circle")
            Assertions.assertEquals("Circle", circle.simpleName)
            Assertions.assertEquals(shape, circle.declaringClass)
            Assertions.assertTrue(shape.isSealed)
            Assertions.assertEquals(setOf("Shape\$Circle", "Shape\$Empty"), shape.permittedSubclasses.map { it.name }.toSet())
        }
    }
}
