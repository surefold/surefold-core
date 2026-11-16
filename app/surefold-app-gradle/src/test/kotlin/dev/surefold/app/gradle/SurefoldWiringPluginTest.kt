package dev.surefold.app.gradle

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import java.nio.file.Path
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A build that runs, because what the plugin does is register a task and fail a build. The surefold-app under test is
 * this repository's own, handed over as a file dependency.
 *
 * The generated project declares no toolchain: it has no resolver to provision one with, and the matrix runner holds
 * only the JDK it was set up with.
 */
class SurefoldWiringPluginTest {

    @Test
    fun `a graph the compiler could not judge is still caught by the task that runs it`(@TempDir dir: Path) {
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

    @Test
    fun `compiling is enough to fail, without waiting for check or for a run`(@TempDir dir: Path) {
        project(dir, "", FAULTY)

        val result = run(dir, "classes")

        withClue("the whole ask is that the fault arrives where a compile error would") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        result.output shouldContain "Pump needs DataSource"
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

    @Test
    fun `FORCE_COLOR and NO_COLOR in Gradle's environment reach every test task, as inputs`(@TempDir dir: Path) {
        project(dir, showsTestSettings, SOUND)

        val out =
            runner(dir, "testSettings")
                .withEnvironment(environment("FORCE_COLOR" to "1", "NO_COLOR" to "1"))
                .build()
                .output

        out shouldContain "env FORCE_COLOR=1 input=1"
        out shouldContain "env NO_COLOR=1 input=1"
    }

    @Test
    fun `-Psurefold test colour reaches the test worker as a system property`(@TempDir dir: Path) {
        project(dir, showsTestSettings, SOUND)

        val out =
            runner(dir, "testSettings", "-Psurefold.test.colour=always").withEnvironment(environment()).build().output

        out shouldContain "property surefold.test.colour=always"
    }

    @Test
    fun `with none of them set, the test task is as it was`(@TempDir dir: Path) {
        project(dir, showsTestSettings, SOUND)

        val out = runner(dir, "testSettings").withEnvironment(environment()).build().output

        out shouldContain "env FORCE_COLOR=null input=null"
        out shouldContain "env NO_COLOR=null input=null"
        out shouldContain "property surefold.test.colour=null"
    }

    @Test
    fun `a sound graph passes check and leaves a diagram behind`(@TempDir dir: Path) {
        project(dir, "", SOUND)

        val result = runner(dir, "check").build()

        result.task(":surefoldWiring")?.outcome shouldBe TaskOutcome.SUCCESS
        dir.resolve("build/reports/surefold/Under.mmd").toFile().readText() shouldContain "graph TD"
    }

    @Test
    fun `the checker runs inside the compiler, which is what puts a fault in the editor`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", SOUND)

        val result = runner(dir, "compileKotlin").build()

        withClue("the same checkers the IDE runs in K2 mode, so this is the editor's answer too") {
            result.output shouldContain "surefold-app: Under provides [Pump]"
        }
    }

    @Test
    fun `a warning stops the build where the extension asks it to`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { failOn = \"WARN\" }", WARNED)

        run(dir, "surefoldWiring").output shouldContain "provided twice"
    }

    @Test
    fun `a sleep in a test is named, with eventually beside it`(@TempDir dir: Path) {
        project(dir, "", SOUND)
        source(dir, "test", SLEEPS)

        val result = runner(dir, "compileTestKotlin").build()

        result.output shouldContain "Thread.sleep waits a fixed time"
        result.output shouldContain "TimeUnit.sleep waits a fixed time"
        result.output shouldContain "surefold-test's eventually(within)"
    }
}
