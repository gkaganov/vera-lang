package vera.jvm.lowering

import vera.ast.VeraTraitDeclaration
import vera.shared.model.Identifier

/**
 * The traits each trait requires, transitively: `trait A requires B` and `trait B requires C, Eq`
 * make every member of `A` also a member of `B`, `C`, and `Eq`. Requirements are validated, and
 * free of cycles, before this is used.
 */
internal class JvmTraitRequirements(private val traitsByName: Map<Identifier, VeraTraitDeclaration>) {

    private val closures = mutableMapOf<Identifier, Set<Identifier>>()

    /** Every trait [trait] requires, built-in or user, not including [trait] itself. */
    fun of(trait: Identifier): Set<Identifier> = closures.getOrPut(trait) {
        buildSet {
            traitsByName[trait]?.requirements?.forEach { required ->
                add(required)
                addAll(of(required))
            }
        }
    }

    /** The user traits [trait] requires, transitively. */
    fun userTraitsOf(trait: Identifier): Set<Identifier> = of(trait).filterTo(mutableSetOf()) { it in traitsByName }

    /** Whether [trait] is [required] or requires it. */
    fun satisfies(trait: Identifier, required: Identifier): Boolean = trait == required || required in of(trait)
}
