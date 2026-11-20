package dev.surefold.arch

/**
 * Where a rule looks: `a.b..` is package `a.b` and every package under it; `a.b` is package `a.b`, or the top-level
 * class `a.b`.
 */
internal class Pattern(private val text: String) {
    private val deep = text.endsWith("..")
    private val name = text.removeSuffix("..")

    fun matches(place: Place): Boolean =
        if (deep) place.packageName == name || place.packageName.startsWith("$name.")
        else place.packageName == name || place.topLevelClass == name

    override fun toString(): String = text
}

/** Where code or what it refers to lives: a package, and the top-level class it is in, where there is one. */
internal data class Place(val packageName: String, val topLevelClass: String?)

internal enum class Kind {
    /** Code in `from` must not use `to`. */
    FORBID,

    /** Only code in `from` may use `to`. */
    ONLY,
}

/** One dependency rule, as `id|forbid or only|from|to|reason` (spec 0315). */
internal data class Rule(val id: String, val kind: Kind, val from: Pattern, val to: Pattern, val reason: String) {

    /** Whether [code] referring to [target] breaks this rule. Code inside `to` may always use `to`. */
    fun brokenBy(code: Place, target: Place): Boolean =
        to.matches(target) &&
            !to.matches(code) &&
            when (kind) {
                Kind.FORBID -> from.matches(code)
                Kind.ONLY -> !from.matches(code)
            }
}

/** The rules [options] name, or why one of them is not a rule. */
internal fun parseRules(options: List<String>): Pair<List<Rule>, List<String>> {
    val rules = options.mapNotNull(::parseRule)
    val faults = options.filter { parseRule(it) == null }.map { "rule \"$it\" is not $SHAPE" }
    val twice = rules.groupBy { it.id }.filterValues { it.size > 1 }.keys.map { "two rules are called $it" }
    return rules to faults + twice
}

private fun parseRule(option: String): Rule? {
    val parts = option.split('|', limit = FIELDS)
    if (parts.size < FIELDS || parts.dropLast(1).any { it.isBlank() }) return null
    val (id, kind, from, to) = parts
    val parsed = Kind.entries.firstOrNull { it.name.lowercase() == kind } ?: return null
    return Rule(id, parsed, Pattern(from), Pattern(to), parts.last())
}

private const val FIELDS = 5

private const val SHAPE = "id|forbid or only|from|to|reason"
