package dev.surefold.app.gradle

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty

/** `surefoldArch { }`: the dependency rules `dev.surefold.arch` checks as each compilation compiles (spec 0315). */
abstract class SurefoldArchExtension {

    /** The files that still break a rule, one `path rule-id` line each, paths relative to the project directory. */
    abstract val baseline: RegularFileProperty

    internal val rules = mutableListOf<ArchRule>()

    /** A rule called [id]; [reason] is what its error tells whoever breaks it. */
    fun rule(id: String, reason: String, action: Action<ArchRule>) {
        val rule = ArchRule(id, reason)
        action.execute(rule)
        // Checked here, so a rule that reads wrong fails configuration naming the build file, not a compile later.
        rule.option()
        rules += rule
    }
}

/** One rule: `code` with `mustNotUse`, or `onlyIn` with `mayUse`. Patterns are `a.b..` for a package and below. */
class ArchRule internal constructor(internal val id: String, private val reason: String) {
    private var code: String? = null
    private var mustNotUse: String? = null
    private var onlyIn: String? = null
    private var mayUse: String? = null
    internal var compilations: Set<String> = setOf("main")
        private set

    /** Where the rule applies. */
    fun code(pattern: String) {
        code = pattern
    }

    /** What code under [code] must not refer to. */
    fun mustNotUse(pattern: String) {
        mustNotUse = pattern
    }

    /** The only place that may refer to [mayUse]. */
    fun onlyIn(pattern: String) {
        onlyIn = pattern
    }

    /** What only code under [onlyIn] may refer to. */
    fun mayUse(pattern: String) {
        mayUse = pattern
    }

    /** The compilations the rule checks, by name; `main` alone unless this says otherwise. */
    fun compilations(vararg names: String) {
        compilations = names.toSet()
    }

    /** The compiler option, `id|kind|from|to|reason`, or a failed configuration saying what is missing. */
    internal fun option(): String {
        val forbid = listOf(code, mustNotUse)
        val only = listOf(onlyIn, mayUse)
        val (kind, from, to) =
            when {
                forbid.all { it != null } && only.all { it == null } -> Triple("forbid", code, mustNotUse)
                only.all { it != null } && forbid.all { it == null } -> Triple("only", onlyIn, mayUse)
                else ->
                    throw GradleException(
                        "surefoldArch rule \"$id\" needs code(..) with mustNotUse(..), or onlyIn(..) with mayUse(..)",
                    )
            }
        listOf(id, from, to)
            .firstOrNull { '|' in it.orEmpty() }
            ?.let {
                throw GradleException(
                    "surefoldArch rule \"$id\": '|' separates the compiler's fields, so \"$it\" cannot",
                )
            }
        return "$id|$kind|$from|$to|$reason"
    }
}
