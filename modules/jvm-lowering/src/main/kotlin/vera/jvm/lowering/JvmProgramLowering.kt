package vera.jvm.lowering

import vera.ast.VeraClassDeclaration
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraProgram
import vera.ast.VeraTraitDeclaration
import vera.ast.VeraTraitKind
import vera.ast.VeraType
import vera.ast.render
import vera.jvm.ir.JvmAbstractMethod
import vera.jvm.ir.JvmClass
import vera.jvm.ir.JvmConstructor
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmField
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmProgram
import vera.jvm.ir.JvmPutStatic
import vera.jvm.ir.JvmObjectConstruction
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitType
import vera.shared.model.Identifier
import vera.shared.model.at

object JvmProgramLowering {

    fun lower(mainClassName: Identifier, program: VeraProgram): JvmProgram {
        val classDeclarations = program.declarations
            .filterIsInstance<VeraClassDeclaration>()
        val functionDeclarationList = program.declarations
            .filterIsInstance<VeraFunctionDeclaration>()
        val traitDeclarations = program.declarations
            .filterIsInstance<VeraTraitDeclaration>()
        val topLevelNames = mutableSetOf<Identifier>()
        functionDeclarationList.forEach { declaration ->
            if (JvmFunctionLowering.isBuiltinName(declaration.name)) {
                throw JvmLoweringException.FunctionNameConflictsWithBuiltin(declaration.name).at(declaration.span)
            }
            if (declaration.name in JvmBuiltinTraits.names) {
                throw JvmLoweringException.NameConflictsWithBuiltinTrait(declaration.name).at(declaration.span)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.DuplicateFunction(declaration.name).at(declaration.span)
            }
        }
        val functionDeclarations = functionDeclarationList.associateBy { it.name }

        val duplicateTrait = traitDeclarations
            .groupBy { it.name }
            .values
            .firstOrNull { it.size > 1 }
            ?.get(1)
        if (duplicateTrait != null) {
            throw JvmLoweringException.DuplicateTrait(duplicateTrait.name, duplicateTrait.kind).at(duplicateTrait.span)
        }
        traitDeclarations.forEach { declaration ->
            if (declaration.name in JvmBuiltinTraits.names) {
                throw JvmLoweringException.NameConflictsWithBuiltinTrait(declaration.name).at(declaration.span)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.TopLevelNameConflict(declaration.name).at(declaration.span)
            }
        }

        val traitsByName = traitDeclarations.associateBy { it.name }
        traitDeclarations.forEach { trait -> validateRequirements(trait, traitsByName) }
        val requirements = JvmTraitRequirements(traitsByName)

        // Cases nested in trait blocks are classes too, named `Owner$Case`; only top-level classes
        // take part in the top-level namespace below. A case gets its owner's requirements.
        val allClassDeclarations = classDeclarations + traitDeclarations.flatMap { trait ->
            trait.cases.map { veraCase ->
                veraCase.copy(traits = veraCase.traits + requirements.of(trait.name).filterNot { it in veraCase.traits })
            }
        }
        val duplicateClass = allClassDeclarations
            .groupBy { it.name }
            .values
            .firstOrNull { it.size > 1 }
            ?.get(1)
        if (duplicateClass != null) {
            throw JvmLoweringException.DuplicateClassType(duplicateClass.name).at(duplicateClass.span)
        }
        classDeclarations.forEach { declaration ->
            if (JvmFunctionLowering.isBuiltinName(declaration.name)) {
                throw JvmLoweringException.ClassNameConflictsWithBuiltin(declaration.name).at(declaration.span)
            }
            if (declaration.name in JvmBuiltinTraits.names) {
                throw JvmLoweringException.NameConflictsWithBuiltinTrait(declaration.name).at(declaration.span)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.TopLevelNameConflict(declaration.name).at(declaration.span)
            }
        }
        classDeclarations.firstOrNull { it.name == mainClassName }?.let { conflicting ->
            throw JvmLoweringException.ClassNameConflictsWithProgramClass(mainClassName).at(conflicting.span)
        }
        if (mainClassName == JvmUnitType.name) {
            throw JvmLoweringException.ProgramClassConflictsWithUnit(mainClassName)
        }

        allClassDeclarations.forEach { veraClass ->
            requireTraitForms(veraClass, traitsByName)
            requireStatedInterfaceNames(veraClass, traitsByName)
            requireRequiredTraits(veraClass, requirements)
        }
        val typeNames = JvmTypeNames(allClassDeclarations.map { it.name }.toSet(), traitsByName.keys)
        traitDeclarations.firstOrNull { it.name == mainClassName }?.let { conflicting ->
            throw JvmLoweringException.TraitNameConflictsWithProgramClass(mainClassName, conflicting.kind).at(conflicting.span)
        }
        traitDeclarations.forEach { trait ->
            located(trait.span) { validateTrait(trait, typeNames) }
        }

        // Interfaces are structural: every class and trait with an interface's members fits it. From
        // here on a class lists the interfaces it fits among its traits, and a trait among the traits
        // it requires, so conversions, member access, and JVM interfaces treat them alike.
        val conformance = JvmInterfaceConformance(traitsByName, requirements, typeNames)
        allClassDeclarations.forEach { veraClass -> conformance.requireStatedInterfaces(veraClass) }
        val fittedClasses = allClassDeclarations.map { veraClass ->
            veraClass.copy(traits = veraClass.traits + conformance.interfacesFittedBy(veraClass).filterNot { it in veraClass.traits })
        }
        val fittedTraits = traitDeclarations.map { trait ->
            if (trait.kind == VeraTraitKind.INTERFACE) {
                trait
            } else {
                trait.copy(requirements = trait.requirements + conformance.interfacesFittedBy(trait))
            }
        }
        val classesByName = fittedClasses.associateBy { it.name }
        val fittedTraitsByName = fittedTraits.associateBy { it.name }
        val fittedRequirements = JvmTraitRequirements(fittedTraitsByName)

        val jvmDeclaredClasses = fittedClasses.map { declaration ->
            located(declaration.span) {
                lowerClass(
                    declaration,
                    mainClassName,
                    functionDeclarations,
                    classesByName,
                    fittedTraitsByName,
                    fittedRequirements,
                    conformance,
                )
            }
        }

        val jvmMethods = mutableListOf<JvmMethod>()
        functionDeclarationList.forEach { declaration ->
            jvmMethods += located(declaration.span) {
                JvmFunctionLowering.lower(mainClassName, functionDeclarations, classesByName, fittedTraitsByName, declaration)
            }
        }
        lowerEntryPointAdapter(mainClassName, functionDeclarations, typeNames)?.let { adapter ->
            jvmMethods += adapter
        }

        val jvmInterfaces = fittedTraits.map { trait ->
            lowerTraitInterface(trait, typeNames, fittedClasses, fittedTraits, fittedRequirements)
        }
        val jvmClasses = listOf(lowerUnitClass()) + jvmInterfaces + jvmDeclaredClasses +
            JvmClass(mainClassName, jvmMethods)
        return JvmProgram(jvmClasses)
    }

    /**
     * A user trait becomes a JVM interface declaring its methods, so trait-typed values can call them.
     * Traits are closed, so each becomes a sealed interface that permits exactly the classes that have it.
     * An interface stays open: a plain JVM interface that Java classes can implement too.
     */
    private fun lowerTraitInterface(
        trait: VeraTraitDeclaration,
        typeNames: JvmTypeNames,
        classDeclarations: List<VeraClassDeclaration>,
        traitDeclarations: List<VeraTraitDeclaration>,
        requirements: JvmTraitRequirements,
    ): JvmClass {
        // A required user trait is a superinterface; requiring `Eq` or `Display` makes `equals(Object)`
        // or `toString()` part of the interface, so trait-typed values can be compared or printed.
        val requiredBuiltins = requirements.of(trait.name).filter { it in JvmBuiltinTraits.names }
        val objectMethods = buildList {
            if (JvmBuiltinTraits.eq in requiredBuiltins) add(JvmAbstractMethod(JvmObjectMethods.equalsName, JvmObjectMethods.objectEqualsSignature))
            if (JvmBuiltinTraits.display in requiredBuiltins) add(JvmAbstractMethod(JvmObjectMethods.toStringName, JvmObjectMethods.toStringSignature))
        }
        return JvmClass(
            name = trait.name,
            methods = emptyList(),
            isInterface = true,
            interfaces = trait.requirements.filter { it !in JvmBuiltinTraits.names },
            // A sealed interface permits its classes and the traits that require it.
            permittedSubclasses = if (trait.kind == VeraTraitKind.INTERFACE) {
                emptyList()
            } else {
                classDeclarations
                    .filter { veraClass -> trait.name in veraClass.traits }
                    .map { veraClass -> veraClass.name } +
                    traitDeclarations.filter { other -> trait.name in other.requirements }.map { other -> other.name }
            },
            abstractMethods = trait.methods.map { method ->
                JvmAbstractMethod(
                    method.name,
                    JvmMethodSignature(
                        method.parameters.map { parameter ->
                            JvmParameter(parameter.name, JvmTypeLowering.lower(parameter.type, typeNames))
                        },
                        JvmTypeLowering.lowerReturn(method.returnType, typeNames),
                    ),
                )
            } + trait.fields.map { field ->
                // A required field is read through an accessor named like the field, as Java records do.
                JvmAbstractMethod(field.name, JvmMethodSignature(emptyList(), JvmTypeLowering.lower(field.type, typeNames)))
            } + objectMethods,
        )
    }

    /**
     * Required traits must exist, appear once, and not lead back to the trait that requires them. Traits
     * require traits and interfaces require interfaces; both may require the built-in traits.
     */
    private fun validateRequirements(trait: VeraTraitDeclaration, traitsByName: Map<Identifier, VeraTraitDeclaration>) {
        val seen = mutableSetOf<Identifier>()
        trait.requirements.forEach { required ->
            if (!seen.add(required)) {
                throw JvmLoweringException.DuplicateRequirement(trait.name, required, trait.kind).at(trait.span)
            }
            if (required in JvmBuiltinTraits.names) return@forEach
            val requiredTrait = traitsByName[required]
            if (requiredTrait == null) {
                val candidates = traitsByName.values.filter { other -> other.kind == trait.kind }.map { other -> other.name }
                val suggestion = JvmNameHints.closest(required, candidates + JvmBuiltinTraits.names)
                throw when (trait.kind) {
                    VeraTraitKind.TRAIT -> JvmLoweringException.UnknownTrait(required, suggestion)
                    VeraTraitKind.INTERFACE -> JvmLoweringException.UnknownInterface(required, suggestion)
                }.at(trait.span)
            }
            if (trait.kind == VeraTraitKind.TRAIT && requiredTrait.kind == VeraTraitKind.INTERFACE) {
                throw JvmLoweringException.TraitRequiresInterface(trait.name, required).at(trait.span)
            }
            if (trait.kind == VeraTraitKind.INTERFACE && requiredTrait.kind == VeraTraitKind.TRAIT) {
                throw JvmLoweringException.InterfaceRequiresTrait(trait.name, required).at(trait.span)
            }
        }
        val visiting = mutableListOf(trait.name)
        fun visit(name: Identifier) {
            traitsByName[name]?.requirements?.forEach { required ->
                if (required == trait.name) {
                    throw JvmLoweringException.RequirementCycle(visiting + required, trait.kind).at(trait.span)
                }
                if (required !in visiting) {
                    visiting += required
                    visit(required)
                    visiting.removeLast()
                }
            }
        }
        visit(trait.name)
    }

    /**
     * A class joining a trait must list the traits it requires itself, so the class declaration shows
     * every trait it has. Cases get their owner's requirements automatically.
     */
    private fun requireRequiredTraits(veraClass: VeraClassDeclaration, requirements: JvmTraitRequirements) {
        veraClass.traits.forEach { trait ->
            requirements.of(trait).forEach { required ->
                if (required !in veraClass.traits) {
                    throw JvmLoweringException.MissingRequiredTrait(veraClass.name, trait, required).at(veraClass.span)
                }
            }
        }
    }

    private fun lowerEntryPointAdapter(
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        typeNames: JvmTypeNames,
    ): JvmMethod? {
        val entryPoint = functionDeclarations[Identifier("main")] ?: return null
        if (entryPoint.parameters.isNotEmpty()) return null

        val entryPointSignature = JvmMethodSignature(
            emptyList(),
            JvmTypeLowering.lowerReturn(entryPoint.returnType, typeNames),
        )
        return JvmMethod(
            Identifier("main"),
            listOf(JvmParameter(Identifier("args"), JvmType.Array(JvmType.STRING))),
            JvmType.VOID,
            JvmExplicitReturnBlock(
                listOf(
                    JvmExpressionStatement(
                        JvmStaticCall(
                            mainClassName,
                            entryPoint.name,
                            emptyList(),
                            entryPointSignature,
                        ),
                    ),
                    JvmReturn(null),
                ),
            ),
        )
    }

    /** `Unit` is the built-in field-less class: a singleton with `Eq` and a `Display` of `Unit`. */
    private fun lowerUnitClass(): JvmClass {
        val unitClassName = JvmUnitType.name
        val (instanceField, staticInitializer) = singletonMembers(unitClassName)
        return JvmClass(
            name = unitClassName,
            methods = listOf(
                staticInitializer,
                JvmObjectMethods.equalsMethod(unitClassName, emptyList()),
                JvmObjectMethods.toStringMethod(unitClassName, emptyList()),
            ),
            fields = listOf(instanceField),
            constructors = listOf(JvmConstructor(emptyList(), isPrivate = true)),
        )
    }

    /**
     * The static `INSTANCE` field and the `<clinit>` that creates it, which hold the single value of a
     * field-less class. Its constructor is private, so no other instance exists.
     */
    private fun singletonMembers(className: Identifier): Pair<JvmField, JvmMethod> {
        val classType = JvmType.ClassType(className)
        val instanceField = JvmField(Identifier("INSTANCE"), classType, isStatic = true)
        val staticInitializer = JvmMethod(
            Identifier("<clinit>"),
            emptyList(),
            JvmType.VOID,
            JvmExplicitReturnBlock(
                listOf(
                    JvmPutStatic(
                        ownerClass = className,
                        fieldName = instanceField.name,
                        fieldType = classType,
                        fieldDescriptor = className,
                        value = JvmObjectConstruction(
                            className = className,
                            arguments = emptyList(),
                            signature = JvmMethodSignature(emptyList(), JvmType.VOID),
                        ),
                    ),
                    JvmReturn(null),
                ),
            ),
        )
        return instanceField to staticInitializer
    }

    private fun validateTrait(trait: VeraTraitDeclaration, typeNames: JvmTypeNames) {
        val seenMethods = mutableSetOf<Identifier>()
        trait.methods.forEach { method ->
            if (!seenMethods.add(method.name)) {
                throw JvmLoweringException.DuplicateTraitMethod(trait.name, method.name, trait.kind).at(method.span)
            }
            val seenParameters = mutableSetOf<Identifier>()
            method.parameters.forEach { parameter ->
                if (!seenParameters.add(parameter.name)) {
                    throw JvmLoweringException.ShadowingNotAllowed(parameter.name).at(parameter.span)
                }
                located(parameter.span) { JvmTypeLowering.lower(parameter.type, typeNames) }
            }
            located(method.span) { JvmTypeLowering.lowerReturn(method.returnType, typeNames) }
        }
        val seenFields = mutableSetOf<Identifier>()
        trait.fields.forEach { field ->
            if (!seenFields.add(field.name)) {
                throw JvmLoweringException.DuplicateTraitField(trait.name, field.name, trait.kind).at(field.span)
            }
            if (field.name in seenMethods) {
                throw JvmLoweringException.TraitFieldConflictsWithMethod(trait.name, field.name, trait.kind).at(field.span)
            }
            located(field.span) { JvmTypeLowering.lower(field.type, typeNames) }
        }
    }

    /**
     * A trait in block form owns its cases, so only those cases have it; any other class joining it
     * with `has` is rejected. `has` names traits only: classes fit interfaces by their members.
     */
    private fun requireTraitForms(veraClass: VeraClassDeclaration, traitsByName: Map<Identifier, VeraTraitDeclaration>) {
        veraClass.traits.forEach { traitName ->
            val veraTrait = traitsByName[traitName] ?: return@forEach
            if (veraTrait.kind == VeraTraitKind.INTERFACE) {
                throw JvmLoweringException.InterfaceJoinedWithHas(veraClass.name, traitName).at(veraClass.span)
            }
            if (veraTrait.cases.isNotEmpty() && veraClass.owner != traitName) {
                throw JvmLoweringException.BlockTraitJoined(veraClass.name, traitName).at(veraClass.span)
            }
        }
    }

    /** `implements` names each interface once, and only interfaces. */
    private fun requireStatedInterfaceNames(
        veraClass: VeraClassDeclaration,
        traitsByName: Map<Identifier, VeraTraitDeclaration>,
    ) {
        val seen = mutableSetOf<Identifier>()
        veraClass.interfaces.forEach { name ->
            if (!seen.add(name)) {
                throw JvmLoweringException.DuplicateTraitImplementation(veraClass.name, name, VeraTraitKind.INTERFACE)
                    .at(veraClass.span)
            }
            val stated = traitsByName[name]
            if (name in JvmBuiltinTraits.names || stated?.kind == VeraTraitKind.TRAIT) {
                throw JvmLoweringException.TraitImplemented(veraClass.name, name).at(veraClass.span)
            }
            if (stated == null) {
                val interfaceNames = traitsByName.values.filter { it.kind == VeraTraitKind.INTERFACE }.map { it.name }
                throw JvmLoweringException.UnknownInterface(name, JvmNameHints.closest(name, interfaceNames))
                    .at(veraClass.span)
            }
        }
    }

    /**
     * A class must have the members of each trait it lists. The interfaces among its traits were
     * found by their members, or checked where it states them, so they need no check here.
     */
    private fun validateClassTraits(
        veraClass: VeraClassDeclaration,
        traitsByName: Map<Identifier, VeraTraitDeclaration>,
        conformance: JvmInterfaceConformance,
    ) {
        val members = conformance.classMembers(veraClass)
        val seenTraits = mutableSetOf<Identifier>()
        veraClass.traits.forEach { traitName ->
            if (!seenTraits.add(traitName)) {
                throw JvmLoweringException.DuplicateTraitImplementation(veraClass.name, traitName).at(veraClass.span)
            }
            if (traitName in JvmBuiltinTraits.names) return@forEach
            val trait = traitsByName[traitName]
                ?: throw JvmLoweringException.UnknownTrait(
                    traitName,
                    JvmNameHints.closest(
                        traitName,
                        traitsByName.values.filter { it.kind == VeraTraitKind.TRAIT }.map { it.name } + JvmBuiltinTraits.names,
                    ),
                ).at(veraClass.span)
            if (trait.kind == VeraTraitKind.INTERFACE) return@forEach
            conformance.memberMismatch(veraClass.name, members, trait, veraClass.span)?.let { mismatch -> throw mismatch }
        }
    }

    private fun requireFieldTraits(
        veraClass: VeraClassDeclaration,
        jvmFields: List<JvmField>,
        classesByName: Map<Identifier, VeraClassDeclaration>,
        traitsByName: Map<Identifier, VeraTraitDeclaration>,
        requirements: JvmTraitRequirements,
        trait: Identifier,
    ) {
        veraClass.fields.zip(jvmFields).forEach { (veraField, field) ->
            val fieldType = field.type
            // A trait-typed field supports `Eq` or `Display` when its trait requires it.
            if (fieldType is JvmType.InterfaceType && !requirements.satisfies(fieldType.name, trait)) {
                throw JvmLoweringException.ClassFieldTraitTypeNotSupported(
                    veraClass.name,
                    field.name,
                    fieldType.name,
                    trait,
                    traitsByName[fieldType.name]?.kind ?: VeraTraitKind.TRAIT,
                ).at(veraField.span)
            }
            if (fieldType !is JvmType.ClassType || fieldType == JvmUnitType) return@forEach
            val fieldClass = classesByName[fieldType.name] ?: return@forEach
            if (trait !in fieldClass.traits) {
                throw JvmLoweringException.ClassFieldRequiresTrait(
                    veraClass.name,
                    field.name,
                    fieldType.name,
                    trait,
                ).at(veraField.span)
            }
        }
    }

    private fun lowerClass(
        veraClass: VeraClassDeclaration,
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        classesByName: Map<Identifier, VeraClassDeclaration>,
        traitsByName: Map<Identifier, VeraTraitDeclaration>,
        requirements: JvmTraitRequirements,
        conformance: JvmInterfaceConformance,
    ): JvmClass {
        val typeNames = JvmTypeNames(classesByName.keys, traitsByName.keys)
        val outerNames = buildSet {
            addAll(functionDeclarations.keys)
            addAll(classesByName.keys)
            addAll(JvmFunctionLowering.builtinNames())
        }
        validateClassTraits(veraClass, traitsByName, conformance)
        val hasEq = JvmBuiltinTraits.eq in veraClass.traits
        val hasDisplay = JvmBuiltinTraits.display in veraClass.traits
        val seenFields = mutableSetOf<Identifier>()
        val jvmFields = veraClass.fields.map { field ->
            if (!seenFields.add(field.name)) {
                throw JvmLoweringException.DuplicateClassField(veraClass.name, field.name).at(field.span)
            }
            if (field.name in outerNames) {
                throw JvmLoweringException.ShadowingNotAllowed(field.name).at(field.span)
            }
            if (field.type == VeraType.UNKNOWN) {
                throw JvmLoweringException.ClassFieldTypeNotAllowed(
                    veraClass.name,
                    field.name,
                    field.type.render(),
                ).at(field.span)
            }
            JvmField(field.name, located(field.span) { JvmTypeLowering.lower(field.type, typeNames) })
        }
        if (hasEq) {
            requireFieldTraits(veraClass, jvmFields, classesByName, traitsByName, requirements, JvmBuiltinTraits.eq)
        }
        if (hasDisplay) {
            requireFieldTraits(veraClass, jvmFields, classesByName, traitsByName, requirements, JvmBuiltinTraits.display)
        }
        val seenMethods = mutableSetOf<Identifier>()
        veraClass.methods.forEach { method ->
            if (!seenMethods.add(method.name)) {
                throw JvmLoweringException.DuplicateClassMethod(veraClass.name, method.name).at(method.span)
            }
            if (method.name in seenFields) {
                throw JvmLoweringException.ClassMethodConflictsWithField(veraClass.name, method.name).at(method.span)
            }
            if (method.name in outerNames) {
                throw JvmLoweringException.ShadowingNotAllowed(method.name).at(method.span)
            }
            if (hasEq && method.name == JvmObjectMethods.equalsName) {
                throw JvmLoweringException.ClassMethodConflictsWithGeneratedMethod(
                    veraClass.name,
                    method.name,
                    JvmBuiltinTraits.eq,
                ).at(method.span)
            }
            if (hasDisplay && method.name == JvmObjectMethods.toStringName) {
                throw JvmLoweringException.ClassMethodConflictsWithGeneratedMethod(
                    veraClass.name,
                    method.name,
                    JvmBuiltinTraits.display,
                ).at(method.span)
            }
        }
        val jvmMethods = veraClass.methods.map { method ->
            located(method.span) {
                JvmFunctionLowering.lower(
                    mainClassName,
                    functionDeclarations,
                    classesByName,
                    traitsByName,
                    method,
                    receiverClass = veraClass,
                )
            }
        } + buildList {
            if (hasEq) {
                add(JvmObjectMethods.equalsMethod(veraClass.name, jvmFields))
                add(JvmObjectMethods.objectEqualsMethod(veraClass.name))
            }
            if (hasDisplay) add(JvmObjectMethods.toStringMethod(veraClass.name, jvmFields))
            // Fields required by the class's traits get accessors that implement the interfaces.
            val requiredFieldNames = veraClass.traits.flatMap { trait -> traitsByName[trait]?.fields.orEmpty() }
                .map { field -> field.name }
                .toSet()
            jvmFields.filter { field -> field.name in requiredFieldNames }.forEach { field ->
                add(JvmObjectMethods.fieldAccessorMethod(veraClass.name, field))
            }
        }
        val interfaces = veraClass.traits.filter { trait -> trait !in JvmBuiltinTraits.names }
        if (jvmFields.isEmpty()) {
            // A class without fields has exactly one value, written by its name.
            val (instanceField, staticInitializer) = singletonMembers(veraClass.name)
            return JvmClass(
                name = veraClass.name,
                methods = jvmMethods + staticInitializer,
                fields = listOf(instanceField),
                constructors = listOf(JvmConstructor(emptyList(), isPrivate = true)),
                interfaces = interfaces,
                owner = veraClass.owner,
            )
        }
        val constructorParameters = jvmFields.map { field ->
            JvmParameter(field.name, field.type)
        }
        return JvmClass(
            name = veraClass.name,
            methods = jvmMethods,
            fields = jvmFields,
            constructors = listOf(JvmConstructor(constructorParameters)),
            interfaces = interfaces,
            owner = veraClass.owner,
        )
    }
}
