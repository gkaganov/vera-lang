package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraLexException
import vera.parser.VeraParseException

/** Hints for Java syntax and names that Vera spells differently. */
class JavaHabitTests {
    @Nested
    inner class Operators {
        @Test
        fun `Java negation`() = assertCompilationFails(
            function("print(!True)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `!`; write `not` instead, for example `not done`",
        )

        @Test
        fun `Java logical and`() = assertCompilationFails(
            function("print(True && False)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `&&`; write `and` instead",
        )

        @Test
        fun `Java logical or`() = assertCompilationFails(
            function("print(True || False)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `||`; write `or` instead",
        )

        @Test
        fun `Java increments`() = assertCompilationFails(
            function("val count = 0", "count++"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `++`; write `x = x + 1` instead",
        )

        @Test
        fun `Java compound assignments`() = assertCompilationFails(
            function("val count = 0", "count -= 2"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `-=`; write `x = x - value` instead",
        )

        @Test
        fun `String concatenation with plus`() = assertCompilationFails(
            function("val name = \"Vera\"", "print(\"Hello \" + name)"),
            JvmLoweringException.StringConcatenationNotSupported::class,
            "Vera builds strings with templates, not `+`; write \"text \\{value}\"",
        )
    }

    @Nested
    inner class Syntax {
        @Test
        fun `Java object creation with new`() = assertCompilationFails(
            function("val point = new Point()"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `new`; call the class directly: `Point(...)`",
        )

        @Test
        fun `Java inheritance with extends`() = assertCompilationFails(
            "class Square extends Shape {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no inheritance; share behaviour through traits with `has`, or compose classes",
        )

        @Test
        fun `Java-style declarations with primitive types`() = assertCompilationFails(
            function("int count = 0"),
            VeraParseException.JavaSyntax::class,
            "Vera declares variables with `val`: write `val count: Int = ...`",
        )

        @Test
        fun `Java-style declarations with Vera types`() = assertCompilationFails(
            function("String name = \"Vera\""),
            VeraParseException.JavaSyntax::class,
            "Vera declares variables with `val`: write `val name: String = ...`",
        )

        @Test
        fun `Java modifiers on declarations`() = assertCompilationFails(
            "public fn main() {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no `public` modifier; declarations are written without modifiers",
        )

        @Test
        fun `Java modifiers on fields`() = assertCompilationFails(
            "class Point {\n    private x: Int\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no `private` modifier",
        )

        @Test
        fun `Java final bindings`() = assertCompilationFails(
            function("final val count = 1"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `final`; bindings are immutable by default, so write `val`",
        )

        @Test
        fun `Java for loops`() = assertCompilationFails(
            function("for (item in items) {", "}"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `for` loop; use `while`",
        )

        @Test
        fun `Java and Kotlin var declarations`() = assertCompilationFails(
            function("var count = 0"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `var`; declare with `val`, or `mut val` for a binding you rebind",
        )
    }

    @Nested
    inner class Names {
        @Test
        fun `Java primitive type names`() = assertCompilationFails(
            "fn show(value: int) {\n}",
            JvmLoweringException.UnknownClassType::class,
            "class type int is not defined; Vera's integer type is `Int`",
        )

        @Test
        fun `Java void return types`() = assertCompilationFails(
            "fn show(): void {\n}",
            JvmLoweringException.UnknownClassType::class,
            "class type void is not defined; use `Unit`, or leave out the return type",
        )

        @Test
        fun `Java null`() = assertCompilationFails(
            function("val value = null"),
            JvmLoweringException.UnknownVariable::class,
            "variable null is not defined; Vera has no `null`",
        )

        @Test
        fun `Java this`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "fn getX(): Int {", "    return this.x", "}"),
            JvmLoweringException.UnknownVariable::class,
            "variable this is not defined; Vera has no `this`; refer to fields and methods by their names",
        )
    }
}
