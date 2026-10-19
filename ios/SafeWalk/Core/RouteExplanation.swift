import Foundation

/// A higher-risk stretch of a route (spec 03 §5): one edge piece at least 25 m long at or above `high`.
struct HigherRiskBlock: Equatable, Sendable {
    let edge: Int
    let street: String?
    let metres: Double
}

/// One plain-language sentence saying why this route is shown, built only from the router's own numbers
/// (template text, no AI). Example: "Avoids 3 higher-risk blocks on S State St, 4% longer."
/// Mirrors RouteExplanation in android/core so both apps say the same thing.
struct RouteExplanation: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        /// A lower-risk route is shown next to the direct one.
        case lowerRisk
        /// A safer preference was asked for, but the direct route is already the lower-risk choice.
        case directIsLowerRisk
        /// Preference Direct with a forecast loaded.
        case direct
        /// No forecast matches the street graph, so only the direct route exists.
        case noForecast
    }

    let kind: Kind
    /// Higher-risk blocks the lower-risk route avoids (`.lowerRisk`), or the direct route passes (`.direct`).
    var blocks = 0
    /// Named street most of those blocks are on; nil when none has more than half of them.
    var street: String? = nil
    /// true when every one of `blocks` is on `street`.
    var allOnStreet = false
    var riskCutPercent = 0
    /// Extra length of the lower-risk route over the direct one, in whole percent.
    var longerPercent = 0

    var text: String {
        switch kind {
        case .lowerRisk:
            return blocks > 0
                ? "Avoids \(blockCount(blocks))\(whereText), \(longerText)."
                : "Takes lower-risk streets for \(riskCutPercent)% lower predicted risk, \(longerText)."
        case .directIsLowerRisk:
            return "The direct route is already the lower-risk choice at this time."
        case .direct:
            return blocks > 0
                ? "Shortest route; passes \(blockCount(blocks))\(whereText)."
                : "Shortest route, with no higher-risk blocks at this time."
        case .noForecast:
            return "Shortest route. No risk forecast is loaded, so streets aren't compared."
        }
    }

    private func blockCount(_ n: Int) -> String { n == 1 ? "1 higher-risk block" : "\(n) higher-risk blocks" }
    private var whereText: String {
        guard let street else { return "" }
        return allOnStreet ? " on \(street)" : ", mostly on \(street)"
    }
    private var longerText: String { longerPercent <= 0 ? "about the same distance" : "\(longerPercent)% longer" }

    static func lowerRisk(direct: RouteResult, lower: RouteResult, riskCutPercent: Int) -> RouteExplanation {
        let lowerEdges = Set(lower.edgeList)
        let avoided = direct.higherRiskBlocks.filter { !lowerEdges.contains($0.edge) }
        let main = mainStreet(avoided)
        let longer = direct.lengthMetres > 0
            ? Int(((lower.lengthMetres - direct.lengthMetres) / direct.lengthMetres * 100).rounded()) : 0
        return RouteExplanation(kind: .lowerRisk, blocks: avoided.count, street: main.street, allOnStreet: main.all,
                                riskCutPercent: riskCutPercent, longerPercent: longer)
    }

    static func direct(_ route: RouteResult) -> RouteExplanation {
        let main = mainStreet(route.higherRiskBlocks)
        return RouteExplanation(kind: .direct, blocks: route.higherRiskBlocks.count, street: main.street, allOnStreet: main.all)
    }

    /// The named street holding more than half of `blocks` (so at most one can), and whether it holds all of them.
    static func mainStreet(_ blocks: [HigherRiskBlock]) -> (street: String?, all: Bool) {
        var counts: [String: Int] = [:]
        for b in blocks { if let s = b.street { counts[s, default: 0] += 1 } }
        guard let top = counts.max(by: { $0.value < $1.value }), top.value * 2 > blocks.count else { return (nil, false) }
        return (top.key, top.value == blocks.count)
    }
}
