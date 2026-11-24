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

/** Name resolution: unknown names with suggestions, no shadowing, and top-level names. */
class NameResolutionTests {
    @Nested
    inner class UnknownNames {
        @Test
        fun `misspelled variables`() = assertCompilationFails(
            function("val count = 1", "print(cuont)"),
            JvmLoweringException.UnknownVariable::class,
            "variable cuont is not defined; did you mean `count`?",
        )

        @Test
        fun `misspelled functions`() = assertCompilationFails(
            "fn greet() {\n}\n" + function("gret()"),
            JvmLoweringException.UnknownFunction::class,
            "function gret is not defined; did you mean `greet`?",
        )

        @Test
        fun `misspelled built-ins`() = assertCompilationFails(
            function("pirnt(1)"),
            JvmLoweringException.UnknownFunction::class,
            "function pirnt is not defined; did you mean `print`?",
        )

        @Test
        fun `misspelled fields`() = assertCompilationFails(
            classDeclaration("Size", "val width: Int", "val height: Int") +
                function("print(Size(width = 1, height = 2).widht)"),
            JvmLoweringException.UnknownClassField::class,
            "class Size has no field widht; did you mean `width`?",
        )

        @Test
        fun `misspelled methods`() = assertCompilationFails(
            classDeclaration("Size", "val width: Int", "fn area(): Int {", "    return width", "}") +
                function("print(Size(2).aera())"),
            JvmLoweringException.UnknownClassMethod::class,
            "class Size has no method aera; did you mean `area`?",
        )

        @Test
        fun `misspelled types`() = assertCompilationFails(
            "fn show(value: Strng) {\n}",
            JvmLoweringException.UnknownClassType::class,
            "class type Strng is not defined; did you mean `String`?",
        )

        @Test
        fun `misspelled traits`() = assertCompilationFails(
            "class Point has Eqq {\n    val x: Int\n}",
            JvmLoweringException.UnknownTrait::class,
            "trait Eqq is not defined; did you mean `Eq`?",
        )

        @Test
        fun `undefined names`() = assertCompilationFails(
            function("print(missing)"),
            JvmLoweringException.UnknownVariable::class,
            "variable missing is not defined",
        )

        @Test
        fun `member access on undefined names`() = assertCompilationFails(
            function("print(missing.value)"),
            JvmLoweringException.UnknownVariable::class,
            "variable missing is not defined",
        )

        @Test
        fun `invalid type names`() = assertCompilationFails(
            "fn $TEST_MAIN(): Nope { }",
            JvmLoweringException.UnknownClassType::class,
            "class type Nope is not defined",
        )

        @Test
        fun `unknown names without a close match get no suggestion`() {
            val error = Assertions.assertThrows(JvmLoweringException.UnknownVariable::class.java) {
                VeraCompiler.compile(Identifier("ErrorTest"), function("val count = 1", "print(total)"))
            }
            Assertions.assertEquals("variable total is not defined", error.message)
        }
    }

    @Nested
    inner class Shadowing {
        @Test
        fun `locals in nested blocks shadowing outer locals`() = assertCompilationFails(
            function("val value = 1", "if True {", "    val value = 2", "}"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already in scope; shadowing is not allowed",
        )

        @Test
        fun `duplicate parameters`() = assertCompilationFails(
            "fn testMain(value: Int, value: Int) { }",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already in scope; shadowing is not allowed",
        )

        @Test
        fun `parameters shadowing fields`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn compute(x: Int): Int {",
                "    return x",
                "}",
            ),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name x is already in scope; shadowing is not allowed",
        )

        @Test
        fun `locals shadowing fields`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn compute(): Int {",
                "    val x = 1",
                "    return x",
                "}",
            ),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name x is already in scope; shadowing is not allowed",
        )

        @Test
        fun `locals in nested blocks shadowing fields`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn compute(): Int {",
                "    if True {",
                "        val x = 1",
                "    }",
                "    return x",
                "}",
            ),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name x is already in scope; shadowing is not allowed",
        )

        @Test
        fun `locals shadowing parameters`() = assertCompilationFails(
            "fn testMain(value: Int) {\n    val value = 1\n}",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already in scope; shadowing is not allowed",
        )

        @Test
        fun `locals shadowing locals`() = assertCompilationFails(
            function("val value = 1", "val value = 2"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already in scope; shadowing is not allowed",
        )

        @Test
        fun `parameters shadowing top-level functions`() = assertCompilationFails(
            "fn helper() { }\nfn testMain(helper: Int) { }",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name helper is already in scope; shadowing is not allowed",
        )

        @Test
        fun `class fields shadowing builtins`() = assertCompilationFails(
            classDeclaration("Point", "val print: Int"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name print is already in scope; shadowing is not allowed",
        )

        @Test
        fun `class methods shadowing top-level functions`() = assertCompilationFails(
            "fn helper() { }\n" + classDeclaration("Point", "fn helper() { }"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name helper is already in scope; shadowing is not allowed",
        )

        @Test
        fun `class methods shadowing class names`() = assertCompilationFails(
            classDeclaration("Point", "fn Point() { }"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name Point is already in scope; shadowing is not allowed",
        )
    }

    @Nested
    inner class TopLevelNames {
        @Test
        fun `top-level functions conflicting with builtins`() = assertCompilationFails(
            "fn print() { }",
            JvmLoweringException.FunctionNameConflictsWithBuiltin::class,
            "function print conflicts with the built-in function print",
        )

        @Test
        fun `declarations conflicting with builtin traits`() = assertCompilationFails(
            classDeclaration("Display", "val x: Int"),
            JvmLoweringException.NameConflictsWithBuiltinTrait::class,
            "name Display is reserved for a built-in trait",
        )

        @Test
        fun `traits conflicting with class names`() = assertCompilationFails(
            traitDeclaration("Point") + "class Point {\n    val x: Int\n}",
            JvmLoweringException.TopLevelNameConflict::class,
            "top-level name Point is declared more than once",
        )

        @Test
        fun `classes conflicting with builtins`() = assertCompilationFails(
            "class print { }",
            JvmLoweringException.ClassNameConflictsWithBuiltin::class,
            "class print conflicts with the built-in function print",
        )

        @Test
        fun `duplicate top-level functions`() = assertCompilationFails(
            "fn helper() { }\nfn helper() { }",
            JvmLoweringException.DuplicateFunction::class,
            "function helper is declared more than once",
        )

        @Test
        fun `top-level functions conflicting with objects`() = assertCompilationFails(
            "class Conflict { }\nfn Conflict() { }",
            JvmLoweringException.TopLevelNameConflict::class,
            "top-level name Conflict is declared more than once",
        )
    }
}
