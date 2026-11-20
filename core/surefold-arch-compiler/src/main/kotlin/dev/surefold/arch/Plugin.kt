package dev.surefold.arch

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** What the plugin is called wherever the compiler names one: the CLI, and the Gradle plugin. */
const val PLUGIN_ID: String = "dev.surefold.arch"

internal val RULES = CompilerConfigurationKey.create<List<String>>("dependency rules")

/** The rules, one option each, as the Gradle plugin writes them from `surefoldArch { }`. */
@OptIn(ExperimentalCompilerApi::class)
class ArchCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> =
        listOf(
            CliOption(
                optionName = "rule",
                valueDescription = "id|forbid or only|from|to|reason",
                description = "A dependency rule; from and to are a.b.. for a package and below, or a.b",
                required = false,
                allowMultipleOccurrences = true,
            ),
        )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "rule" -> configuration.appendList(RULES, value)
            else -> error("unknown option ${option.optionName}")
        }
    }
}

/**
 * The plugin, which is a checker and nothing else: nothing is generated, so the editor runs the same check as the
 * compiler (spec 0315).
 */
@OptIn(ExperimentalCompilerApi::class)
class ArchCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messages = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        // Checkers loaded into a compiler they were not built for would fail out of sight: the build says so instead.
        if (!builtForThisCompiler()) {
            messages?.report(CompilerMessageSeverity.WARNING, versionMismatch())
            return
        }
        val (rules, faults) = parseRules(configuration.getList(RULES))
        // A rule that does not read checks nothing, which a passing build would hide.
        faults.forEach { messages?.report(CompilerMessageSeverity.ERROR, "surefold: SUREFOLD_ARCH_CONFIG: $it") }
        if (faults.isNotEmpty() || rules.isEmpty()) return
        FirExtensionRegistrarAdapter.registerExtension(ArchFirExtensionRegistrar(rules))
    }
}

/** Registers the checker against a session, which is what both the compiler and the IDE build. */
internal class ArchFirExtensionRegistrar(private val rules: List<Rule>) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> ArchCheckers(session, rules) }
    }
}

private class ArchCheckers(session: FirSession, rules: List<Rule>) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val fileCheckers: Set<FirDeclarationChecker<FirFile>> = setOf(ReferenceChecker(rules))
        }
}
