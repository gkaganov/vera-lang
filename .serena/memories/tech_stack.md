# Tech Stack

- Kotlin 2.4.0 (`org.jetbrains.kotlin.jvm`), Gradle Kotlin DSL (`.gradle.kts`).
- JVM toolchain Java 25 for source + bytecode (`libs.versions.jvm = "25"`).
- Java ClassFile API used for bytecode generation (requires Java 25).
- JUnit Jupiter 6.0.3 (`org.junit.jupiter` + `kotlin("test")`), JUnit Platform.
- Arrow Core 2.2.3 used in `ast` and `shared-model`.
- Version catalog: `gradle/libs.versions.toml` (single source for versions).
- Plugin `com.github.ben-manes.versions` (dependency update checks).
- Gradle config cache enabled; Kotlin compiler execution `in-process` (`gradle.properties`; Kotlin daemon misbehaves).
- Group `org.vera-lang`, version `0.0.1-SNAPSHOT`.

Package roots: Kotlin packages start `vera.*` (not `org.vera_lang.*`); CLI `Main` is in default package.
