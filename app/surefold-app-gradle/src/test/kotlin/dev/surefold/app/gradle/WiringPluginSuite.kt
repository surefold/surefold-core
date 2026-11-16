package dev.surefold.app.gradle

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import dev.surefold.junit.withTempDirectory
import org.gradle.testkit.runner.TaskOutcome

/**
 * What the wiring plugin does in a real build: its task, and the fault it alone catches; the test task's colour
 * settings; the diagram; `failOn`; and, end to end, that it hands the checker `verbose` and `test` (spec 0314). What
 * the checker says about a source is tested in-process, in surefold-app-compiler.
 *
 * Each test is a TestKit build of a project applying the plugin to this repository's own surefold-app, one at a time:
 * each build is a Gradle daemon of its own. The generated project declares no toolchain: it has no resolver to
 * provision one with, and the matrix runner holds only the JDK it was set up with.
 */
object WiringPluginSuite : Suite(concurrent = false) {
    init {
        test("a graph the compiler could not judge is still caught by the task that runs it") {
            withTempDirectory { dir ->
                // Unreadable on purpose: the compiler gives up on it, so this is the task's catch alone,
                // which is the division of work the whole design rests on.
                project(dir, "", UNREADABLE_AND_FAULTY)

                val result = run(dir, "surefoldWiring")

                result.task(":surefoldWiring")?.outcome shouldBe TaskOutcome.FAILED
                // Also the only place the no-reflect label is exercised: this generated project has
                // kotlin-reflect nowhere, and without stripping its notice the sentence reads
                // "Pump (Kotlin reflection is not available) needs DataSource (Kotlin reflection ...)".
                result.output shouldContain "Pump needs DataSource, and nothing builds it"
                withClue("the IDE makes an entry in the Build window out of the compiler's own shape") {
                    // Canonical, because a temporary directory on this platform is reached through a symlink
                    // and the checker reports the path it actually opened.
                    val source = dir.resolve("src/main/kotlin/Under.kt").toFile().canonicalPath
                    result.output shouldContain "e: file://$source:"
                }
            }
        }

        test("compiling is enough to fail, without waiting for check or for a run") {
            withTempDirectory { dir ->
                project(dir, "", FAULTY)

                val result = run(dir, "classes")

                withClue("the whole ask is that the fault arrives where a compile error would") {
                    result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
                }
                result.output shouldContain "Pump needs DataSource"
            }
        }

        test("FORCE_COLOR and NO_COLOR in Gradle's environment reach every test task, as inputs") {
            withTempDirectory { dir ->
                project(dir, showsTestSettings, SOUND)

                val out =
                    runner(dir, "testSettings")
                        .withEnvironment(environment("FORCE_COLOR" to "1", "NO_COLOR" to "1"))
                        .build()
                        .output

                out shouldContain "env FORCE_COLOR=1 input=1"
                out shouldContain "env NO_COLOR=1 input=1"
            }
        }

        test("-Psurefold test colour reaches the test worker as a system property") {
            withTempDirectory { dir ->
                project(dir, showsTestSettings, SOUND)

                val out =
                    runner(dir, "testSettings", "-Psurefold.test.colour=always")
                        .withEnvironment(environment())
                        .build()
                        .output

                out shouldContain "property surefold.test.colour=always"
            }
        }

        test("with none of them set, the test task is as it was") {
            withTempDirectory { dir ->
                project(dir, showsTestSettings, SOUND)

                val out = runner(dir, "testSettings").withEnvironment(environment()).build().output

                out shouldContain "env FORCE_COLOR=null input=null"
                out shouldContain "env NO_COLOR=null input=null"
                out shouldContain "property surefold.test.colour=null"
            }
        }

        test("a sound graph passes check and leaves a diagram behind") {
            withTempDirectory { dir ->
                project(dir, "", SOUND)

                val result = runner(dir, "check").build()

                result.task(":surefoldWiring")?.outcome shouldBe TaskOutcome.SUCCESS
                dir.resolve("build/reports/surefold/Under.mmd").toFile().readText() shouldContain "graph TD"
            }
        }

        test("the checker runs inside the compiler, which is what puts a fault in the editor") {
            withTempDirectory { dir ->
                project(dir, "surefoldWiring { verbose = true }", SOUND)

                val result = runner(dir, "compileKotlin").build()

                withClue("the same checkers the IDE runs in K2 mode, so this is the editor's answer too") {
                    result.output shouldContain "surefold-app: Under provides [Pump]"
                }
            }
        }

        test("a warning stops the build where the extension asks it to") {
            withTempDirectory { dir ->
                project(dir, "surefoldWiring { failOn = \"WARN\" }", WARNED)

                run(dir, "surefoldWiring").output shouldContain "provided twice"
            }
        }

        test("a sleep in a test is named, with eventually beside it") {
            withTempDirectory { dir ->
                project(dir, "", SOUND)
                source(dir, "test", SLEEPS)

                val result = runner(dir, "compileTestKotlin").build()

                result.output shouldContain "Thread.sleep waits a fixed time"
                result.output shouldContain "TimeUnit.sleep waits a fixed time"
                result.output shouldContain "surefold-test's eventually(within)"
            }
        }
    }
}

/** Prints what the plugin gave the test task: the colour settings, and whether each is one of its inputs. */
private val showsTestSettings =
    """
    tasks.register("testSettings") {
        val test = tasks.test.get()
        doLast {
            listOf("FORCE_COLOR", "NO_COLOR").forEach { name ->
                println("env ${'$'}name=" + test.environment[name] + " input=" + test.inputs.properties[name])
            }
            println("property surefold.test.colour=" + test.systemProperties["surefold.test.colour"])
        }
    }
    """
        .trimIndent()

/** This JVM's environment, with [extra] on top: TestKit replaces the environment whole. */
private fun environment(vararg extra: Pair<String, String>): Map<String, String> =
    System.getenv().filterKeys { it != "FORCE_COLOR" && it != "NO_COLOR" } + extra
