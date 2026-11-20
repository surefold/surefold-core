package dev.surefold.arch

import java.io.File

/** The files excused from a rule, each by its path from the project directory and the rule's id (spec 0315). */
internal class Baseline(val name: String, private val entries: Set<Pair<String, String>>) {
    fun excuses(path: String, rule: String): Boolean = (path to rule) in entries
}

/**
 * [file] read as `path rule-id` a line, `#` starting a comment, with [root] the directory each path is from; or why a
 * line does not belong in it. A file that is gone is a fault too, so the baseline only ever names what is there.
 */
internal fun readBaseline(file: File, root: File, rules: Set<String>): Pair<Baseline, List<String>> {
    val name = file.canonicalFile.relativeToOrNull(root)?.invariantSeparatorsPath ?: file.path
    val lines = if (file.isFile) file.readLines() else emptyList()
    val read =
        lines.mapIndexed { index, line -> index + 1 to line.substringBefore('#').trim() }.filter { it.second != "" }
    val faults =
        listOfNotNull(if (file.isFile) null else "$name is not there") +
            read.mapNotNull { (number, line) ->
                val parts = line.split(Regex("\\s+"))
                when {
                    parts.size != 2 -> "$name line $number is not 'path rule-id'"
                    parts[1] !in rules -> "$name line $number names rule ${parts[1]}, which no rule is called"
                    !root.resolve(parts[0]).isFile -> "$name line $number names ${parts[0]}, which is not there"
                    else -> null
                }
            }
    val entries = read.map { it.second.split(Regex("\\s+")) }.filter { it.size == 2 }.map { it[0] to it[1] }.toSet()
    return Baseline(name, entries) to faults
}
