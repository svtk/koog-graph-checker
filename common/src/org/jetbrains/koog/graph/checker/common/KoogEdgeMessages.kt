package org.jetbrains.koog.graph.checker.common

import java.text.MessageFormat

/**
 * The single, shared `MessageFormat` template for the edge-type-mismatch diagnostic. Both layers use
 * it verbatim, so the text stays identical, while each layer renders the two type slots with its own
 * renderer: `RENDER_TYPE` on the compiler side (which adaptively disambiguates same-named types from
 * different packages) and `KaTypeRendererForSource` in the IDE.
 *
 * Placeholders:
 * - `{0}` subject — see [edgeMismatchSubject];
 * - `{1}` origin — see [edgeMismatchOrigin];
 * - `{2}` the value type reaching the target node (`IntermediateOutput`);
 * - `{3}` the target node's input type (`OutgoingInput`).
 *
 * `MessageFormat` escaping: apostrophes are doubled (`node''s`) and the literal braces of
 * `transformed { }` are quoted (`'{ }'`). The compiler renderer and `java.text.MessageFormat` (used
 * by [buildEdgeTypeMismatchMessage] for the IDE) apply the same un-escaping.
 *
 * Covers spec §1.1–1.5: names the nodes (§1.1), distinguishes a post-transform value type (§1.4),
 * appends an actionable hint (§1.3), and uses straight apostrophes (§1.5).
 */
const val EDGE_TYPE_MISMATCH_MESSAGE: String =
    "{0}: {1} {2} does not match the target node''s input type {3}. " +
        "Insert `transformed '{ }'` to convert {2} to {3}, or change the target node''s input type."

/** §1.1 — names the edge's source and target nodes, or a type-only subject when a name is unavailable. */
fun edgeMismatchSubject(sourceNode: String?, targetNode: String?): String =
    if (sourceNode != null && targetNode != null) {
        "Invalid edge from node '$sourceNode' to node '$targetNode'"
    } else {
        "Invalid edge"
    }

/** §1.4 — phrases the offending value as a post-transform type or the bare source-node output. */
fun edgeMismatchOrigin(afterTransform: Boolean): String =
    if (afterTransform) "the value type after the transform" else "the source node's output type"

/**
 * Formats the full message from already-rendered type names, for the IDE layer (which renders the
 * types eagerly with `KaTypeRendererForSource`). The compiler layer instead passes the subject,
 * origin, and raw types to its diagnostic factory and lets `RENDER_TYPE` fill `{2}`/`{3}` — both
 * paths go through the same [EDGE_TYPE_MISMATCH_MESSAGE] template, so the text matches.
 */
fun buildEdgeTypeMismatchMessage(
    sourceNode: String?,
    targetNode: String?,
    fromType: String,
    toType: String,
    afterTransform: Boolean,
): String = MessageFormat.format(
    EDGE_TYPE_MISMATCH_MESSAGE,
    edgeMismatchSubject(sourceNode, targetNode),
    edgeMismatchOrigin(afterTransform),
    fromType,
    toType,
)
