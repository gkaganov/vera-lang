package vera.shared.model

/**
 * A name. A case nested in a trait is named `Owner$Case` (its JVM binary name), since `$` cannot appear
 * in Vera source; [display] shows it as Vera writes it, `Owner.Case`.
 */
@JvmInline value class Identifier(val value: String) {
    val display: String get() = value.replace('$', '.')
}
