package dev.surefold.app.gradle

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import dev.surefold.junit.withTempDirectory
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Spec 0315: a build applying `dev.surefold.arch`, resolving `surefold-arch-compiler` from the local repository as a
 * consumer does. One build at a time: each is a Gradle daemon of its own (spec 0313).
 */
object ArchPluginSuite : Suite(concurrent = false) {
    init {
        test("a forbidden call fails compileKotlin") {
            withTempDirectory { dir ->
                archProject(dir, rule = FORBID_B)
                source(dir, "main", USES_B, "a/Uses.kt")

                val result = archRunner(dir, "classes").buildAndFail()

                result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
                result.output shouldContain "surefold: [no-b]"
            }
        }

        test("a rule for main alone lets a test source make the same call") {
            withTempDirectory { dir ->
                archProject(dir, rule = FORBID_B)
                source(dir, "test", USES_B, "a/Uses.kt")

                val result = archRunner(dir, "testClasses").build()

                result.task(":compileTestKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
            }
        }

        test("a rule with no mustNotUse fails configuration, naming the rule") {
            withTempDirectory { dir ->
                archProject(dir, rule = """rule("half", "why") { code("a..") }""")

                val result = archRunner(dir, "classes").buildAndFail()

                result.output shouldContain
                    "surefoldArch rule \"half\" needs code(..) with mustNotUse(..), or onlyIn(..) with mayUse(..)"
            }
        }

        // Fails until spec-0315-baseline is underneath: the compiler there accepts `baseline` and `root` and reports
        // SUREFOLD_ARCH_STALE. Without it the first build fails with "Unsupported plugin option: ...:baseline".
        test("editing only the baseline compiles again, and reports a stale entry in a file that did not change") {
            withTempDirectory { dir ->
                archProject(dir, rule = "$FORBID_B\n    baseline = file(\"arch-baseline.txt\")")
                source(dir, "main", USES_B, "a/Uses.kt")
                source(dir, "main", "package a\n\nfun clean() = 1\n", "a/Clean.kt")
                val baseline = dir.resolve("arch-baseline.txt").toFile()
                baseline.writeText("src/main/kotlin/a/Uses.kt no-b\n")

                withClue("the baselined file compiles") {
                    archRunner(dir, "classes", "--build-cache").build().task(":compileKotlin")?.outcome shouldBe
                        TaskOutcome.SUCCESS
                }
                baseline.appendText("src/main/kotlin/a/Clean.kt no-b\n")
                val result = archRunner(dir, "classes", "--build-cache").buildAndFail()

                withClue("FAILED is neither UP-TO-DATE nor FROM-CACHE: the compiler ran") {
                    result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
                }
                result.output shouldContain "[no-b] Clean.kt no longer breaks this rule"
            }
        }
    }
}

/** A project in [dir] applying `dev.surefold.arch`, with [rule] inside its `surefoldArch { }`, and package `b`. */
private fun archProject(dir: Path, rule: String) {
    val repo =
        requireNotNull(System.getProperty("surefold.checker.repo")) { "the build must pass -Dsurefold.checker.repo" }
    val version = System.getProperty("surefold.checker.version")
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
            kotlin("jvm") version "2.4.10"
            id("dev.surefold.arch") version "$version"
        }

        repositories {
            maven { url = uri("$repo") }
            mavenCentral()
        }

        surefoldArch {
            $rule
        }
        """
                .trimIndent(),
        )
    source(dir, "main", "package b\n\nfun g() = 1\n", "b/B.kt")
}

private fun archRunner(dir: Path, vararg args: String): GradleRunner =
    GradleRunner.create().withProjectDir(dir.toFile()).withArguments(*args, "--stacktrace")

private const val FORBID_B = """rule("no-b", "a reaches b through a port") { code("a.."); mustNotUse("b..") }"""

private const val USES_B = "package a\n\nfun uses() = b.g()\n"
