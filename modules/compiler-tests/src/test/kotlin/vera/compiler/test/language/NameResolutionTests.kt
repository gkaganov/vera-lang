package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.classDeclaration
import vera.compiler.test.compilationError
import vera.compiler.test.function
import vera.compiler.test.traitDeclaration
import vera.jvm.lowering.JvmLoweringException

/** Name resolution: unknown names with suggestions, no shadowing, and top-level names. */
class NameResolutionTests {
    @Nested
    inner class UnknownNames {
        @Test
        fun `rejects misspelled variables`() = assertCompilationFails(
            function("val count = 1", "print(cuont)"),
            JvmLoweringException.UnknownVariable::class,
            "variable cuont is not defined; did you mean `count`?",
        )

        @Test
        fun `rejects misspelled functions`() = assertCompilationFails(
            "fn greet() {\n}\n" + function("gret()"),
            JvmLoweringException.UnknownFunction::class,
            "function gret is not defined; did you mean `greet`?",
        )

        @Test
        fun `rejects misspelled built-ins`() = assertCompilationFails(
            function("pirnt(1)"),
            JvmLoweringException.UnknownFunction::class,
            "function pirnt is not defined; did you mean `print`?",
        )

        @Test
        fun `rejects misspelled fields`() = assertCompilationFails(
            classDeclaration("Size", "val width: Int", "val height: Int") +
                function("print(Size(width = 1, height = 2).widht)"),
            JvmLoweringException.UnknownClassField::class,
            "class Size has no field widht; did you mean `width`?",
        )

        @Test
        fun `rejects misspelled methods`() = assertCompilationFails(
            classDeclaration("Size", "val width: Int", "fn area(): Int {", "    return width", "}") +
                function("print(Size(2).aera())"),
            JvmLoweringException.UnknownClassMethod::class,
            "class Size has no method aera; did you mean `area`?",
        )

        @Test
        fun `rejects misspelled types`() = assertCompilationFails(
            "fn show(value: Strng) {\n}",
            JvmLoweringException.UnknownClassType::class,
            "type Strng is not defined; did you mean `String`?",
        )

        @Test
        fun `suggests singleton values for misspelled values`() = assertCompilationFails(
            "class Empty {}\n" + function("print(Emtpy)"),
            JvmLoweringException.UnknownVariable::class,
            "variable Emtpy is not defined; did you mean `Empty`?",
        )

        @Test
        fun `suggests Unit and True for misspelled built-in values`() = assertCompilationFails(
            function("print(Unti)"),
            JvmLoweringException.UnknownVariable::class,
            "variable Unti is not defined; did you mean `Unit`?",
        )

        @Test
        fun `names the built-in traits for unknown traits without a close match`() = assertCompilationFails(
            "class Point has Comparable {\n    val x: Int\n}",
            JvmLoweringException.UnknownTrait::class,
            "trait Comparable is not defined; the built-in traits are `Eq` and `Display`",
        )

        @Test
        fun `rejects misspelled traits`() = assertCompilationFails(
            "class Point has Eqq {\n    val x: Int\n}",
            JvmLoweringException.UnknownTrait::class,
            "trait Eqq is not defined; did you mean `Eq`?",
        )

        @Test
        fun `rejects member access on undefined names`() = assertCompilationFails(
            function("print(missing.value)"),
            JvmLoweringException.UnknownVariable::class,
            "variable missing is not defined",
        )

        @Test
        fun `rejects unknown type names`() = assertCompilationFails(
            "fn main(): Nope { }",
            JvmLoweringException.UnknownClassType::class,
            "type Nope is not defined",
        )

        @Test
        fun `unknown names without a close match get no suggestion`() {
            val error = compilationError(function("val count = 1", "print(total)"))
            Assertions.assertInstanceOf(JvmLoweringException.UnknownVariable::class.java, error)
            Assertions.assertEquals("variable total is not defined", error.message)
        }
    }

    @Nested
    inner class Shadowing {
        @Test
        fun `rejects locals in nested blocks shadowing outer locals`() = assertCompilationFails(
            function("val value = 1", "if True {", "    val value = 2", "}"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already used by local value; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects duplicate parameters`() = assertCompilationFails(
            "fn main(value: Int, value: Int) { }",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already used by parameter value",
        )

        @Test
        fun `rejects parameters shadowing fields`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn compute(x: Int): Int {",
                "    return x",
                "}",
            ),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name x is already used by field x of class Point; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects locals shadowing fields`() = assertCompilationFails(
            classDeclaration(
                "Point",
                "val x: Int",
                "fn compute(): Int {",
                "    val x = 1",
                "    return x",
                "}",
            ),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name x is already used by field x of class Point; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects locals in nested blocks shadowing fields`() = assertCompilationFails(
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
            "name x is already used by field x of class Point; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects locals shadowing parameters`() = assertCompilationFails(
            "fn main(value: Int) {\n    val value = 1\n}",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already used by parameter value",
        )

        @Test
        fun `rejects locals shadowing locals`() = assertCompilationFails(
            function("val value = 1", "val value = 2"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name value is already used by local value",
        )

        @Test
        fun `rejects parameters shadowing top-level functions`() = assertCompilationFails(
            "fn helper() { }\nfn main(helper: Int) { }",
            JvmLoweringException.ShadowingNotAllowed::class,
            "name helper is already used by function helper; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects class fields shadowing builtins`() = assertCompilationFails(
            classDeclaration("Point", "val print: Int"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name print is already used by built-in function print; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects class methods shadowing top-level functions`() = assertCompilationFails(
            "fn helper() { }\n" + classDeclaration("Point", "fn helper() { }"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name helper is already used by function helper; Vera does not allow shadowing, so rename it",
        )

        @Test
        fun `rejects class methods shadowing class names`() = assertCompilationFails(
            classDeclaration("Point", "fn Point() { }"),
            JvmLoweringException.ShadowingNotAllowed::class,
            "name Point is already used by class Point; Vera does not allow shadowing, so rename it",
        )
    }

    @Nested
    inner class TopLevelNames {
        @Test
        fun `rejects top-level functions conflicting with builtins`() = assertCompilationFails(
            "fn print() { }",
            JvmLoweringException.FunctionNameConflictsWithBuiltin::class,
            "function print conflicts with the built-in function print",
        )

        @Test
        fun `rejects declarations conflicting with builtin traits`() = assertCompilationFails(
            classDeclaration("Display", "val x: Int"),
            JvmLoweringException.NameConflictsWithBuiltinTrait::class,
            "name Display is reserved for a built-in trait",
        )

        @Test
        fun `rejects traits conflicting with class names`() = assertCompilationFails(
            traitDeclaration("Point") + "class Point {\n    val x: Int\n}",
            JvmLoweringException.TopLevelNameConflict::class,
            "top-level name Point is declared more than once",
        )

        @Test
        fun `rejects classes conflicting with builtins`() = assertCompilationFails(
            "class print { }",
            JvmLoweringException.ClassNameConflictsWithBuiltin::class,
            "class print conflicts with the built-in function print",
        )

        @Test
        fun `rejects duplicate top-level functions`() = assertCompilationFails(
            "fn helper() { }\nfn helper() { }",
            JvmLoweringException.DuplicateFunction::class,
            "function helper is declared more than once",
        )

        @Test
        fun `rejects top-level functions conflicting with classes`() = assertCompilationFails(
            "class Conflict { }\nfn Conflict() { }",
            JvmLoweringException.TopLevelNameConflict::class,
            "top-level name Conflict is declared more than once",
        )
    }
}
