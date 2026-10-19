package app.safewalk.core

import kotlin.math.roundToInt

/** A higher-risk stretch of a route (03-routing.md section 5): one edge piece at least 25 m long at or above `high`. */
data class HigherRiskBlock(val edge: Int, val street: String?, val metres: Double)

/**
 * One plain-language sentence saying why this route is shown, built only from the router's own numbers
 * (template text, no AI). Example: "Avoids 3 higher-risk blocks on S State St, 4% longer."
 */
data class RouteExplanation(
    val kind: Kind,
    /** Higher-risk blocks the lower-risk route avoids (LOWER_RISK), or the direct route passes (DIRECT). */
    val blocks: Int = 0,
    /** Named street most of those blocks are on; null when none has more than half of them. */
    val street: String? = null,
    /** true when every one of [blocks] is on [street]. */
    val allOnStreet: Boolean = false,
    val riskCutPct: Int = 0,
    /** Extra length of the lower-risk route over the direct one, in whole percent. */
    val longerPct: Int = 0,
) {
    enum class Kind {
        /** A lower-risk route is shown next to the direct one. */
        LOWER_RISK,
        /** A safer level was asked for, but the direct route is already the lower-risk choice. */
        DIRECT_IS_LOWER_RISK,
        /** Level Direct with a forecast loaded. */
        DIRECT,
        /** No forecast matches the street graph, so only the direct route exists. */
        NO_FORECAST,
    }

    val text: String get() = when (kind) {
        Kind.LOWER_RISK ->
            if (blocks > 0) "Avoids ${blockCount(blocks)}${where()}, ${longer()}."
            else "Takes lower-risk streets for $riskCutPct% lower predicted risk, ${longer()}."
        Kind.DIRECT_IS_LOWER_RISK -> "The direct route is already the lower-risk choice at this time."
        Kind.DIRECT ->
            if (blocks > 0) "Shortest route; passes ${blockCount(blocks)}${where()}."
            else "Shortest route, with no higher-risk blocks at this time."
        Kind.NO_FORECAST -> "Shortest route. No risk forecast is loaded, so streets aren't compared."
    }

    private fun blockCount(n: Int) = if (n == 1) "1 higher-risk block" else "$n higher-risk blocks"
    private fun where() = when {
        street == null -> ""
        allOnStreet -> " on $street"
        else -> ", mostly on $street"
    }
    private fun longer() = if (longerPct <= 0) "about the same distance" else "$longerPct% longer"

    companion object {
        fun lowerRisk(direct: Route, safer: Route, directBlocks: List<HigherRiskBlock>, riskCutPct: Int): RouteExplanation {
            val saferEdges = safer.pieces.mapTo(HashSet()) { it.edge }
            val avoided = directBlocks.filter { it.edge !in saferEdges }
            val (street, all) = mainStreet(avoided)
            val longer = if (direct.lengthM > 0) ((safer.lengthM - direct.lengthM) / direct.lengthM * 100).roundToInt() else 0
            return RouteExplanation(Kind.LOWER_RISK, avoided.size, street, all, riskCutPct, longer)
        }

        fun direct(blocks: List<HigherRiskBlock>): RouteExplanation {
            val (street, all) = mainStreet(blocks)
            return RouteExplanation(Kind.DIRECT, blocks.size, street, all)
        }

        /** The named street holding more than half of [blocks] (so at most one can), and whether it holds all of them. */
        fun mainStreet(blocks: List<HigherRiskBlock>): Pair<String?, Boolean> {
            val top = blocks.filter { it.street != null }.groupBy { it.street!! }.entries
                .maxByOrNull { it.value.size } ?: return null to false
            if (top.value.size * 2 <= blocks.size) return null to false
            return top.key to (top.value.size == blocks.size)
        }
    }
}
