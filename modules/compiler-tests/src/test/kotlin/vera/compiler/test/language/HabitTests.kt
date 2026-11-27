package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraLexException
import vera.parser.VeraParseException

/**
 * Hints for Java and Kotlin habits: code that compiles there but is spelled differently in Vera gets
 * an error that says what to write instead. Groups follow the sections of the language guide; tests
 * are named after the language the habit comes from.
 */
class HabitTests {
    @Nested
    inner class Lexical {
        @Test
        fun `Java block comments`() = assertCompilationFails(
            function("/* note */", "print(1)"),
            VeraLexException.BlockComment::class,
            "Vera has no block comments; start each line with `//`",
        )
    }

    @Nested
    inner class Types {
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
        fun `Java and Kotlin hex literals`() = assertCompilationFails(
            function("print(0x10)"),
            VeraLexException.UnsupportedNumberLiteral::class,
            "Vera supports only decimal `Int` literals such as `1000`",
        )

        @Test
        fun `Java and Kotlin binary literals`() = assertCompilationFails(
            function("print(0b101)"),
            VeraLexException.UnsupportedNumberLiteral::class,
            "Vera supports only decimal `Int` literals",
        )

        @Test
        fun `Java and Kotlin underscores in numbers`() = assertCompilationFails(
            function("print(1_000)"),
            VeraLexException.UnsupportedNumberLiteral::class,
            "Vera supports only decimal `Int` literals",
        )

        @Test
        fun `Java and Kotlin Long literals`() = assertCompilationFails(
            function("print(10L)"),
            VeraLexException.UnsupportedNumberLiteral::class,
            "Vera supports only decimal `Int` literals",
        )

        @Test
        fun `Java and Kotlin decimal fractions`() = assertCompilationFails(
            function("print(1.5)"),
            VeraLexException.UnsupportedNumberLiteral::class,
            "Vera supports only decimal `Int` literals",
        )
    }

    @Nested
    inner class Bindings {
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
        fun `Java final bindings`() = assertCompilationFails(
            function("final val count = 1"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `final`; bindings are immutable by default, so write `val`",
        )

        @Test
        fun `Java and Kotlin var declarations`() = assertCompilationFails(
            function("var count = 0"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `var`; declare with `val`, or `mut val` for a binding you rebind",
        )

        @Test
        fun `Kotlin lateinit var declarations`() = assertCompilationFails(
            function("lateinit var count: Int"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `lateinit` modifier; declare the binding with its value: `val name = ...`",
        )

        @Test
        fun `Kotlin const val declarations`() = assertCompilationFails(
            function("const val limit = 1"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `const` modifier",
        )

        @Test
        fun `Java and Kotlin declarations assigned later`() = assertCompilationFails(
            function("val count: Int"),
            VeraParseException.JavaSyntax::class,
            "a `val` needs a value: write `val count: Int = ...`, or choose it with " +
                "`val count = if ... { ... } else { ... }`",
        )

        @Test
        fun `Kotlin top-level values`() = assertCompilationFails(
            "val limit = 1",
            VeraParseException.JavaSyntax::class,
            "Vera has no top-level values; use a function such as `fn limit(): Int = 1`",
        )

        @Test
        fun `Kotlin top-level constants`() = assertCompilationFails(
            "const val limit = 1",
            VeraParseException.JavaSyntax::class,
            "Vera has no top-level values",
        )

        @Test
        fun `Java chained assignments`() = assertCompilationFails(
            function("mut val first = 1", "mut val second = 2", "first = second = 3"),
            VeraParseException.JavaSyntax::class,
            "rebinding is a statement, not an expression; rebind each variable on its own line",
        )

        @Test
        fun `Java and Kotlin field assignments`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int") + function("val point = Point(1)", "point.x = 3"),
            VeraParseException.JavaSyntax::class,
            "field x cannot be rebound; objects are immutable, so create a new object with the new value",
        )

        @Test
        fun `Java null`() = assertCompilationFails(
            function("val value = null"),
            JvmLoweringException.UnknownVariable::class,
            "variable null is not defined; Vera has no `null`",
        )
    }

    @Nested
    inner class Functions {
        @Test
        fun `Kotlin fun declarations`() = assertCompilationFails(
            "fun main() {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera declares functions with `fn`",
        )

        @Test
        fun `Java method declarations`() = assertCompilationFails(
            "int add(int a) {\n    return a\n}",
            VeraParseException.JavaSyntax::class,
            "Vera declares functions with `fn`: write `fn add(...): Type`",
        )

        @Test
        fun `Java parameters with the type first`() = assertCompilationFails(
            "fn add(int a): Int = a",
            VeraParseException.JavaSyntax::class,
            "Vera writes the type after the name: `a: Int`",
        )

        @Test
        fun `Kotlin default parameter values`() = assertCompilationFails(
            "fn add(a: Int = 1): Int = a",
            VeraParseException.JavaSyntax::class,
            "Vera has no default parameter values; callers pass every argument",
        )

        @Test
        fun `parameters without a type`() = assertCompilationFails(
            "fn show(value) {\n}",
            VeraParseException.JavaSyntax::class,
            "parameter value needs a type: write `value: Type`",
        )

        @Test
        fun `Java modifiers on declarations`() = assertCompilationFails(
            "public fn main() {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no `public` modifier; declarations are written without modifiers",
        )
    }

    @Nested
    inner class Expressions {
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
        fun `Java decrements`() = assertCompilationFails(
            function("mut val count = 0", "count--"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `--`; write `x = x - 1` instead",
        )

        @Test
        fun `Java decrements do not continue onto the next line`() = assertCompilationFails(
            function("mut val count = 5", "val other = 2", "count = count--", "other"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `--`",
            line = 4,
            column = 18,
        )

        @Test
        fun `Java compound assignments`() = assertCompilationFails(
            function("val count = 0", "count -= 2"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `-=`; write `x = x - value` instead",
        )

        @Test
        fun `Kotlin identity equality`() = assertCompilationFails(
            function("print(1 === 1)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `===`; write `==`; Vera has no reference identity, so `==` compares values",
        )

        @Test
        fun `Kotlin identity inequality`() = assertCompilationFails(
            function("print(1 !== 1)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `!==`; write `!=`",
        )

        @Test
        fun `Kotlin not-null assertions`() = assertCompilationFails(
            function("val value = 1", "print(value!!)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `!!`; Vera has no `null`, so there is nothing to assert",
        )

        @Test
        fun `Kotlin safe calls`() = assertCompilationFails(
            function("val value = 1", "print(value?.x)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `?.`; Vera has no `null`, so every value is present",
        )

        @Test
        fun `Kotlin elvis operators`() = assertCompilationFails(
            function("val value = 1", "print(value ?: 2)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `?:`",
        )

        @Test
        fun `Java ternaries`() = assertCompilationFails(
            function("print(True ? 1 : 2)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `?`; Vera has no `? :`; write `if condition { a } else { b }`",
        )

        @Test
        fun `Java bitwise and`() = assertCompilationFails(
            function("print(1 & 2)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `&`; Vera has no bitwise operators; for logic write `and`",
        )

        @Test
        fun `Java bitwise or`() = assertCompilationFails(
            function("print(1 | 2)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `|`; Vera has no bitwise operators; for logic write `or`, and for forward " +
                "application `|>`",
        )

        @Test
        fun `Java bitwise xor`() = assertCompilationFails(
            function("print(1 ^ 2)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `^`; Vera has no bitwise operators",
        )

        @Test
        fun `Java bitwise complement`() = assertCompilationFails(
            function("print(~1)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `~`",
        )

        @Test
        fun `Java shifts`() = assertCompilationFails(
            function("print(1 << 2)", "print(8 >> 1)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `<<`; Vera has no bitwise operators",
        )

        @Test
        fun `Java right shifts`() = assertCompilationFails(
            function("print(8 >> 1)"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `>>`",
        )

        @Test
        fun `Java unary plus`() = assertCompilationFails(
            function("print(+1)"),
            VeraParseException.JavaSyntax::class,
            "Vera has no unary `+`; write the number itself",
        )

        @Test
        fun `rejects String concatenation with plus`() = assertCompilationFails(
            function("val name = \"Vera\"", "print(\"Hello \" + name)"),
            JvmLoweringException.StringConcatenationNotSupported::class,
            "Vera builds strings with templates, not `+`; write \"text \\{value}\"",
        )
    }

    @Nested
    inner class Strings {
        @Test
        fun `Java and Kotlin char literals`() = assertCompilationFails(
            function("print('a')"),
            VeraLexException.CharLiteral::class,
            "Vera strings use double quotes, and there is no `Char` type: write `\"c\"`",
        )

        @Test
        fun `Java and Kotlin text blocks`() = assertCompilationFails(
            function("print(\"\"\"", "text", "\"\"\")"),
            VeraLexException.TextBlock::class,
            "Vera has no multi-line strings; write `\\n` for a line break",
        )
    }

    @Nested
    inner class ControlFlow {
        @Test
        fun `Java if without braces`() = assertCompilationFails(
            function("if (True) print(1)"),
            VeraParseException.JavaSyntax::class,
            "Vera requires braces: write `if condition { ... } else { ... }`",
        )

        @Test
        fun `Java else without braces`() = assertCompilationFails(
            function("if True {", "    print(1)", "} else print(2)"),
            VeraParseException.JavaSyntax::class,
            "Vera requires braces",
        )

        @Test
        fun `Java for loops`() = assertCompilationFails(
            function("for (item in items) {", "}"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `for` loop; use `while`",
        )

        @Test
        fun `Java do-while loops`() = assertCompilationFails(
            function("do {", "    print(1)", "} while (False)"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `do`-`while`; use `while True { ... }` with a `break`",
        )

        @Test
        fun `Kotlin when`() = assertCompilationFails(
            function("when (1) {", "}"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `when`; use `match` on a trait, or an `if` / `else if` chain",
        )

        @Test
        fun `Java switch`() = assertCompilationFails(
            function("switch (1) {", "}"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `switch`",
        )

        @Test
        fun `Java labeled breaks`() = assertCompilationFails(
            function("while True {", "    break outer", "}"),
            VeraParseException.JavaSyntax::class,
            "Vera has no labeled jumps; `break` and `continue` target the innermost `while`",
        )

        @Test
        fun `Kotlin labeled breaks`() = assertCompilationFails(
            function("while True {", "    break@outer", "}"),
            VeraLexException.JavaOperator::class,
            "Vera does not use `@`; Vera has no annotations or labels",
        )
    }

    @Nested
    inner class Match {
        private val shape = "trait Shape {}\n" + classDeclaration("Circle has Shape", "val radius: Int") +
            classDeclaration("Rect has Shape", "val width: Int")

        @Test
        fun `Kotlin else arms`() = assertCompilationFails(
            shape + "fn area(shape: Shape): Int = match shape {\n    Circle(radius) -> radius\n    else -> 0\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no catch-all arm; list every case, so adding a case later is a compile error",
        )

        @Test
        fun `Java and Kotlin wildcard arms`() = assertCompilationFails(
            shape + "fn area(shape: Shape): Int = match shape {\n    Circle(radius) -> radius\n    _ -> 0\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no catch-all arm",
        )

        @Test
        fun `Kotlin is patterns`() = assertCompilationFails(
            shape + "fn area(shape: Shape): Int = match shape {\n    is Circle -> 1\n    is Rect -> 2\n}",
            VeraParseException.JavaSyntax::class,
            "write the class as the pattern: `Circle ->`, or `Circle(field) ->` to bind fields",
        )

        @Test
        fun `Java case patterns`() = assertCompilationFails(
            shape + "fn area(shape: Shape): Int = match shape {\n    case Circle c -> 1\n}",
            VeraParseException.JavaSyntax::class,
            "write the class as the pattern: `Circle ->`",
        )

        @Test
        fun `Java and Kotlin several patterns in one arm`() = assertCompilationFails(
            shape + "fn area(shape: Shape): Int = match shape {\n    Circle, Rect -> 1\n}",
            VeraParseException.JavaSyntax::class,
            "a match arm has one pattern; write a separate arm for each case",
        )

        @Test
        fun `Java record patterns with types`() = assertCompilationFails(
            shape + "fn area(shape: Shape): Int = match shape {\n    Circle(Int radius) -> radius\n    Rect -> 0\n}",
            VeraParseException.JavaSyntax::class,
            "patterns bind fields by name, without a type: write `radius`",
        )
    }

    @Nested
    inner class Classes {
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
        fun `Java modifiers on fields`() = assertCompilationFails(
            "class Point {\n    private x: Int\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no `private` modifier",
        )

        @Test
        fun `Kotlin primary constructors`() = assertCompilationFails(
            "class Point(val x: Int, val y: Int)",
            VeraParseException.JavaSyntax::class,
            "Vera declares fields in the class body: `class Point { val x: Int }`; the constructor is generated",
        )

        @Test
        fun `Java records`() = assertCompilationFails(
            "record Point(int x) {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera declares fields in the class body",
        )

        @Test
        fun `Kotlin data classes`() = assertCompilationFails(
            "data class Point {\n    val x: Int\n}",
            VeraParseException.JavaSyntax::class,
            "every Vera class is a data class: write `class`, and add `has Eq, Display` for equality and printing",
        )

        @Test
        fun `Kotlin objects`() = assertCompilationFails(
            "object Config {\n}",
            VeraParseException.JavaSyntax::class,
            "a class without fields is a singleton: write `class Config { }`",
        )

        @Test
        fun `Kotlin supertype lists`() = assertCompilationFails(
            "class Point : Eq {\n    val x: Int\n}",
            VeraParseException.JavaSyntax::class,
            "Vera lists traits with `has` (`class Point has Eq`) and interfaces with `implements`",
        )

        @Test
        fun `Java and Kotlin generic classes`() = assertCompilationFails(
            "class Box<T> {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no generic types yet",
        )

        @Test
        fun `Java and Kotlin field default values`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int = 0"),
            VeraParseException.JavaSyntax::class,
            "fields have no default values; pass the value to the constructor",
        )

        @Test
        fun `Kotlin constructor blocks`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "constructor(x: Int) {", "}"),
            VeraParseException.JavaSyntax::class,
            "the constructor is generated from the fields; Vera has no constructor bodies",
        )

        @Test
        fun `Kotlin init blocks`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "init {", "}"),
            VeraParseException.JavaSyntax::class,
            "the constructor is generated from the fields",
        )

        @Test
        fun `Java constructors`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "Point(int x) {", "}"),
            VeraParseException.JavaSyntax::class,
            "the constructor is generated from the fields",
        )

        @Test
        fun `Kotlin fun methods`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "fun double(): Int = x * 2"),
            VeraParseException.JavaSyntax::class,
            "Vera declares methods with `fn`",
        )

        @Test
        fun `Kotlin override methods`() = assertCompilationFails(
            "trait Shape {\n    fn area(): Int\n}\n" +
                classDeclaration("Square has Shape", "val side: Int", "override fn area(): Int = side"),
            VeraParseException.JavaSyntax::class,
            "Vera has no `override`; a method with the trait's signature implements it",
        )

        @Test
        fun `Java override annotations`() = assertCompilationFails(
            "trait Shape {\n    fn area(): Int\n}\n" +
                classDeclaration("Square has Shape", "val side: Int", "@Override", "fn area(): Int = side"),
            VeraLexException.JavaOperator::class,
            "Vera has no annotations",
        )

        @Test
        fun `Java this`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "fn getX(): Int {", "    return this.x", "}"),
            JvmLoweringException.UnknownVariable::class,
            "variable this is not defined; Vera has no `this`; refer to fields and methods by their names",
        )
    }

    @Nested
    inner class Traits {
        @Test
        fun `Java and Kotlin enums`() = assertCompilationFails(
            "enum Color { RED, GREEN }",
            VeraParseException.JavaSyntax::class,
            "Vera writes enums as a trait with cases: `trait Color { Red, Green }`",
        )

        @Test
        fun `Kotlin enum classes`() = assertCompilationFails(
            "enum class Color { RED, GREEN }",
            VeraParseException.JavaSyntax::class,
            "Vera writes enums as a trait with cases",
        )

        @Test
        fun `Java and Kotlin sealed classes`() = assertCompilationFails(
            "sealed class Shape {\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no inheritance; a closed set of classes is a trait with cases: `trait Shape { Circle { ... } }`",
        )

        @Test
        fun `Java default methods in traits`() = assertCompilationFails(
            "trait Shape {\n    fn area(): Int = 0\n}",
            VeraParseException.JavaSyntax::class,
            "trait methods have no bodies; each class implements the method itself, because Vera has no inheritance",
        )

        @Test
        fun `Kotlin interface method bodies`() = assertCompilationFails(
            "interface Named {\n    fn name(): String {\n        return \"x\"\n    }\n}",
            VeraParseException.JavaSyntax::class,
            "interface methods have no bodies",
        )

        @Test
        fun `Java default keyword in traits`() = assertCompilationFails(
            "trait Shape {\n    default fn area(): Int\n}",
            VeraParseException.JavaSyntax::class,
            "Vera has no `default`",
        )

        @Test
        fun `Kotlin fun in traits`() = assertCompilationFails(
            "trait Shape {\n    fun area(): Int\n}",
            VeraParseException.JavaSyntax::class,
            "Vera declares methods with `fn`",
        )

        @Test
        fun `Kotlin supertypes of interfaces`() = assertCompilationFails(
            "interface Named {}\ninterface Pet : Named {}",
            VeraParseException.JavaSyntax::class,
            "an interface lists what it requires with `requires`: `requires Named`",
        )

        @Test
        fun `Java and Kotlin generic traits`() = assertCompilationFails(
            "trait Box<T> {}",
            VeraParseException.JavaSyntax::class,
            "Vera has no generic types yet",
        )
    }
}
