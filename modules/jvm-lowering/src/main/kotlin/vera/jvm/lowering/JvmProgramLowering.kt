package vera.jvm.lowering

import vera.ast.VeraClassDeclaration
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraParameter
import vera.ast.VeraProgram
import vera.ast.VeraTraitDeclaration
import vera.ast.VeraType
import vera.ast.render
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
                throw JvmLoweringException.FunctionNameConflictsWithBuiltin(declaration.name)
            }
            if (declaration.name in JvmBuiltinTraits.names) {
                throw JvmLoweringException.NameConflictsWithBuiltinTrait(declaration.name)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.DuplicateFunction(declaration.name)
            }
        }
        val functionDeclarations = functionDeclarationList.associateBy { it.name }

        val duplicateTrait = traitDeclarations
            .groupBy { it.name }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        if (duplicateTrait != null) {
            throw JvmLoweringException.DuplicateTrait(duplicateTrait)
        }
        traitDeclarations.forEach { declaration ->
            if (declaration.name in JvmBuiltinTraits.names) {
                throw JvmLoweringException.NameConflictsWithBuiltinTrait(declaration.name)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.TopLevelNameConflict(declaration.name)
            }
        }

        val duplicateClass = classDeclarations
            .groupBy { it.name }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        if (duplicateClass != null) {
            throw JvmLoweringException.DuplicateClassType(duplicateClass)
        }
        classDeclarations.forEach { declaration ->
            if (JvmFunctionLowering.isBuiltinName(declaration.name)) {
                throw JvmLoweringException.ClassNameConflictsWithBuiltin(declaration.name)
            }
            if (declaration.name in JvmBuiltinTraits.names) {
                throw JvmLoweringException.NameConflictsWithBuiltinTrait(declaration.name)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.TopLevelNameConflict(declaration.name)
            }
        }
        if (classDeclarations.any { it.name == mainClassName }) {
            throw JvmLoweringException.ClassNameConflictsWithProgramClass(mainClassName)
        }
        if (mainClassName == JvmUnitType.name) {
            throw JvmLoweringException.ProgramClassConflictsWithUnit(mainClassName)
        }

        val classesByName = classDeclarations.associateBy { it.name }
        val classNames = classesByName.keys
        val traitsByName = traitDeclarations.associateBy { it.name }
        traitDeclarations.forEach { trait ->
            validateTrait(trait, classNames)
        }

        val jvmDeclaredClasses = classDeclarations.map { declaration ->
            lowerClass(declaration, mainClassName, functionDeclarations, classesByName, traitsByName)
        }

        val jvmMethods = mutableListOf<JvmMethod>()
        functionDeclarationList.forEach { declaration ->
            jvmMethods += JvmFunctionLowering
                .lower(mainClassName, functionDeclarations, classesByName, declaration)
        }
        lowerEntryPointAdapter(mainClassName, functionDeclarations, classNames)?.let { adapter ->
            jvmMethods += adapter
        }

        val jvmClasses = listOf(lowerUnitClass()) + jvmDeclaredClasses + JvmClass(mainClassName, jvmMethods)
        return JvmProgram(jvmClasses)
    }

    private fun lowerEntryPointAdapter(
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        classNames: Set<Identifier>,
    ): JvmMethod? {
        val entryPoint = functionDeclarations[Identifier("main")] ?: return null
        if (entryPoint.parameters.isNotEmpty()) return null

        val entryPointSignature = JvmMethodSignature(
            emptyList(),
            JvmTypeLowering.lowerReturn(entryPoint.returnType, classNames),
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

    private fun lowerUnitClass(): JvmClass {
        val unitClassName = JvmUnitType.name
        val instanceField = JvmField(Identifier("INSTANCE"), JvmUnitType, isStatic = true)
        val staticInitializer = JvmMethod(
            Identifier("<clinit>"),
            emptyList(),
            JvmType.VOID,
            JvmExplicitReturnBlock(
                listOf(
                    JvmPutStatic(
                        ownerClass = unitClassName,
                        fieldName = instanceField.name,
                        fieldType = JvmUnitType,
                        fieldDescriptor = Identifier("Unit"),
                        value = JvmObjectConstruction(
                            className = unitClassName,
                            arguments = emptyList(),
                            signature = JvmMethodSignature(emptyList(), JvmType.VOID),
                        ),
                    ),
                    JvmReturn(null),
                ),
            ),
        )
        return JvmClass(
            name = unitClassName,
            methods = listOf(
                staticInitializer,
                JvmObjectMethods.equalsMethod(unitClassName, emptyList()),
                JvmObjectMethods.constantToStringMethod(unitClassName, "Unit"),
            ),
            fields = listOf(instanceField),
            constructors = listOf(JvmConstructor(emptyList())),
        )
    }

    private fun validateTrait(trait: VeraTraitDeclaration, classNames: Set<Identifier>) {
        val seenMethods = mutableSetOf<Identifier>()
        trait.methods.forEach { method ->
            if (!seenMethods.add(method.name)) {
                throw JvmLoweringException.DuplicateTraitMethod(trait.name, method.name)
            }
            val seenParameters = mutableSetOf<Identifier>()
            method.parameters.forEach { parameter ->
                if (!seenParameters.add(parameter.name)) {
                    throw JvmLoweringException.ShadowingNotAllowed(parameter.name)
                }
                JvmTypeLowering.lower(parameter.type, classNames)
            }
            JvmTypeLowering.lowerReturn(method.returnType, classNames)
        }
    }

    private fun validateClassTraits(
        veraClass: VeraClassDeclaration,
        traitsByName: Map<Identifier, VeraTraitDeclaration>,
        classNames: Set<Identifier>,
    ) {
        val seenTraits = mutableSetOf<Identifier>()
        veraClass.traits.forEach { traitName ->
            if (!seenTraits.add(traitName)) {
                throw JvmLoweringException.DuplicateTraitImplementation(veraClass.name, traitName)
            }
            if (traitName in JvmBuiltinTraits.names) return@forEach
            val trait = traitsByName[traitName]
                ?: throw JvmLoweringException.UnknownTrait(traitName)
            trait.methods.forEach { traitMethod ->
                val classMethod = veraClass.methods.firstOrNull { it.name == traitMethod.name }
                    ?: throw JvmLoweringException.MissingTraitMethod(
                        veraClass.name,
                        traitName,
                        traitMethod.name,
                    )
                val expectedParameters = parameterTypes(traitMethod.parameters, classNames)
                val expectedReturn = JvmTypeLowering.lowerReturn(traitMethod.returnType, classNames)
                val actualParameters = parameterTypes(classMethod.parameters, classNames)
                val actualReturn = JvmTypeLowering.lowerReturn(classMethod.returnType, classNames)
                if (expectedParameters != actualParameters || expectedReturn != actualReturn) {
                    throw JvmLoweringException.TraitMethodSignatureMismatch(
                        className = veraClass.name,
                        trait = traitName,
                        method = traitMethod.name,
                        expectedParameters = expectedParameters,
                        expectedReturn = expectedReturn,
                        actualParameters = actualParameters,
                        actualReturn = actualReturn,
                    )
                }
            }
        }
    }

    private fun requireFieldTraits(
        veraClass: VeraClassDeclaration,
        jvmFields: List<JvmField>,
        classesByName: Map<Identifier, VeraClassDeclaration>,
        trait: Identifier,
    ) {
        jvmFields.forEach { field ->
            val fieldType = field.type
            if (fieldType !is JvmType.ClassType || fieldType == JvmUnitType) return@forEach
            val fieldClass = classesByName[fieldType.name] ?: return@forEach
            if (trait !in fieldClass.traits) {
                throw JvmLoweringException.ClassFieldRequiresTrait(
                    veraClass.name,
                    field.name,
                    fieldType.name,
                    trait,
                )
            }
        }
    }

    private fun parameterTypes(
        parameters: List<VeraParameter>,
        classNames: Set<Identifier>,
    ): List<JvmType> = parameters.map { JvmTypeLowering.lower(it.type, classNames) }

    private fun lowerClass(
        veraClass: VeraClassDeclaration,
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        classesByName: Map<Identifier, VeraClassDeclaration>,
        traitsByName: Map<Identifier, VeraTraitDeclaration>,
    ): JvmClass {
        val classNames = classesByName.keys
        val outerNames = buildSet {
            addAll(functionDeclarations.keys)
            addAll(classesByName.keys)
            addAll(JvmFunctionLowering.builtinNames())
        }
        validateClassTraits(veraClass, traitsByName, classNames)
        val hasEq = JvmBuiltinTraits.eq in veraClass.traits
        val hasDisplay = JvmBuiltinTraits.display in veraClass.traits
        val seenFields = mutableSetOf<Identifier>()
        val jvmFields = veraClass.fields.map { field ->
            if (!seenFields.add(field.name)) {
                throw JvmLoweringException.DuplicateClassField(veraClass.name, field.name)
            }
            if (field.name in outerNames) {
                throw JvmLoweringException.ShadowingNotAllowed(field.name)
            }
            if (field.type == VeraType.UNKNOWN) {
                throw JvmLoweringException.ClassFieldTypeNotAllowed(
                    veraClass.name,
                    field.name,
                    field.type.render(),
                )
            }
            JvmField(field.name, JvmTypeLowering.lower(field.type, classNames))
        }
        if (hasEq) {
            requireFieldTraits(veraClass, jvmFields, classesByName, JvmBuiltinTraits.eq)
        }
        if (hasDisplay) {
            requireFieldTraits(veraClass, jvmFields, classesByName, JvmBuiltinTraits.display)
        }
        val seenMethods = mutableSetOf<Identifier>()
        veraClass.methods.forEach { method ->
            if (!seenMethods.add(method.name)) {
                throw JvmLoweringException.DuplicateClassMethod(veraClass.name, method.name)
            }
            if (method.name in seenFields) {
                throw JvmLoweringException.ClassMethodConflictsWithField(veraClass.name, method.name)
            }
            if (method.name in outerNames) {
                throw JvmLoweringException.ShadowingNotAllowed(method.name)
            }
            if (hasEq && method.name == JvmObjectMethods.equalsName) {
                throw JvmLoweringException.ClassMethodConflictsWithGeneratedMethod(
                    veraClass.name,
                    method.name,
                    JvmBuiltinTraits.eq,
                )
            }
            if (hasDisplay && method.name == JvmObjectMethods.toStringName) {
                throw JvmLoweringException.ClassMethodConflictsWithGeneratedMethod(
                    veraClass.name,
                    method.name,
                    JvmBuiltinTraits.display,
                )
            }
        }
        val jvmMethods = veraClass.methods.map { method ->
            JvmFunctionLowering.lower(
                mainClassName,
                functionDeclarations,
                classesByName,
                method,
                receiverClass = veraClass,
            )
        } + buildList {
            if (hasEq) add(JvmObjectMethods.equalsMethod(veraClass.name, jvmFields))
            if (hasDisplay) add(JvmObjectMethods.toStringMethod(veraClass.name, jvmFields))
        }
        val constructorParameters = jvmFields.map { field ->
            JvmParameter(field.name, field.type)
        }
        return JvmClass(
            name = veraClass.name,
            methods = jvmMethods,
            fields = jvmFields,
            constructors = listOf(JvmConstructor(constructorParameters)),
        )
    }
}
