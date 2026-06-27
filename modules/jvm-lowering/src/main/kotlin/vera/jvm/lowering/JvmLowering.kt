package vera.jvm.lowering

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import vera.ast.BindStatement
import vera.ast.BoolLiteral
import vera.ast.ChainedExpression
import vera.ast.Expression
import vera.ast.IdentifierAsExpression
import vera.ast.IfExpression
import vera.ast.InfixOperator
import vera.ast.IntLiteral
import vera.ast.PrimaryExpression
import vera.ast.RebindStatement
import vera.ast.ReturnStatement
import vera.ast.StringLiteral
import vera.ast.VeraArguments
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraMemberAccess
import vera.ast.VeraStatement
import vera.ast.VeraType
import vera.jvm.ir.BindLabel
import vera.jvm.ir.CreateLabel
import vera.jvm.ir.IfFalseJumpTo
import vera.jvm.ir.IntBinaryOperation
import vera.jvm.ir.IntBinaryOperator
import vera.jvm.ir.Invokestatic
import vera.jvm.ir.JumpTo
import vera.jvm.ir.JvmLabel
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodBuilder
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmType
import vera.jvm.ir.LoadConstant
import vera.jvm.ir.LoadLocal
import vera.jvm.ir.LocalTable
import vera.jvm.ir.LocalTable.Local
import vera.jvm.ir.Print
import vera.jvm.ir.Return
import vera.jvm.ir.StoreLocal
import vera.shared.model.Identifier

@JvmInline private value class PendingSymbol(val text: Identifier)

private enum class Builtin(val keyword: Identifier) {

    PRINT(Identifier("print"));

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: Identifier): Builtin? = byKeyword[keyword]
    }
}

class JvmLowering(private val className: Identifier, private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>) {

    private data class VeraLocal(val name: Identifier, val slot: LocalTable.Slot, val veraType: VeraType)

    private val veraLocals = mutableMapOf<Identifier, VeraLocal>()
    private lateinit var jvmMethodBuilder: JvmMethodBuilder

    fun lowerFunction(veraFunction: VeraFunctionDeclaration): JvmMethod {
        val jvmParameters = veraFunction.parameters
            .map { veraParam -> JvmParameter(veraParam.name, veraParam.type.toJvmType()) }
        jvmMethodBuilder = JvmMethodBuilder(veraFunction.name, jvmParameters, veraFunction.returnType.toJvmType())

        veraFunction.parameters.forEachIndexed { index, veraParam ->
            val newLocal = VeraLocal(veraParam.name, LocalTable.Slot(index), veraParam.type)
            veraLocals[newLocal.name] = newLocal
        }

        val explicitReturnOnAllBranches = veraFunction.statements
            .map { processStatement(it) }
            .any { it }
        if (!explicitReturnOnAllBranches) {
            jvmMethodBuilder.addInstruction(Return())
        }
        return jvmMethodBuilder.build()
    }

    /** @return `true` if this statement explicitly returns from the current function. */
    private fun processStatement(statement: VeraStatement): Boolean {
        return when (statement) {
            is BindStatement -> {
                processBindStatement(statement)
                false
            }

            is Expression -> {
                processExpression(statement)
                false
            }

            is ReturnStatement -> {
                val expression = statement.expression
                if (expression != null) {
                    val resultType = processExpression(expression)
                    jvmMethodBuilder.addInstruction(Return(resultType.toJvmType()))
                } else {
                    jvmMethodBuilder.addInstruction(Return())
                }
                true
            }

            is RebindStatement -> TODO()
        }
    }

    private fun processBindStatement(bindStatement: BindStatement) {
        val type = processExpression(bindStatement.expression)
        if (bindStatement.type != VeraType.UNKNOWN && bindStatement.type != type) {
            error("the type of the bind statement must either UNKNOWN (to be inferred) or the same as the containing expression")
        }
        declareLocal(bindStatement.name, type)
    }

    private fun declareLocal(name: Identifier, type: VeraType) {
        val newLocal = VeraLocal(name, jvmMethodBuilder.nextFreeLocalSlot, type)
        veraLocals[newLocal.name] = newLocal
        jvmMethodBuilder.addInstruction(
            StoreLocal(
                Local(newLocal.slot, newLocal.veraType.toJvmType())
            )
        )
    }

    private fun processExpression(expression: Expression): VeraType {
        val mainExpression = expression.chainedExpressions.first()
        val chainType = processChainedExpression(mainExpression)
        val chainedExpressions = expression.chainedExpressions.tail

        if (expression.chainOperators.size != chainedExpressions.size) {
            error("the number of infix operators and chained expressions must be equal")
        }

        for (operatorExpr in expression.chainOperators.zip(chainedExpressions)) {
            val infixOperator = operatorExpr.first
            val chainedExpression = operatorExpr.second

            if (chainType.toJvmType() != JvmType.INT) error("infix operators are only defined for numeric types")
            val chainedtype = processChainedExpression(chainedExpression)
            if (chainedtype != chainType) error("all result types in a chained expression need to be the same")

            jvmMethodBuilder.addInstruction(
                IntBinaryOperation(mapOperator(infixOperator))
            )
        }
        return chainType
    }

    private fun mapOperator(operator: InfixOperator): IntBinaryOperator = when (operator) {
        InfixOperator.ADD -> IntBinaryOperator.ADD
        InfixOperator.SUBTRACT -> IntBinaryOperator.SUB
        InfixOperator.MULTIPLY -> IntBinaryOperator.MUL
        InfixOperator.DIVIDE -> IntBinaryOperator.DIV
        InfixOperator.EQUALS -> TODO()
        InfixOperator.NOT_EQUALS -> TODO()
    }

    private fun processChainedExpression(chainedExpression: ChainedExpression): VeraType {
        val expressionResult = processPrimaryExpression(chainedExpression.primaryExpression)
        val expressionResultType = expressionResult.leftOrNull()
        val pendingSymbol = expressionResult.getOrNull()

        // no pending symbol -> expression fully resolved
        if (expressionResultType != null) {
            return expressionResultType
        }

        // pending symbol -> function/builtin call
        val fnName = pendingSymbol!!.text
        val arguments = mutableListOf<VeraType>()
        for (exprData in chainedExpression.data) {
            when (exprData) {
                is VeraArguments -> arguments.addAll(processArguments(exprData))
                is VeraMemberAccess -> TODO()
            }
        }

        val builtin = Builtin.from(fnName)
        return if (builtin != null) {
            processBuiltinCall(builtin, arguments)
        } else {
            val fnDeclaration = visibleFunctions[fnName] ?: error("function ${fnName.value} not found.")
            jvmMethodBuilder.addInstruction(
                Invokestatic(
                    className,
                    fnDeclaration.name,
                    JvmMethodSignature(
                        fnDeclaration.parameters.map { veraParameter -> JvmParameter(veraParameter.name, veraParameter.type.toJvmType()) },
                        fnDeclaration.returnType.toJvmType()
                    )
                )
            )
            fnDeclaration.returnType
        }
    }

    private fun processArguments(arguments: VeraArguments): List<VeraType> {
        val types = mutableListOf<VeraType>()
        for (expression in arguments.expressions) {
            types.add(processExpression(expression))
        }
        return types
    }

    private fun processBuiltinCall(builtin: Builtin, arguments: List<VeraType>): VeraType {
        return when (builtin) {
            Builtin.PRINT -> {
                if (arguments.size != 1) error("print requires exactly 1 argument")
                jvmMethodBuilder.addInstruction(Print(arguments.first().toJvmType()))
                VeraType.UNIT
            }
        }
    }

    private fun processPrimaryExpression(primaryExpression: PrimaryExpression): Either<VeraType, PendingSymbol> {
        return when (primaryExpression) {
            is IntLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.INT, primaryExpression.value)
                )
                Left(VeraType.INT)
            }

            is StringLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.STRING, primaryExpression.value)
                )
                Left(VeraType.STRING)
            }

            is BoolLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.BOOL, primaryExpression == BoolLiteral.TRUE)
                )
                Left(VeraType.BOOL)
            }

            is IfExpression -> {
                val conditionType = processExpression(primaryExpression.condition)
                if (conditionType != VeraType.BOOL) error("conditions must result in a bool")

                val elseLabel = JvmLabel()
                jvmMethodBuilder.addInstruction(CreateLabel(elseLabel))
                jvmMethodBuilder.addInstruction(IfFalseJumpTo(elseLabel))
                primaryExpression.thenBlock.forEach { processStatement(it) }

                if (primaryExpression.elseBlock.isEmpty()) {
                    jvmMethodBuilder.addInstruction(BindLabel(elseLabel))
                } else {
                    // do not fall through
                    val endLabel = JvmLabel()
                    jvmMethodBuilder.addInstruction(CreateLabel(endLabel))
                    jvmMethodBuilder.addInstruction(JumpTo(endLabel))

                    jvmMethodBuilder.addInstruction(BindLabel(elseLabel))
                    primaryExpression.elseBlock.forEach { processStatement(it) }

                    jvmMethodBuilder.addInstruction(BindLabel(endLabel))
                }
                Left(VeraType.UNIT)
            }

            is IdentifierAsExpression -> {
                val name = primaryExpression.identifier
                val veraLocal = veraLocals[name]
                if (veraLocal != null) {
                    jvmMethodBuilder.addInstruction(
                        LoadLocal(
                            Local(veraLocal.slot, veraLocal.veraType.toJvmType())
                        )
                    )
                    Left(veraLocal.veraType)
                } else {
                    // not a local -> builtin/function name
                    Right(PendingSymbol(name))
                }
            }

            is Expression -> {
                val type = processExpression(primaryExpression)
                Left(type)
            }
        }
    }

    private fun VeraType.toJvmType(): JvmType {
        return when (this) {
            VeraType.INT -> JvmType.INT
            VeraType.STRING -> JvmType.STRING
            VeraType.BOOL -> JvmType.BOOL
            VeraType.UNIT, VeraType.UNKNOWN -> JvmType.VOID
        }
    }
}
