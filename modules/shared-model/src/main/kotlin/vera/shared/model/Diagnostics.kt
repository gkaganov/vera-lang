package vera.shared.model

data class SourceLocation(val line: Int, val column: Int)

abstract class VeraException(message: String) : RuntimeException(message) {
    fun render(): String = message ?: "unknown compiler error"
}
