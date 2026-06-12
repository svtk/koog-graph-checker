package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.koog.graph.checker.common.EDGE_TYPE_MISMATCH_MESSAGE
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory4
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error4
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.psi.KtElement

object KoogDiagnostics : KtDiagnosticsContainer() {
    /**
     * Parameters: `{0}` subject and `{1}` origin are prose built by [KoogEdgeTypeMismatchChecker]
     * (the wording is conditional on node names and whether a transform is involved); `{2}` and `{3}`
     * are the value type reaching the target and the target node's input type, kept as raw
     * [ConeKotlinType]s so the renderer fills them with `RENDER_TYPE`. The IDE layer formats the same
     * [EDGE_TYPE_MISMATCH_MESSAGE] template with its own type renderer, so the text stays identical.
     */
    val KOOG_EDGE_TYPE_MISMATCH: KtDiagnosticFactory4<String, String, ConeKotlinType, ConeKotlinType>
        by error4<KtElement, String, String, ConeKotlinType, ConeKotlinType>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = KoogDiagnosticsDefaultMessages
}

object KoogDiagnosticsDefaultMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Koog") { map ->
        map.put(
            KoogDiagnostics.KOOG_EDGE_TYPE_MISMATCH,
            EDGE_TYPE_MISMATCH_MESSAGE,
            CommonRenderers.STRING,
            CommonRenderers.STRING,
            FirDiagnosticRenderers.RENDER_TYPE,
            FirDiagnosticRenderers.RENDER_TYPE,
        )
    }
}
