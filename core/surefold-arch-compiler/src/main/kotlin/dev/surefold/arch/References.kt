package dev.surefold.arch

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * Every reference in a file, by the symbol it resolved to, checked against every rule (spec 0315). One walk of the file
 * rather than a checker per kind of reference, so a file's whole verdict is known in one place.
 */
internal class ReferenceChecker(private val rules: List<Rule>) : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val walk = Walk(declaration.packageDirective.packageFqName.asString(), declaration.name)
        declaration.accept(walk)
        walk.found.forEach { reference ->
            rules
                .filter { it.brokenBy(reference.code, reference.target) }
                .forEach { rule ->
                    reporter.reportOn(
                        reference.source,
                        ArchDiagnostics.SUREFOLD_ARCH_FORBIDDEN,
                        "[${rule.id}] ${reference.codeName} uses ${reference.targetName}; ${rule.reason}. " +
                            "Fix the reference, or baseline '${declaration.name}' for ${rule.id}",
                        context,
                    )
                }
        }
    }
}

/** One reference: where it is written, the code it is in, and what it refers to. */
private class Reference(
    val source: KtSourceElement,
    val code: Place,
    val codeName: String,
    val target: Place,
    val targetName: String,
)

private class Walk(private val packageName: String, private val fileName: String) : FirVisitorVoid() {
    val found = mutableListOf<Reference>()
    private var topLevelClass: String? = null

    override fun visitElement(element: FirElement) {
        if (element is FirRegularClass && topLevelClass == null && element.symbol.classId.outerClassId == null) {
            topLevelClass = element.symbol.classId.asSingleFqName().asString()
            element.acceptChildren(this)
            topLevelClass = null
            return
        }
        if (element is FirQualifiedAccessExpression) access(element)
        element.acceptChildren(this)
    }

    private fun access(access: FirQualifiedAccessExpression) {
        val symbol = access.calleeReference.toResolvedCallableSymbol() ?: return
        if (symbol is FirLocalPropertySymbol || symbol is FirValueParameterSymbol) return
        val id = symbol.callableId ?: return
        val owner = id.classId
        val name =
            if (symbol is FirConstructorSymbol && owner != null) owner.fqName()
            else id.asFqNameForDebugInfo().asString()
        add(access.source, Place(id.packageName.asString(), owner?.outermost()), name)
    }

    private fun add(source: KtSourceElement?, target: Place, targetName: String) {
        // A fake source is code the compiler wrote, such as a loop's iterator() call: the real one is reported.
        if (source == null || source.kind is KtFakeSourceElementKind) return
        val code = Place(packageName, topLevelClass)
        found += Reference(source, code, topLevelClass ?: "$packageName ($fileName)", target, targetName)
    }
}

private fun ClassId.outermost(): String = outermostClassId.asSingleFqName().asString()

private fun ClassId.fqName(): String = asSingleFqName().asString()
