package dev.surefold.gen

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test

class CheckTest {

    @Test
    fun `a property that rejects the empty string fails on an edge before random`() {
        val failure =
            shouldThrow<AssertionError> {
                check(Gen.string, samples = 100, seed = 99L) { value ->
                    require(value.isNotEmpty()) { "empty" }
                }
            }
        failure.message!!.shouldContain("edge")
        failure.message!!.shouldContain("\"\"")
    }

    @Test
    fun `a seeded check replays the same first random sample`() {
        val seen = mutableListOf<Int>()
        // Exhaust edges with a gen that has none, so the first sample is random.
        val gen = Gen(emptyList()) { it.nextInt() }
        check(gen, samples = 3, seed = 42L) { seen += it }
        val again = mutableListOf<Int>()
        check(gen, samples = 3, seed = 42L) { again += it }
        again shouldBe seen
    }

    @Test
    fun `a failure message includes the seed and the sample`() {
        val failure =
            shouldThrow<AssertionError> {
                check(Gen.const(7), samples = 1, seed = 123L) { value ->
                    require(value != 7) { "nope" }
                }
            }
        failure.message!!.shouldContain("seed=123")
        failure.message!!.shouldContain("sample=7")
    }
}
