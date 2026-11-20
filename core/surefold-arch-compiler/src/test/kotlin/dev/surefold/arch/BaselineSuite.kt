package dev.surefold.arch

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/** Spec 0315's third entry: a checked-in baseline excuses the files it names, and only ever shrinks. */
object BaselineSuite : Suite() {
    init {
        test("a file the baseline names compiles, and a new file with the same reference does not") {
            val said =
                compile(
                    mapOf(B, CALLER, "a/New.kt" to USES_B.replace("val n", "val m")),
                    listOf(FORBID),
                    BASELINE,
                    OPTIONS,
                )

            said.succeeded shouldBe false
            said.output shouldNotContain "Caller.kt"
            said.output shouldContain
                "New.kt:3: surefold: [a-not-b] a (New.kt) uses b.helper; a stays out of b. " +
                    "Fix the reference, or baseline it: 'a/New.kt a-not-b' in gradle/baseline.txt"
        }

        test("comments and blank lines in the baseline are read past") {
            val said = compile(mapOf(B, CALLER), listOf(FORBID), BASELINE, OPTIONS)

            withClue(said.output) { said.succeeded shouldBe true }
        }

        test("a baselined file that no longer breaks its rule fails as stale, so the baseline only shrinks") {
            val said = compile(mapOf(B, "a/Caller.kt" to "package a\n\nval n = 1\n"), listOf(FORBID), BASELINE, OPTIONS)

            said.succeeded shouldBe false
            said.output shouldContain
                "Caller.kt:1: surefold: [a-not-b] a/Caller.kt no longer breaks this rule: " +
                    "delete its line from gradle/baseline.txt"
        }

        test("a file baselined for one rule is still held to the others") {
            val other = "x-only|only|x..|b..|only x reaches b"

            val said = compile(mapOf(B, CALLER), listOf(FORBID, other), BASELINE, OPTIONS)

            said.output shouldContain "surefold: [x-only] a (Caller.kt) uses b.helper; only x reaches b."
        }

        test("a line for a rule the build declares but this compilation does not check is read past") {
            val clean = "a/Caller.kt" to "package a\n\nval n = 1\n"

            val none = compile(mapOf(B, clean), emptyList(), BASELINE, OPTIONS + "declared=a-not-b")
            val other = compile(mapOf(B, clean), listOf(OTHER), BASELINE, OPTIONS + "declared=a-not-b")

            withClue(none.output) { none.succeeded shouldBe true }
            withClue(other.output) { other.succeeded shouldBe true }
        }

        test("without the declared ids, a line for a rule this compilation does not check still fails") {
            val said = compile(mapOf(B, CALLER), listOf(OTHER), BASELINE, OPTIONS)

            said.succeeded shouldBe false
            said.output shouldContain "SUREFOLD_ARCH_CONFIG: gradle/baseline.txt line 3 names rule a-not-b"
        }

        test("a baseline line naming a rule nobody declared, or a file that is not there, fails the compile") {
            val said =
                compile(
                    mapOf(B, CALLER),
                    listOf(FORBID),
                    mapOf("gradle/baseline.txt" to "a/Caller.kt a-not-b\na/Caller.kt nope\na/Gone.kt a-not-b\nwhat\n"),
                    OPTIONS,
                )

            said.succeeded shouldBe false
            said.output shouldContain
                "SUREFOLD_ARCH_CONFIG: gradle/baseline.txt line 2 names rule nope, which no rule is"
            said.output shouldContain
                "SUREFOLD_ARCH_CONFIG: gradle/baseline.txt line 3 names a/Gone.kt, which is not there"
            said.output shouldContain "SUREFOLD_ARCH_CONFIG: gradle/baseline.txt line 4 is not 'path rule-id'"
        }
    }
}

private const val FORBID = "a-not-b|forbid|a..|b..|a stays out of b"

private const val OTHER = "c-not-b|forbid|c..|b..|c stays out of b"

private val OPTIONS = listOf("baseline={dir}/gradle/baseline.txt", "root={dir}")

private val BASELINE =
    mapOf("gradle/baseline.txt" to "# what still breaks a rule\n\na/Caller.kt a-not-b  # calls b until it moves\n")

private val B = "b/B.kt" to "package b\n\nfun helper(): Int = 1\n"

private const val USES_B = "package a\n\nval n = b.helper()\n"

private val CALLER = "a/Caller.kt" to USES_B
