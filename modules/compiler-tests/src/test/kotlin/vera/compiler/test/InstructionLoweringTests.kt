package vera.compiler.test

import org.junit.jupiter.api.Test
import vera.jvm.instruction.FlatInstructionException
import vera.jvm.instruction.FlatLoadConstant
import vera.jvm.instruction.FlatMethodBuilder
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
import vera.shared.model.Identifier

/** Flat instruction lowering invariants, checked with hand-built IR. */
class InstructionLoweringTests {
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

    private fun methodBuilder(): FlatMethodBuilder = FlatMethodBuilder(
        Identifier("main"),
        emptyList(),
        JvmType.VOID,
    )

    private fun jvmMethod(statements: List<JvmStatement>): JvmMethod = JvmMethod(
        Identifier("main"),
        emptyList(),
        JvmType.VOID,
        JvmExplicitReturnBlock(statements),
    )

    private fun jvmProgram(method: JvmMethod): JvmProgram = JvmProgram(
        listOf(JvmClass(Identifier("ErrorTest"), listOf(method))),
    )
}
