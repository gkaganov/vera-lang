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
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmClass
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmIntLiteral
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmProgram
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmType
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

        private fun record(name: String, vararg lines: String): String = buildString {
            appendLine("record $name {")
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
                "expected a function or record declaration",
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
                "if branches with different types",
                function("var value = if True {", "    1", "} else {", "    \"no\"", "}"),
                JvmLoweringException.IfBranchTypeMismatch::class,
                "if branches must have the same type",
            ),
            CompileErrorCase(
                "forward application to a non-function",
                function("print(1 |> 2)"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must be a function name",
            ),
            CompileErrorCase(
                "forward application containing member access",
                function("print(1 |> value.name)"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must not contain member access",
            ),
            CompileErrorCase(
                "forward application with multiple argument lists",
                function("print(1 |> f()())"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must contain at most one argument list",
            ),
            CompileErrorCase(
                "forward application groups an or-expression on the right",
                function("print(1 |> f or True)"),
                JvmLoweringException.InvalidForwardApplication::class,
                "right-hand side of '|>' must be a function name",
            ),
            CompileErrorCase(
                "calls to unknown functions",
                function("missing()"),
                JvmLoweringException.UnknownFunction::class,
                "function missing not found.",
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
                JvmLoweringException.UnknownRecordType::class,
                "record type Nope is not defined",
            ),
            CompileErrorCase(
                "member access on non-record values",
                function("var x = 1", "print(x.foo)"),
                JvmLoweringException.MemberAccessRequiresRecord::class,
                "member access requires a record value",
            ),
            CompileErrorCase(
                "printing records",
                record("Point", "x: Int") + function("print(Point(1))"),
                JvmLoweringException.PrintArgumentTypeNotSupported::class,
                "print does not support values of type Point",
            ),
            CompileErrorCase(
                "printing Unit",
                function("print(Unit)"),
                JvmLoweringException.PrintArgumentTypeNotSupported::class,
                "print does not support values of type Unit",
            ),
            CompileErrorCase(
                "duplicate record fields",
                record("Point", "x: Int", "x: String"),
                JvmLoweringException.DuplicateRecordField::class,
                "record Point has duplicate field x",
            ),
            CompileErrorCase(
                "unknown record field types",
                record("Point", "x: Missing"),
                JvmLoweringException.UnknownRecordType::class,
                "record type Missing is not defined",
            ),
            CompileErrorCase(
                "duplicate record types",
                record("Point", "x: Int") + record("Point", "y: Int"),
                JvmLoweringException.DuplicateRecordType::class,
                "record Point is declared more than once",
            ),
            CompileErrorCase(
                "record names conflicting with the program class",
                record("ErrorTest", "x: Int"),
                JvmLoweringException.RecordNameConflictsWithProgramClass::class,
                "record ErrorTest conflicts with the generated program class",
            ),
            CompileErrorCase(
                "record constructors with the wrong arity",
                record("Point", "x: Int") + function("var point = Point()"),
                JvmLoweringException.RecordConstructorArityMismatch::class,
                "record Point constructor requires 1 arguments, got 0",
            ),
            CompileErrorCase(
                "record constructor arguments with the wrong type",
                record("Point", "x: Int") + function("var point = Point(\"wrong\")"),
                JvmLoweringException.RecordConstructorArgumentTypeMismatch::class,
                "record Point field x requires INT, got STRING",
            ),
            CompileErrorCase(
                "unknown record fields",
                record("Point", "x: Int") + function("print(Point(1).y)"),
                JvmLoweringException.UnknownRecordField::class,
                "record Point has no field y",
            ),
            CompileErrorCase(
                "record types used as values",
                record("Point", "x: Int") + function("var point = Point"),
                JvmLoweringException.RecordTypeUsedAsValue::class,
                "record type Point cannot be used as a value",
            ),
            CompileErrorCase(
                "calling record fields",
                record("Point", "x: Int") + function("print(Point(1).x())"),
                JvmLoweringException.ValueCallNotSupported::class,
                "calling a value is not supported yet",
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
