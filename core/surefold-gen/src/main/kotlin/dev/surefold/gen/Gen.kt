package dev.surefold.gen

import kotlin.random.Random

/**
 * A generator of values of type [A]: gotcha [edges] tried first by [check], then random draws from a seed.
 *
 * Compose with [map], [flatMap] and [zip] into domain types; take one value with [sample].
 */
class Gen<out A>
internal constructor(
    /** Ordered gotchas this generator always knows; [check] runs them before random samples. */
    val edges: List<A>,
    private val sampleFrom: (Random) -> A,
) {

    /** One value. The same [seed] draws the same value for this generator. */
    fun sample(seed: Long = Random.Default.nextLong()): A = sampleFrom(Random(seed))

    internal fun draw(random: Random): A = sampleFrom(random)

    fun <B> map(transform: (A) -> B): Gen<B> =
        Gen(cap(edges.map(transform))) { random -> transform(sampleFrom(random)) }

    fun <B> flatMap(transform: (A) -> Gen<B>): Gen<B> =
        Gen(cap(edges.flatMap { transform(it).edges })) { random ->
            transform(sampleFrom(random)).sampleFrom(random)
        }

    fun <B, C> zip(other: Gen<B>, transform: (A, B) -> C): Gen<C> =
        Gen(cap(edges.flatMap { a -> other.edges.map { b -> transform(a, b) } })) { random ->
            transform(sampleFrom(random), other.sampleFrom(random))
        }

    fun filter(predicate: (A) -> Boolean): Gen<A> =
        Gen(edges.filter(predicate)) { random ->
            var next = sampleFrom(random)
            var tries = 0
            while (!predicate(next) && tries < FILTER_TRIES) {
                next = sampleFrom(random)
                tries++
            }
            require(predicate(next)) { "Gen.filter discarded $FILTER_TRIES draws" }
            next
        }

    companion object {
        const val DEFAULT_STRING_MAX = 32
        const val DEFAULT_LIST_MAX = 16
        const val EDGE_CAP = 32
        private const val FILTER_TRIES = 100

        fun <A> const(value: A): Gen<A> = Gen(listOf(value)) { value }

        fun <A> oneOf(first: A, vararg rest: A): Gen<A> = from(listOf(first, *rest))

        fun <A> from(values: Iterable<A>): Gen<A> {
            val list = values.toList()
            require(list.isNotEmpty()) { "Gen.from needs at least one value" }
            return Gen(cap(list)) { random -> list[random.nextInt(list.size)] }
        }

        val int: Gen<Int> = Gen(listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) { it.nextInt() }

        fun int(range: IntRange): Gen<Int> {
            require(!range.isEmpty()) { "Gen.int needs a non-empty range" }
            val common = listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE).filter { it in range }
            val edges = cap((listOf(range.first, range.last) + common).distinct())
            return Gen(edges) {
                val span = range.last.toLong() - range.first.toLong() + 1
                (range.first.toLong() + it.nextLong(span)).toInt()
            }
        }

        val long: Gen<Long> = Gen(listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) { it.nextLong() }

        val boolean: Gen<Boolean> = Gen(listOf(false, true)) { it.nextBoolean() }

        val double: Gen<Double> =
            Gen(listOf(Double.NEGATIVE_INFINITY, -0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, Double.NaN)) {
                it.nextDouble()
            }

        val string: Gen<String>
            get() = string(DEFAULT_STRING_MAX)

        fun string(maxLength: Int): Gen<String> {
            require(maxLength >= 0) { "string maxLength must be >= 0" }
            val long = "a".repeat(maxLength)
            val edges = listOf("", " ", "\n", "\t", "café", "日本語", long).filter { it.length <= maxLength }.distinct()
            return Gen(edges) { random ->
                val length = random.nextInt(maxLength + 1)
                buildString(length) {
                    repeat(length) { append(CHARS[random.nextInt(CHARS.length)]) }
                }
            }
        }

        fun <A> list(element: Gen<A>, maxSize: Int = DEFAULT_LIST_MAX): Gen<List<A>> {
            require(maxSize >= 0) { "list maxSize must be >= 0" }
            val singleton = element.edges.firstOrNull()?.let { listOf(it) }
            val nearMax =
                if (maxSize == 0) emptyList()
                else
                    List(maxSize) { index ->
                        element.edges.getOrElse(index % element.edges.size.coerceAtLeast(1)) {
                            element.sample(index.toLong())
                        }
                    }
            val edges = buildList {
                add(emptyList())
                if (singleton != null) add(singleton)
                if (maxSize > 1) add(nearMax)
            }
            return Gen(cap(edges)) { random ->
                val size = random.nextInt(maxSize + 1)
                List(size) { element.draw(random) }
            }
        }

        private fun <A> cap(values: List<A>): List<A> = values.distinct().take(EDGE_CAP)

        private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-."
    }
}
