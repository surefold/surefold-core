package dev.surefold.app.gradle

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue
import dev.surefold.junit.withTempDirectory
import java.io.File
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Spec 0041: a build that applies `dev.surefold.compiler` alone, with no wiring plugin, and compiles against this
 * repository's own surefold, resolving the checker from the local repository as a consumer does. One build at a time:
 * each is a Gradle daemon of its own (spec 0313).
 */
object SurefoldForksPluginSuite : Suite(concurrent = false) {
    init {
        test("a Deferred returned from a scope fails the compile") {
            withTempDirectory { dir ->
                forksProject(dir, ESCAPING)

                val result = forksRunner(dir).buildAndFail()

                result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
                result.output shouldContain "surefold: scope returns a Deferred"
            }
        }

        test("the value a scope awaited compiles") {
            withTempDirectory { dir ->
                forksProject(dir, AWAITED_FORK)

                val result = forksRunner(dir).build()

                result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
                withClue("a checker built for another compiler warns rather than going quiet") {
                    result.output shouldNotContain "surefold-compiler was built for Kotlin"
                }
            }
        }
    }
}

private fun forksProject(dir: Path, source: String) {
    val underTest =
        requireNotNull(System.getProperty("surefold.app.underTest")) { "the build must pass -Dsurefold.app.underTest" }
    val repo =
        requireNotNull(System.getProperty("surefold.checker.repo")) { "the build must pass -Dsurefold.checker.repo" }
    val version = System.getProperty("surefold.checker.version")
    val jars = underTest.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }
    dir.resolve("settings.gradle.kts")
        .toFile()
        .writeText(
            """
        pluginManagement {
            repositories {
                maven { url = uri("$repo") }
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
            kotlin("jvm") version "2.4.21"
            id("dev.surefold.compiler") version "$version"
        }

        repositories {
            maven { url = uri("$repo") }
            mavenCentral()
        }

        dependencies { implementation(files($jars)) }
        """
                .trimIndent(),
        )
    source(dir, "main", source, "Under.kt")
}

private fun forksRunner(dir: Path): GradleRunner =
    GradleRunner.create().withProjectDir(dir.toFile()).withArguments("classes", "--stacktrace")

private const val ESCAPING =
    """
import dev.surefold.scope

fun load() = scope { async { 1 } }
"""

private const val AWAITED_FORK =
    """
import dev.surefold.scope

fun load(): Int = scope { async { 1 }.await() }
"""
