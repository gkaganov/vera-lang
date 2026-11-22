package vera.compiler.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import vera.compiler.VeraCompiler
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
        )

        @JvmStatic
        fun parserErrors(): List<CompileErrorCase> = listOf(
            CompileErrorCase(
                "non-function declarations",
                "var value = 1",
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
                function("var value ="),
                VeraParseException.ExpectedExpression::class,
                "expected an expression",
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
                    "    var value = 1 + if True {",
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
                function("if True {", "    var inner = 1", "}", "print(inner)"),
                JvmLoweringException.UnknownVariable::class,
                "variable inner is not defined",
            ),
            CompileErrorCase(
                "locals used after the loop body that declares them",
                function("while False {", "    var inner = 1", "}", "print(inner)"),
                JvmLoweringException.UnknownVariable::class,
                "variable inner is not defined",
            ),
            CompileErrorCase(
                "locals in nested blocks shadowing outer locals",
                function("var value = 1", "if True {", "    var value = 2", "}"),
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
                function("var value = 1", "value = \"text\""),
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
                function("var value: Int = \"text\""),
                JvmLoweringException.BindTypeMismatch::class,
                "the type of the bind statement must either UNKNOWN",
            ),
            CompileErrorCase(
                "non-Bool logical operands",
                function("var value = 1 and True"),
                JvmLoweringException.BoolOperandsRequired::class,
                "operator 'and' is only defined for Bool operands",
            ),
            CompileErrorCase(
                "binary operands with different types",
                function("var value = 1 + \"x\""),
                JvmLoweringException.OperandTypesDiffer::class,
                "operator '+' requires both operands to have the same type",
            ),
            CompileErrorCase(
                "non-Int binary operands",
                function("var value = \"x\" + \"y\""),
                JvmLoweringException.IntOperandsRequired::class,
                "operator '+' is only defined for Int operands",
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
                "operator '<' is only defined for Int operands, got BOOL",
            ),
            CompileErrorCase(
                "if branches with different types",
                function("var value = if True {", "    1", "} else {", "    \"no\"", "}"),
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
                "function missing not found.",
            ),
            CompileErrorCase(
                "function calls with the wrong arity",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    "fn testMain(): Int {\n    return add(1)\n}",
                JvmLoweringException.FunctionArityMismatch::class,
                "function add requires 2 arguments, got 1",
            ),
            CompileErrorCase(
                "function arguments with the wrong type",
                "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                    "fn testMain(): Int {\n    return add(1, \"wrong\")\n}",
                JvmLoweringException.FunctionArgumentTypeMismatch::class,
                "function add parameter right requires INT, got STRING",
            ),
            CompileErrorCase(
                "function return values with the wrong type",
                "fn testMain(): Int {\n    return \"wrong\"\n}",
                JvmLoweringException.ReturnTypeMismatch::class,
                "return requires INT, got STRING",
            ),
            CompileErrorCase(
                "Unit functions returning a value",
                "fn testMain() {\n    return 1\n}",
                JvmLoweringException.ReturnTypeMismatch::class,
                "return requires Unit, got INT",
            ),
            CompileErrorCase(
                "non-Unit functions without a return statement",
                "fn testMain(): Int {\n}",
                JvmLoweringException.MissingReturnStatement::class,
                "missing return statement for type INT",
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
                function("var x = 1", "print(x.foo)"),
                JvmLoweringException.MemberAccessRequiresObject::class,
                "member access requires an object",
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
                "class Square method area does not match trait Shape: expected () -> INT, got () -> STRING",
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
                classDeclaration("Point", "x: Int") + function("var point = Point()"),
                JvmLoweringException.ClassConstructorArityMismatch::class,
                "class Point constructor requires 1 argument, got 0",
            ),
            CompileErrorCase(
                "class constructor arguments with the wrong type",
                classDeclaration("Point", "x: Int") + function("var point = Point(\"wrong\")"),
                JvmLoweringException.ClassConstructorArgumentTypeMismatch::class,
                "class Point field x requires INT, got STRING",
            ),
            CompileErrorCase(
                "unknown class fields",
                classDeclaration("Point", "x: Int") + function("print(Point(1).y)"),
                JvmLoweringException.UnknownClassField::class,
                "class Point has no field y",
            ),
            CompileErrorCase(
                "class types used as values",
                classDeclaration("Point", "x: Int") + function("var point = Point"),
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
                "class Point method add parameter other requires INT, got STRING",
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
                "return requires INT, got STRING",
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
                    "    var x = 1",
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
                    "        var x = 1",
                    "    }",
                    "    return x",
                    "}",
                ),
                JvmLoweringException.ShadowingNotAllowed::class,
                "name x is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "locals shadowing parameters",
                "fn testMain(value: Int) {\n    var value = 1\n}",
                JvmLoweringException.ShadowingNotAllowed::class,
                "name value is already in scope; shadowing is not allowed",
            ),
            CompileErrorCase(
                "locals shadowing locals",
                function("var value = 1", "var value = 2"),
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
