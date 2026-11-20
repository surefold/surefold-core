package dev.surefold.arch

import java.util.Properties
import org.jetbrains.kotlin.config.KotlinCompilerVersion

/**
 * Whether this plugin is running in the compiler it was built against, by major and minor, as `surefold-compiler`
 * compares them: checkers are compiler API with no stability promise.
 */
internal fun builtForThisCompiler(): Boolean = series(builtFor) == series(KotlinCompilerVersion.VERSION)

/** What the compiler says when it is not, naming both versions, since either could be the one to change. */
internal fun versionMismatch(): String =
    "surefold-arch-compiler was built for Kotlin $builtFor and this compiler is ${KotlinCompilerVersion.VERSION}, " +
        "so its rules are not checked; use the surefold release built for this Kotlin"

private fun series(version: String): String = version.split('.').take(2).joinToString(".")

/** Written into this jar by its own build; see `surefold-arch-compiler/build.gradle.kts`. */
private val builtFor: String by lazy {
    val read =
        Properties().apply {
            ArchDiagnostics::class.java.getResourceAsStream("/surefold-arch.properties")?.use { load(it) }
        }
    read.getProperty("kotlin") ?: error("surefold-arch-compiler was built without surefold-arch.properties")
}
