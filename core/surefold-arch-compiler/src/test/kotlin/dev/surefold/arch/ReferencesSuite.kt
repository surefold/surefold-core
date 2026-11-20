package dev.surefold.arch

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue

/** Spec 0315's second entry: every kind of reference a rule can be broken by, each one resolved, not read as text. */
object ReferencesSuite : Suite() {
    init {
        mapOf(
                "a property's type" to "val box: b.Box? = null",
                "a parameter's type" to "fun take(box: b.Box?) = box",
                "a return type" to "fun give(): b.Box? = null",
                "a supertype" to "class Mine : b.Base()",
                "an interface implemented" to "class Mine : b.Shape",
                "a type argument" to "val boxes: List<b.Box> = emptyList()",
                "a cast" to "fun cast(x: Any) = x as b.Box",
                "a type check" to "fun check(x: Any) = x is b.Box",
                "an annotation" to "@b.Marker class Marked",
                "a callable reference" to "val f = b::helper",
                "a constructor reference" to "val make = b::Box",
                "an object" to "val one = b.Single",
                "a type alias from elsewhere that expands to b" to "val box: c.Crate? = null",
            )
            .forEach { (kind, code) ->
                test("$kind breaks a rule") {
                    val said = compile(mapOf(B, C, "a/Uses.kt" to "package a\n\n$code\n"), listOf(FORBID))

                    withClue(said.output) {
                        said.succeeded shouldBe false
                        said.output shouldContain "Uses.kt:3: surefold: [a-not-b]"
                    }
                }
            }

        mapOf(
                "an import" to "import b.Box",
                "an aliased import" to "import b.Box as Crate",
                "a star import" to "import b.*",
                "an import of a function" to "import b.helper",
            )
            .forEach { (kind, import) ->
                test("$kind breaks a rule even when nothing uses it") {
                    val said = compile(mapOf(B, "a/Uses.kt" to "package a\n\n$import\n"), listOf(FORBID))

                    withClue(said.output) { said.output shouldContain "Uses.kt:3: surefold: [a-not-b]" }
                }
            }

        test("a type alias in b is named itself, as well as for what it expands to") {
            val said = compile(mapOf(B, "a/Uses.kt" to "package a\n\nval n: b.Count = 1\n"), listOf(FORBID))

            said.output shouldContain "surefold: [a-not-b] a (Uses.kt) uses b.Count;"
        }

        test("a call through an object is one error, not one for the object and one for the call") {
            val said = compile(mapOf(B, "a/Uses.kt" to "package a\n\nval n = b.Single.size\n"), listOf(FORBID))

            said.messages.count { "surefold:" in it } shouldBe 1
        }

        test("what the rule does not name compiles, however it is referred to") {
            val code = "import c.Crate\n\nval box: Crate? = null\nval list: List<String> = emptyList()\n"
            val said = compile(mapOf(B, C, "a/Uses.kt" to "package a\n\n$code"), listOf("a-not-z|forbid|a..|z..|no"))

            withClue(said.output) { said.succeeded shouldBe true }
        }
    }
}

private const val FORBID = "a-not-b|forbid|a..|b..|a stays out of b"

private val B =
    "b/B.kt" to
        """
        package b

        fun helper(): Int = 1

        class Box(val size: Int)

        open class Base

        interface Shape

        annotation class Marker

        object Single { val size: Int = 1 }

        typealias Count = Int
        """
            .trimIndent()

private val C = "c/C.kt" to "package c\n\ntypealias Crate = b.Box\n"
