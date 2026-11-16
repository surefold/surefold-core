package dev.surefold.app.compiler

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

/** What compiling one source with the checker loaded said: whether it compiled, and every error and warning. */
internal data class Compiled(val exit: ExitCode, val messages: List<String>) {
    /** Every error and warning, one a line, as a build's output shows them. */
    val output: String
        get() = messages.joinToString("\n")

    val succeeded: Boolean
        get() = exit == ExitCode.OK
}

/**
 * [source] compiled in this JVM against surefold-app and what it builds on, with the checker loaded from this module's
 * classes and handed [test] and [verbose] as the Gradle plugin hands them (spec 0314). The source is written as [name]
 * to a directory made and deleted here, since the compiler reads files and a finding can name one.
 */
internal fun compile(
    source: String,
    test: Boolean = false,
    verbose: Boolean = false,
    name: String = "Snippet.kt",
): Compiled {
    val plugin =
        requireNotNull(System.getProperty("surefold.app.compiler.plugin")) {
            "the build must pass -Dsurefold.app.compiler.plugin; see surefold-app-compiler/build.gradle.kts"
        }
    val dir = createTempDirectory("surefold-app-compiler")
    try {
        val file = dir.resolve(name).apply { writeText(source) }
        val said = Said()
        val arguments =
            K2JVMCompilerArguments().apply {
                freeArgs = listOf(file.toString())
                destination = dir.resolve("out").toString()
                classpath = System.getProperty("java.class.path")
                noStdlib = true
                noReflect = true
                jvmTarget = "25"
                pluginClasspaths = plugin.split(File.pathSeparator).toTypedArray()
                pluginOptions = arrayOf("plugin:$PLUGIN_ID:test=$test", "plugin:$PLUGIN_ID:verbose=$verbose")
            }
        val exit = K2JVMCompiler().exec(said, Services.EMPTY, arguments)
        return Compiled(exit, said.messages)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

private class Said : MessageCollector {
    val messages = mutableListOf<String>()

    override fun clear() = messages.clear()

    override fun hasErrors(): Boolean = messages.any { it.startsWith("ERROR") }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        if (severity.isError || severity.isWarning) messages += "$severity: $message"
    }
}
