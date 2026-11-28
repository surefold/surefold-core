package dev.surefold.gen

import kotlin.random.Random

/**
 * Runs [property] against [gen]'s edges first, then random samples up to [samples], stopping at the first failure. The
 * failure names the [seed], the sample, and whether it was an edge so [Gen.sample] can replay a random draw.
 */
fun <A> check(
    gen: Gen<A>,
    samples: Int = 100,
    seed: Long = Random.Default.nextLong(),
    property: (A) -> Unit,
) {
    require(samples >= 0) { "samples must be >= 0" }
    val edges = gen.edges.take(Gen.EDGE_CAP)
    edges.forEach { value ->
        runCatching { property(value) }
            .onFailure { throw CheckFailed(seed = seed, sample = value, edge = true, cause = it) }
    }
    val remaining = (samples - edges.size).coerceAtLeast(0)
    if (remaining == 0) return
    val random = Random(seed)
    repeat(remaining) {
        val value = gen.draw(random)
        runCatching { property(value) }
            .onFailure { throw CheckFailed(seed = seed, sample = value, edge = false, cause = it) }
    }
}

/** [check] for two generators; same edge-then-random order as a [Gen.zip]. */
fun <A, B> check(
    first: Gen<A>,
    second: Gen<B>,
    samples: Int = 100,
    seed: Long = Random.Default.nextLong(),
    property: (A, B) -> Unit,
) {
    check(first.zip(second) { a, b -> a to b }, samples, seed) { (a, b) -> property(a, b) }
}

/** A property that failed: [message] names seed and sample; [cause] is what the property threw. */
class CheckFailed(val seed: Long, val sample: Any?, val edge: Boolean, cause: Throwable) :
    AssertionError(
        buildString {
            append("property failed")
            if (edge) append(" on edge")
            append(": seed=").append(seed)
            append(" sample=").append(render(sample))
        },
        cause,
    )

private fun render(sample: Any?): String =
    when (sample) {
        null -> "null"
        is String -> "\"" + sample + "\""
        else -> sample.toString()
    }
