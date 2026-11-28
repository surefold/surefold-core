package dev.surefold.gen

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import org.junit.jupiter.api.Test

class GenTest {

    @Test
    fun `sample with the same seed draws the same value`() {
        val gen = Gen.int
        gen.sample(seed = 7L) shouldBe gen.sample(seed = 7L)
    }

    @Test
    fun `map and zip build a domain type`() {
        data class Person(val name: String, val age: Int)

        val people = Gen.string.zip(Gen.int(1..120)) { name, age -> Person(name, age) }
        val person = people.sample(seed = 1L)
        (person.age in 1..120) shouldBe true
        people.edges.any { it.age in 1..120 } shouldBe true
    }

    @Test
    fun `int edges include the common boundaries`() {
        Gen.int.edges shouldContain Int.MIN_VALUE
        Gen.int.edges shouldContain -1
        Gen.int.edges shouldContain 0
        Gen.int.edges shouldContain 1
        Gen.int.edges shouldContain Int.MAX_VALUE
    }

    @Test
    fun `bounded int edges stay inside the range and include the ends`() {
        val edges = Gen.int(2..5).edges
        edges shouldContain 2
        edges shouldContain 5
        edges.all { it in 2..5 } shouldBe true
    }

    @Test
    fun `string edges include empty whitespace and non-ascii gotchas`() {
        val edges = Gen.string.edges
        edges shouldContain ""
        edges shouldContain " "
        edges shouldContain "\n"
        edges shouldContain "\t"
        edges.any { it.any { ch -> ch.code > 127 } } shouldBe true
        edges.any { it.length == Gen.DEFAULT_STRING_MAX } shouldBe true
    }

    @Test
    fun `list edges include empty singleton and near max size`() {
        val edges = Gen.list(Gen.const(1)).edges
        edges shouldContain emptyList()
        edges shouldContain listOf(1)
        edges.any { it.size == Gen.DEFAULT_LIST_MAX } shouldBe true
    }

    @Test
    fun `const oneOf and from expose their values as edges`() {
        Gen.const("x").edges shouldBe listOf("x")
        Gen.oneOf(1, 2, 3).edges shouldContain 1
        Gen.from(listOf("a", "b")).sample(seed = 0L).let { it == "a" || it == "b" } shouldBe true
    }

    @Test
    fun `composed edges stay within the edge cap`() {
        val many = Gen.int.zip(Gen.int) { a, b -> a to b }.zip(Gen.int) { ab, c -> Triple(ab.first, ab.second, c) }
        (many.edges.size <= Gen.EDGE_CAP) shouldBe true
    }
}
