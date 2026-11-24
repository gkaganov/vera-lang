package vera.jvm.lowering

import vera.shared.model.Identifier

/** Help text for unknown names: a close known name ("did you mean"), or advice for a Java habit. */
internal object JvmNameHints {

    /** The candidate closest to [name], if it is close enough to be a likely misspelling. */
    fun closest(name: Identifier, candidates: Collection<Identifier>): Identifier? =
        candidates
            .asSequence()
            .filter { candidate -> candidate != name }
            .map { candidate -> candidate to distance(name.value.lowercase(), candidate.value.lowercase()) }
            .filter { (_, distance) -> distance <= maxDistance(name.value) }
            .minWithOrNull(compareBy({ it.second }, { it.first.value }))
            ?.first

    /** Advice for names that only exist in Java, used where a variable is expected. */
    fun forJavaValue(name: Identifier): String? = when (name.value) {
        "null" -> "Vera has no `null`"
        "this" -> "Vera has no `this`; refer to fields and methods by their names"
        else -> null
    }

    /** Advice for Java type names, used where a type is expected. */
    fun forJavaType(name: Identifier): String? = when (name.value) {
        "int", "Integer" -> "Vera's integer type is `Int`"
        "boolean", "Boolean" -> "Vera's boolean type is `Bool`"
        "string" -> "Vera's string type is `String`"
        "void", "Void" -> "use `Unit`, or leave out the return type"
        "long", "short", "byte", "double", "float", "char" -> "Vera does not support `${name.value}` yet; use `Int`"
        "Object" -> "Vera has no universal `Object` type"
        else -> null
    }

    /** Joins a message with a hint, or with a "did you mean" suggestion when there is no hint. */
    fun withHelp(message: String, hint: String?, suggestion: Identifier?): String {
        val help = hint ?: suggestion?.let { "did you mean `${it.display}`?" } ?: return message
        return "$message; $help"
    }

    private fun maxDistance(name: String): Int = if (name.length <= 3) 1 else 2

    /** Optimal string alignment distance: insertions, deletions, substitutions, and swaps. */
    private fun distance(left: String, right: String): Int {
        val rows = Array(left.length + 1) { IntArray(right.length + 1) }
        for (i in 0..left.length) rows[i][0] = i
        for (j in 0..right.length) rows[0][j] = j
        for (i in 1..left.length) {
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                var best = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1, rows[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    best = minOf(best, rows[i - 2][j - 2] + 1)
                }
                rows[i][j] = best
            }
        }
        return rows[left.length][right.length]
    }
}
