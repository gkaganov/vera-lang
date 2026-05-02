package org.greg

class Locals {

    @JvmInline value class Slot(val id: Int)
    @JvmInline value class Name(val text: String)

    data class Local(val slot: Slot, val type: VeraAst.Type)

    private val locals: MutableMap<Name, Local> = mutableMapOf()
    private var nextFreeSlot = Slot(0)

    fun declare(name: Name, type: VeraAst.Type): Slot {
        val currentSlot = nextFreeSlot
        require(name !in locals.keys) { "Duplicate local name: '$name'" }
        locals[name] = Local(nextFreeSlot, type)
        nextFreeSlot = Slot(nextFreeSlot.id + 1)
        return currentSlot
    }

    fun getLocal(name: String): Local? = locals[Name(name)]
}
