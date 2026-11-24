package vera.jvm.lowering

import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBlock
import vera.ast.VeraBlockBody
import vera.ast.VeraBoolLiteral
import vera.ast.VeraBreakStatement
import vera.ast.VeraChainedExpression
import vera.ast.VeraContinueStatement
import vera.ast.VeraExplicitReturnBlock
import vera.ast.VeraExpression
import vera.ast.VeraExpressionBody
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
import vera.ast.VeraImplicitReturnBlock
import vera.ast.VeraIntLiteral
import vera.ast.VeraMatchArm
import vera.ast.VeraMatchExpression
import vera.ast.VeraMemberAccess
import vera.ast.VeraMutableParameter
import vera.ast.VeraOperator
import vera.ast.VeraParameter
import vera.ast.VeraPrimaryExpression
import vera.ast.VeraRebindStatement
import vera.ast.VeraClassDeclaration
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraStringTemplate
import vera.ast.VeraTraitDeclaration
import vera.ast.VeraTraitKind
import vera.ast.VeraTraitMethod
import vera.ast.VeraTemplateText
import vera.ast.VeraTemplateValue
import vera.ast.VeraType
import vera.ast.VeraUnaryOperation
import vera.ast.VeraUnaryOperator
import vera.ast.VeraUnitLiteral
import vera.ast.VeraUnitReturnBlock
import vera.ast.VeraWhileStatement
import vera.ast.render
import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmBlock
import vera.jvm.ir.JvmBreak
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmContinue
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmGetField
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
import vera.jvm.ir.JvmInstanceCall
import vera.jvm.ir.JvmIntBinary
import vera.jvm.ir.JvmIntLiteral
import vera.jvm.ir.JvmIntUnary
import vera.jvm.ir.JvmIntUnaryOperator
import vera.jvm.ir.JvmLet
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmLocalRead
import vera.jvm.ir.JvmLogicalAnd
import vera.jvm.ir.JvmLogicalNot
import vera.jvm.ir.JvmLogicalOr
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmObjectConstruction
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmStringLiteral
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitLiteral
import vera.jvm.ir.JvmUnitReturnBlock
import vera.jvm.ir.JvmUnitType
import vera.jvm.ir.JvmUpcast
import vera.jvm.ir.JvmSingletonValue
import vera.jvm.ir.JvmBlockExpression
import vera.jvm.ir.JvmCheckCast
import vera.jvm.ir.JvmInstanceOf
import vera.jvm.ir.JvmVarDeclaration
import vera.jvm.ir.JvmWhile
import vera.jvm.ir.render
import vera.shared.model.Identifier
import vera.shared.model.SourceSpan
import vera.shared.model.at

private enum class Builtin(val keyword: Identifier, val jvmBuiltin: JvmBuiltin) {

    PRINT(Identifier("print"), JvmBuiltin.PRINT);

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: Identifier): Builtin? = byKeyword[keyword]
        fun allNames(): Set<Identifier> = byKeyword.keys
    }
}

private sealed interface PrimaryResolution {
    data class Resolved(val expression: JvmExpression) : PrimaryResolution
    data class Pending(val identifier: Identifier) : PrimaryResolution
}

internal object JvmFunctionLowering {
    internal fun isBuiltinName(name: Identifier): Boolean = Builtin.from(name) != null

    internal fun builtinNames(): Set<Identifier> = Builtin.allNames()

    fun lower(
        className: Identifier,
        visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
        visibleClasses: Map<Identifier, VeraClassDeclaration>,
        visibleTraits: Map<Identifier, VeraTraitDeclaration>,
        veraFunction: VeraFunctionDeclaration,
        receiverClass: VeraClassDeclaration? = null,
    ): JvmMethod = JvmFunctionLoweringState(className, visibleFunctions, visibleClasses, visibleTraits, receiverClass)
        .lower(veraFunction)
}

private class JvmFunctionLoweringState(
    private val className: Identifier,
    private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
    private val visibleClasses: Map<Identifier, VeraClassDeclaration>,
    private val visibleTraits: Map<Identifier, VeraTraitDeclaration>,
    private val receiverClass: VeraClassDeclaration?,
) {

    private val typeNames = JvmTypeNames(visibleClasses.keys, visibleTraits.keys)
    private val traitRequirements = JvmTraitRequirements(visibleTraits)

    private val receiverLocal: JvmLocal? = receiverClass?.let { veraClass ->
        JvmLocal(Identifier("<this>"), JvmType.ClassType(veraClass.name))
    }

    private val reservedNames: Set<Identifier> = buildSet {
        addAll(visibleFunctions.keys)
        addAll(visibleClasses.keys)
        addAll(visibleTraits.keys)
        addAll(JvmFunctionLowering.builtinNames())
        receiverClass?.let { veraClass ->
            addAll(veraClass.fields.map { field -> field.name })
            addAll(veraClass.methods.map { method -> method.name })
        }
    }

    /**
     * A lowered statement, whether it always returns from the current function, and, when it always
     * leaves its block, a description of how; any statement after it in the same block is unreachable.
     */
    private data class LoweredStatement(
        val jvmStatement: JvmStatement?,
        val returns: Boolean,
        val exit: String? = null,
    )

    /**
     * A lowered call argument. [explicitName] is set for `name = value`, [shorthandName] for a bare
     * identifier (which may stand for the parameter of the same name), and [forwarded] for the value
     * passed by `|>`, which always fills the first parameter.
     */
    private data class LoweredArgument(
        val jvmExpression: JvmExpression,
        val explicitName: Identifier? = null,
        val shorthandName: Identifier? = null,
        val forwarded: Boolean = false,
        val span: SourceSpan? = null,
    )

    /** A lowered expression and whether evaluating it always returns from the current function. */
    private data class LoweredExpression(val jvmExpression: JvmExpression, val returns: Boolean)

    /** A lowered block and whether executing it always returns from the current function. */
    private data class LoweredBlock(val jvmBlock: JvmBlock, val returns: Boolean)

    /** A visible local: its IR local, whether it may be rebound (`mut val`), and whether it is a parameter. */
    private data class LocalBinding(val jvmLocal: JvmLocal, val mutable: Boolean, val parameter: Boolean)

    /** Visible locals by block scope, innermost last. The first scope holds parameters and the body. */
    private val scopes = mutableListOf(mutableMapOf<Identifier, LocalBinding>())

    /**
     * How many enclosing expressions are partially evaluated at this point. `break` and `continue` may
     * only target a loop entered at the same depth, so a jump never leaves values on the operand stack.
     */
    private var expressionDepth = 0

    /** The [expressionDepth] at which each enclosing loop was entered, innermost last. */
    private val loopExpressionDepths = mutableListOf<Int>()

    private var temporaryCount = 0
    private lateinit var currentReturnType: JvmType

    fun lower(veraFunction: VeraFunctionDeclaration): JvmMethod {
        currentReturnType = JvmTypeLowering.lowerReturn(veraFunction.returnType, typeNames)

        val jvmParameters = veraFunction.parameters
            .map { veraParameter ->
                val jvmLocal = located(veraParameter.span) {
                    declareLocal(veraParameter.name, lowerType(veraParameter.type), parameter = true)
                }
                JvmParameter(jvmLocal.name, jvmLocal.type)
            }

        val jvmStatements = when (val veraBody = veraFunction.body) {
            is VeraBlockBody -> lowerBlockBody(veraFunction, veraBody)
            is VeraExpressionBody -> listOf(JvmReturn(lowerExpressionBody(veraFunction, veraBody)))
        }

        return JvmMethod(
            veraFunction.name,
            jvmParameters,
            currentReturnType,
            JvmExplicitReturnBlock(jvmStatements),
            receiver = receiverLocal,
        )
    }

    private fun lowerBlockBody(veraFunction: VeraFunctionDeclaration, veraBody: VeraBlockBody): List<JvmStatement> {
        val loweredStatements = lowerStatementList(veraBody.block.statements)
        val jvmStatements = loweredStatements.mapNotNull { loweredStatement -> loweredStatement.jvmStatement }.toMutableList()
        if (loweredStatements.none { loweredStatement -> loweredStatement.returns }) {
            if (currentReturnType != JvmUnitType) {
                throw JvmLoweringException.MissingReturnStatement(currentReturnType).at(veraFunction.span)
            }
            jvmStatements += JvmReturn(null)
        }
        return jvmStatements
    }

    /**
     * Lowers `= expression`, which returns the expression's value. An omitted return type means `Unit`,
     * so an expression of another type needs its return type written out.
     */
    private fun lowerExpressionBody(
        veraFunction: VeraFunctionDeclaration,
        veraBody: VeraExpressionBody,
    ): JvmExpression {
        val jvmValue = lowerStatementExpression(veraBody.expression).jvmExpression
        val jvmReturned = coerce(jvmValue, currentReturnType)
        if (jvmReturned == null) {
            if (veraFunction.returnType == VeraType.UNKNOWN) {
                throw JvmLoweringException.ExpressionBodyReturnTypeRequired(veraFunction.name, jvmValue.type)
                    .at(veraFunction.span)
            }
            throw JvmLoweringException.ReturnTypeMismatch(currentReturnType, jvmValue.type).at(veraBody.expression.span)
        }
        return jvmReturned
    }

    /** @return the lowered statement and whether it explicitly returns from the current function. */
    private fun lowerStatement(veraStatement: VeraStatement): LoweredStatement =
        located(veraStatement.span) { lowerStatementNode(veraStatement) }

    private fun lowerStatementNode(veraStatement: VeraStatement): LoweredStatement {
        return when (veraStatement) {
            is VeraBindStatement -> LoweredStatement(lowerBindStatement(veraStatement), false)

            is VeraExpression -> {
                val loweredExpression = lowerStatementExpression(veraStatement.expression)
                LoweredStatement(
                    JvmExpressionStatement(loweredExpression.jvmExpression),
                    loweredExpression.returns,
                    exit = when {
                        !loweredExpression.returns -> null
                        veraStatement.expression is VeraMatchExpression -> "a match whose arms all return"
                        else -> "an if whose branches all return"
                    },
                )
            }

            is VeraReturnStatement -> {
                val jvmValue = veraStatement.expression
                    ?.let { veraExpression -> lowerExpression(veraExpression) }
                val jvmReturnExpression = if (jvmValue == null) {
                    if (currentReturnType != JvmUnitType) {
                        throw JvmLoweringException.MissingReturnValue(currentReturnType)
                    }
                    null
                } else {
                    coerce(jvmValue, currentReturnType)
                        ?: throw JvmLoweringException.ReturnTypeMismatch(currentReturnType, jvmValue.type)
                }
                LoweredStatement(JvmReturn(jvmReturnExpression), true, exit = "return")
            }

            is VeraWhileStatement -> LoweredStatement(lowerWhileStatement(veraStatement), false)

            is VeraRebindStatement -> LoweredStatement(lowerRebindStatement(veraStatement), false)

            is VeraMutableParameter -> {
                makeParameterMutable(veraStatement)
                LoweredStatement(jvmStatement = null, returns = false)
            }

            is VeraBreakStatement -> {
                val loopDepth = loopExpressionDepths.lastOrNull()
                    ?: throw JvmLoweringException.BreakOutsideLoop()
                requireLoopJumpDepth("break", loopDepth)
                LoweredStatement(JvmBreak, false, exit = "break")
            }

            is VeraContinueStatement -> {
                val loopDepth = loopExpressionDepths.lastOrNull()
                    ?: throw JvmLoweringException.ContinueOutsideLoop()
                requireLoopJumpDepth("continue", loopDepth)
                LoweredStatement(JvmContinue, false, exit = "continue")
            }
        }
    }

    private fun requireLoopJumpDepth(keyword: String, loopDepth: Int) {
        if (expressionDepth != loopDepth) {
            throw JvmLoweringException.LoopJumpInsideExpression(keyword)
        }
    }

    private fun lowerWhileStatement(veraWhileStatement: VeraWhileStatement): JvmStatement {
        val jvmCondition = nested { lowerPrimaryExpression(veraWhileStatement.condition) }
        if (jvmCondition.type != JvmType.BOOL) {
            throw JvmLoweringException.ConditionNotBool(jvmCondition.type).at(veraWhileStatement.condition.span)
        }
        loopExpressionDepths += expressionDepth
        val jvmBody = try {
            withScope { lowerBlockStatements(veraWhileStatement.body.statements) }
        } finally {
            loopExpressionDepths.removeLast()
        }
        return JvmWhile(jvmCondition, JvmUnitReturnBlock(jvmBody))
    }

    /**
     * `mut val name` without a value: lets the parameter [VeraMutableParameter.name] be rebound from
     * here on. It must be written directly in the function body, so the rest of the function sees it.
     */
    private fun makeParameterMutable(veraStatement: VeraMutableParameter) {
        val name = veraStatement.name
        if (scopes.size != 1) {
            throw JvmLoweringException.MutableParameterOutsideFunctionBody(name)
        }
        val functionScope = scopes.first()
        val binding = functionScope[name]
        if (binding == null || !binding.parameter) {
            throw JvmLoweringException.MutableParameterRequiresParameter(name)
        }
        if (binding.mutable) {
            throw JvmLoweringException.MutableParameterRepeated(name)
        }
        functionScope[name] = binding.copy(mutable = true)
    }

    private fun lowerRebindStatement(veraRebindStatement: VeraRebindStatement): JvmStatement {
        val binding = lookupBinding(veraRebindStatement.name)
            ?: throw unknownVariable(veraRebindStatement.name)
        if (!binding.mutable) {
            throw if (binding.parameter) {
                JvmLoweringException.ParameterRebound(veraRebindStatement.name)
            } else {
                JvmLoweringException.ImmutableBindingRebound(veraRebindStatement.name)
            }
        }
        val jvmLocal = binding.jvmLocal
        val jvmExpression = lowerExpression(veraRebindStatement.expression)
        val jvmValue = coerce(jvmExpression, jvmLocal.type)
            ?: throw JvmLoweringException.RebindTypeMismatch(jvmLocal.name, jvmLocal.type, jvmExpression.type)
        return JvmRebind(jvmLocal, jvmValue)
    }

    private fun lowerBindStatement(veraBindStatement: VeraBindStatement): JvmStatement {
        requireFreshName(veraBindStatement.name)
        val jvmExpression = lowerExpression(veraBindStatement.expression)
        // A declared type wins over the value's type, so `val shape: Shape = Circle(...)` holds a Shape.
        val jvmValue = if (veraBindStatement.type == VeraType.UNKNOWN) {
            jvmExpression
        } else {
            coerce(jvmExpression, lowerType(veraBindStatement.type))
                ?: throw JvmLoweringException.BindTypeMismatch(
                    veraBindStatement.name,
                    veraBindStatement.type.render(),
                    jvmExpression.type,
                )
        }
        val jvmLocal = declareLocal(veraBindStatement.name, jvmValue.type, mutable = veraBindStatement.mutable)
        return JvmVarDeclaration(jvmLocal, jvmValue)
    }

    /** Lowers the expression of a bind, rebind, or return statement. */
    private fun lowerExpression(veraExpression: VeraExpression): JvmExpression =
        lowerStatementExpression(veraExpression.expression).jvmExpression

    /**
     * Lowers an expression that forms a whole statement, so it starts on an empty operand stack. Only an
     * `if` keeps that position for its branches; any other expression nests its operands.
     */
    private fun lowerStatementExpression(veraExpression: VeraPrimaryExpression): LoweredExpression =
        if (veraExpression is VeraIfExpression) {
            located(veraExpression.span) { lowerIfExpression(veraExpression) }
        } else if (veraExpression is VeraMatchExpression) {
            located(veraExpression.span) { lowerMatchExpression(veraExpression) }
        } else {
            LoweredExpression(nested { lowerPrimaryExpression(veraExpression) }, returns = false)
        }

    private fun lowerIfExpression(veraIfExpression: VeraIfExpression): LoweredExpression {
        val jvmCondition = nested { lowerPrimaryExpression(veraIfExpression.condition) }
        if (jvmCondition.type != JvmType.BOOL) {
            throw JvmLoweringException.ConditionNotBool(jvmCondition.type).at(veraIfExpression.condition.span)
        }
        val loweredThen = lowerBlock(veraIfExpression.thenBlock)
        val loweredElse = veraIfExpression.elseBlock?.let { veraElseBlock -> lowerBlock(veraElseBlock) }
            ?: return LoweredExpression(JvmIfExpression(jvmCondition, loweredThen.jvmBlock, null), returns = false)
        val thenType = loweredThen.jvmBlock.type
        val elseType = loweredElse.jvmBlock.type
        val jvmType = unifyTypes(listOf(thenType, elseType))
            ?: throw JvmLoweringException.IfBranchTypeMismatch(thenType, elseType)
        return LoweredExpression(
            JvmIfExpression(
                jvmCondition,
                coerceBlock(loweredThen.jvmBlock, jvmType),
                coerceBlock(loweredElse.jvmBlock, jvmType),
            ),
            returns = loweredThen.returns && loweredElse.returns,
        )
    }

    /**
     * Lowers `match` to a chain of `instanceof` tests on the scrutinee, evaluated once into a temporary.
     * The scrutinee must be a trait (or a single class), every case must be covered exactly once,
     * and the last arm needs no test because the earlier arms rule out every other case.
     */
    private fun lowerMatchExpression(veraMatch: VeraMatchExpression): LoweredExpression {
        val jvmScrutinee = nested { lowerPrimaryExpression(veraMatch.scrutinee) }
        val cases = matchCases(jvmScrutinee.type, veraMatch.scrutinee)
        val scrutineeName = Identifier(jvmScrutinee.type.render())
        val jvmTemporary = JvmLocal(Identifier("<match${temporaryCount++}>"), jvmScrutinee.type)

        val ownerTrait = (jvmScrutinee.type as? JvmType.InterfaceType)?.name
        val coveredClasses = mutableSetOf<Identifier>()
        val loweredArms = veraMatch.arms.map { veraArm ->
            val pattern = veraArm.pattern
            // In a match on the trait that owns a case, the case is written unqualified (`Red`).
            val ownedName = ownerTrait?.let { owner -> Identifier("${owner.value}\$${pattern.className.value}") }
            val veraClass = ownedName?.let { name -> visibleClasses[name] }?.takeIf { it in cases }
                ?: visibleClasses[pattern.className]
            if (veraClass == null || veraClass !in cases) {
                val candidates = cases.map { case ->
                    if (case.owner != null && case.owner == ownerTrait) Identifier(case.name.value.substringAfter('$')) else case.name
                }
                throw JvmLoweringException.MatchPatternNotACase(
                    pattern.className,
                    scrutineeName,
                    JvmNameHints.closest(pattern.className, candidates),
                ).at(pattern.span)
            }
            if (!coveredClasses.add(veraClass.name)) {
                throw JvmLoweringException.MatchArmRepeated(veraClass.name).at(pattern.span)
            }
            veraClass to lowerMatchArm(veraArm, veraClass, jvmTemporary)
        }
        val missing = cases.map { case -> case.name }.filterNot { name -> name in coveredClasses }
        if (missing.isNotEmpty()) {
            throw JvmLoweringException.MatchNotExhaustive(scrutineeName, missing).at(veraMatch.span)
        }

        val armTypes = loweredArms.map { (_, loweredArm) -> loweredArm.jvmBlock.type }
        val jvmType = unifyTypes(armTypes)
            ?: throw JvmLoweringException.MatchArmTypeMismatch(armTypes.distinct()).at(veraMatch.span)
        val jvmArms = loweredArms.map { (veraClass, loweredArm) -> veraClass to coerceBlock(loweredArm.jvmBlock, jvmType) }

        val (_, lastBlock) = jvmArms.last()
        val jvmChain = jvmArms.dropLast(1).foldRight(JvmBlockExpression(lastBlock) as JvmExpression) { (veraClass, jvmBlock), rest ->
            JvmIfExpression(
                JvmInstanceOf(JvmLocalRead(jvmTemporary), veraClass.name),
                jvmBlock,
                JvmImplicitReturnBlock(emptyList(), rest),
            )
        }
        return LoweredExpression(
            JvmLet(jvmTemporary, jvmScrutinee, jvmChain),
            returns = loweredArms.all { (_, loweredArm) -> loweredArm.returns },
        )
    }

    /** The classes a `match` on a value of [jvmType] must cover. */
    private fun matchCases(jvmType: JvmType, veraScrutinee: VeraPrimaryExpression): List<VeraClassDeclaration> =
        when (jvmType) {
            is JvmType.InterfaceType -> {
                if (visibleTraits[jvmType.name]?.kind == VeraTraitKind.INTERFACE) {
                    throw JvmLoweringException.MatchOnInterface(jvmType.name).at(veraScrutinee.span)
                }
                // Traits are closed, so the classes that have the trait are all of its cases.
                visibleClasses.values.filter { veraClass -> jvmType.name in veraClass.traits }
            }

            is JvmType.ClassType -> listOf(
                visibleClasses[jvmType.name]
                    ?: throw JvmLoweringException.MatchScrutineeNotAnObject(jvmType).at(veraScrutinee.span),
            )

            else -> throw JvmLoweringException.MatchScrutineeNotAnObject(jvmType).at(veraScrutinee.span)
        }

    /**
     * Lowers one arm: casts the scrutinee to the arm's class, binds the pattern's fields as locals in
     * the arm's own scope, then lowers the body.
     */
    private fun lowerMatchArm(veraArm: VeraMatchArm, veraClass: VeraClassDeclaration, jvmTemporary: JvmLocal): LoweredBlock =
        withScope {
            val classType = JvmType.ClassType(veraClass.name)
            val jvmBindings = mutableListOf<JvmStatement>()
            if (veraArm.pattern.fields.isNotEmpty()) {
                val jvmCase = JvmLocal(Identifier("<case${temporaryCount++}>"), classType)
                jvmBindings += JvmVarDeclaration(jvmCase, JvmCheckCast(JvmLocalRead(jvmTemporary), classType))
                veraArm.pattern.fields.forEach { veraFieldPattern ->
                    val veraField = veraClass.fields.firstOrNull { field -> field.name == veraFieldPattern.field }
                        ?: throw JvmLoweringException.UnknownClassField(
                            veraClass.name,
                            veraFieldPattern.field,
                            JvmNameHints.closest(veraFieldPattern.field, veraClass.fields.map { field -> field.name }),
                        ).at(veraFieldPattern.span)
                    val fieldType = lowerType(veraField.type)
                    val jvmLocal = located(veraFieldPattern.span) { declareLocal(veraFieldPattern.binding, fieldType) }
                    jvmBindings += JvmVarDeclaration(
                        jvmLocal,
                        JvmGetField(JvmLocalRead(jvmCase), veraClass.name, veraField.name, fieldType),
                    )
                }
            }
            val loweredBody = lowerBlock(veraArm.body)
            LoweredBlock(prependStatements(jvmBindings, loweredBody.jvmBlock), loweredBody.returns)
        }

    private fun prependStatements(jvmStatements: List<JvmStatement>, jvmBlock: JvmBlock): JvmBlock = when (jvmBlock) {
        is JvmImplicitReturnBlock -> JvmImplicitReturnBlock(jvmStatements + jvmBlock.statements, jvmBlock.result)
        is JvmUnitReturnBlock -> JvmUnitReturnBlock(jvmStatements + jvmBlock.statements)
        is JvmExplicitReturnBlock -> JvmExplicitReturnBlock(jvmStatements + jvmBlock.statements)
    }

    /**
     * The single type that branch values of [jvmTypes] share: their common type, a branch type every
     * other branch converts to, or else the one trait that all of them have, so
     * `if c { Circle(...) } else { Empty }` is a `Shape`. Interfaces are not inferred this way, because
     * a class fits them by its members alone; a branch typed as an interface, or a declared type,
     * selects one.
     */
    private fun unifyTypes(jvmTypes: List<JvmType>): JvmType? {
        val distinctTypes = jvmTypes.distinct()
        distinctTypes.singleOrNull()?.let { jvmType -> return jvmType }
        distinctTypes.singleOrNull { target -> distinctTypes.all { jvmType -> isAssignable(jvmType, target) } }
            ?.let { jvmType -> return jvmType }
        val traitSets = distinctTypes.map { jvmType ->
            val traits = when (jvmType) {
                is JvmType.InterfaceType -> setOf(jvmType.name) + traitRequirements.userTraitsOf(jvmType.name)
                is JvmType.ClassType -> visibleClasses[jvmType.name]?.traits?.filter { it in visibleTraits }?.toSet()
                    ?: return null
                else -> return null
            }
            traits.filter { trait -> visibleTraits[trait]?.kind == VeraTraitKind.TRAIT }.toSet()
        }
        val commonTraits = traitSets.reduce { left, right -> left intersect right }
        return commonTraits.singleOrNull()?.let { trait -> JvmType.InterfaceType(trait) }
    }

    /** Converts a branch block's value to [jvmType], which [unifyTypes] guarantees it is assignable to. */
    private fun coerceBlock(jvmBlock: JvmBlock, jvmType: JvmType): JvmBlock {
        if (jvmBlock.type == jvmType) return jvmBlock
        val jvmResultBlock = jvmBlock as? JvmImplicitReturnBlock
            ?: throw JvmLoweringException.InternalInvariant("branch of type ${jvmBlock.type.render()} has no value to convert")
        val jvmResult = coerce(jvmResultBlock.result, jvmType)
            ?: throw JvmLoweringException.InternalInvariant("branch value is not assignable to ${jvmType.render()}")
        return JvmImplicitReturnBlock(jvmResultBlock.statements, jvmResult)
    }

    private inline fun <T> nested(lowering: () -> T): T {
        expressionDepth++
        try {
            return lowering()
        } finally {
            expressionDepth--
        }
    }

    private inline fun <T> withScope(lowering: () -> T): T {
        scopes += mutableMapOf()
        try {
            return lowering()
        } finally {
            scopes.removeLast()
        }
    }

    private fun unknownVariable(name: Identifier): JvmLoweringException.UnknownVariable {
        val candidates = scopes.flatMap { scope -> scope.keys } +
            (receiverClass?.fields?.map { field -> field.name } ?: emptyList())
        return JvmLoweringException.UnknownVariable(
            name,
            JvmNameHints.closest(name, candidates),
            JvmNameHints.forJavaValue(name),
        )
    }

    /** Names that can be called by a bare name here: functions, built-ins, classes, sibling methods. */
    private fun callableNames(): Set<Identifier> = buildSet {
        addAll(visibleFunctions.keys)
        addAll(JvmFunctionLowering.builtinNames())
        addAll(visibleClasses.keys)
        receiverClass?.methods?.forEach { method -> add(method.name) }
    }

    /**
     * Returns [jvmExpression] as a value of [expectedType], or `null` when it is not assignable. A class
     * value is assignable to each user trait it has and each interface it fits, through a [JvmUpcast].
     */
    private fun coerce(jvmExpression: JvmExpression, expectedType: JvmType): JvmExpression? {
        val actualType = jvmExpression.type
        if (actualType == expectedType) return jvmExpression
        if (!isAssignable(actualType, expectedType) || expectedType !is JvmType.InterfaceType) return null
        return JvmUpcast(jvmExpression, expectedType)
    }

    /**
     * Whether a value of [actualType] can be used as [expectedType]: a class value as one of its traits or
     * interfaces, and a trait value as a trait or interface it requires or fits.
     */
    private fun isAssignable(actualType: JvmType, expectedType: JvmType): Boolean = when {
        actualType == expectedType -> true
        expectedType !is JvmType.InterfaceType -> false
        actualType is JvmType.ClassType -> visibleClasses[actualType.name]?.traits?.contains(expectedType.name) == true
        actualType is JvmType.InterfaceType -> traitRequirements.satisfies(actualType.name, expectedType.name)
        else -> false
    }

    private fun lookupLocal(name: Identifier): JvmLocal? = lookupBinding(name)?.jvmLocal

    private fun lookupBinding(name: Identifier): LocalBinding? =
        scopes.asReversed().firstNotNullOfOrNull { scope -> scope[name] }

    private fun declareLocal(
        name: Identifier,
        jvmType: JvmType,
        mutable: Boolean = false,
        parameter: Boolean = false,
    ): JvmLocal {
        requireFreshName(name)
        val jvmLocal = JvmLocal(name, jvmType)
        scopes.last()[name] = LocalBinding(jvmLocal, mutable, parameter)
        return jvmLocal
    }

    private fun lowerPrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): JvmExpression =
        located(veraPrimaryExpression.span) { lowerPrimaryExpressionNode(veraPrimaryExpression) }

    private fun lowerPrimaryExpressionNode(veraPrimaryExpression: VeraPrimaryExpression): JvmExpression {
        return when (veraPrimaryExpression) {
            is VeraBinaryOperation -> lowerBinaryOperation(veraPrimaryExpression)
            is VeraChainedExpression -> lowerChainedExpression(veraPrimaryExpression)
            else -> when (val resolution = resolvePrimaryExpression(veraPrimaryExpression)) {
                is PrimaryResolution.Resolved -> resolution.expression
                is PrimaryResolution.Pending -> lowerNamedValue(resolution.identifier)
            }
        }
    }

    /**
     * A bare name used as a value that is not a local or field: the single value of a field-less
     * class (`Empty`), or an error explaining why the name is not a value.
     */
    /** The case `Owner.Case` named by [access], which must be owned by the trait [traitName]. */
    private fun requireTraitCase(traitName: Identifier, access: VeraMemberAccess): VeraClassDeclaration {
        val caseName = Identifier("${traitName.value}\$${access.member.value}")
        return visibleClasses[caseName]?.takeIf { veraCase -> veraCase.owner == traitName }
            ?: throw JvmLoweringException.UnknownTraitCase(
                traitName,
                access.member,
                JvmNameHints.closest(access.member, caseNamesOf(traitName)),
            ).at(access.span)
    }

    /** The unqualified names of the cases [traitName] owns. */
    private fun caseNamesOf(traitName: Identifier): List<Identifier> =
        visibleClasses.values
            .filter { veraClass -> veraClass.owner == traitName }
            .map { veraCase -> Identifier(veraCase.name.value.substringAfter('$')) }

    /** A hint for an unqualified case name such as `Red`: write it qualified, as `Color.Red`. */
    private fun qualifiedCaseHint(name: Identifier): String? {
        val veraCase = visibleClasses.values.firstOrNull { veraClass ->
            val owner = veraClass.owner
            owner != null && veraClass.name.value == "${owner.value}\$${name.value}"
        } ?: return null
        return "a case is written with its trait: `${veraCase.name.display}`"
    }

    private fun lowerNamedValue(name: Identifier): JvmExpression {
        val veraClass = visibleClasses[name]
        if (veraClass != null) {
            if (veraClass.fields.isEmpty()) return JvmSingletonValue(JvmType.ClassType(name))
            throw JvmLoweringException.ClassTypeUsedAsValue(name)
        }
        val receiver = receiverClass
        if (receiver != null && receiver.methods.any { method -> method.name == name }) {
            throw JvmLoweringException.ClassMethodUsedAsValue(receiver.name, name)
        }
        qualifiedCaseHint(name)?.let { hint -> throw JvmLoweringException.UnknownVariable(name, hint = hint) }
        throw unknownVariable(name)
    }

    private fun lowerUnaryOperation(veraUnaryOperation: VeraUnaryOperation): JvmExpression {
        val jvmOperand = lowerPrimaryExpression(veraUnaryOperation.operand)
        return when (veraUnaryOperation.operator) {
            VeraUnaryOperator.NEGATE -> {
                if (jvmOperand.type != JvmType.INT) {
                    throw JvmLoweringException.IntOperandsRequired(
                        veraUnaryOperation.operator.symbol,
                        jvmOperand.type,
                    )
                }
                JvmIntUnary(JvmIntUnaryOperator.NEGATE, jvmOperand)
            }

            VeraUnaryOperator.NOT -> {
                requireBoolOperand(jvmOperand.type, veraUnaryOperation.operator.symbol)
                JvmLogicalNot(jvmOperand)
            }
        }
    }

    private fun lowerBinaryOperation(veraOperation: VeraBinaryOperation): JvmExpression {
        when (veraOperation.operator) {
            VeraOperator.AND -> {
                val jvmLeft = lowerPrimaryExpression(veraOperation.left)
                val jvmRight = lowerPrimaryExpression(veraOperation.right)
                requireBoolOperand(jvmLeft.type, veraOperation.operator.symbol)
                requireBoolOperand(jvmRight.type, veraOperation.operator.symbol)
                return JvmLogicalAnd(jvmLeft, jvmRight)
            }

            VeraOperator.OR -> {
                val jvmLeft = lowerPrimaryExpression(veraOperation.left)
                val jvmRight = lowerPrimaryExpression(veraOperation.right)
                requireBoolOperand(jvmLeft.type, veraOperation.operator.symbol)
                requireBoolOperand(jvmRight.type, veraOperation.operator.symbol)
                return JvmLogicalOr(jvmLeft, jvmRight)
            }

            VeraOperator.FORWARD_APPLICATION -> return lowerForwardApplication(veraOperation)
            else -> {}
        }

        val jvmLeft = lowerPrimaryExpression(veraOperation.left)
        val jvmRight = lowerPrimaryExpression(veraOperation.right)
        if (veraOperation.operator == VeraOperator.ADD && (jvmLeft.type == JvmType.STRING || jvmRight.type == JvmType.STRING)) {
            throw JvmLoweringException.StringConcatenationNotSupported()
        }
        if (veraOperation.operator == VeraOperator.EQUALS || veraOperation.operator == VeraOperator.NOT_EQUALS) {
            // Objects of different classes compare as the one trait they share, if it requires `Eq`.
            val sharedType = if (jvmLeft.type != jvmRight.type) unifyTypes(listOf(jvmLeft.type, jvmRight.type)) else null
            val (jvmLeftOperand, jvmRightOperand) = if (sharedType is JvmType.InterfaceType) {
                (coerce(jvmLeft, sharedType) ?: jvmLeft) to (coerce(jvmRight, sharedType) ?: jvmRight)
            } else {
                jvmLeft to jvmRight
            }
            validateEqualityOperands(veraOperation.operator, jvmLeftOperand.type, jvmRightOperand.type)
            return lowerEquality(veraOperation.operator, jvmLeftOperand, jvmRightOperand)
        }
        validateIntOperands(veraOperation.operator, jvmLeft.type, jvmRight.type)

        return when (veraOperation.operator) {
            VeraOperator.EQUALS, VeraOperator.NOT_EQUALS ->
                throw JvmLoweringException.InternalInvariant("unreachable equality operator")
            VeraOperator.LESS -> JvmComparison(JvmComparisonOperator.LT, jvmLeft, jvmRight)
            VeraOperator.GREATER -> JvmComparison(JvmComparisonOperator.GT, jvmLeft, jvmRight)
            VeraOperator.LESS_EQ -> JvmComparison(JvmComparisonOperator.LTE, jvmLeft, jvmRight)
            VeraOperator.GREATER_EQ -> JvmComparison(JvmComparisonOperator.GTE, jvmLeft, jvmRight)
            VeraOperator.ADD -> JvmIntBinary(JvmIntBinaryOperator.ADD, jvmLeft, jvmRight)
            VeraOperator.SUBTRACT -> JvmIntBinary(JvmIntBinaryOperator.SUB, jvmLeft, jvmRight)
            VeraOperator.MULTIPLY -> JvmIntBinary(JvmIntBinaryOperator.MUL, jvmLeft, jvmRight)
            VeraOperator.DIVIDE -> JvmIntBinary(JvmIntBinaryOperator.DIV, jvmLeft, jvmRight)
            VeraOperator.PERCENT -> JvmIntBinary(JvmIntBinaryOperator.REM, jvmLeft, jvmRight)
            VeraOperator.AND, VeraOperator.OR, VeraOperator.FORWARD_APPLICATION ->
                throw JvmLoweringException.InternalInvariant("unreachable binary operator")
        }
    }

    private fun lowerForwardApplication(veraOperation: VeraBinaryOperation): JvmExpression {
        return when (val veraRight = veraOperation.right) {
            is VeraIdentifierAsExpression -> {
                val loweredArguments = listOf(
                    LoweredArgument(lowerPrimaryExpression(veraOperation.left), forwarded = true),
                )
                lowerNamedCall(veraRight.identifier, loweredArguments)
            }

            // A bare call such as `f(a)` evaluates the forwarded value first anyway.
            is VeraChainedExpression if veraRight.data.size == 1 -> lowerChainedExpression(veraRight) {
                lowerPrimaryExpression(veraOperation.left)
            }

            // A member chain such as `box().plus(a)` evaluates its receiver first, so the forwarded
            // value is computed into a temporary beforehand to keep left-to-right source order.
            is VeraChainedExpression -> {
                val jvmForwarded = lowerPrimaryExpression(veraOperation.left)
                val jvmTemporary = JvmLocal(Identifier("<forwarded${temporaryCount++}>"), jvmForwarded.type)
                val jvmBody = lowerChainedExpression(veraRight) { JvmLocalRead(jvmTemporary) }
                JvmLet(jvmTemporary, jvmForwarded, jvmBody)
            }

            else -> throw JvmLoweringException.InvalidForwardApplication(
                JvmForwardApplicationIssue.RIGHT_SIDE_NOT_CALLABLE,
            )
        }
    }

    private fun requireBoolOperand(jvmType: JvmType, operatorSymbol: String) {
        if (jvmType != JvmType.BOOL) {
            throw JvmLoweringException.BoolOperandsRequired(operatorSymbol, jvmType)
        }
    }

    private fun lowerEquality(
        veraOperator: VeraOperator,
        jvmLeft: JvmExpression,
        jvmRight: JvmExpression,
    ): JvmExpression {
        val jvmType = jvmLeft.type
        if (jvmType is JvmType.InterfaceType) {
            requireTraitBuiltin(jvmType.name, JvmBuiltinTraits.eq, "compared")
            val equalsCall = JvmObjectMethods.traitEqualsCall(jvmLeft, jvmType.name, jvmRight)
            return if (veraOperator == VeraOperator.EQUALS) equalsCall else JvmLogicalNot(equalsCall)
        }
        if (jvmType is JvmType.ClassType) {
            if (!hasBuiltinTrait(jvmType.name, JvmBuiltinTraits.eq)) {
                throw JvmLoweringException.ObjectEqualityRequiresTrait(jvmType.name)
            }
            val equalsCall = JvmObjectMethods.equalsCall(jvmLeft, jvmType.name, jvmRight)
            return if (veraOperator == VeraOperator.EQUALS) equalsCall else JvmLogicalNot(equalsCall)
        }
        val jvmOperator = if (veraOperator == VeraOperator.EQUALS) {
            JvmComparisonOperator.EQ
        } else {
            JvmComparisonOperator.NEQ
        }
        return JvmComparison(jvmOperator, jvmLeft, jvmRight)
    }

    /** A trait-typed value can be [action] only when its trait requires the built-in trait [builtin]. */
    private fun requireTraitBuiltin(trait: Identifier, builtin: Identifier, action: String) {
        if (!traitRequirements.satisfies(trait, builtin)) {
            throw JvmLoweringException.TraitRequirementMissing(
                trait,
                builtin,
                action,
                visibleTraits[trait]?.kind ?: VeraTraitKind.TRAIT,
            )
        }
    }

    private fun hasBuiltinTrait(className: Identifier, trait: Identifier): Boolean =
        className == JvmUnitType.name || visibleClasses[className]?.traits?.contains(trait) == true

    private fun validateEqualityOperands(
        veraOperator: VeraOperator,
        jvmLeftType: JvmType,
        jvmRightType: JvmType,
    ) {
        if (jvmLeftType != jvmRightType) {
            throw JvmLoweringException.OperandTypesDiffer(veraOperator.symbol, jvmLeftType, jvmRightType)
        }
        when (jvmLeftType) {
            JvmType.INT, JvmType.BOOL, JvmType.STRING -> {}
            is JvmType.ClassType, is JvmType.InterfaceType -> {}
            else -> throw JvmLoweringException.EqualityOperandsRequired(veraOperator.symbol, jvmLeftType)
        }
    }

    private fun validateIntOperands(
        veraOperator: VeraOperator,
        jvmLeftType: JvmType,
        jvmRightType: JvmType,
    ) {
        if (jvmLeftType != jvmRightType) {
            throw JvmLoweringException.OperandTypesDiffer(veraOperator.symbol, jvmLeftType, jvmRightType)
        }
        if (jvmLeftType != JvmType.INT) {
            throw JvmLoweringException.IntOperandsRequired(veraOperator.symbol, jvmLeftType)
        }
    }

    private fun lowerChainedExpression(
        veraChainedExpression: VeraChainedExpression,
        forwardedArgument: (() -> JvmExpression)? = null,
    ): JvmExpression {
        val forwardedArgumentIndex = if (forwardedArgument == null) {
            -1
        } else {
            if (veraChainedExpression.data.lastOrNull() !is VeraArguments) {
                throw JvmLoweringException.InvalidForwardApplication(
                    JvmForwardApplicationIssue.RIGHT_SIDE_NOT_CALLABLE,
                )
            }
            var previousWasArguments = false
            veraChainedExpression.data.forEach { chainedData ->
                val isArguments = chainedData is VeraArguments
                if (isArguments && previousWasArguments) {
                    throw JvmLoweringException.InvalidForwardApplication(
                        JvmForwardApplicationIssue.RIGHT_SIDE_CALLS_CALL_RESULT,
                    )
                }
                previousWasArguments = isArguments
            }
            veraChainedExpression.data.lastIndex
        }
        var jvmExpression: JvmExpression
        var dataIndex: Int

        when (val resolution = resolvePrimaryExpression(veraChainedExpression.primaryExpression)) {
            is PrimaryResolution.Resolved -> {
                jvmExpression = resolution.expression
                dataIndex = 0
            }

            is PrimaryResolution.Pending -> {
                val veraName = resolution.identifier
                val firstData = veraChainedExpression.data.firstOrNull()
                val nameSpan = veraChainedExpression.primaryExpression.span
                if (firstData is VeraArguments) {
                    val loweredArguments = lowerForwardedArguments(
                        forwardedArgument,
                        forwardedArgumentIndex == 0,
                    ) { lowerArguments(firstData) }
                    jvmExpression = located(nameSpan) { lowerNamedCall(veraName, loweredArguments) }
                    dataIndex = 1
                } else if (firstData is VeraMemberAccess && veraName in visibleTraits) {
                    // `Shape.Circle(...)` or `Color.Red`: a case owned by the trait.
                    val veraCase = requireTraitCase(veraName, firstData)
                    val callData = veraChainedExpression.data.getOrNull(1)
                    if (callData is VeraArguments) {
                        val loweredArguments = lowerForwardedArguments(
                            forwardedArgument,
                            forwardedArgumentIndex == 1,
                        ) { lowerArguments(callData) }
                        jvmExpression = located(firstData.span) { lowerObjectConstruction(veraCase, loweredArguments) }
                        dataIndex = 2
                    } else {
                        jvmExpression = located(firstData.span) { lowerNamedValue(veraCase.name) }
                        dataIndex = 1
                    }
                } else {
                    // `Empty.describe()`: a field-less class's name is its single value.
                    jvmExpression = located(nameSpan) { lowerNamedValue(veraName) }
                    dataIndex = 0
                }
            }
        }

        while (dataIndex < veraChainedExpression.data.size) {
            when (val chainedData = veraChainedExpression.data[dataIndex]) {
                is VeraMemberAccess -> {
                    val receiverType = jvmExpression.type
                    if (receiverType is JvmType.InterfaceType) {
                        // A trait-typed value exposes only the trait's fields and methods.
                        val veraTrait = visibleTraits[receiverType.name]
                            ?: throw JvmLoweringException.UnknownClassType(receiverType.name).at(chainedData.span)
                        val member = chainedData.member
                        // Its own members, and those of the traits it requires, declared by one of them.
                        val declaringTraits = listOf(veraTrait) +
                            traitRequirements.userTraitsOf(veraTrait.name).mapNotNull { name -> visibleTraits[name] }
                        val nextAccess = veraChainedExpression.data.getOrNull(dataIndex + 1)
                        val fieldTrait = declaringTraits.firstOrNull { trait -> trait.fields.any { it.name == member } }
                        if (fieldTrait != null) {
                            // A required field is read through the accessor the interface declares.
                            if (nextAccess is VeraArguments) {
                                throw JvmLoweringException.ValueCallNotSupported().at(nextAccess.span)
                            }
                            val traitField = fieldTrait.fields.first { field -> field.name == member }
                            jvmExpression = JvmInstanceCall(
                                receiver = jvmExpression,
                                ownerClass = fieldTrait.name,
                                methodName = member,
                                arguments = emptyList(),
                                signature = JvmMethodSignature(emptyList(), lowerType(traitField.type)),
                                ownerIsInterface = true,
                            )
                            dataIndex++
                            continue
                        }
                        val declaringTrait = declaringTraits.firstOrNull { trait -> trait.methods.any { it.name == member } }
                        if (nextAccess !is VeraArguments) {
                            if (declaringTrait != null) {
                                throw JvmLoweringException.ClassMethodUsedAsValue(receiverType.name, member)
                                    .at(chainedData.span)
                            }
                            throw JvmLoweringException.UnknownTraitField(
                                receiverType.name,
                                member,
                                JvmNameHints.closest(member, declaringTraits.flatMap { trait -> trait.fields.map { it.name } }),
                                veraTrait.kind,
                            ).at(chainedData.span)
                        }
                        if (declaringTrait == null) {
                            throw JvmLoweringException.UnknownTraitMethod(
                                receiverType.name,
                                member,
                                JvmNameHints.closest(member, declaringTraits.flatMap { trait -> trait.methods.map { it.name } }),
                                veraTrait.kind,
                            ).at(chainedData.span)
                        }
                        val traitMethod = declaringTrait.methods.first { method -> method.name == member }
                        val loweredArguments = lowerForwardedArguments(
                            forwardedArgument,
                            forwardedArgumentIndex == dataIndex + 1,
                        ) { lowerArguments(nextAccess) }
                        val jvmReceiver = jvmExpression
                        jvmExpression = located(chainedData.span) {
                            lowerTraitMethodCall(jvmReceiver, declaringTrait, traitMethod, loweredArguments)
                        }
                        dataIndex += 2
                        continue
                    }
                    val ownerType = receiverType as? JvmType.ClassType
                        ?: throw JvmLoweringException.MemberAccessRequiresObject(jvmExpression.type).at(chainedData.span)
                    val veraClass = visibleClasses[ownerType.name]
                        ?: throw JvmLoweringException.UnknownClassType(ownerType.name).at(chainedData.span)
                    val member = chainedData.member
                    val nextData = veraChainedExpression.data.getOrNull(dataIndex + 1)
                    val veraField = veraClass.fields.firstOrNull { it.name == member }
                    if (veraField != null) {
                        if (nextData is VeraArguments) {
                            throw JvmLoweringException.ValueCallNotSupported().at(nextData.span)
                        }
                        jvmExpression = JvmGetField(
                            receiver = jvmExpression,
                            ownerClass = ownerType.name,
                            fieldName = member,
                            fieldType = lowerType(veraField.type),
                        )
                        dataIndex++
                    } else {
                        val veraMethod = veraClass.methods.firstOrNull { it.name == member }
                        if (veraMethod == null) {
                            if (nextData is VeraArguments) {
                                throw JvmLoweringException.UnknownClassMethod(
                                    ownerType.name,
                                    member,
                                    JvmNameHints.closest(member, veraClass.methods.map { method -> method.name }),
                                ).at(chainedData.span)
                            }
                            throw JvmLoweringException.UnknownClassField(
                                ownerType.name,
                                member,
                                JvmNameHints.closest(member, veraClass.fields.map { field -> field.name }),
                            ).at(chainedData.span)
                        }
                        if (nextData is VeraArguments) {
                            val loweredArguments = lowerForwardedArguments(
                                forwardedArgument,
                                forwardedArgumentIndex == dataIndex + 1,
                            ) { lowerArguments(nextData) }
                            val jvmReceiver = jvmExpression
                            jvmExpression = located(chainedData.span) {
                                lowerInstanceMethodCall(jvmReceiver, veraClass, veraMethod, loweredArguments)
                            }
                            dataIndex += 2
                        } else {
                            throw JvmLoweringException.ClassMethodUsedAsValue(ownerType.name, member).at(chainedData.span)
                        }
                    }
                }

                is VeraArguments -> throw JvmLoweringException.ValueCallNotSupported().at(chainedData.span)
            }
        }

        return jvmExpression
    }

    private fun lowerForwardedArguments(
        forwardedArgument: (() -> JvmExpression)?,
        shouldInject: Boolean,
        lowerExplicitArguments: () -> List<LoweredArgument>,
    ): List<LoweredArgument> {
        if (!shouldInject) {
            return lowerExplicitArguments()
        }
        val forward = forwardedArgument
            ?: throw JvmLoweringException.InternalInvariant("missing forwarded argument")
        return listOf(LoweredArgument(forward(), forwarded = true)) + lowerExplicitArguments()
    }

    private fun lowerNamedCall(
        veraName: Identifier,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        val veraClass = visibleClasses[veraName]
        val receiver = receiverClass
        val receiverMethod = receiver?.methods?.firstOrNull { it.name == veraName }
        return when {
            veraClass != null -> lowerObjectConstruction(veraClass, loweredArguments)
            receiver != null && receiverMethod != null ->
                lowerInstanceMethodCall(readReceiver(), receiver, receiverMethod, loweredArguments)
            else -> lowerFunctionCall(veraName, loweredArguments)
        }
    }

    private fun requireFreshName(name: Identifier) {
        if (name in reservedNames || lookupLocal(name) != null) {
            throw JvmLoweringException.ShadowingNotAllowed(name)
        }
    }

    private fun readReceiver(): JvmExpression {
        val jvmReceiver = receiverLocal
            ?: throw JvmLoweringException.InternalInvariant("method call without a receiver")
        return JvmLocalRead(jvmReceiver)
    }

    private fun lowerInstanceMethodCall(
        jvmReceiver: JvmExpression,
        veraClass: VeraClassDeclaration,
        veraMethod: VeraFunctionDeclaration,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression = lowerMethodCall(
        jvmReceiver,
        ownerName = veraClass.name,
        methodName = veraMethod.name,
        parameters = veraMethod.parameters,
        returnType = veraMethod.returnType,
        loweredArguments = loweredArguments,
        ownerIsInterface = false,
    )

    /** Calls a method declared by a trait on a trait-typed value, through the trait's interface. */
    private fun lowerTraitMethodCall(
        jvmReceiver: JvmExpression,
        veraTrait: VeraTraitDeclaration,
        veraMethod: VeraTraitMethod,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression = lowerMethodCall(
        jvmReceiver,
        ownerName = veraTrait.name,
        methodName = veraMethod.name,
        parameters = veraMethod.parameters,
        returnType = veraMethod.returnType,
        loweredArguments = loweredArguments,
        ownerIsInterface = true,
    )

    private fun lowerMethodCall(
        jvmReceiver: JvmExpression,
        ownerName: Identifier,
        methodName: Identifier,
        parameters: List<VeraParameter>,
        returnType: VeraType,
        loweredArguments: List<LoweredArgument>,
        ownerIsInterface: Boolean,
    ): JvmExpression {
        val jvmParameters = parameters.map { veraParameter ->
            JvmParameter(veraParameter.name, lowerType(veraParameter.type))
        }
        if (loweredArguments.size != jvmParameters.size) {
            throw JvmLoweringException.MethodArityMismatch(
                ownerName,
                methodName,
                jvmParameters.size,
                loweredArguments.size,
            )
        }
        requireArgumentNames(
            "method ${ownerName.display}.${methodName.display}",
            parameters.map { veraParameter -> veraParameter.name },
            loweredArguments,
        )
        val jvmArguments = loweredArguments.mapIndexed { index, loweredArgument ->
            val jvmArgument = loweredArgument.jvmExpression
            val expectedType = jvmParameters[index].type
            coerce(jvmArgument, expectedType) ?: throw JvmLoweringException.MethodArgumentTypeMismatch(
                ownerName,
                methodName,
                parameters[index].name,
                expectedType,
                jvmArgument.type,
            ).at(loweredArgument.span)
        }
        return JvmInstanceCall(
            receiver = jvmReceiver,
            ownerClass = ownerName,
            methodName = methodName,
            arguments = jvmArguments,
            signature = JvmMethodSignature(
                jvmParameters,
                JvmTypeLowering.lowerReturn(returnType, typeNames),
            ),
            ownerIsInterface = ownerIsInterface,
        )
    }

    private fun lowerObjectConstruction(
        veraClass: VeraClassDeclaration,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        if (veraClass.fields.isEmpty()) {
            throw JvmLoweringException.SingletonConstructed(veraClass.name)
        }
        if (loweredArguments.size != veraClass.fields.size) {
            throw JvmLoweringException.ClassConstructorArityMismatch(
                veraClass.name,
                veraClass.fields.size,
                loweredArguments.size,
            )
        }
        requireArgumentNames(
            "class ${veraClass.name.display}",
            veraClass.fields.map { field -> field.name },
            loweredArguments,
        )
        val jvmFieldTypes = veraClass.fields.map { field -> lowerType(field.type) }
        val jvmArguments = loweredArguments.mapIndexed { index, loweredArgument ->
            val jvmArgument = loweredArgument.jvmExpression
            val expectedType = jvmFieldTypes[index]
            coerce(jvmArgument, expectedType) ?: throw JvmLoweringException.ClassConstructorArgumentTypeMismatch(
                veraClass.name,
                veraClass.fields[index].name,
                expectedType,
                jvmArgument.type,
            ).at(loweredArgument.span)
        }

        val jvmParameters = veraClass.fields.mapIndexed { index, field ->
            JvmParameter(field.name, jvmFieldTypes[index])
        }
        return JvmObjectConstruction(
            className = veraClass.name,
            arguments = jvmArguments,
            signature = JvmMethodSignature(jvmParameters, JvmType.VOID),
        )
    }

    private fun lowerFunctionCall(
        veraFunctionName: Identifier,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        val builtin = Builtin.from(veraFunctionName)
        if (builtin != null) {
            return lowerBuiltinCall(builtin, loweredArguments)
        }

        val veraFunctionDeclaration = visibleFunctions[veraFunctionName]
            ?: throw JvmLoweringException.UnknownFunction(
                veraFunctionName,
                JvmNameHints.closest(veraFunctionName, callableNames()),
                qualifiedCaseHint(veraFunctionName),
            )
        val jvmParameters = veraFunctionDeclaration.parameters.map { veraParameter ->
            JvmParameter(veraParameter.name, lowerType(veraParameter.type))
        }
        if (loweredArguments.size != jvmParameters.size) {
            throw JvmLoweringException.FunctionArityMismatch(
                veraFunctionName,
                jvmParameters.size,
                loweredArguments.size,
            )
        }
        requireArgumentNames(
            "function ${veraFunctionName.display}",
            veraFunctionDeclaration.parameters.map { veraParameter -> veraParameter.name },
            loweredArguments,
        )
        val jvmArguments = loweredArguments.mapIndexed { index, loweredArgument ->
            val jvmArgument = loweredArgument.jvmExpression
            val expectedType = jvmParameters[index].type
            coerce(jvmArgument, expectedType) ?: throw JvmLoweringException.FunctionArgumentTypeMismatch(
                veraFunctionName,
                veraFunctionDeclaration.parameters[index].name,
                expectedType,
                jvmArgument.type,
            ).at(loweredArgument.span)
        }
        val jvmSignature = JvmMethodSignature(
            jvmParameters,
            JvmTypeLowering.lowerReturn(veraFunctionDeclaration.returnType, typeNames),
        )
        return JvmStaticCall(
            className,
            veraFunctionDeclaration.name,
            jvmArguments,
            jvmSignature,
        )
    }

    private fun lowerArguments(veraArguments: VeraArguments): List<LoweredArgument> =
        veraArguments.arguments.map { veraArgument ->
            val veraExpression = veraArgument.expression
            LoweredArgument(
                jvmExpression = lowerPrimaryExpression(veraExpression),
                explicitName = veraArgument.name,
                span = veraArgument.span,
                shorthandName = (veraExpression as? VeraIdentifierAsExpression)
                    ?.identifier
                    ?.takeIf { veraArgument.name == null },
            )
        }

    /**
     * Checks argument names against [parameterNames] once the arity is known to match. Only written
     * arguments count: the value forwarded by `|>` fills the first parameter without a name. A call
     * with one written argument passes it without a name. A call with two or more written arguments
     * names every one in parameter order, either as `name = value` or as a bare identifier with the
     * parameter's name.
     */
    private fun requireArgumentNames(
        callee: String,
        parameterNames: List<Identifier>,
        loweredArguments: List<LoweredArgument>,
    ) {
        val writtenArguments = loweredArguments.filterNot { loweredArgument -> loweredArgument.forwarded }
        if (writtenArguments.size < 2) {
            val explicitName = writtenArguments.firstOrNull()?.explicitName ?: return
            throw JvmLoweringException.SingleArgumentNamed(callee, explicitName).at(writtenArguments.first().span)
        }
        loweredArguments.forEachIndexed { index, loweredArgument ->
            val expectedName = parameterNames[index]
            val position = index + 1
            val explicitName = loweredArgument.explicitName
            val shorthandName = loweredArgument.shorthandName
            when {
                loweredArgument.forwarded -> {}
                explicitName == expectedName -> {}
                explicitName != null ->
                    throw JvmLoweringException.ArgumentNameMismatch(callee, position, explicitName, expectedName)
                        .at(loweredArgument.span)
                shorthandName == expectedName -> {}
                shorthandName != null && shorthandName in parameterNames ->
                    throw JvmLoweringException.ArgumentNameMismatch(callee, position, shorthandName, expectedName)
                        .at(loweredArgument.span)
                else -> throw JvmLoweringException.ArgumentNameRequired(callee, position, expectedName)
                    .at(loweredArgument.span)
            }
        }
    }

    private fun lowerBuiltinCall(
        builtin: Builtin,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        return when (builtin.jvmBuiltin) {
            JvmBuiltin.PRINT -> {
                if (loweredArguments.size != 1) {
                    throw JvmLoweringException.BuiltinArityMismatch(
                        JvmBuiltin.PRINT,
                        1,
                        loweredArguments.size,
                    )
                }
                requireArgumentNames("print", listOf(Identifier("value")), loweredArguments)
                val jvmArguments = loweredArguments.map { loweredArgument -> loweredArgument.jvmExpression }
                val argument = jvmArguments.first()
                val argumentType = argument.type
                if (argumentType is JvmType.InterfaceType) {
                    requireTraitBuiltin(argumentType.name, JvmBuiltinTraits.display, "printed")
                    return JvmBuiltinCall(JvmBuiltin.PRINT, listOf(JvmObjectMethods.traitToStringCall(argument, argumentType.name)))
                }
                if (argumentType is JvmType.ClassType) {
                    if (!hasBuiltinTrait(argumentType.name, JvmBuiltinTraits.display)) {
                        throw JvmLoweringException.PrintRequiresDisplayTrait(argumentType.name)
                    }
                    return JvmBuiltinCall(
                        JvmBuiltin.PRINT,
                        listOf(JvmObjectMethods.toStringCall(argument, argumentType.name)),
                    )
                }
                if (argumentType != JvmType.INT && argumentType != JvmType.STRING && argumentType != JvmType.BOOL) {
                    throw JvmLoweringException.PrintArgumentTypeNotSupported(argumentType)
                }
                if (argumentType == JvmType.BOOL) {
                    return JvmBuiltinCall(JvmBuiltin.PRINT, listOf(JvmObjectMethods.boolToString(argument)))
                }
                JvmBuiltinCall(JvmBuiltin.PRINT, jvmArguments)
            }
        }
    }

    private fun lowerBlock(veraBlock: VeraBlock): LoweredBlock = withScope {
        val loweredStatements = lowerStatementList(veraBlock.statements)
        val jvmStatements = loweredStatements.mapNotNull { loweredStatement -> loweredStatement.jvmStatement }
        val statementsReturn = loweredStatements.any { loweredStatement -> loweredStatement.returns }
        when (veraBlock) {
            is VeraExplicitReturnBlock -> LoweredBlock(JvmExplicitReturnBlock(jvmStatements), statementsReturn)

            is VeraImplicitReturnBlock -> {
                requireReachable(loweredStatements, veraBlock.result.span)
                val loweredResult = lowerStatementExpression(veraBlock.result)
                LoweredBlock(
                    JvmImplicitReturnBlock(jvmStatements, loweredResult.jvmExpression),
                    statementsReturn || loweredResult.returns,
                )
            }

            is VeraUnitReturnBlock -> LoweredBlock(JvmUnitReturnBlock(jvmStatements), statementsReturn)
        }
    }

    private fun lowerBlockStatements(veraStatements: List<VeraStatement>): List<JvmStatement> =
        lowerStatementList(veraStatements).mapNotNull { loweredStatement -> loweredStatement.jvmStatement }

    /** Lowers a block's statements in order, rejecting any statement after one that always leaves it. */
    private fun lowerStatementList(veraStatements: List<VeraStatement>): List<LoweredStatement> {
        val loweredStatements = mutableListOf<LoweredStatement>()
        veraStatements.forEach { veraStatement ->
            requireReachable(loweredStatements, veraStatement.span)
            loweredStatements += lowerStatement(veraStatement)
        }
        return loweredStatements
    }

    /** Rejects the code at [span] when the preceding statement always leaves the block. */
    private fun requireReachable(precedingStatements: List<LoweredStatement>, span: SourceSpan) {
        val exit = precedingStatements.lastOrNull()?.exit ?: return
        throw JvmLoweringException.UnreachableStatement(exit).at(span)
    }

    private fun resolvePrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): PrimaryResolution =
        located(veraPrimaryExpression.span) { resolvePrimaryExpressionNode(veraPrimaryExpression) }

    private fun resolvePrimaryExpressionNode(
        veraPrimaryExpression: VeraPrimaryExpression,
    ): PrimaryResolution {
        return when (veraPrimaryExpression) {
            is VeraIntLiteral -> PrimaryResolution.Resolved(JvmIntLiteral(veraPrimaryExpression.value))

            is VeraStringLiteral -> PrimaryResolution.Resolved(JvmStringLiteral(veraPrimaryExpression.value))

            is VeraStringTemplate -> PrimaryResolution.Resolved(lowerStringTemplate(veraPrimaryExpression))

            is VeraBoolLiteral -> PrimaryResolution.Resolved(
                JvmBoolLiteral(veraPrimaryExpression.value),
            )

            is VeraUnitLiteral -> PrimaryResolution.Resolved(JvmUnitLiteral)

            is VeraUnaryOperation -> PrimaryResolution.Resolved(lowerUnaryOperation(veraPrimaryExpression))

            is VeraIfExpression -> PrimaryResolution.Resolved(lowerIfExpression(veraPrimaryExpression).jvmExpression)

            is VeraMatchExpression -> PrimaryResolution.Resolved(lowerMatchExpression(veraPrimaryExpression).jvmExpression)

            is VeraIdentifierAsExpression -> {
                val veraIdentifier = veraPrimaryExpression.identifier
                val jvmLocal = lookupLocal(veraIdentifier)
                if (jvmLocal != null) {
                    PrimaryResolution.Resolved(JvmLocalRead(jvmLocal))
                } else {
                    resolveReceiverField(veraIdentifier) ?: PrimaryResolution.Pending(veraIdentifier)
                }
            }

            is VeraBinaryOperation -> PrimaryResolution.Resolved(lowerBinaryOperation(veraPrimaryExpression))

            is VeraChainedExpression -> PrimaryResolution.Resolved(lowerChainedExpression(veraPrimaryExpression))
        }
    }

    /**
     * Lowers `"text \{value} text"` to a `StringBuilder` chain, evaluating placeholders left to right.
     * Values render as `print` renders them: `Bool` as `True`/`False`, objects through `Display`.
     */
    private fun lowerStringTemplate(veraTemplate: VeraStringTemplate): JvmExpression {
        val jvmPieces = veraTemplate.parts.mapNotNull { veraPart ->
            when (veraPart) {
                is VeraTemplateText -> veraPart.text.takeIf { text -> text.isNotEmpty() }?.let(::JvmStringLiteral)
                is VeraTemplateValue -> lowerTemplateValue(lowerPrimaryExpression(veraPart.expression))
            }
        }
        return JvmObjectMethods.concatenate(jvmPieces)
    }

    private fun lowerTemplateValue(jvmValue: JvmExpression): JvmExpression {
        val jvmType = jvmValue.type
        return when {
            jvmType == JvmType.INT || jvmType == JvmType.STRING -> jvmValue
            jvmType == JvmType.BOOL -> JvmObjectMethods.boolToString(jvmValue)
            jvmType is JvmType.InterfaceType -> {
                requireTraitBuiltin(jvmType.name, JvmBuiltinTraits.display, "used in string templates")
                JvmObjectMethods.traitToStringCall(jvmValue, jvmType.name)
            }
            jvmType is JvmType.ClassType -> {
                if (!hasBuiltinTrait(jvmType.name, JvmBuiltinTraits.display)) {
                    throw JvmLoweringException.TemplateValueRequiresDisplayTrait(jvmType.name)
                }
                JvmObjectMethods.toStringCall(jvmValue, jvmType.name)
            }
            else -> throw JvmLoweringException.TemplateValueTypeNotSupported(jvmType)
        }
    }

    private fun resolveReceiverField(identifier: Identifier): PrimaryResolution.Resolved? {
        val jvmReceiver = receiverLocal ?: return null
        val veraClass = receiverClass ?: return null
        val veraField = veraClass.fields.firstOrNull { it.name == identifier } ?: return null
        return PrimaryResolution.Resolved(
            JvmGetField(
                receiver = JvmLocalRead(jvmReceiver),
                ownerClass = veraClass.name,
                fieldName = identifier,
                fieldType = lowerType(veraField.type),
            ),
        )
    }

    private fun lowerType(veraType: VeraType): JvmType =
        JvmTypeLowering.lower(veraType, typeNames)
}
