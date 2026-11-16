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
