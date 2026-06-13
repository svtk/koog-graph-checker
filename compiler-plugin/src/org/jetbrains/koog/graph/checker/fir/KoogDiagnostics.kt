package org.jetbrains.koog.graph.checker.fir

import org.jetbrains.koog.graph.checker.common.EDGE_TYPE_MISMATCH_MESSAGE
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory4
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error4
import org.jetbrains.kotlin.diagnostics.warning1
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

    // Structural graph checks (spec §2.1–§2.6). The full message is built once in the shared
    // `common` module ([org.jetbrains.koog.graph.checker.common.analyzeGraph]) and passed through as
    // `{0}`, so the compiler and IDE text stay byte-identical. §2.1–§2.3 are errors (Koog throws at
    // runtime); §2.4–§2.6 are warnings (legal Kotlin, silently wrong).
    val KOOG_FINISH_OUTGOING_EDGE: KtDiagnosticFactory1<String> by error1<KtElement, String>()
    val KOOG_FINISH_UNREACHABLE: KtDiagnosticFactory1<String> by error1<KtElement, String>()
    val KOOG_DUPLICATE_NODE_NAME: KtDiagnosticFactory1<String> by error1<KtElement, String>()
    val KOOG_UNREACHABLE_NODE: KtDiagnosticFactory1<String> by warning1<KtElement, String>()
    val KOOG_SHADOWED_EDGE: KtDiagnosticFactory1<String> by warning1<KtElement, String>()
    val KOOG_DEAD_END_NODE: KtDiagnosticFactory1<String> by warning1<KtElement, String>()

    // Edge-condition exhaustiveness (spec §2.7–§2.9). Both arrive fully formatted from `common`; both
    // are warnings (a non-exhaustive fan-out is legal Kotlin that silently stalls only at runtime).
    val KOOG_MISSING_EDGE_CASES: KtDiagnosticFactory1<String> by warning1<KtElement, String>()
    val KOOG_ALL_CONDITIONAL_FANOUT: KtDiagnosticFactory1<String> by warning1<KtElement, String>()

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
        // The structural messages arrive fully formatted from `common`, so the template is just "{0}".
        map.put(KoogDiagnostics.KOOG_FINISH_OUTGOING_EDGE, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_FINISH_UNREACHABLE, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_DUPLICATE_NODE_NAME, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_UNREACHABLE_NODE, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_SHADOWED_EDGE, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_DEAD_END_NODE, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_MISSING_EDGE_CASES, "{0}", CommonRenderers.STRING)
        map.put(KoogDiagnostics.KOOG_ALL_CONDITIONAL_FANOUT, "{0}", CommonRenderers.STRING)
    }
}
