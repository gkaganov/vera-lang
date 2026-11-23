package vera.compiler

import vera.shared.model.SourceSpan
import vera.shared.model.VeraException

/**
 * Renders compiler errors for people: the message, the file position, and the offending source
 * line with the error's span underlined.
 *
 * ```text
 * error: operator '+' requires both operands to have the same type, got Int and Bool
 *  --> main.vera:3:11
 *   |
 * 3 |     print(1 + True)
 *   |           ^^^^^^^^
 * ```
 */
object VeraDiagnostics {

    fun render(exception: VeraException, source: String, fileName: String): String {
        val message = exception.message ?: "unknown compiler error"
        val span = exception.span ?: return "error: $message"
        val lines = source.split("\r\n", "\n", "\r")
        val line = lines.getOrNull(span.start.line - 1)
            ?: return "error: $message\n --> $fileName:${span.start.line}:${span.start.column}"
        val lineNumber = span.start.line.toString()
        val gutter = " ".repeat(lineNumber.length)
        return buildString {
            appendLine("error: $message")
            appendLine("$gutter--> $fileName:${span.start.line}:${span.start.column}")
            appendLine("$gutter |")
            appendLine("$lineNumber | $line")
            append("$gutter | ${underline(line, span)}")
        }
    }

    /**
     * Marks the span's columns on [line] with `^`. A span that continues onto later lines is marked
     * to the end of its first line. Tabs before the span are kept so the marks stay aligned.
     */
    private fun underline(line: String, span: SourceSpan): String {
        val startIndex = (span.start.column - 1).coerceIn(0, line.length)
        val endIndex = if (span.end.line == span.start.line) {
            (span.end.column - 1).coerceIn(startIndex, line.length)
        } else {
            line.length
        }
        val indent = line.take(startIndex).map { char -> if (char == '\t') '\t' else ' ' }.joinToString("")
        val width = (endIndex - startIndex).coerceAtLeast(1)
        return indent + "^".repeat(width)
    }
}
