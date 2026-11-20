package dev.surefold.app.gradle

import java.util.Properties
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.InternalSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * `dev.surefold.arch`: the rules in `surefoldArch { }` checked by `surefold-arch-compiler` in each compilation they
 * name (spec 0315), so a forbidden reference fails to compile.
 */
class SurefoldArchPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        target.extensions.create("surefoldArch", SurefoldArchExtension::class.java)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = arch("group"), artifactId = arch("archArtifact"), version = arch("version"))

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val arch = project.extensions.getByType(SurefoldArchExtension::class.java)
        val name = kotlinCompilation.compilationName
        val root = project.projectDir.absolutePath
        // The baseline's content is an input here rather than through a FilesSubpluginOption: outside kapt the Kotlin
        // plugin keys one of those on its path string, so editing the file would leave the compile up to date.
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.inputs
                .file(arch.baseline)
                .optional()
                .withPropertyName("surefoldArchBaseline")
                .withPathSensitivity(PathSensitivity.NAME_ONLY)
        }
        return project.provider {
            val rules = arch.rules.filter { name in it.compilations }.map { SubpluginOption("rule", it.option()) }
            // Every rule the build declares, so the baseline's lines for rules this compilation skips still read.
            val declared = arch.rules.map { SubpluginOption("declared", it.id) }
            // Paths are not inputs, so a cache entry moves between checkouts.
            val baseline =
                arch.baseline.orNull?.asFile?.let {
                    listOf(InternalSubpluginOption("baseline", it.absolutePath), InternalSubpluginOption("root", root))
                }
            rules + declared + baseline.orEmpty()
        }
    }

    private companion object {
        const val PLUGIN_ID = "dev.surefold.arch"

        /** Written into this jar by its own build, so the coordinates cannot drift from the release. */
        private val coordinates: Properties by lazy {
            Properties().apply {
                SurefoldArchPlugin::class.java.getResourceAsStream("/checker.properties")?.use { load(it) }
                    ?: error("surefold-app-gradle was built without checker.properties; see its build script")
            }
        }

        fun arch(key: String): String = coordinates.getProperty(key) ?: error("checker.properties has no $key")
    }
}
