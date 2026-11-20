package dev.surefold.arch

import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

/** What compiling some sources with the plugin loaded said: whether they compiled, and every error and warning. */
internal data class Compiled(val exit: ExitCode, val messages: List<String>) {
    /** Every error and warning, one a line, as a build's output shows them. */
    val output: String
        get() = messages.joinToString("\n")

    val succeeded: Boolean
        get() = exit == ExitCode.OK
}

/**
 * [sources], path to text, compiled in this JVM with the plugin loaded from this module's classes and handed each of
 * [rules] as the Gradle plugin hands them. [files] are written beside the sources before compiling, and [options] are
 * any other plugin options, each with `{dir}` standing for the directory everything is written to.
 */
internal fun compile(
    sources: Map<String, String>,
    rules: List<String>,
    files: Map<String, String> = emptyMap(),
    options: List<String> = emptyList(),
): Compiled {
    val plugin =
        requireNotNull(System.getProperty("surefold.arch.plugin")) {
            "the build must pass -Dsurefold.arch.plugin; see surefold-arch-compiler/build.gradle.kts"
        }
    val dir = createTempDirectory("surefold-arch").toRealPath()
    try {
        (sources + files).forEach { (path, text) -> dir.write(path, text) }
        val said = Said()
        val arguments =
            K2JVMCompilerArguments().apply {
                freeArgs = sources.keys.map { dir.resolve(it).toString() }
                destination = dir.resolve("out").toString()
                classpath = System.getProperty("java.class.path")
                noStdlib = true
                noReflect = true
                jvmTarget = "21"
                pluginClasspaths = plugin.split(File.pathSeparator).toTypedArray()
                pluginOptions =
                    (rules.map { "rule=$it" } + options.map { it.replace("{dir}", dir.toString()) })
                        .map { "plugin:$PLUGIN_ID:$it" }
                        .toTypedArray()
            }
        val exit = K2JVMCompiler().exec(said, Services.EMPTY, arguments)
        return Compiled(exit, said.messages)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

private fun Path.write(path: String, text: String) {
    val file = resolve(path)
    file.parent.createDirectories()
    file.writeText(text)
}

private class Said : MessageCollector {
    val messages = mutableListOf<String>()

    override fun clear() = messages.clear()

    override fun hasErrors(): Boolean = messages.any { it.startsWith("ERROR") }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        if (severity.isError || severity.isWarning) {
            messages += "$severity: ${location?.let { "${File(it.path).name}:${it.line}: " }.orEmpty()}$message"
        }
    }
}
