package vera.jvm.lowering

import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraProgram
import vera.ast.VeraClassDeclaration
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
        val topLevelNames = mutableSetOf<Identifier>()
        functionDeclarationList.forEach { declaration ->
            if (JvmFunctionLowering.isBuiltinName(declaration.name)) {
                throw JvmLoweringException.FunctionNameConflictsWithBuiltin(declaration.name)
            }
            if (!topLevelNames.add(declaration.name)) {
                throw JvmLoweringException.DuplicateFunction(declaration.name)
            }
        }
        val functionDeclarations = functionDeclarationList.associateBy { it.name }

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
        val jvmDeclaredClasses = classDeclarations.map { declaration ->
            lowerClass(declaration, mainClassName, functionDeclarations, classesByName)
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
            methods = listOf(staticInitializer),
            fields = listOf(instanceField),
            constructors = listOf(JvmConstructor(emptyList())),
        )
    }

    private fun lowerClass(
        veraClass: VeraClassDeclaration,
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        classesByName: Map<Identifier, VeraClassDeclaration>,
    ): JvmClass {
        val classNames = classesByName.keys
        val outerNames = buildSet {
            addAll(functionDeclarations.keys)
            addAll(classesByName.keys)
            addAll(JvmFunctionLowering.builtinNames())
        }
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
        }
        val jvmMethods = veraClass.methods.map { method ->
            JvmFunctionLowering.lower(
                mainClassName,
                functionDeclarations,
                classesByName,
                method,
                receiverClass = veraClass,
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
        )
    }
}
