package vera.jvm.lowering

import vera.ast.VeraClassDeclaration
import vera.ast.VeraField
import vera.ast.VeraParameter
import vera.ast.VeraTraitDeclaration
import vera.ast.VeraTraitKind
import vera.ast.VeraType
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier
import vera.shared.model.SourceSpan
import vera.shared.model.at

/** The fields and methods a class, or a trait with the traits it requires, offers. */
internal data class JvmMembers(val fields: List<VeraField>, val methods: List<JvmMemberMethod>)

internal data class JvmMemberMethod(
    val name: Identifier,
    val parameters: List<VeraParameter>,
    val returnType: VeraType,
    val span: SourceSpan,
)

/**
 * Member checks for traits and interfaces. A class with a trait must have the trait's members; an
 * interface is structural, so every class and trait with its members fits it, whether or not the
 * class states it with `implements`.
 */
internal class JvmInterfaceConformance(
    private val traitsByName: Map<Identifier, VeraTraitDeclaration>,
    private val requirements: JvmTraitRequirements,
    private val typeNames: JvmTypeNames,
) {

    private val interfaces = traitsByName.values.filter { trait -> trait.kind == VeraTraitKind.INTERFACE }

    /** The interfaces [veraClass] fits, in declaration order. */
    fun interfacesFittedBy(veraClass: VeraClassDeclaration): List<Identifier> {
        val members = classMembers(veraClass)
        val builtins = veraClass.traits.filter { trait -> trait in JvmBuiltinTraits.names }.toSet()
        return interfaces.filter { veraInterface -> fits(veraClass.name, members, builtins, veraInterface) }
            .map { veraInterface -> veraInterface.name }
    }

    /** The interfaces values of [trait] fit: those whose members [trait] and the traits it requires have. */
    fun interfacesFittedBy(trait: VeraTraitDeclaration): List<Identifier> {
        val declaringTraits = listOf(trait) + requirements.userTraitsOf(trait.name).mapNotNull { name -> traitsByName[name] }
        val members = JvmMembers(
            fields = declaringTraits.flatMap { declaring -> declaring.fields },
            methods = declaringTraits.flatMap { declaring ->
                declaring.methods.map { method -> JvmMemberMethod(method.name, method.parameters, method.returnType, method.span) }
            },
        )
        val builtins = requirements.of(trait.name).filter { name -> name in JvmBuiltinTraits.names }.toSet()
        return interfaces.filter { veraInterface -> fits(trait.name, members, builtins, veraInterface) }
            .map { veraInterface -> veraInterface.name }
    }

    /** Checks the interfaces [veraClass] states with `implements`, reporting the first member it lacks. */
    fun requireStatedInterfaces(veraClass: VeraClassDeclaration) {
        val members = classMembers(veraClass)
        veraClass.interfaces.forEach { name ->
            val veraInterface = traitsByName[name] ?: return@forEach
            contractsOf(veraInterface).forEach { contract ->
                memberMismatch(veraClass.name, members, contract, veraClass.span)?.let { mismatch -> throw mismatch }
            }
            builtinsOf(veraInterface).forEach { builtin ->
                if (builtin !in veraClass.traits) {
                    throw JvmLoweringException.MissingRequiredTrait(veraClass.name, name, builtin, VeraTraitKind.INTERFACE)
                        .at(veraClass.span)
                }
            }
        }
    }

    /**
     * The first of [contract]'s own fields and methods that [members] lacks or declares differently, or
     * `null` when [members] has them all. Fields must match fields and methods must match methods, by
     * name, type, and parameter names.
     */
    fun memberMismatch(
        className: Identifier,
        members: JvmMembers,
        contract: VeraTraitDeclaration,
        span: SourceSpan,
    ): JvmLoweringException? {
        val kind = contract.kind
        contract.fields.forEach { contractField ->
            val field = members.fields.firstOrNull { field -> field.name == contractField.name }
                ?: return JvmLoweringException.MissingTraitField(className, contract.name, contractField.name, contractField.type, kind)
                    .at(span)
            val expectedType = JvmTypeLowering.lower(contractField.type, typeNames)
            val actualType = located(field.span) { JvmTypeLowering.lower(field.type, typeNames) }
            if (expectedType != actualType) {
                return JvmLoweringException.TraitFieldTypeMismatch(className, contract.name, contractField.name, expectedType, actualType, kind)
                    .at(field.span)
            }
        }
        contract.methods.forEach { contractMethod ->
            val method = members.methods.firstOrNull { method -> method.name == contractMethod.name }
                ?: return JvmLoweringException.MissingTraitMethod(className, contract.name, contractMethod.name, kind).at(span)
            val expectedParameters = parameterTypes(contractMethod.parameters)
            val expectedReturn = JvmTypeLowering.lowerReturn(contractMethod.returnType, typeNames)
            val actualParameters = located(method.span) { parameterTypes(method.parameters) }
            val actualReturn = located(method.span) { JvmTypeLowering.lowerReturn(method.returnType, typeNames) }
            if (expectedParameters != actualParameters || expectedReturn != actualReturn) {
                return JvmLoweringException.TraitMethodSignatureMismatch(
                    className = className,
                    trait = contract.name,
                    method = contractMethod.name,
                    expectedParameters = expectedParameters,
                    expectedReturn = expectedReturn,
                    actualParameters = actualParameters,
                    actualReturn = actualReturn,
                    kind = kind,
                ).at(method.span)
            }
            contractMethod.parameters.zip(method.parameters).forEach { (contractParameter, parameter) ->
                if (contractParameter.name != parameter.name) {
                    return JvmLoweringException.TraitMethodParameterNameMismatch(
                        className = className,
                        trait = contract.name,
                        method = contractMethod.name,
                        expected = contractParameter.name,
                        actual = parameter.name,
                        kind = kind,
                    ).at(parameter.span)
                }
            }
        }
        return null
    }

    fun classMembers(veraClass: VeraClassDeclaration): JvmMembers = JvmMembers(
        fields = veraClass.fields,
        methods = veraClass.methods.map { method -> JvmMemberMethod(method.name, method.parameters, method.returnType, method.span) },
    )

    /** Whether [members] and [builtins] satisfy [veraInterface] and every interface it requires. */
    private fun fits(
        ownerName: Identifier,
        members: JvmMembers,
        builtins: Set<Identifier>,
        veraInterface: VeraTraitDeclaration,
    ): Boolean =
        builtins.containsAll(builtinsOf(veraInterface)) &&
            contractsOf(veraInterface).all { contract -> memberMismatch(ownerName, members, contract, contract.span) == null }

    /** [veraInterface] and the interfaces it requires, whose members a fitting class must all have. */
    private fun contractsOf(veraInterface: VeraTraitDeclaration): List<VeraTraitDeclaration> =
        listOf(veraInterface) + requirements.userTraitsOf(veraInterface.name).mapNotNull { name -> traitsByName[name] }

    private fun builtinsOf(veraInterface: VeraTraitDeclaration): Set<Identifier> =
        requirements.of(veraInterface.name).filter { name -> name in JvmBuiltinTraits.names }.toSet()

    private fun parameterTypes(parameters: List<VeraParameter>): List<JvmType> =
        parameters.map { parameter -> JvmTypeLowering.lower(parameter.type, typeNames) }
}
