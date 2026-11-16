package dev.surefold.app.gradle

import java.io.File
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner

/*
 * What every build these tests run is made of: a project applying the wiring plugin to this repository's own
 * surefold-app, the runner that builds it, and the graphs and sources each test compiles (spec 0313).
 */

/** A project in [dir] applying the wiring plugin, with [wiring] in its build file and [graph] as its main source. */
internal fun project(dir: Path, wiring: String, graph: String) {
    val underTest =
        requireNotNull(System.getProperty("surefold.app.underTest")) {
            "the build must pass -Dsurefold.app.underTest; see surefold-app-gradle/build.gradle.kts"
        }
    val checker =
        requireNotNull(System.getProperty("surefold.checker.repo")) {
            "the build must pass -Dsurefold.checker.repo; see surefold-app-gradle/build.gradle.kts"
        }
    val version = System.getProperty("surefold.checker.version")
    val jars = underTest.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }

    dir.resolve("settings.gradle.kts")
        .toFile()
        .writeText(
            """
        pluginManagement {
            repositories {
                maven { url = uri("$checker") }
                gradlePluginPortal()
            }
        }

        rootProject.name = "under-test"
        """
                .trimIndent(),
        )
    dir.resolve("build.gradle.kts")
        .toFile()
        .writeText(
            """
        plugins {
            kotlin("jvm") version "2.4.10"
            id("dev.surefold.wiring") version "$version"
        }

        // Where the compiler plugin comes from; the Kotlin plugin resolves it by coordinates.
        repositories {
            maven { url = uri("$checker") }
            mavenCentral()
        }

        dependencies { implementation(files($jars)) }

        $wiring
        """
                .trimIndent(),
        )
    source(dir, "main", graph, "Under.kt")
}

/** A build of [dir] that is expected to fail. */
internal fun run(dir: Path, vararg args: String) = runner(dir, *args).buildAndFail()

internal fun runner(dir: Path, vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(dir.toFile()).withArguments(*args, "--stacktrace")

/** [text] as a Kotlin source in source set [set], replacing whatever an earlier call wrote there under [name]. */
internal fun source(dir: Path, set: String, text: String, name: String = "Waits.kt") {
    val file = dir.resolve("src/$set/kotlin/$name").toFile()
    file.parentFile.mkdirs()
    file.writeText(text)
}

internal const val IMPORTS =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single
import kotlin.reflect.typeOf

class Pump
class DataSource
"""

internal const val FAULTY =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single { _: DataSource -> Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

internal const val SOUND =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Unreadable and short of a key: what only a task that runs the graph can catch. */
internal const val UNREADABLE_AND_FAULTY =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = listOf(single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
    override fun AppScope.run(root: Pump) = Unit
}
"""

internal const val WARNED =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<Pump> { Pump() } + single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Both sleeps the JDK has, the way a test reaches for them. */
internal const val SLEEPS =
    """
import java.util.concurrent.TimeUnit

fun waits() {
    Thread.sleep(200)
    TimeUnit.MILLISECONDS.sleep(200)
}
"""
