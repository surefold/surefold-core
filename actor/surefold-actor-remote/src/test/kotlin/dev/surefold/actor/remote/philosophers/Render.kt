package dev.surefold.actor.remote.philosophers

/** Who ate when, one row per philosopher, as a strip [width] characters wide. */
fun timeline(seen: Seen, unit: String, width: Int = 64): String {
    val meals = seen.meals()
    val end = meals.flatten().maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    fun col(at: Long) = (at * (width - 1) / end).toInt()
    val rows = NAMES.mapIndexed { seat, name ->
        val strip = CharArray(width) { '·' }
        meals[seat].forEach { (from, to) -> (col(from)..col(to)).forEach { strip[it] = '█' } }
        "  %-6s │%s│ %d meal%s".format(name, String(strip), meals[seat].size, if (meals[seat].size == 1) "" else "s")
    }
    val axis = "  %-6s └%s┘".format("", "─".repeat(width))
    val scale = "  %-6s  0%${width - 1}s".format("", "$end $unit")
    return (rows + axis + scale).joinToString("\n")
}

/** The forks as a ring, with who holds each, and who waits: a deadlock reads as a cycle. */
fun forks(states: List<String>): String = states.mapIndexed { i, s -> "  fork-$i  $s" }.joinToString("\n")

fun verdict(ok: Boolean, text: String) = (if (ok) "  ✔ " else "  ✘ ") + text

fun banner(title: String) = "\n  ━━ $title ${"━".repeat((70 - title.length).coerceAtLeast(3))}\n"
