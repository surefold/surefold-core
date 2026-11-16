package dev.surefold.app.compiler

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.jetbrains.kotlin.config.KotlinCompilerVersion

/**
 * The version guard, tested where it can be: the compiler on this module's own test classpath is the one it was built
 * against, so the answer here is the answer a consumer on the same Kotlin gets.
 */
object VersionsSuite : Suite() {
    init {
        test("the compiler this was built against is the one it recognises") { builtForThisCompiler() shouldBe true }

        test("the sentence names both versions, because either could be the one to change") {
            withClue("a reader told only 'wrong version' has to go and find out which two") {
                versionMismatch() shouldContain KotlinCompilerVersion.VERSION
            }
        }

        test("the jar says what it was built for, or the build that made it was wrong") {
            versionMismatch() shouldContain "built for Kotlin"
        }
    }
}
