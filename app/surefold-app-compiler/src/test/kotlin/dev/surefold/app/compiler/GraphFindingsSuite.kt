package dev.surefold.app.compiler

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/** A graph, as a SurefoldApp in `Under.kt`, compiled in-process with the checker reading it. */
private fun graph(source: String, verbose: Boolean = false) = compile(source, verbose = verbose, name = "Under.kt")

/**
 * What the checker reads in a graph and says about it: missing keys, what the root does not reach, a key provided
 * twice, a ring, and the graphs it gives up on (spec 0314).
 */
object GraphFindingsSuite : Suite() {
    init {
        test("the checker runs inside the compiler, which is what puts a fault in the editor") {
            val result = graph(SOUND, verbose = true)

            result.succeeded shouldBe true
            withClue("the same checkers the IDE runs in K2 mode, so this is the editor's answer too") {
                result.output shouldContain "surefold-app: Under provides [Pump]"
            }
        }

        test("a missing key is a compiler error on the recipe that asked") {
            val result = graph(FAULTY)

            withClue("the whole ask was an error while you type, and this is the compiler's own") {
                result.succeeded shouldBe false
            }
            withClue("the sentence surefoldWiring prints for the same fault, so a reader meets one wording") {
                result.output shouldContain "Pump needs DataSource, and nothing builds it"
            }
        }

        test("surefold-app-actor's actors and actor are read from the reader's list too") {
            val result = graph(LIBRARY_ACTORS, verbose = true)

            result.succeeded shouldBe true
            withClue("actors needs nothing, and actor takes Actors and then what its behaviour does") {
                result.output shouldContain "provides [ActorRef<Ping>, Actors, Ledger], and is short of []"
            }
        }

        test("a graph the reader could not follow says so, whether or not it was asked") {
            val result = graph(UNREADABLE)

            result.succeeded shouldBe true
            withClue("otherwise silence means both a sound graph and a graph nobody looked at") {
                result.output shouldContain "this graph was not read here, and is checked by surefoldWiring alone"
            }
        }

        test("a graph with a choice is read, the choice a node providing its key") {
            val result = graph(CHOSEN, verbose = true)

            result.succeeded shouldBe true
            withClue("read rather than left to surefoldWiring") { result.output shouldNotContain "was not read here" }
            result.output shouldContain "provides [Kind, Pump], and is short of []"
        }

        test("a sound graph compiles without a word") {
            val result = graph(SOUND)

            result.succeeded shouldBe true
            result.output shouldNotContain "surefold-app"
        }

        test("a graph the reader gave up on compiles, and is left to the task") {
            val result = graph(UNREADABLE)

            result.succeeded shouldBe true
            withClue("a red line under working code costs more than a fault found a moment later") {
                result.output shouldNotContain "nothing builds it"
            }
        }

        test("a module written as a chain, a name and a choice is read through all three") {
            val result = graph(COMPOSED, verbose = true)

            result.succeeded shouldBe true
            result.output shouldContain "provides [DataSource, Pump, Valve], and is short of []"
        }

        test("a shape the reader does not know abandons the application rather than guessing") {
            val result = graph(UNREADABLE, verbose = true)

            result.succeeded shouldBe true
            withClue("a red line under working code costs more than saying nothing") {
                result.output shouldContain "was not read: gave up at"
                result.output shouldNotContain "is short of"
            }
        }

        test("the compiler says nothing about a graph unless it is asked to") {
            graph(SOUND).output shouldNotContain "surefold-app: checking"
        }

        test("a node the root does not reach is named at the top of what it took with it") {
            val result = graph(FORGOTTEN, verbose = true)

            result.succeeded shouldBe true
            withClue("one module left out is one line to change, not one line per node under it") {
                result.output shouldContain "and nothing reaches [Audit]"
            }
        }

        test("a root the graph does not build says nothing about what reaches it") {
            val result = graph(ROOTLESS, verbose = true)

            withClue("an unmatched root unreaches every node at once, which is the one wrong answer") {
                result.output shouldNotContain "nothing reaches"
            }
        }

        test("a node nothing reaches is a warning on the recipe that built it") {
            val result = graph(FORGOTTEN)

            withClue("the sentence surefoldWiring prints for the same fault, so a reader meets one wording") {
                result.output shouldContain "surefold-app: nothing reaches Audit, and it is built on every start"
            }
            withClue("a node built for nothing is waste, not a broken graph") { result.succeeded shouldBe true }
            withClue("one edit put Ledger out of reach too; naming it is that edit reported twice") {
                result.output shouldNotContain "nothing reaches Ledger"
            }
        }

        test("a graph the reader gave up on says nothing about what reaches what") {
            withClue("a warning under correct code costs more than one surefoldWiring prints a moment later") {
                graph(UNREADABLE).output shouldNotContain "nothing reaches"
            }
        }

        test("a key two recipes provide is a warning on the one that wins") {
            val result = graph(TWICE)
            val shadowed = TWICE.lines().indexOfFirst { it.contains("single<Pump>") } + 1

            withClue("the sentence surefoldWiring prints for the same fault, and the line it names") {
                result.output shouldContain
                    "surefold-app: Pump is provided twice; this one wins over Under.kt:$shadowed"
            }
            withClue("a shadowed recipe is a warning; only the task decides whether one stops a build") {
                result.succeeded shouldBe true
            }
        }

        test("a replacement that says it is one is not an accident to report") {
            withClue("overriding forgives the collisions its own merge introduces, and so does this") {
                graph(OVERRIDDEN).output shouldNotContain "provided twice"
            }
        }

        test("the branches of a choice are alternatives, not two recipes for one key") {
            withClue("every branch provides DataSource and every assembly gets one of them") {
                graph(COMPOSED).output shouldNotContain "provided twice"
            }
        }

        test("two recipes that need each other are an error naming the ring") {
            val result = graph(RING)

            withClue("a cycle is a FAIL at runtime, so it is an error here") { result.succeeded shouldBe false }
            withClue("the path surefoldWiring prints for the same ring, in the same order") {
                result.output shouldContain "surefold-app: cycle DataSource -> Pump -> DataSource"
            }
        }

        test("a graph the reader gave up on says nothing about a ring either") {
            val result = graph(UNREADABLE)

            result.succeeded shouldBe true
            result.output shouldNotContain "cycle"
        }
    }
}
