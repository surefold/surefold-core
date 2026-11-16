package dev.surefold.app.compiler

/*
 * The graphs the checker's tests compile, beside what each test brings of its own (spec 0314). A graph is a
 * `SurefoldApp` whose module the checker reads.
 */

/** What every graph here imports, and the two types its recipes build. */
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

/** A graph with nothing to say about it. */
internal const val SOUND =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

internal const val FAULTY =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single { _: DataSource -> Pump() }
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

/** A module out of a collection: legal, sound, and nothing a reader can follow. */
internal const val UNREADABLE =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        listOf(single<DataSource> { DataSource() }, single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
    override fun AppScope.run(root: Pump) = Unit
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
