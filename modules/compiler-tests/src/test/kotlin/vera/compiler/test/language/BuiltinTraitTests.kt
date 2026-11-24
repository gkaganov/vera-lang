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

/** The built-in traits `Eq` and `Display`. */
class BuiltinTraitTests {
    @Nested
    inner class Eq {
        @Test
        fun `objects with Eq compare structurally`(testInfo: TestInfo) {
            val source = """
                        class Point has Eq {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN() {
                            print(Point(x = 1, y = 2) == Point(x = 1, y = 2))
                            print(Point(x = 1, y = 2) == Point(x = 1, y = 3))
                            print(Point(x = 1, y = 2) != Point(x = 1, y = 3))
                            print(Point(x = 1, y = 2) != Point(x = 1, y = 2))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False$EOL", testInfo)
        }

        @Test
        fun `nested objects with Eq compare structurally`(testInfo: TestInfo) {
            val source = """
                        class Point has Eq {
                            val x: Int
                            val y: Int
                        }
                        class Line has Eq {
                            val start: Point
                            val end: Point
                        }
                        fn $TEST_MAIN() {
                            print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) == Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)))
                            print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) == Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 5)))
                            print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)) != Line(start = Point(x = 0, y = 2), end = Point(x = 3, y = 4)))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True$EOL", testInfo)
        }

        @Test
        fun `Eq compares String and Bool fields by value`(testInfo: TestInfo) {
            val source = """
                        class Label has Eq {
                            val text: String
                            val active: Bool
                        }
                        fn $TEST_MAIN() {
                            print(Label(text = "same", active = True) == Label(text = "same", active = True))
                            print(Label(text = "same", active = True) == Label(text = "other", active = True))
                            print(Label(text = "same", active = True) != Label(text = "same", active = False))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True$EOL", testInfo)
        }

        @Test
        fun `objects with Eq and no fields are equal`(testInfo: TestInfo) {
            val source = """
                        class Empty has Eq {
                        }
                        fn $TEST_MAIN() {
                            print(Empty == Empty)
                            print(Empty != Empty)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False$EOL", testInfo)
        }

        @Test
        fun `object equality works on variables`(testInfo: TestInfo) {
            val source = """
                        class Point has Eq {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN(): Int {
                            val first = Point(x = 1, y = 2)
                            val second = Point(x = 1, y = 2)
                            if first == second {
                                return 1
                            }
                            return 0
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }

        @Test
        fun `object equality without the Eq trait`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1) == Point(1))"),
            JvmLoweringException.ObjectEqualityRequiresTrait::class,
            "class Point does not have the Eq trait",
        )

        @Test
        fun `Eq fields whose types lack Eq`() = assertCompilationFails(
            "class Point {\n    val x: Int\n}\nclass Line has Eq {\n    val start: Point\n}",
            JvmLoweringException.ClassFieldRequiresTrait::class,
            "class Line field start has type Point, which does not have the Eq trait",
        )

        @Test
        fun `class methods conflicting with generated trait methods`() = assertCompilationFails(
            "class Point has Eq {\n    val x: Int\n" +
                "    fn equals(other: Point): Bool {\n        return True\n    }\n}",
            JvmLoweringException.ClassMethodConflictsWithGeneratedMethod::class,
            "class Point method equals conflicts with the generated method for trait Eq",
        )
    }

    @Nested
    inner class Display {
        @Test
        fun `Display prints objects with their fields`(testInfo: TestInfo) {
            val source = """
                        class Point has Display {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN() {
                            print(Point(x = 1, y = 2))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Point(x: 1, y: 2)$EOL", testInfo)
        }

        @Test
        fun `Display objects can be forwarded to print`(testInfo: TestInfo) {
            val source = """
                        class Point has Display {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN() {
                            Point(x = 1, y = 2) |> print
                            Point(x = 3, y = 4) |> print()
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Point(x: 1, y: 2)${EOL}Point(x: 3, y: 4)$EOL", testInfo)
        }

        @Test
        fun `Display prints nested objects recursively`(testInfo: TestInfo) {
            val source = """
                        class Point has Display {
                            val x: Int
                            val y: Int
                        }
                        class Line has Display {
                            val start: Point
                            val end: Point
                        }
                        fn $TEST_MAIN() {
                            print(Line(start = Point(x = 1, y = 2), end = Point(x = 3, y = 4)))
                        }
                    """.trimIndent()
            assertProgramPrints(
                source,
                "Line(start: Point(x: 1, y: 2), end: Point(x: 3, y: 4))$EOL",
                testInfo,
            )
        }

        @Test
        fun `Display prints String Bool and Unit fields`(testInfo: TestInfo) {
            val source = """
                        class Message has Display {
                            val text: String
                            val active: Bool
                        }
                        class Signal has Display {
                            val flag: Unit
                        }
                        fn $TEST_MAIN() {
                            print(Message(text = "hello", active = True))
                            print(Signal(Unit))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Message(text: hello, active: True)${EOL}Signal(flag: Unit)$EOL", testInfo)
        }

        @Test
        fun `Eq and Display can be combined`(testInfo: TestInfo) {
            val source = """
                        class Point has Eq, Display {
                            val x: Int
                            val y: Int
                        }
                        fn $TEST_MAIN() {
                            print(Point(x = 1, y = 2))
                            print(Point(x = 1, y = 2) == Point(x = 1, y = 2))
                            print(Point(x = 1, y = 2) != Point(x = 2, y = 2))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Point(x: 1, y: 2)${EOL}True${EOL}True$EOL", testInfo)
        }

        @Test
        fun `objects without the Display trait in string templates`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(\"at \\{Point(1)}\")"),
            JvmLoweringException.TemplateValueRequiresDisplayTrait::class,
            "class Point does not have the Display trait, so its objects cannot be used in a string template",
        )

        @Test
        fun `printing objects without the Display trait`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("print(Point(1))"),
            JvmLoweringException.PrintRequiresDisplayTrait::class,
            "class Point does not have the Display trait",
        )

        @Test
        fun `Display fields whose types lack Display`() = assertCompilationFails(
            "class Point {\n    val x: Int\n}\nclass Line has Display {\n    val start: Point\n}",
            JvmLoweringException.ClassFieldRequiresTrait::class,
            "class Line field start has type Point, which does not have the Display trait",
        )

        @Test
        fun `class methods conflicting with generated Display methods`() = assertCompilationFails(
            "class Point has Display {\n    val x: Int\n" +
                "    fn toString(): String {\n        return \"point\"\n    }\n}",
            JvmLoweringException.ClassMethodConflictsWithGeneratedMethod::class,
            "class Point method toString conflicts with the generated method for trait Display",
        )

        @Test
        fun `forwarding objects without the Display trait to print`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("Point(1) |> print"),
            JvmLoweringException.PrintRequiresDisplayTrait::class,
            "class Point does not have the Display trait",
        )
    }
}
