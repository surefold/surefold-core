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

/** A chain, a name it is reached through, and a choice whose branches are unioned. */
internal const val COMPOSED =
    """$IMPORTS
class Valve

private val chosen: Module =
    if (System.getenv("X") == null) single<DataSource> { DataSource() } else single<DataSource> { DataSource() }

private val named: Module = single { _: DataSource -> Pump() } + single { _: DataSource -> Valve() }

object Under : SurefoldApp<Pump>() {
    override val module: Module = named + chosen
    override fun AppScope.run(root: Pump) = Unit
}
"""

/**
 * surefold-app-actor's factories, compiled against the real module. The reader holds its own copy of what each takes,
 * and this is the only build that would notice that copy going stale. surefold-integrations does the same for the
 * factories its modules ship.
 */
internal const val LIBRARY_ACTORS =
    """
import dev.surefold.actor.ActorRef
import dev.surefold.actor.behaviour
import dev.surefold.actor.stay
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.actor.actor
import dev.surefold.app.actor.actors
import dev.surefold.app.single

class Ping
class Ledger

object Under : SurefoldApp<ActorRef<Ping>>() {
    override val module: Module =
        actors() +
            single<Ledger> { Ledger() } +
            actor<Ping, Ledger>("ping") { _ -> behaviour<Ping, Unit>(Unit) { _, _, _ -> stay() } }
    override fun AppScope.run(root: ActorRef<Ping>) = Unit
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

/** A module out of a collection: legal, sound, and nothing a reader can follow. */
internal const val UNREADABLE =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        listOf(single<DataSource> { DataSource() }, single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
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

/** A node nothing takes, and a node only that one takes: one edit, and so one thing to say. */
internal const val FORGOTTEN =
    """$IMPORTS
class Ledger
class Audit(val ledger: Ledger)

object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() } + single<Ledger> { Ledger() } + single { l: Ledger -> Audit(l) }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A graph that builds no root: sound to read, and unanswerable about what reaches what. */
internal const val ROOTLESS =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<DataSource> { DataSource() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Two recipes for one key, on two lines, so the report has a line to name that is not its own. */
internal const val TWICE =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() } +
            single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** The same collision, said out loud. */
internal const val OVERRIDDEN =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.overriding
import dev.surefold.app.single

class Pump

object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() }.overriding(single<Pump> { Pump() })
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Each of two recipes needing the other: legal to write, impossible to assemble. */
internal const val RING =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single { _: DataSource -> Pump() } + single { _: Pump -> DataSource() }
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

/** A setting that picks the module providing the root, made inside the graph (spec 0136). */
internal const val CHOSEN =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.choice
import dev.surefold.app.single

class Pump
enum class Kind { Small, Large }

object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Kind> { Kind.Small } + choice<Kind, Pump> { _ -> single<Pump> { Pump() } }
    override fun AppScope.run(root: Pump) = Unit
}
"""
