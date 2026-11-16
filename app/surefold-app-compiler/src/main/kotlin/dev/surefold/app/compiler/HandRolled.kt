package dev.surefold.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Calls that hand-roll something surefold ships, each named where it is written (specs 0132 and 0312). */
internal class HandRolledChecker(private val test: Boolean) : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val called = symbol.callableId ?: return
        val said = said(symbol, called)
        handRolled(symbol, called, expression).forEach { reporter.reportOn(expression.source, it, said, context) }
        if (!test && called == readingString) {
            val path = (expression.argumentList.arguments.firstOrNull() as? FirLiteralExpression)?.value as? String
            if (path != null && path.substringAfterLast('.').lowercase() in secretNames) {
                reporter.reportOn(expression.source, SurefoldDiagnostics.SUREFOLD_SECRET_AS_STRING, path, context)
            }
        }
    }

    /**
     * Each warning [called] earns, named by what it called. A thread, latch, lock or queue is named in main and test
     * code alike: a test's is the one an agent copies next (spec 0312).
     */
    private fun handRolled(
        symbol: FirCallableSymbol<*>,
        called: CallableId,
        expression: FirFunctionCall,
    ): List<KtDiagnosticFactory1<String>> =
        listOfNotNull(
            SurefoldDiagnostics.SUREFOLD_SLEEP_IN_TEST.takeIf { test && called in sleeps },
            SurefoldDiagnostics.SUREFOLD_SCHEDULER_BY_HAND.takeIf { !test && schedules(symbol, called) },
            SurefoldDiagnostics.SUREFOLD_THREAD_BY_HAND.takeIf { startsThreads(symbol, called) },
            SurefoldDiagnostics.SUREFOLD_LATCH_BY_HAND.takeIf { latches(symbol, called, expression) },
            SurefoldDiagnostics.SUREFOLD_LOCK_BY_HAND.takeIf { locks(symbol, called) },
            SurefoldDiagnostics.SUREFOLD_QUEUE_BY_HAND.takeIf { queues(symbol, called) },
        )
}

private val sleeps =
    setOf(
        CallableId(ClassId(FqName("java.lang"), Name.identifier("Thread")), Name.identifier("sleep")),
        CallableId(ClassId(FqName("java.util.concurrent"), Name.identifier("TimeUnit")), Name.identifier("sleep")),
    )

private val readingString =
    CallableId(
        ClassId(FqName("dev.surefold.app.typesafe"), Name.identifier("Reading")),
        Name.identifier("string"),
    )

/**
 * `Secrets.default`'s names: the checker cannot depend on surefold-app-typesafe, so SecretNamesSuite keeps them equal.
 */
private val secretNames: Set<String> =
    setOf(
        "password",
        "passphrase",
        "secret",
        "secretkey",
        "token",
        "apitoken",
        "accesstoken",
        "key",
        "apikey",
        "accesskey",
        "privatekey",
        "credential",
        "credentials",
    )

private fun schedules(symbol: FirCallableSymbol<*>, called: CallableId): Boolean =
    called in schedulerFactories || (symbol is FirConstructorSymbol && called.classId in schedulers)

private val executors = ClassId(FqName("java.util.concurrent"), Name.identifier("Executors"))

private val schedulerFactories =
    setOf("newScheduledThreadPool", "newSingleThreadScheduledExecutor")
        .map { CallableId(executors, Name.identifier(it)) }
        .toSet()

private val schedulers =
    setOf(
        ClassId(FqName("java.util.concurrent"), Name.identifier("ScheduledThreadPoolExecutor")),
        ClassId(FqName("java.util"), Name.identifier("Timer")),
    )

private val thread = ClassId(FqName("java.lang"), Name.identifier("Thread"))

private val threadStarters =
    setOf("ofVirtual", "ofPlatform", "startVirtualThread").map { CallableId(thread, Name.identifier(it)) }.toSet() +
        CallableId(FqName("kotlin.concurrent"), Name.identifier("thread")) +
        setOf(
                "newFixedThreadPool",
                "newCachedThreadPool",
                "newSingleThreadExecutor",
                "newWorkStealingPool",
                "newVirtualThreadPerTaskExecutor",
                "newThreadPerTaskExecutor",
            )
            .map { CallableId(executors, Name.identifier(it)) }

private val pools =
    setOf(
        thread,
        ClassId(FqName("java.util.concurrent"), Name.identifier("ThreadPoolExecutor")),
        ClassId(FqName("java.util.concurrent"), Name.identifier("ForkJoinPool")),
    )

/** A thread or a pool of them, started or built here; the scheduled pools are [schedules]'s, not this. */
private fun startsThreads(symbol: FirCallableSymbol<*>, called: CallableId): Boolean =
    called in threadStarters || (symbol is FirConstructorSymbol && called.classId in pools)

private val latchTypes =
    setOf("CountDownLatch", "CyclicBarrier", "Phaser")
        .map { ClassId(FqName("java.util.concurrent"), Name.identifier(it)) }
        .toSet()

private val completableFuture = ClassId(FqName("java.util.concurrent"), Name.identifier("CompletableFuture"))

/**
 * A latch, a barrier, or a `CompletableFuture()` made empty to be completed later, which is a latch with a value. A
 * future from `supplyAsync` or `completedFuture` is a value handed back, and is left alone.
 */
private fun latches(symbol: FirCallableSymbol<*>, called: CallableId, expression: FirFunctionCall): Boolean =
    symbol is FirConstructorSymbol &&
        (called.classId in latchTypes ||
            (called.classId == completableFuture && expression.argumentList.arguments.isEmpty()))

private val synchronizedCall = CallableId(FqName("kotlin"), Name.identifier("synchronized"))

private val lockTypes =
    setOf(
        ClassId(FqName("java.util.concurrent.locks"), Name.identifier("ReentrantLock")),
        ClassId(FqName("java.util.concurrent.locks"), Name.identifier("ReentrantReadWriteLock")),
        ClassId(FqName("java.util.concurrent.locks"), Name.identifier("StampedLock")),
    )

/** Kotlin's `synchronized(lock) { }`, or a lock built here. `@Synchronized` is [SynchronizedChecker]'s. */
private fun locks(symbol: FirCallableSymbol<*>, called: CallableId): Boolean =
    called == synchronizedCall || (symbol is FirConstructorSymbol && called.classId in lockTypes)

private val queueTypes =
    setOf("LinkedBlockingQueue", "ArrayBlockingQueue", "SynchronousQueue", "LinkedTransferQueue")
        .map { ClassId(FqName("java.util.concurrent"), Name.identifier(it)) }
        .toSet()

/** A blocking queue built here, to hand values from one thread to another. */
private fun queues(symbol: FirCallableSymbol<*>, called: CallableId): Boolean =
    symbol is FirConstructorSymbol && called.classId in queueTypes

/**
 * `Thread.sleep`, `Timer(..)` for a constructor, whose name is the class's for Java and `<init>` for Kotlin, or `thread
 * { }` for a top-level function.
 */
private fun said(symbol: FirCallableSymbol<*>, called: CallableId): String {
    val owner = called.classId?.shortClassName
    return when {
        symbol is FirConstructorSymbol -> "$owner(..)"
        owner == null -> "${called.callableName} { }"
        else -> "$owner.${called.callableName}"
    }
}
