package org.greg

class OperandStack {

    private val stack: ArrayDeque<VeraAst.Type> = ArrayDeque()

    fun push(type: VeraAst.Type) {
        stack.add(type)
    }

    fun pop(): VeraAst.Type =
        stack.removeLastOrNull()
            ?: error("Operand stack underflow.")
}
