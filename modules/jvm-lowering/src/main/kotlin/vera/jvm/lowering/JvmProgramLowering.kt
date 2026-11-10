package vera.jvm.lowering

import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraProgram
import vera.ast.VeraRecordDeclaration
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
import vera.jvm.ir.JvmRecordConstruction
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitType
import vera.shared.model.Identifier

object JvmProgramLowering {

    fun lower(mainClassName: Identifier, program: VeraProgram): JvmProgram {
        val recordDeclarations = program.declarations
            .filterIsInstance<VeraRecordDeclaration>()
        val functionDeclarationList = program.declarations
            .filterIsInstance<VeraFunctionDeclaration>()
        functionDeclarationList.forEach { declaration ->
            if (JvmFunctionLowering.isBuiltinName(declaration.name)) {
                throw JvmLoweringException.FunctionNameConflictsWithBuiltin(declaration.name)
            }
        }
        val functionDeclarations = functionDeclarationList.associateBy { it.name }

        val duplicateRecord = recordDeclarations
            .groupBy { it.name }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        if (duplicateRecord != null) {
            throw JvmLoweringException.DuplicateRecordType(duplicateRecord)
        }
        if (recordDeclarations.any { it.name == mainClassName }) {
            throw JvmLoweringException.RecordNameConflictsWithProgramClass(mainClassName)
        }
        if (mainClassName == JvmUnitType.name) {
            throw JvmLoweringException.ProgramClassConflictsWithUnit(mainClassName)
        }

        val recordsByName = recordDeclarations.associateBy { it.name }
        val recordNames = recordsByName.keys
        val jvmRecordClasses = recordDeclarations.map { declaration ->
            lowerRecord(declaration, mainClassName, functionDeclarations, recordsByName)
        }

        val jvmMethods = mutableListOf<JvmMethod>()
        functionDeclarationList.forEach { declaration ->
            jvmMethods += JvmFunctionLowering
                .lower(mainClassName, functionDeclarations, recordsByName, declaration)
        }
        lowerEntryPointAdapter(mainClassName, functionDeclarations, recordNames)?.let { adapter ->
            jvmMethods += adapter
        }

        val jvmClasses = listOf(lowerUnitClass()) + jvmRecordClasses + JvmClass(mainClassName, jvmMethods)
        return JvmProgram(jvmClasses)
    }

    private fun lowerEntryPointAdapter(
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        recordNames: Set<Identifier>,
    ): JvmMethod? {
        val entryPoint = functionDeclarations[Identifier("main")] ?: return null
        if (entryPoint.parameters.isNotEmpty()) return null

        val entryPointSignature = JvmMethodSignature(
            emptyList(),
            JvmTypeLowering.lowerReturn(entryPoint.returnType, recordNames),
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
                        value = JvmRecordConstruction(
                            recordName = unitClassName,
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

    private fun lowerRecord(
        record: VeraRecordDeclaration,
        mainClassName: Identifier,
        functionDeclarations: Map<Identifier, VeraFunctionDeclaration>,
        recordsByName: Map<Identifier, VeraRecordDeclaration>,
    ): JvmClass {
        val recordNames = recordsByName.keys
        val seenFields = mutableSetOf<Identifier>()
        val jvmFields = record.fields.map { field ->
            if (!seenFields.add(field.name)) {
                throw JvmLoweringException.DuplicateRecordField(record.name, field.name)
            }
            if (field.type == VeraType.UNKNOWN) {
                throw JvmLoweringException.RecordFieldTypeNotAllowed(
                    record.name,
                    field.name,
                    field.type.render(),
                )
            }
            JvmField(field.name, JvmTypeLowering.lower(field.type, recordNames))
        }
        val seenMethods = mutableSetOf<Identifier>()
        record.methods.forEach { method ->
            if (!seenMethods.add(method.name)) {
                throw JvmLoweringException.DuplicateRecordMethod(record.name, method.name)
            }
            if (method.name in seenFields) {
                throw JvmLoweringException.RecordMethodConflictsWithField(record.name, method.name)
            }
        }
        val jvmMethods = record.methods.map { method ->
            JvmFunctionLowering.lower(
                mainClassName,
                functionDeclarations,
                recordsByName,
                method,
                receiverRecord = record,
            )
        }
        val constructorParameters = jvmFields.map { field ->
            JvmParameter(field.name, field.type)
        }
        return JvmClass(
            name = record.name,
            methods = jvmMethods,
            fields = jvmFields,
            constructors = listOf(JvmConstructor(constructorParameters)),
        )
    }
}
