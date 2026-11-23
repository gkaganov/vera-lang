package vera.shared.model

/** A 1-based position in the source text. */
data class SourceLocation(val line: Int, val column: Int) {
    fun render(): String = "at line $line, column $column"
}

/** A source range from [start] (inclusive) to [end] (exclusive). A span may cross lines. */
data class SourceSpan(val start: SourceLocation, val end: SourceLocation)

abstract class VeraException(message: String) : RuntimeException(message) {

    /**
     * Where in the source the error is. Set by the stage that knows it; the first span attached
     * wins, so the innermost construct being compiled when the error occurred is reported.
     */
    var span: SourceSpan? = null
        private set

    fun attachSpan(span: SourceSpan) {
        if (this.span == null) {
            this.span = span
        }
    }

    /** The message followed by the error's position, when known. */
    fun render(): String {
        val text = message ?: "unknown compiler error"
        val start = span?.start ?: return text
        return "$text ${start.render()}"
    }
}

/** Attaches [span] (when known) and returns the same exception, for `throw error.at(span)`. */
fun <E : VeraException> E.at(span: SourceSpan?): E {
    if (span != null) {
        attachSpan(span)
    }
    return this
}
