package dev.surefold.app.gradle

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotContain
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
    fun `a missing key is a compiler error on the recipe that asked`(@TempDir dir: Path) {
        project(dir, "", FAULTY)

        val result = run(dir, "compileKotlin")

        withClue("the whole ask was an error while you type, and this is the compiler's own") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        withClue("the sentence surefoldWiring prints for the same fault, so a reader meets one wording") {
            result.output shouldContain "Pump needs DataSource, and nothing builds it"
        }
    }

    @Test
    fun `surefold-app-actor's actors and actor are read from the reader's list too`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", LIBRARY_ACTORS)

        val result = runner(dir, "compileKotlin").build()

        withClue("actors needs nothing, and actor takes Actors and then what its behaviour does") {
            result.output shouldContain "provides [ActorRef<Ping>, Actors, Ledger], and is short of []"
        }
    }

    @Test
    fun `a graph the reader could not follow says so, whether or not it was asked`(@TempDir dir: Path) {
        project(dir, "", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("otherwise silence means both a sound graph and a graph nobody looked at") {
            result.output shouldContain "this graph was not read here, and is checked by surefoldWiring alone"
        }
    }

    @Test
    fun `a graph with a choice is read, the choice a node providing its key`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", CHOSEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("read rather than left to surefoldWiring") { result.output shouldNotContain "was not read here" }
        result.output shouldContain "provides [Kind, Pump], and is short of []"
    }

    @Test
    fun `a sound graph compiles without a word`(@TempDir dir: Path) {
        project(dir, "", SOUND)

        runner(dir, "compileKotlin").build().output shouldNotContain "surefold-app"
    }

    @Test
    fun `a graph the reader gave up on compiles, and is left to the task`(@TempDir dir: Path) {
        project(dir, "", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("a red line under working code costs more than a fault found a moment later") {
            result.output shouldNotContain "nothing builds it"
        }
    }

    @Test
    fun `a module written as a chain, a name and a choice is read through all three`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", COMPOSED)

        runner(dir, "compileKotlin").build().output shouldContain
            "provides [DataSource, Pump, Valve], and is short of []"
    }

    @Test
    fun `a shape the reader does not know abandons the application rather than guessing`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("a red line under working code costs more than saying nothing") {
            result.output shouldContain "was not read: gave up at"
            result.output shouldNotContain "is short of"
        }
    }

    @Test
    fun `the compiler says nothing about a graph unless it is asked to`(@TempDir dir: Path) {
        project(dir, "", SOUND)

        runner(dir, "compileKotlin").build().output shouldNotContain "surefold-app: checking"
    }

    @Test
    fun `a node the root does not reach is named at the top of what it took with it`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", FORGOTTEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("one module left out is one line to change, not one line per node under it") {
            result.output shouldContain "and nothing reaches [Audit]"
        }
    }

    @Test
    fun `a root the graph does not build says nothing about what reaches it`(@TempDir dir: Path) {
        project(dir, "surefoldWiring { verbose = true }", ROOTLESS)

        val result = runner(dir, "compileKotlin").build()

        withClue("an unmatched root unreaches every node at once, which is the one wrong answer") {
            result.output shouldNotContain "nothing reaches"
        }
    }

    @Test
    fun `a node nothing reaches is a warning on the recipe that built it`(@TempDir dir: Path) {
        project(dir, "", FORGOTTEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("the sentence surefoldWiring prints for the same fault, so a reader meets one wording") {
            result.output shouldContain "surefold-app: nothing reaches Audit, and it is built on every start"
        }
        withClue("a node built for nothing is waste, not a broken graph") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        }
        withClue("one edit put Ledger out of reach too; naming it is that edit reported twice") {
            result.output shouldNotContain "nothing reaches Ledger"
        }
    }

    @Test
    fun `a graph the reader gave up on says nothing about what reaches what`(@TempDir dir: Path) {
        project(dir, "", UNREADABLE)

        withClue("a warning under correct code costs more than one surefoldWiring prints a moment later") {
            runner(dir, "compileKotlin").build().output shouldNotContain "nothing reaches"
        }
    }

    @Test
    fun `a key two recipes provide is a warning on the one that wins`(@TempDir dir: Path) {
        project(dir, "", TWICE)

        val result = runner(dir, "compileKotlin").build()

        val shadowed = TWICE.lines().indexOfFirst { it.contains("single<Pump>") } + 1

        withClue("the sentence surefoldWiring prints for the same fault, and the line it names") {
            result.output shouldContain "surefold-app: Pump is provided twice; this one wins over Under.kt:$shadowed"
        }
        withClue("a shadowed recipe is a warning; only the task decides whether one stops a build") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        }
    }

    @Test
    fun `a replacement that says it is one is not an accident to report`(@TempDir dir: Path) {
        project(dir, "", OVERRIDDEN)

        withClue("overriding forgives the collisions its own merge introduces, and so does this") {
            runner(dir, "compileKotlin").build().output shouldNotContain "provided twice"
        }
    }

    @Test
    fun `the branches of a choice are alternatives, not two recipes for one key`(@TempDir dir: Path) {
        project(dir, "", COMPOSED)

        withClue("every branch provides DataSource and every assembly gets one of them") {
            runner(dir, "compileKotlin").build().output shouldNotContain "provided twice"
        }
    }

    @Test
    fun `two recipes that need each other are an error naming the ring`(@TempDir dir: Path) {
        project(dir, "", RING)

        val result = run(dir, "compileKotlin")

        withClue("a cycle is a FAIL at runtime, so it is an error here") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        withClue("the path surefoldWiring prints for the same ring, in the same order") {
            result.output shouldContain "surefold-app: cycle DataSource -> Pump -> DataSource"
        }
    }

    @Test
    fun `a graph the reader gave up on says nothing about a ring either`(@TempDir dir: Path) {
        project(dir, "", UNREADABLE)

        runner(dir, "compileKotlin").build().output shouldNotContain "cycle"
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
