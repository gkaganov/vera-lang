package vera.jvm.lowering

import vera.shared.model.Identifier

/** Built-in traits that the compiler implements for classes that opt in with `has`. */
internal object JvmBuiltinTraits {

    /** Provides structural object equality. */
    val eq: Identifier = Identifier("Eq")

    /** Provides `toString`, used by `print`. */
    val display: Identifier = Identifier("Display")

    val names: Set<Identifier> = setOf(eq, display)
}
