package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.psi.KtElement

object KoogDiagnostics : KtDiagnosticsContainer() {
    val KOOG_EDGE_TYPE_MISMATCH: KtDiagnosticFactory2<ConeKotlinType, ConeKotlinType> by error2<KtElement, ConeKotlinType, ConeKotlinType>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = KoogDiagnosticsDefaultMessages
}

object KoogDiagnosticsDefaultMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Koog") { map ->
        map.put(
            KoogDiagnostics.KOOG_EDGE_TYPE_MISMATCH,
            "Invalid edge: the edge’s output type {0} does not match the target node’s input type {1}.",
            FirDiagnosticRenderers.RENDER_TYPE,
            FirDiagnosticRenderers.RENDER_TYPE,
        )
    }
}
