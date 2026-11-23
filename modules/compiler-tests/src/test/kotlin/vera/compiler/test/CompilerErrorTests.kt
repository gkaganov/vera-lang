package vera.compiler.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import vera.compiler.VeraCompiler
import vera.compiler.VeraDiagnostics
import vera.jvm.bytecode.emitter.BytecodeEmitter
import vera.jvm.bytecode.emitter.BytecodeEmitException
import vera.jvm.instruction.FlatClass
import vera.jvm.instruction.FlatInstructionException
import vera.jvm.instruction.FlatMethod
import vera.jvm.instruction.FlatMethodBuilder
import vera.jvm.instruction.FlatProgram
import vera.jvm.instruction.FlatLabel
import vera.jvm.instruction.FlatJump
import vera.jvm.instruction.FlatLoadConstant
import vera.jvm.instruction.FlatPop
import vera.jvm.instruction.lowering.FlatInstructionLowering
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmBreak
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmClass
import vera.jvm.ir.JvmContinue
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
import vera.jvm.ir.JvmIntBinary
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmIntLiteral
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmProgram
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitReturnBlock
import vera.jvm.ir.JvmWhile
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraLexException
import vera.parser.VeraParseException
import vera.shared.model.Identifier
import vera.shared.model.VeraException
import kotlin.reflect.KClass

class CompilerErrorTests {

    data class CompileErrorCase(
        val description: String,
        val source: String,
        val expectedType: KClass<out VeraException>,
        val messagePart: String,
    ) {
        override fun toString(): String = description
    }

    companion object {
        private const val TEST_MAIN = "testMain"

        private fun function(vararg lines: String): String = buildString {
            appendLine("fn $TEST_MAIN() {")
            lines.forEach { appendLine("    $it") }
            appendLine("}")
        }

        private fun classDeclaration(name: String, vararg lines: String): String = buildString {
            appendLine("class $name {")
            lines.forEach { appendLine("    $it") }
            appendLine("}")
        }

        private fun traitDeclaration(name: String, vararg lines: String): String = buildString {
            appendLine("trait $name {")
            lines.forEach { appendLine("    $it") }
            appendLine("}")
        }

        @JvmStatic
        fun lexerErrors(): List<CompileErrorCase> = listOf(
            CompileErrorCase(
                "unexpected characters",
                "fn $TEST_MAIN() { @ }",
                VeraLexException.UnexpectedCharacter::class,
                "unexpected character '@'",
            ),
            CompileErrorCase(
                "a single pipe is not forward application",
                function("print(1 | 2)"),
                VeraLexException.UnexpectedCharacter::class,
                "unexpected character '|'",
            ),
            CompileErrorCase(
                "invalid string escapes",
                function("print(\"bad \\q\")"),
                VeraLexException.InvalidStringEscape::class,
                "invalid string escape '\\q'",
            ),
            CompileErrorCase(
                "newlines in strings",
                "fn $TEST_MAIN() { print(\"a\nb\") }",
                VeraLexException.UnterminatedString::class,
                "unterminated string literal",
            ),
            CompileErrorCase(
                "unterminated strings",
                "fn $TEST_MAIN() { print(\"abc",
                VeraLexException.UnterminatedString::class,
                "unterminated string literal",
            ),
            CompileErrorCase(
                "line breaks inside template placeholders",
                function("print(\"sum: \\{1 +", "    2}\")"),
                VeraLexException.UnterminatedString::class,
                "unterminated string literal at line 2, column 11",
            ),
            CompileErrorCase(
                "unclosed template placeholders",
                function("print(\"value: \\{1\")"),
                VeraLexException.UnterminatedString::class,
                "unterminated string literal",
            ),
            CompileErrorCase(
                "Java negation",
                function("print(!True)"),
                VeraLexException.JavaOperator::class,
                "Vera does not use `!`; write `not` instead, for example `not done`",
            ),
            CompileErrorCase(
                "Java logical and",
                function("print(True && False)"),
                VeraLexException.JavaOperator::class,
                "Vera does not use `&&`; write `and` instead",
            ),
            CompileErrorCase(
                "Java logical or",
                function("print(True || False)"),
                VeraLexException.JavaOperator::class,
                "Vera does not use `||`; write `or` instead",
            ),
            CompileErrorCase(
                "Java increments",
                function("val count = 0", "count++"),
                VeraLexException.JavaOperator::class,
                "Vera does not use `++`; write `x = x + 1` instead",
            ),
            CompileErrorCase(
                "Java compound assignments",
                function("val count = 0", "count -= 2"),
                VeraLexException.JavaOperator::class,
                "Vera does not use `-=`; write `x = x - value` instead",
            ),
            CompileErrorCase(
                "semicolons",
                function("print(1);"),
                VeraLexException.UnexpectedSemicolon::class,
                "Vera does not use semicolons; end the statement with a newline at line 2, column 13",
            ),
        )

        @JvmStatic
        fun parserErrors(): List<CompileErrorCase> = listOf(
            CompileErrorCase(
                "non-function declarations",
                "val value = 1",
                VeraParseException.ExpectedDeclaration::class,
                "expected a function, class, or trait declaration",
            ),
            CompileErrorCase(
                "missing tokens",
                "fn $TEST_MAIN( { }",
                VeraParseException.ExpectedToken::class,
                "expected parameter name",
            ),
            CompileErrorCase(
                "missing expressions",
                function("val value ="),
                VeraParseException.ExpectedExpression::class,
                "expected an expression",
            ),
            CompileErrorCase(
                "empty template placeholders",
                function("print(\"value: \\{}\")"),
                VeraParseException.ExpectedExpression::class,
                "expected an expression",
            ),
            CompileErrorCase(
                "template placeholders with two expressions",
                function("val a = 1", "val b = 2", "print(\"\\{a b}\")"),
                VeraParseException.ExpectedToken::class,
                "expected `}` to close the template placeholder",
            ),
            CompileErrorCase(
                "Java object creation with new",
                function("val point = new Point()"),
                VeraParseException.JavaSyntax::class,
                "Vera has no `new`; call the class directly: `Point(...)`",
            ),
            CompileErrorCase(
                "Java inheritance with extends",
                "class Square extends Shape {\n}",
                VeraParseException.JavaSyntax::class,
                "Vera has no inheritance; share behaviour through traits with `has`, or compose classes",
            ),
            CompileErrorCase(
                "Java interfaces with implements",
                "class Square implements Shape {\n}",
                VeraParseException.JavaSyntax::class,
                "Vera lists traits with `has`: `class Square has Trait`",
            ),
            CompileErrorCase(
                "Java-style declarations with primitive types",
                function("int count = 0"),
                VeraParseException.JavaSyntax::class,
                "Vera declares variables with `val`: write `val count: Int = ...`",
            ),
            CompileErrorCase(
                "Java-style declarations with Vera types",
                function("String name = \"Vera\""),
                VeraParseException.JavaSyntax::class,
                "Vera declares variables with `val`: write `val name: String = ...`",
            ),
            CompileErrorCase(
                "Java modifiers on declarations",
                "public fn main() {\n}",
                VeraParseException.JavaSyntax::class,
                "Vera has no `public` modifier; declarations are written without modifiers",
            ),
            CompileErrorCase(
                "Java modifiers on fields",
                "class Point {\n    private x: Int\n}",
                VeraParseException.JavaSyntax::class,
                "Vera has no `private` modifier",
            ),
            CompileErrorCase(
                "Java final bindings",
                function("final val count = 1"),
                VeraParseException.JavaSyntax::class,
                "Vera has no `final`; bindings are immutable by default, so write `val`",
            ),
            CompileErrorCase(
                "Java for loops",
                function("for (item in items) {", "}"),
                VeraParseException.JavaSyntax::class,
                "Vera has no `for` loop; use `while`",
            ),
            CompileErrorCase(
                "explicit Unit return types",
                "fn log(text: String): Unit {\n}",
                VeraParseException.ExplicitUnitReturnType::class,
                "leave out the return type for a function that returns `Unit`; remove `: Unit`",
            ),
            CompileErrorCase(
                "explicit Unit return types on trait methods",
                traitDeclaration("Resettable", "fn reset(): Unit"),
                VeraParseException.ExplicitUnitReturnType::class,
                "leave out the return type for a function that returns `Unit`",
            ),
            CompileErrorCase(
                "Java and Kotlin var declarations",
                function("var count = 0"),
                VeraParseException.JavaSyntax::class,
                "Vera has no `var`; declare with `val`, or `mut val` for a binding you rebind",
            ),
            CompileErrorCase(
                "mut without val",
                function("mut count = 0"),
                VeraParseException.ExpectedToken::class,
                "expected `val` after `mut`, found 'count'",
            ),
            CompileErrorCase(
                "missing statement terminators",
                function("print(1) print(2)"),
                VeraParseException.ExpectedEndOfLine::class,
                "expected end of line",
            ),
            CompileErrorCase(
                "trait methods sharing a line",
                "trait Shape { fn area(): Int fn perimeter(): Int }",
                VeraParseException.ExpectedEndOfLine::class,
                "expected end of line",
            ),
            CompileErrorCase(
                "out-of-range Int literals",
                function("print(999999999999999999999)"),
                VeraParseException.InvalidIntLiteral::class,
                "invalid Int literal",
            ),
            CompileErrorCase(
                "rebind update blocks",
                function("value = {", "    1", "}"),
                VeraParseException.UnsupportedRebindUpdateBlock::class,
                "rebind update blocks are not supported",
            ),
        )

        @JvmStatic
        fun loweringErrors(): List<CompileErrorCase> = listOf(
            CompileErrorCase(
                "non-Bool conditions",
                function("if 1 {", "}"),
                JvmLoweringException.ConditionNotBool::class,
                "conditions must result in a bool",
            ),
            CompileErrorCase(
                "break outside loops",
                function("break"),
                JvmLoweringException.BreakOutsideLoop::class,
                "break is only allowed inside a loop",
            ),
            CompileErrorCase(
                "continue outside loops",
                function("continue"),
                JvmLoweringException.ContinueOutsideLoop::class,
                "continue is only allowed inside a loop",
            ),
            CompileErrorCase(
                "break inside call arguments",
                "fn add(a: Int, b: Int): Int {\n    return a + b\n}\n" + function(
                    "while True {",
                    "    print(add(1, if True {",
                    "        break",
                    "        0",
                    "    } else {",
                    "        1",
                    "    }))",
                    "}",
                ),
                JvmLoweringException.LoopJumpInsideExpression::class,
                "break cannot be used inside a nested expression",
            ),
            CompileErrorCase(
                "continue inside operands",
                function(
                    "while True {",
                    "    val value = 1 + if True {",
                    "        continue",
                    "        0",
                    "    } else {",
                    "        1",
                    "    }",
                    "}",
                ),
                JvmLoweringException.LoopJumpInsideExpression::class,
                "continue cannot be used inside a nested expression",
            ),
            CompileErrorCase(
                "statements after return",
                function("return", "print(1)"),
                JvmLoweringException.UnreachableStatement::class,
                "unreachable statement after return",
            ),
            CompileErrorCase(
                "statements after break",
                function("while True {", "    break", "    print(1)", "}"),
                JvmLoweringException.UnreachableStatement::class,
                "unreachable statement after break",
            ),
            CompileErrorCase(
                "statements after continue",
                function("while True {", "    continue", "    print(1)", "}"),
                JvmLoweringException.UnreachableStatement::class,
                "unreachable statement after continue",
            ),
            CompileErrorCase(
                "block results after break",
                function("while True {", "    val value = if True {", "        break", "        0", "    } else {", "        1", "    }", "}"),
                JvmLoweringException.UnreachableStatement::class,
                "unreachable statement after break",
            ),
            CompileErrorCase(
                "statements after an if that returns from every branch",
                "fn pick(flag: Bool): Int {\n    if flag {\n        return 1\n    } else {\n        return 2\n    }\n" +
                    "    return 3\n}",
                JvmLoweringException.UnreachableStatement::class,
                "unreachable statement after an if whose branches all return",
            ),
            CompileErrorCase(
                "misspelled variables",
                function("val count = 1", "print(cuont)"),
                JvmLoweringException.UnknownVariable::class,
                "variable cuont is not defined; did you mean `count`?",
            ),
            CompileErrorCase(
                "misspelled functions",
                "fn greet() {\n}\n" + function("gret()"),
                JvmLoweringException.UnknownFunction::class,
                "function gret is not defined; did you mean `greet`?",
            ),
            CompileErrorCase(
                "misspelled built-ins",
                function("pirnt(1)"),
                JvmLoweringException.UnknownFunction::class,
                "function pirnt is not defined; did you mean `print`?",
            ),
            CompileErrorCase(
                "misspelled fields",
                classDeclaration("Size", "width: Int", "height: Int") +
                    function("print(Size(width = 1, height = 2).widht)"),
                JvmLoweringException.UnknownClassField::class,
                "class Size has no field widht; did you mean `width`?",
            ),
            CompileErrorCase(
                "misspelled methods",
                classDeclaration("Size", "width: Int", "fn area(): Int {", "    return width", "}") +
                    function("print(Size(2).aera())"),
                JvmLoweringException.UnknownClassMethod::class,
                "class Size has no method aera; did you mean `area`?",
            ),
            CompileErrorCase(
                "misspelled types",
                "fn show(value: Strng) {\n}",
                JvmLoweringException.UnknownClassType::class,
                "class type Strng is not defined; did you mean `String`?",
            ),
            CompileErrorCase(
                "misspelled traits",
                "class Point has Eqq {\n    x: Int\n}",
                JvmLoweringException.UnknownTrait::class,
                "trait Eqq is not defined; did you mean `Eq`?",
            ),
            CompileErrorCase(
                "Java primitive type names",
                "fn show(value: int) {\n}",
                JvmLoweringException.UnknownClassType::class,
                "class type int is not defined; Vera's integer type is `Int`",
            ),
            CompileErrorCase(
                "Java void return types",
                "fn show(): void {\n}",
                JvmLoweringException.UnknownClassType::class,
                "class type void is not defined; use `Unit`, or leave out the return type",
            ),
            CompileErrorCase(
                "Java null",
                function("val value = null"),
                JvmLoweringException.UnknownVariable::class,
                "variable null is not defined; Vera has no `null`",
            ),
            CompileErrorCase(
                "Java this",
                classDeclaration("Point", "x: Int", "fn getX(): Int {", "    return this.x", "}"),
                JvmLoweringException.UnknownVariable::class,
                "variable this is not defined; Vera has no `this`; refer to fields and methods by their names",
            ),
            CompileErrorCase(
                "String concatenation with plus",
                function("val name = \"Vera\"", "print(\"Hello \" + name)"),
                JvmLoweringException.StringConcatenationNotSupported::class,
                "Vera builds strings with templates, not `+`; write \"text \\{value}\"",
            ),
            CompileErrorCase(
                "expression bodies without a return type",
                "fn double(value: Int) = value * 2",
                JvmLoweringException.ExpressionBodyReturnTypeRequired::class,
                "function double returns Int, so its return type must be written: `fn double(...): Int = ...`",
            ),
            CompileErrorCase(
                "expression bodies of the wrong type",
                "fn double(value: Int): String = value * 2",
                JvmLoweringException.ReturnTypeMismatch::class,
                "return requires String, got Int",
            ),
            CompileErrorCase(
                "rebinding val bindings",
                function("val count = 0", "count = 1"),
                JvmLoweringException.ImmutableBindingRebound::class,
                "count is immutable; declare it with `mut val` to rebind it",
            ),
            CompileErrorCase(
                "rebinding parameters",
                "fn next(value: Int): Int {\n    value = value + 1\n    return value\n}",
                JvmLoweringException.ParameterRebound::class,
                "parameter value cannot be rebound; write `mut val value` first to make it rebindable",
            ),
            CompileErrorCase(
                "mut val without a value for a local",
                function("val count = 0", "mut val count"),
                JvmLoweringException.MutableParameterRequiresParameter::class,
                "`mut val count` without a value makes a parameter rebindable, but count is not a parameter",
            ),
            CompileErrorCase(
                "rebinding a parameter before it is made rebindable",
                "fn next(value: Int): Int {\n    value = value + 1\n    mut val value\n    return value\n}",
                JvmLoweringException.ParameterRebound::class,
                "parameter value cannot be rebound; write `mut val value` first to make it rebindable at line 2, column 5",
            ),
            CompileErrorCase(
                "mut val without a value repeated",
                "fn next(value: Int): Int {\n    mut val value\n    mut val value\n    return value\n}",
                JvmLoweringException.MutableParameterRepeated::class,
                "parameter value is already rebindable",
            ),
            CompileErrorCase(
                "mut val without a value inside a block",
                "fn next(value: Int) {\n    if True {\n        mut val value\n    }\n}",
                JvmLoweringException.MutableParameterOutsideFunctionBody::class,
                "`mut val value` must be written directly in the function body, not inside a block",
            ),
            CompileErrorCase(
                "undefined names",
                function("print(missing)"),
                JvmLoweringException.UnknownVariable::class,
                "variable missing is not defined",
            ),
            CompileErrorCase(
                "member access on undefined names",
                function("print(missing.value)"),
                JvmLoweringException.UnknownVariable::class,
                "variable missing is not defined",
            ),
            CompileErrorCase(
                "locals used after the if branch that declares them",
                function("if True {", "    val inner = 1", "}", "print(inner)"),
                JvmLoweringException.UnknownVariable::class,
                "variable inner is not defined",
            ),
            CompileErrorCase(
                "locals used after the loop body that declares them",
                function("while False {", "    val inner = 1", "}", "print(inner)"),
                JvmLoweringException.UnknownVariable::class,
                "variable inner is not defined",
            ),
            CompileErrorCase(
                "locals in nested blocks shadowing outer locals",
                function("val value = 1", "if True {", "    val value = 2", "}"),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name value is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "rebinding unknown variables",
                function("missing = 1"),
                JvmLoweringException.UnknownVariable::class,
                "variable missing is not defined",
            ),
            CompileErrorCase(
                "rebinding to a different type",
                function("mut val value = 1", "value = \"text\""),
                JvmLoweringException.RebindTypeMismatch::class,
                "cannot rebind variable value",
            ),
            CompileErrorCase(
                "returns without a value in non-Unit functions",
                "fn $TEST_MAIN(): Int {\n    return\n}",
                JvmLoweringException.MissingReturnValue::class,
                "return without an expression requires a Unit function",
            ),
            CompileErrorCase(
                "bindings that contradict their declared type",
                function("val value: Int = \"text\""),
                JvmLoweringException.BindTypeMismatch::class,
                "variable value is declared as Int but bound to a value of type String",
            ),
            CompileErrorCase(
                "non-Bool logical operands",
                function("val value = 1 and True"),
                JvmLoweringException.BoolOperandsRequired::class,
                "operator 'and' is only defined for Bool operands",
            ),
            CompileErrorCase(
                "non-Bool not operands",
                function("val value = not 1"),
                JvmLoweringException.BoolOperandsRequired::class,
                "operator 'not' is only defined for Bool operands",
            ),
            CompileErrorCase(
                "not applied to an Int comparison operand",
                function("val value = not 1 == 2"),
                JvmLoweringException.BoolOperandsRequired::class,
                "operator 'not' is only defined for Bool operands",
            ),
            CompileErrorCase(
                "binary operands with different types",
                function("val value = 1 * \"x\""),
                JvmLoweringException.OperandTypesDiffer::class,
                "operator '*' requires both operands to have the same type",
            ),
            CompileErrorCase(
                "non-Int binary operands",
                function("val value = \"x\" * \"y\""),
                JvmLoweringException.IntOperandsRequired::class,
                "operator '*' is only defined for Int operands",
            ),
            CompileErrorCase(
                "non-Int unary operands",
                function("print(-\"text\")"),
                JvmLoweringException.IntOperandsRequired::class,
                "operator '-' is only defined for Int operands",
            ),
            CompileErrorCase(
                "equality operands with different types",
                function("print(1 == True)"),
                JvmLoweringException.OperandTypesDiffer::class,
                "operator '==' requires both operands to have the same type",
            ),
            CompileErrorCase(
                "object equality without the Eq trait",
                classDeclaration("Point", "x: Int") + function("print(Point(1) == Point(1))"),
                JvmLoweringException.ObjectEqualityRequiresTrait::class,
                "class Point does not have the Eq trait",
            ),
            CompileErrorCase(
                "ordering Bool operands",
                function("print(True < False)"),
                JvmLoweringException.IntOperandsRequired::class,
                "operator '<' is only defined for Int operands, got Bool",
            ),
            CompileErrorCase(
                "if branches with different types",
                function("val value = if True {", "    1", "} else {", "    \"no\"", "}"),
                JvmLoweringException.IfBranchTypeMismatch::class,
                "if branches must have the same type",
            ),
            CompileErrorCase(
                "forward application to a non-callable value",
                function("print(1 |> 2)"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must be a callable name or member call",
            ),
            CompileErrorCase(
                "forward application to a member access without a call",
                function("print(1 |> value.name)"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must be a callable name or member call",
            ),
            CompileErrorCase(
                "forward application calling a call result",
                function("print(1 |> f()())"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' cannot call the result of another call",
            ),
            CompileErrorCase(
                "forward application groups an or-expression on the right",
                function("print(1 |> f or True)"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must be a callable name or member call",
            ),
            CompileErrorCase(
                "calls to unknown functions",
                function("missing()"),
                JvmLoweringException.UnknownFunction::class,
                "function missing is not defined",
            ),
            CompileErrorCase(
                "function calls with the wrong arity",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    "fn testMain(): Int {\n    return add(1)\n}",
                JvmLoweringException.FunctionArityMismatch::class,
                "function add requires 2 arguments, got 1",
            ),
            CompileErrorCase(
                "unnamed arguments in multi-argument calls",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    function("print(add(1, 2))"),
                JvmLoweringException.ArgumentNameRequired::class,
                "argument 1 of function add must be named: write `left = ...`",
            ),
            CompileErrorCase(
                "named arguments out of parameter order",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    function("print(add(right = 2, left = 1))"),
                JvmLoweringException.ArgumentNameMismatch::class,
                "argument 1 of function add is named right, but parameter 1 is left; " +
                    "arguments must follow the parameter order",
            ),
            CompileErrorCase(
                "shorthand arguments out of parameter order",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    function("val left = 1", "val right = 2", "print(add(right, left))"),
                JvmLoweringException.ArgumentNameMismatch::class,
                "argument 1 of function add is named right, but parameter 1 is left",
            ),
            CompileErrorCase(
                "shorthand arguments that match no parameter",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    function("val first = 1", "print(add(first, right = 2))"),
                JvmLoweringException.ArgumentNameRequired::class,
                "argument 1 of function add must be named: write `left = ...`",
            ),
            CompileErrorCase(
                "unnamed constructor arguments",
                classDeclaration("Point", "x: Int", "y: Int") + function("print(Point(1, 2).x)"),
                JvmLoweringException.ArgumentNameRequired::class,
                "argument 1 of class Point must be named: write `x = ...`",
            ),
            CompileErrorCase(
                "unnamed method arguments",
                classDeclaration("Box", "base: Int", "fn plus(a: Int, b: Int): Int {", "    return a + b", "}") +
                    function("print(Box(1).plus(a = 1, 2))"),
                JvmLoweringException.ArgumentNameRequired::class,
                "argument 2 of method Box.plus must be named: write `b = ...`",
            ),
            CompileErrorCase(
                "a named single written argument after a forwarded value",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    function("print(1 |> add(right = 2))"),
                JvmLoweringException.SingleArgumentNamed::class,
                "a call to function add with one written argument passes it without a name; remove `right =`",
            ),
            CompileErrorCase(
                "unnamed written arguments after a forwarded value",
                "fn clamp(value: Int, low: Int, high: Int): Int {\n    return value\n}\n" +
                    function("print(42 |> clamp(0, high = 10))"),
                JvmLoweringException.ArgumentNameRequired::class,
                "argument 2 of function clamp must be named: write `low = ...`",
            ),
            CompileErrorCase(
                "named single arguments",
                "fn double(value: Int): Int {\n    return value * 2\n}\n" + function("print(double(value = 2))"),
                JvmLoweringException.SingleArgumentNamed::class,
                "a call to function double with one written argument passes it without a name; remove `value =`",
            ),
            CompileErrorCase(
                "named print arguments",
                function("print(value = 1)"),
                JvmLoweringException.SingleArgumentNamed::class,
                "a call to print with one written argument passes it without a name",
            ),
            CompileErrorCase(
                "trait methods with renamed parameters",
                traitDeclaration("Scaled", "fn scale(by: Int): Int") +
                    "\nclass Counter has Scaled {\n    value: Int\n" +
                    "    fn scale(amount: Int): Int {\n        return value * amount\n    }\n}",
                JvmLoweringException.TraitMethodParameterNameMismatch::class,
                "class Counter method scale names a parameter amount, but trait Scaled names it by",
            ),
            CompileErrorCase(
                "function arguments with the wrong type",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    "fn testMain(): Int {\n    return add(left = 1, right = \"wrong\")\n}",
                JvmLoweringException.FunctionArgumentTypeMismatch::class,
                "function add parameter right requires Int, got String",
            ),
            CompileErrorCase(
                "function return values with the wrong type",
                "fn testMain(): Int {\n    return \"wrong\"\n}",
                JvmLoweringException.ReturnTypeMismatch::class,
                "return requires Int, got String",
            ),
            CompileErrorCase(
                "Unit functions returning a value",
                "fn testMain() {\n    return 1\n}",
                JvmLoweringException.ReturnTypeMismatch::class,
                "return requires Unit, got Int",
            ),
            CompileErrorCase(
                "non-Unit functions without a return statement",
                "fn testMain(): Int {\n}",
                JvmLoweringException.MissingReturnStatement::class,
                "missing return statement for type Int",
            ),
            CompileErrorCase(
                "top-level functions conflicting with builtins",
                "fn print() { }",
                JvmLoweringException.FunctionNameConflictsWithBuiltin::class,
                "function print conflicts with the built-in function print",
            ),
            CompileErrorCase(
                "builtin calls with the wrong arity",
                function("print(1, 2)"),
                JvmLoweringException.BuiltinArityMismatch::class,
                "print requires exactly 1 argument",
            ),
            CompileErrorCase(
                "invalid type names",
                "fn $TEST_MAIN(): Nope { }",
                JvmLoweringException.UnknownClassType::class,
                "class type Nope is not defined",
            ),
            CompileErrorCase(
                "member access on non-object values",
                function("val x = 1", "print(x.foo)"),
                JvmLoweringException.MemberAccessRequiresObject::class,
                "member access requires an object",
            ),
            CompileErrorCase(
                "objects without the Display trait in string templates",
                classDeclaration("Point", "x: Int") + function("print(\"at \\{Point(1)}\")"),
                JvmLoweringException.TemplateValueRequiresDisplayTrait::class,
                "class Point does not have the Display trait, so its objects cannot be used in a string template",
            ),
            CompileErrorCase(
                "printing objects without the Display trait",
                classDeclaration("Point", "x: Int") + function("print(Point(1))"),
                JvmLoweringException.PrintRequiresDisplayTrait::class,
                "class Point does not have the Display trait",
            ),
            CompileErrorCase(
                "unknown traits",
                "class Point has Missing {\n    x: Int\n}",
                JvmLoweringException.UnknownTrait::class,
                "trait Missing is not defined",
            ),
            CompileErrorCase(
                "trait methods that are not implemented",
                traitDeclaration("Shape", "fn area(): Int") +
                    "\nclass Square has Shape {\n    side: Int\n}",
                JvmLoweringException.MissingTraitMethod::class,
                "class Square has trait Shape but no method area",
            ),
            CompileErrorCase(
                "trait methods with the wrong signature",
                traitDeclaration("Shape", "fn area(): Int") +
                    "\nclass Square has Shape {\n    side: Int\n" +
                    "    fn area(): String {\n        return \"x\"\n    }\n}",
                JvmLoweringException.TraitMethodSignatureMismatch::class,
                "class Square method area does not match trait Shape: expected () -> Int, got () -> String",
            ),
            CompileErrorCase(
                "traits listed more than once",
                "class Point has Eq, Eq {\n    x: Int\n}",
                JvmLoweringException.DuplicateTraitImplementation::class,
                "class Point has trait Eq more than once",
            ),
            CompileErrorCase(
                "duplicate trait declarations",
                traitDeclaration("Shape") + traitDeclaration("Shape"),
                JvmLoweringException.DuplicateTrait::class,
                "trait Shape is declared more than once",
            ),
            CompileErrorCase(
                "traits conflicting with builtin traits",
                traitDeclaration("Eq"),
                JvmLoweringException.NameConflictsWithBuiltinTrait::class,
                "name Eq is reserved for a built-in trait",
            ),
            CompileErrorCase(
                "declarations conflicting with builtin traits",
                classDeclaration("Display", "x: Int"),
                JvmLoweringException.NameConflictsWithBuiltinTrait::class,
                "name Display is reserved for a built-in trait",
            ),
            CompileErrorCase(
                "Eq fields whose types lack Eq",
                "class Point {\n    x: Int\n}\nclass Line has Eq {\n    start: Point\n}",
                JvmLoweringException.ClassFieldRequiresTrait::class,
                "class Line field start has type Point, which does not have the Eq trait",
            ),
            CompileErrorCase(
                "Display fields whose types lack Display",
                "class Point {\n    x: Int\n}\nclass Line has Display {\n    start: Point\n}",
                JvmLoweringException.ClassFieldRequiresTrait::class,
                "class Line field start has type Point, which does not have the Display trait",
            ),
            CompileErrorCase(
                "class methods conflicting with generated trait methods",
                "class Point has Eq {\n    x: Int\n" +
                    "    fn equals(other: Point): Bool {\n        return True\n    }\n}",
                JvmLoweringException.ClassMethodConflictsWithGeneratedMethod::class,
                "class Point method equals conflicts with the generated method for trait Eq",
            ),
            CompileErrorCase(
                "class methods conflicting with generated Display methods",
                "class Point has Display {\n    x: Int\n" +
                    "    fn toString(): String {\n        return \"point\"\n    }\n}",
                JvmLoweringException.ClassMethodConflictsWithGeneratedMethod::class,
                "class Point method toString conflicts with the generated method for trait Display",
            ),
            CompileErrorCase(
                "forwarding objects without the Display trait to print",
                classDeclaration("Point", "x: Int") + function("Point(1) |> print"),
                JvmLoweringException.PrintRequiresDisplayTrait::class,
                "class Point does not have the Display trait",
            ),
            CompileErrorCase(
                "duplicate trait method parameters",
                traitDeclaration("Shape", "fn scale(by: Int, by: Int): Int"),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name by is already in scope",
            ),
            CompileErrorCase(
                "trailing commas in has clauses",
                "class Point has Eq, {\n    x: Int\n}",
                VeraParseException.ExpectedToken::class,
                "expected trait name",
            ),
            CompileErrorCase(
                "empty has clauses",
                "class Point has {\n    x: Int\n}",
                VeraParseException.ExpectedToken::class,
                "expected trait name",
            ),
            CompileErrorCase(
                "duplicate trait methods",
                traitDeclaration("Shape", "fn area(): Int", "fn area(): Bool"),
                JvmLoweringException.DuplicateTraitMethod::class,
                "trait Shape has duplicate method area",
            ),
            CompileErrorCase(
                "unknown trait method parameter types",
                traitDeclaration("Shape", "fn area(value: Missing): Int"),
                JvmLoweringException.UnknownClassType::class,
                "class type Missing is not defined",
            ),
            CompileErrorCase(
                "traits conflicting with class names",
                traitDeclaration("Point") + "class Point {\n    x: Int\n}",
                JvmLoweringException.TopLevelNameConflict::class,
                "top-level name Point is declared more than once",
            ),
            CompileErrorCase(
                "duplicate class fields",
                classDeclaration("Point", "x: Int", "x: String"),
                JvmLoweringException.DuplicateClassField::class,
                "class Point has duplicate field x",
            ),
            CompileErrorCase(
                "unknown class field types",
                classDeclaration("Point", "x: Missing"),
                JvmLoweringException.UnknownClassType::class,
                "class type Missing is not defined",
            ),
            CompileErrorCase(
                "duplicate class types",
                classDeclaration("Point", "x: Int") + classDeclaration("Point", "y: Int"),
                JvmLoweringException.DuplicateClassType::class,
                "class Point is declared more than once",
            ),
            CompileErrorCase(
                "class names conflicting with the program class",
                classDeclaration("ErrorTest", "x: Int"),
                JvmLoweringException.ClassNameConflictsWithProgramClass::class,
                "class ErrorTest conflicts with the generated program class",
            ),
            CompileErrorCase(
                "class constructors with the wrong arity",
                classDeclaration("Point", "x: Int") + function("val point = Point()"),
                JvmLoweringException.ClassConstructorArityMismatch::class,
                "class Point constructor requires 1 argument, got 0",
            ),
            CompileErrorCase(
                "class constructor arguments with the wrong type",
                classDeclaration("Point", "x: Int") + function("val point = Point(\"wrong\")"),
                JvmLoweringException.ClassConstructorArgumentTypeMismatch::class,
                "class Point field x requires Int, got String",
            ),
            CompileErrorCase(
                "unknown class fields",
                classDeclaration("Point", "x: Int") + function("print(Point(1).y)"),
                JvmLoweringException.UnknownClassField::class,
                "class Point has no field y",
            ),
            CompileErrorCase(
                "class types used as values",
                classDeclaration("Point", "x: Int") + function("val point = Point"),
                JvmLoweringException.ClassTypeUsedAsValue::class,
                "class type Point cannot be used as a value",
            ),
            CompileErrorCase(
                "calling class fields",
                classDeclaration("Point", "x: Int") + function("print(Point(1).x())"),
                JvmLoweringException.ValueCallNotSupported::class,
                "calling a value is not supported yet",
            ),
            CompileErrorCase(
                "unknown class methods",
                classDeclaration("Point", "x: Int") + function("print(Point(1).missing())"),
                JvmLoweringException.UnknownClassMethod::class,
                "class Point has no method missing",
            ),
            CompileErrorCase(
                "class methods called with the wrong arity",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn add(other: Int): Int {",
                    "    return x + other",
                    "}",
                ) + function("print(Point(1).add())"),
                JvmLoweringException.MethodArityMismatch::class,
                "class Point method add requires 1 argument, got 0",
            ),
            CompileErrorCase(
                "class method arguments with the wrong type",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn add(other: Int): Int {",
                    "    return x + other",
                    "}",
                ) + function("print(Point(1).add(\"wrong\"))"),
                JvmLoweringException.MethodArgumentTypeMismatch::class,
                "class Point method add parameter other requires Int, got String",
            ),
            CompileErrorCase(
                "class method return values with the wrong type",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn value(): Int {",
                    "    return \"wrong\"",
                    "}",
                ),
                JvmLoweringException.ReturnTypeMismatch::class,
                "return requires Int, got String",
            ),
            CompileErrorCase(
                "duplicate class methods",
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
            ),
            CompileErrorCase(
                "class methods conflicting with fields",
                classDeclaration(
                    "Point",
                    "value: Int",
                    "fn value(): Int {",
                    "    return value",
                    "}",
                ),
                JvmLoweringException.ClassMethodConflictsWithField::class,
                "class Point has both a field and a method named value",
            ),
            CompileErrorCase(
                "class methods used as values through member access",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn value(): Int {",
                    "    return x",
                    "}",
                ) + function("print(Point(1).value)"),
                JvmLoweringException.ClassMethodUsedAsValue::class,
                "class Point method value must be called",
            ),
            CompileErrorCase(
                "class methods used as bare values",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn value(): Int {",
                    "    return x",
                    "}",
                    "fn other(): Int {",
                    "    return value",
                    "}",
                ),
                JvmLoweringException.ClassMethodUsedAsValue::class,
                "class Point method value must be called",
            ),
            CompileErrorCase(
                "duplicate parameters",
                "fn testMain(value: Int, value: Int) { }",
                JvmLoweringException.ShadowingNotAllowed::class,
                "name value is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "parameters shadowing fields",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn compute(x: Int): Int {",
                    "    return x",
                    "}",
                ),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name x is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "locals shadowing fields",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn compute(): Int {",
                    "    val x = 1",
                    "    return x",
                    "}",
                ),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name x is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "locals in nested blocks shadowing fields",
                classDeclaration(
                    "Point",
                    "x: Int",
                    "fn compute(): Int {",
                    "    if True {",
                    "        val x = 1",
                    "    }",
                    "    return x",
                    "}",
                ),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name x is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "locals shadowing parameters",
                "fn testMain(value: Int) {\n    val value = 1\n}",
                JvmLoweringException.ShadowingNotAllowed::class,
                "name value is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "locals shadowing locals",
                function("val value = 1", "val value = 2"),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name value is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "parameters shadowing top-level functions",
                "fn helper() { }\nfn testMain(helper: Int) { }",
                JvmLoweringException.ShadowingNotAllowed::class,
                "name helper is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "class fields shadowing builtins",
                classDeclaration("Point", "print: Int"),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name print is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "class methods shadowing top-level functions",
                "fn helper() { }\n" + classDeclaration("Point", "fn helper() { }"),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name helper is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "class methods shadowing class names",
                classDeclaration("Point", "fn Point() { }"),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name Point is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "classes conflicting with builtins",
                "class print { }",
                JvmLoweringException.ClassNameConflictsWithBuiltin::class,
                "class print conflicts with the built-in function print",
            ),
            CompileErrorCase(
                "duplicate top-level functions",
                "fn helper() { }\nfn helper() { }",
                JvmLoweringException.DuplicateFunction::class,
                "function helper is declared more than once",
            ),
            CompileErrorCase(
                "top-level functions conflicting with objects",
                "class Conflict { }\nfn Conflict() { }",
                JvmLoweringException.TopLevelNameConflict::class,
                "top-level name Conflict is declared more than once",
            ),
        )

        private fun jvmMethod(statements: List<JvmStatement>): JvmMethod = JvmMethod(
            Identifier(TEST_MAIN),
            emptyList(),
            JvmType.VOID,
            JvmExplicitReturnBlock(statements),
        )

        private fun jvmProgram(method: JvmMethod): JvmProgram = JvmProgram(
            listOf(JvmClass(Identifier("ErrorTest"), listOf(method))),
        )

        private fun methodBuilder(): FlatMethodBuilder = FlatMethodBuilder(
            Identifier(TEST_MAIN),
            emptyList(),
            JvmType.VOID,
        )

        private fun emit(method: FlatMethod): Map<Identifier, ByteArray> =
            BytecodeEmitter.emit(
                FlatProgram(
                    listOf(FlatClass(Identifier("ErrorTest"), listOf(method))),
                ),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lexerErrors")
    fun `lexer errors are reported`(case: CompileErrorCase) {
        assertCompilationFails(case)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parserErrors")
    fun `parser errors are reported`(case: CompileErrorCase) {
        assertCompilationFails(case)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loweringErrors")
    fun `lowering errors are reported`(case: CompileErrorCase) {
        assertCompilationFails(case)
    }

    @Test
    fun `unknown names without a close match get no suggestion`() {
        val error = Assertions.assertThrows(JvmLoweringException.UnknownVariable::class.java) {
            VeraCompiler.compile(Identifier("ErrorTest"), function("val count = 1", "print(total)"))
        }
        Assertions.assertEquals("variable total is not defined", error.message)
    }

    @Test
    fun `diagnostics underline the expression that caused a lowering error`() {
        assertRendersAs(
            "fn testMain() {\n    print(1 + True)\n}\n",
            """
            error: operator '+' requires both operands to have the same type, got Int and Bool
             --> main.vera:2:11
              |
            2 |     print(1 + True)
              |           ^^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics underline the offending argument`() {
        assertRendersAs(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                "fn testMain() {\n    print(add(1, right = 2))\n}\n",
            """
            error: argument 1 of function add must be named: write `left = ...`
             --> main.vera:5:15
              |
            5 |     print(add(1, right = 2))
              |               ^
            """,
        )
    }

    @Test
    fun `diagnostics point at the unexpected token of a parse error`() {
        assertRendersAs(
            "fn testMain() {\n    val value =\n}\n",
            """
            error: expected an expression, found '}'
             --> main.vera:3:1
              |
            3 | }
              | ^
            """,
        )
    }

    @Test
    fun `diagnostics point at the character of a lexer error`() {
        assertRendersAs(
            "fn testMain() {\n    print(1);\n}\n",
            """
            error: Vera does not use semicolons; end the statement with a newline
             --> main.vera:2:13
              |
            2 |     print(1);
              |             ^
            """,
        )
    }

    @Test
    fun `diagnostics underline the header of a conflicting declaration`() {
        assertRendersAs(
            "fn helper() {\n}\nfn helper() {\n}\n",
            """
            error: function helper is declared more than once
             --> main.vera:3:1
              |
            3 | fn helper() {
              | ^^^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics underline a multi-line span to the end of its first line`() {
        assertRendersAs(
            "fn testMain() {\n    val value = if True {\n        1\n    } else {\n        \"a\"\n    }\n}\n",
            """
            error: if branches must have the same type, got Int and String
             --> main.vera:2:17
              |
            2 |     val value = if True {
              |                 ^^^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics widen the gutter for multi-digit line numbers`() {
        assertRendersAs(
            "fn testMain() {\n" + "    print(1)\n".repeat(8) + "    print(missing)\n}\n",
            """
            error: variable missing is not defined
              --> main.vera:10:11
               |
            10 |     print(missing)
               |           ^^^^^^^
            """,
        )
    }

    @Test
    fun `diagnostics without a source location show only the message`() {
        val error = Assertions.assertThrows(JvmLoweringException.ProgramClassConflictsWithUnit::class.java) {
            VeraCompiler.compile(Identifier("Unit"), "fn main() {\n}\n")
        }
        Assertions.assertEquals(
            "error: program class Unit conflicts with the built-in Unit class",
            VeraDiagnostics.render(error, "fn main() {\n}\n", "main.vera"),
        )
    }

    private fun assertRendersAs(source: String, expected: String) {
        val error = try {
            VeraCompiler.compile(Identifier("ErrorTest"), source)
            throw AssertionError("Expected compilation to fail, but it succeeded")
        } catch (exception: VeraException) {
            exception
        }
        Assertions.assertEquals(expected.trimIndent(), VeraDiagnostics.render(error, source, "main.vera"))
    }

    @Test
    fun `instruction builder rejects stack underflow`() {
        assertCompilerError<FlatInstructionException.StackUnderflow>("stack underflow") {
            methodBuilder().addInstruction(FlatPop(JvmType.INT))
        }
    }

    @Test
    fun `instruction builder rejects stack type mismatches`() {
        assertCompilerError<FlatInstructionException.StackTypeMismatch>("invalid stack type") {
            val builder = methodBuilder()
            builder.addInstruction(FlatLoadConstant(JvmType.BOOL, true))
            builder.addInstruction(FlatPop(JvmType.INT))
        }
    }

    @Test
    fun `instruction builder rejects out-of-range parameter indexes`() {
        assertCompilerError<FlatInstructionException.InternalInvariant>("parameter index 1 is out of bounds") {
            methodBuilder().parameterLocal(1)
        }
    }

    @Test
    fun `instruction lowering rejects undefined locals`() {
        val missingLocal = JvmLocal(Identifier("missing"), JvmType.INT)
        assertCompilerError<FlatInstructionException.UndefinedLocal>("local missing is not defined") {
            FlatInstructionLowering.lower(
                jvmProgram(jvmMethod(listOf(JvmRebind(missingLocal, JvmIntLiteral(1))))),
            )
        }
    }

    @Test
    fun `instruction lowering rejects builtin arity mismatches`() {
        val printCall = JvmBuiltinCall(
            JvmBuiltin.PRINT,
            listOf(JvmIntLiteral(1), JvmIntLiteral(2)),
        )
        assertCompilerError<FlatInstructionException.BuiltinArityMismatch>("print requires exactly 1 argument") {
            FlatInstructionLowering.lower(
                jvmProgram(jvmMethod(listOf(JvmExpressionStatement(printCall)))),
            )
        }
    }

    @Test
    fun `instruction lowering rejects break outside loops`() {
        assertCompilerError<FlatInstructionException.BreakOutsideLoop>("break is only allowed inside a loop") {
            FlatInstructionLowering.lower(jvmProgram(jvmMethod(listOf(JvmBreak))))
        }
    }

    @Test
    fun `instruction lowering rejects continue outside loops`() {
        assertCompilerError<FlatInstructionException.ContinueOutsideLoop>("continue is only allowed inside a loop") {
            FlatInstructionLowering.lower(jvmProgram(jvmMethod(listOf(JvmContinue))))
        }
    }

    @Test
    fun `instruction lowering rejects loop jumps with values on the stack`() {
        // `1 + if True { break; 0 } else { 1 }` would leave `1` on the stack when jumping.
        val jvmIf = JvmIfExpression(
            JvmBoolLiteral(true),
            JvmImplicitReturnBlock(listOf(JvmBreak), JvmIntLiteral(0)),
            JvmImplicitReturnBlock(emptyList(), JvmIntLiteral(1)),
        )
        val jvmLoop = JvmWhile(
            JvmBoolLiteral(true),
            JvmUnitReturnBlock(
                listOf(JvmExpressionStatement(JvmIntBinary(JvmIntBinaryOperator.ADD, JvmIntLiteral(1), jvmIf))),
            ),
        )
        assertCompilerError<FlatInstructionException.LoopJumpStackMismatch>(
            "break must leave the operand stack at depth 0, but it has depth 1",
        ) {
            FlatInstructionLowering.lower(jvmProgram(jvmMethod(listOf(jvmLoop))))
        }
    }

    @Test
    fun `bytecode emission rejects unbound labels`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatJump(FlatLabel()))
        assertCompilerError<BytecodeEmitException.UnboundLabel>("unbound label") {
            emit(builder.build())
        }
    }

    @Test
    fun `bytecode emission rejects unsupported constants`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatLoadConstant(JvmType.OBJECT, "value"))
        assertCompilerError<BytecodeEmitException.UnsupportedConstant>("unsupported constant type") {
            emit(builder.build())
        }
    }

    @Test
    fun `bytecode emission rejects constants of the wrong type`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatLoadConstant(JvmType.INT, "not an Int"))
        assertCompilerError<BytecodeEmitException.InvalidConstant>("invalid constant for type INT") {
            emit(builder.build())
        }
    }

    @Test
    fun `program class name cannot collide with Unit`() {
        assertCompilerError<JvmLoweringException.ProgramClassConflictsWithUnit>(
            "program class Unit conflicts with the built-in Unit class",
        ) {
            VeraCompiler.compile(Identifier("Unit"), "fn testMain() { }")
        }
    }

    private fun assertCompilationFails(case: CompileErrorCase) {
        val error = try {
            VeraCompiler.compile(Identifier("ErrorTest"), case.source)
            throw AssertionError("Expected compilation to fail, but it succeeded")
        } catch (exception: VeraException) {
            exception
        }
        assertMatches(error, case.expectedType, case.messagePart)
        Assertions.assertNotNull(error.span, "Expected a source location for: ${error.render()}")
    }

    private inline fun <reified T : VeraException> assertCompilerError(
        messagePart: String,
        crossinline block: () -> Unit,
    ) {
        val error = Assertions.assertThrows(T::class.java) { block() }
        assertMatches(error, T::class, messagePart)
    }

    private fun assertMatches(
        error: VeraException,
        expectedType: KClass<out VeraException>,
        messagePart: String,
    ) {
        Assertions.assertTrue(
            expectedType.isInstance(error),
            "Expected ${expectedType.simpleName}, but got ${error::class.simpleName}: ${error.render()}",
        )
        Assertions.assertTrue(
            error.render().contains(messagePart),
            "Expected error message to contain '$messagePart', but was '${error.render()}'",
        )
    }
}
