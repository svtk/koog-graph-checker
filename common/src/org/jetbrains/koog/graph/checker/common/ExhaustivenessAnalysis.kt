package org.jetbrains.koog.graph.checker.common

/**
 * The layer-agnostic core of the edge-condition exhaustiveness check (spec §2.7–§2.9).
 *
 * §2.9 observes that routing a node's output across conditional outgoing edges is the same problem
 * the Kotlin compiler solves for `when` over that output's type: every reachable value must match
 * some edge, or Koog's `resolveEdge` returns `null` and the run stalls. This file is the transplanted
 * computation — the *domain classifier* and the *pure-discriminator extractor* live in each layer
 * (they need type resolution), while the coverage arithmetic and the residual ("which cases are
 * unhandled") live here, shared between the FIR checker and the IDE inspection exactly like
 * [analyzeGraph].
 *
 * The one adaptation from the compiler is the **flipped soundness bias** (§2.9): the compiler must
 * never falsely claim a `when` *exhaustive*, so an opaque branch contributes no coverage; the linter
 * must never falsely claim a *gap*, so an opaque edge instead **suppresses** the warning (it might be
 * the de-facto catch-all). Only a *pure discriminator* — a clean enum/value/type test — counts as
 * provable coverage; everything else ([EdgeCoverage.Opaque]) bails out. This is what guarantees the
 * enumerable-domain warnings (§2.8 and friends) never false-alarm.
 */

/**
 * A statically-distinguishable case in a fan-out subject's domain: an enum entry, a sealed subtype, a
 * boolean literal, or `null`.
 *
 * [id] is the canonical key the engine matches against [EdgeCoverage.Covers] — both sides are computed
 * by the same layer, so they only need to agree with each other (an enum entry name, a subtype's
 * fully-qualified id, `"true"`/`"false"`/`"null"`). [label] is the human-readable form shown in the
 * diagnostic (`ESCALATE`, `is Cat`, `false`, `null`).
 */
class DomainCase(val id: String, val label: String)

/** The statically-classified domain of a fan-out source node's emitted value (the routing subject). */
sealed class EdgeDomain {
    /**
     * A statically enumerable domain: a finite set of [cases]. [description] is how the domain is
     * named in the diagnostic ("enum Route", "Boolean", "Message.Response"); it is built by the layer
     * so the wording can use the real (short) type name.
     */
    class Enumerable(val cases: List<DomainCase>, val description: String) : EdgeDomain()

    /** Not statically enumerable (Int, String, an arbitrary class …): only a catch-all can exhaust it. */
    object NonEnumerable : EdgeDomain()
}

/** What a single outgoing edge provably contributes to coverage of its source node's domain. */
sealed class EdgeCoverage {
    /** Bare `forwardTo` (optionally `transformed`), or a literal `{ true }` guard — matches anything. */
    object CatchAll : EdgeCoverage()

    /** A pure discriminator proving coverage of exactly these [caseIds] (`it == Route.X`, `onIsInstance<Cat>`). */
    class Covers(val caseIds: List<String>) : EdgeCoverage()

    /** Not statically understood (a guarded Koog operator, a complex lambda, a pre-condition transform). */
    object Opaque : EdgeCoverage()
}

/**
 * One source node's fan-out: its outgoing edges' [edgeCoverages] in declaration order, the classified
 * [domain] of the value it routes on, and where to anchor a finding ([sourceAnchor]).
 */
class NodeFanOut<A>(
    val sourceName: String,
    val sourceAnchor: A,
    val domain: EdgeDomain,
    val edgeCoverages: List<EdgeCoverage>,
)

/**
 * §2.7 is **off by default**. The all-conditional-fan-out nudge over a non-enumerable domain is the
 * only path in this engine that can produce false positives — it would fire on idiomatic, correct
 * Koog graphs whose conditions happen to be total (e.g. the canonical pairing of
 * `onToolCalls { true }` with `onAssistantMessage { true }`, which together cover every LLM outcome
 * but read as "all conditional, no fallback"). The spec ranks it last and opt-in for exactly this
 * reason, so the engine keeps the code path but does not emit it. Flip this to surface the nudge.
 */
private const val ENABLE_ALL_CONDITIONAL_NUDGE = false

/**
 * Runs the exhaustiveness check (spec §2.7–§2.9) over the per-source-node [fanOuts] a layer built from
 * its own AST, and returns the findings. Mirrors [analyzeGraph]'s shape: both layers call this with a
 * model assembled from their own type resolution.
 */
fun <A> analyzeExhaustiveness(fanOuts: List<NodeFanOut<A>>): List<GraphFinding<A>> =
    fanOuts.mapNotNull { analyzeFanOut(it) }

private fun <A> analyzeFanOut(fanOut: NodeFanOut<A>): GraphFinding<A>? {
    val coverages = fanOut.edgeCoverages
    if (coverages.isEmpty()) return null

    // A catch-all anywhere ⇒ the fan-out is total ⇒ nothing to report (an unreachable catch-all is
    // §2.5's concern, not this check's).
    if (coverages.any { it is EdgeCoverage.CatchAll }) return null

    return when (val domain = fanOut.domain) {
        is EdgeDomain.NonEnumerable -> {
            // §2.7 — can't prove a gap over a non-enumerable domain, so this is only a weak nudge.
            if (!ENABLE_ALL_CONDITIONAL_NUDGE || coverages.size < 2) {
                null
            } else {
                GraphFinding(
                    GraphFindingKind.ALL_CONDITIONAL_FANOUT,
                    GraphFindingSeverity.WARNING,
                    fanOut.sourceAnchor,
                    allConditionalFanoutMessage(fanOut.sourceName),
                )
            }
        }

        is EdgeDomain.Enumerable -> {
            // §2.8/§2.9 — an opaque edge might cover anything, so we cannot prove a gap: suppress.
            if (coverages.any { it is EdgeCoverage.Opaque }) return null

            val covered = coverages.filterIsInstance<EdgeCoverage.Covers>().flatMapTo(mutableSetOf()) { it.caseIds }
            val missing = domain.cases.filter { it.id !in covered }
            if (missing.isEmpty()) {
                null
            } else {
                GraphFinding(
                    GraphFindingKind.MISSING_EDGE_CASES,
                    GraphFindingSeverity.WARNING,
                    fanOut.sourceAnchor,
                    missingEdgeCasesMessage(fanOut.sourceName, domain.description, missing.map { it.label }),
                )
            }
        }
    }
}
