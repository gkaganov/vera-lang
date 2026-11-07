# Tech Stack

- Kotlin 2.4.0 (`org.jetbrains.kotlin.jvm`), Gradle Kotlin DSL.
- JVM toolchain Java 25 (`libs.versions.jvm = "25"`); Java ClassFile API requires it.
- JUnit Jupiter 6.0.3 (`org.junit.jupiter`) with explicit `junit-platform-launcher` runtime dependency.
- No Arrow dependency; compiler stages throw named `VeraException` subtypes instead of using typed result wrappers.
- Version catalog `gradle/libs.versions.toml` is the single version source.
- Plugins: Kotlin JVM 2.4.0.
- Gradle config cache enabled; Kotlin compiler execution `in-process` (`gradle.properties`).
- Group `org.vera-lang`, version `0.0.1-SNAPSHOT`.

Kotlin packages start `vera.*`; CLI `Main.kt` is in the default package.
