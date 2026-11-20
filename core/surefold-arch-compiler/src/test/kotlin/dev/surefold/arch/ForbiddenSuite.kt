package dev.surefold.arch

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/** Spec 0315's first entry: calls, property accesses and constructors a rule forbids fail the compile. */
object ForbiddenSuite : Suite() {
    init {
        test("a call a rule forbids fails the compile, naming the rule, the code and what it used") {
            val said = compile(mapOf(B, "a/Caller.kt" to CALLS), listOf(FORBID))

            said.succeeded shouldBe false
            said.output shouldContain
                "ERROR: Caller.kt:4: surefold: [a-not-b] a.Caller uses b.helper; a stays out of b. " +
                    "Fix the reference, or baseline it: 'Caller.kt a-not-b'"
        }

        test("a property read and a constructor are references too") {
            val said = compile(mapOf(B, "a/Caller.kt" to READS), listOf(FORBID))

            said.output shouldContain "Caller.kt:3: surefold: [a-not-b] a (Caller.kt) uses b.limit;"
            said.output shouldContain "Caller.kt:4: surefold: [a-not-b] a (Caller.kt) uses b.Box;"
        }

        test("code the rule does not cover, and the forbidden package itself, compile") {
            val said = compile(mapOf(B, "c/Caller.kt" to CALLS.replace("package a", "package c")), listOf(FORBID))

            withClue(said.output) { said.succeeded shouldBe true }
        }

        test("a.. covers the packages under a, and a alone does not") {
            val nested = mapOf(B, "a/x/Caller.kt" to CALLS.replace("package a", "package a.x"))

            compile(nested, listOf(FORBID)).succeeded shouldBe false
            withClue("a without .. is that package or that class") {
                compile(nested, listOf("a-not-b|forbid|a|b..|a stays out of b")).succeeded shouldBe true
            }
        }

        test("an only rule lets the code it names use the package and fails everything else") {
            val only = "only-k|only|a.Caller|b..|only the caller reaches b"

            compile(mapOf(B, "a/Caller.kt" to CALLS), listOf(only)).succeeded shouldBe true
            val said = compile(mapOf(B, "a/Caller.kt" to CALLS.replace("Caller", "Other")), listOf(only))
            said.output shouldContain "surefold: [only-k] a.Other uses b.helper; only the caller reaches b."
        }

        test("a local value or parameter is not a reference to anything") {
            val said = compile(mapOf("a/Local.kt" to LOCALS), listOf("a-not-a|forbid|z..|a..|a stays out of a"))

            withClue(said.output) { said.succeeded shouldBe true }
        }

        test("a rule that does not parse fails the compile, saying how it should read") {
            val said = compile(mapOf(B), listOf("a-not-b|sometimes|a..|b.."))

            said.succeeded shouldBe false
            said.output shouldContain
                "surefold: SUREFOLD_ARCH_CONFIG: rule \"a-not-b|sometimes|a..|b..\" is not id|forbid or only|from|to|reason"
            said.output shouldNotContain "Caller"
        }

        test("two rules with one id fail the compile") {
            val said = compile(mapOf(B), listOf(FORBID, FORBID))

            said.output shouldContain "surefold: SUREFOLD_ARCH_CONFIG: two rules are called a-not-b"
        }
    }
}

private const val FORBID = "a-not-b|forbid|a..|b..|a stays out of b"

private val B =
    "b/B.kt" to
        """
        package b

        fun helper(): Int = limit

        val limit: Int = 3

        class Box(val size: Int)
        """
            .trimIndent()

private val CALLS =
    """
    package a

    object Caller {
        fun run(): Int = b.helper()
    }
    """
        .trimIndent()

private val READS =
    """
    package a

    fun read(): Int = b.limit
    fun make(): Any = b.Box(1)
    """
        .trimIndent()

private val LOCALS =
    """
    package a

    fun twice(n: Int): Int {
        val m = n
        return m + n
    }
    """
        .trimIndent()
