package dev.surefold.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val synchronizedAnnotation = ClassId(FqName("kotlin.jvm"), Name.identifier("Synchronized"))

/**
 * `@Synchronized` on a function or accessor: a lock on the object by hand, named on the annotation (spec 0312). A call
 * checker cannot see it, since nothing is called.
 */
internal class SynchronizedChecker : FirFunctionChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFunction) {
        val annotation =
            declaration.annotations.firstOrNull {
                it.toAnnotationClassIdSafe(context.session) == synchronizedAnnotation
            } ?: return
        reporter.reportOn(annotation.source, SurefoldDiagnostics.SUREFOLD_LOCK_BY_HAND, "@Synchronized", context)
    }
}
