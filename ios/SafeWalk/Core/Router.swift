import CoreLocation
import Foundation

/// Route preference shown in the UI (spec 03 §3).
enum RoutePreference: String, CaseIterable, Identifiable, Sendable {
    case direct, balanced, lowestRisk

    var id: String { rawValue }
    var lambda: Double {
        switch self {
        case .direct: return 0
        case .balanced: return 0.5
        case .lowestRisk: return 1.0
        }
    }
    var label: String {
        switch self {
        case .direct: return L10n.prefDirect
        case .balanced: return L10n.prefBalanced
        case .lowestRisk: return L10n.prefLowestRisk
        }
    }
}

/// Part of one edge walked in a route, from `fromFrac` to `toFrac` along the edge (0 = u, 1 = v).
struct RoutePiece: Equatable, Sendable {
    let edge: Int
    let fromFrac: Double
    let toFrac: Double
}

struct TurnStep: Equatable, Sendable {
    enum Maneuver: Equatable, Sendable { case start, straight, left, right, uTurn }
    let maneuver: Maneuver
    let street: String
    let lengthMetres: Double
    /// Where this step begins.
    let coordinate: CLLocationCoordinate2D

    static func == (a: TurnStep, b: TurnStep) -> Bool {
        a.maneuver == b.maneuver && a.street == b.street && a.lengthMetres == b.lengthMetres
    }
}

/// A route line piece with its colour band (0 normal, 1 elevated, 2 high or above).
struct RouteSegment: Sendable {
    let coordinates: [CLLocationCoordinate2D]
    let band: Int
}

struct RouteResult: Sendable {
    let pieces: [RoutePiece]
    let lengthMetres: Double
    let cost: Double
    /// Σ piece length × relative risk. Only used to compare two routes.
    let exposure: Double
    let higherRiskStretches: Int
    let polyline: [CLLocationCoordinate2D]
    let segments: [RouteSegment]
    let steps: [TurnStep]

    /// Walking time at 1.3 m/s, rounded to the minute.
    var minutes: Int { Int((lengthMetres / 1.3 / 60).rounded()) }
    var edgeList: [Int] { pieces.map(\.edge) }
}

struct RouteComparison: Sendable {
    let direct: RouteResult
    /// The route for the chosen preference, when it differs enough from Direct to be worth showing.
    let lowerRisk: RouteResult?
    let preference: RoutePreference
    let slot: TimeSlot
    let longWalk: Bool

    /// Whole-percent risk reduction of the lower-risk route vs Direct.
    var riskCutPercent: Int {
        guard let s = lowerRisk, direct.exposure > 0 else { return 0 }
        return Int(((1 - s.exposure / direct.exposure) * 100).rounded())
    }
    var extraMetres: Double { (lowerRisk?.lengthMetres ?? direct.lengthMetres) - direct.lengthMetres }
    var extraMinutes: Int { max(0, (lowerRisk?.minutes ?? direct.minutes) - direct.minutes) }
    var extraPercent: Double { direct.lengthMetres > 0 ? extraMetres / direct.lengthMetres * 100 : 0 }
    /// The route the user walks by default: the lower-risk one if shown, else Direct.
    var recommended: RouteResult { lowerRisk ?? direct }
}

enum RouteFailure: Error, Equatable {
    case snap(SnapFailure)
    case alreadyThere
    case noRoute
}

/// A* over the street graph with virtual start/target nodes on the snapped edges.
/// Mirrors `route()` in reference/safewalk_format.py so both apps return the same answers.
struct Router: Sendable {
    let data: CityData
    var graph: StreetGraph { data.graph }

    static let minShowReduction = 0.10
    static let alreadyThereMetres = 20.0
    static let longWalkMetres = 15_000.0
    static let stretchMinMetres = 25.0

    func snap(_ p: CLLocationCoordinate2D) -> Result<SnapPoint, SnapFailure> {
        data.snapIndex.snap(p, in: graph)
    }

    func compare(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D, slot: TimeSlot,
                 preference: RoutePreference) -> Result<RouteComparison, RouteFailure> {
        let sa: SnapPoint, sb: SnapPoint
        switch snap(a) { case .success(let s): sa = s; case .failure(let f): return .failure(.snap(f)) }
        switch snap(b) { case .success(let s): sb = s; case .failure(let f): return .failure(.snap(f)) }
        let pa = Geometry.point(along: graph.points(edge: sa.edge), frac: sa.frac)
        let pb = Geometry.point(along: graph.points(edge: sb.edge), frac: sb.frac)
        if Geometry.distance(pa, pb) < Self.alreadyThereMetres { return .failure(.alreadyThere) }

        guard let direct = route(from: sa, to: sb, slot: slot.index, lambda: 0) else { return .failure(.noRoute) }
        var lower: RouteResult?
        // Without a matching forecast, route Direct only (spec 03 §7).
        if preference != .direct, data.risk != nil,
           let r = route(from: sa, to: sb, slot: slot.index, lambda: preference.lambda),
           r.edgeList != direct.edgeList,
           direct.exposure > 0, 1 - r.exposure / direct.exposure >= Self.minShowReduction {
            lower = r
        }
        return .success(RouteComparison(direct: direct, lowerRisk: lower, preference: preference, slot: slot,
                                        longWalk: direct.lengthMetres > Self.longWalkMetres))
    }

    func route(from a: SnapPoint, to b: SnapPoint, slot: Int, lambda: Double) -> RouteResult? {
        let g = graph, n = g.nodeCount
        let risk = data.risk
        let lam = risk == nil ? 0 : lambda
        @inline(__always) func rel(_ e: Int) -> Double { risk?.relative(edge: e, slot: slot) ?? 0 }
        @inline(__always) func w(_ e: Int) -> Double { g.length[e] * (1 + lam * rel(e)) }

        let S = n, T = n + 1
        let ea = a.edge, fa = a.frac, eb = b.edge, fb = b.frac
        let ubT = Int(g.edgeU[eb]), vbT = Int(g.edgeV[eb])

        let target = Geometry.point(along: g.points(edge: eb), frac: fb)
        let tl = target.latitude, tn = target.longitude
        let kx = SafeWalkFormat.metresPerDegLon(atLat: tl), ky = SafeWalkFormat.metresPerDegLat
        @inline(__always) func h(_ x: Int) -> Double {
            x >= n ? 0 : 0.999 * hypot((g.nodeLon[x] - tn) * kx, (g.nodeLat[x] - tl) * ky)
        }

        var dist = [Double](repeating: .infinity, count: n + 2)
        var prevNode = [Int32](repeating: -1, count: n + 2)
        var prevPiece = [RoutePiece?](repeating: nil, count: n + 2)
        var done = [Bool](repeating: false, count: n + 2)
        var heap = AStarHeap()

        func relax(_ x: Int, _ d: Double, _ y: Int, _ c: Double, _ piece: RoutePiece) {
            let nd = d + c
            if nd < dist[y] - 1e-9 {
                dist[y] = nd; prevNode[y] = Int32(x); prevPiece[y] = piece
                heap.push(f: nd + h(y), d: nd, node: Int32(y))
            }
        }

        dist[S] = 0
        heap.push(f: h(S), d: 0, node: Int32(S))
        while let top = heap.pop() {
            let x = Int(top.node), d = top.d
            if done[x] { continue }
            done[x] = true
            if x == T { break }
            if x == S {
                relax(x, d, Int(g.edgeU[ea]), w(ea) * fa, RoutePiece(edge: ea, fromFrac: fa, toFrac: 0))
                relax(x, d, Int(g.edgeV[ea]), w(ea) * (1 - fa), RoutePiece(edge: ea, fromFrac: fa, toFrac: 1))
                if ea == eb { relax(x, d, T, w(ea) * abs(fb - fa), RoutePiece(edge: ea, fromFrac: fa, toFrac: fb)) }
                continue
            }
            if x == ubT { relax(x, d, T, w(eb) * fb, RoutePiece(edge: eb, fromFrac: 0, toFrac: fb)) }
            if x == vbT { relax(x, d, T, w(eb) * (1 - fb), RoutePiece(edge: eb, fromFrac: 1, toFrac: fb)) }
            for k in Int(g.adjOffsets[x])..<Int(g.adjOffsets[x + 1]) {
                let e = Int(g.adjEdges[k])
                let forward = Int(g.edgeU[e]) == x
                relax(x, d, g.other(e, from: x), w(e),
                      RoutePiece(edge: e, fromFrac: forward ? 0 : 1, toFrac: forward ? 1 : 0))
            }
        }
        guard dist[T].isFinite else { return nil }

        var pieces: [RoutePiece] = []
        var x = T
        while x != S {
            guard let p = prevPiece[x] else { return nil }
            pieces.append(p)
            x = Int(prevNode[x])
        }
        pieces.reverse()
        return summarize(pieces, cost: dist[T], slot: slot)
    }

    private func summarize(_ pieces: [RoutePiece], cost: Double, slot: Int) -> RouteResult {
        let g = graph
        var length = 0.0, exposure = 0.0, stretches = 0
        var polyline: [CLLocationCoordinate2D] = []
        var segments: [RouteSegment] = []
        var stepsRaw: [(name: String, length: Double, pts: [CLLocationCoordinate2D])] = []

        for p in pieces {
            let pl = g.length[p.edge] * abs(p.toFrac - p.fromFrac)
            let r = data.risk?.relative(edge: p.edge, slot: slot) ?? 0
            length += pl
            exposure += pl * r
            let band = data.risk?.band(edge: p.edge, slot: slot) ?? 0
            if band == 2 && pl >= Self.stretchMinMetres { stretches += 1 }

            let sub = Geometry.subPolyline(g.points(edge: p.edge), from: p.fromFrac, to: p.toFrac)
            for c in sub where polyline.last.map({ $0.latitude != c.latitude || $0.longitude != c.longitude }) ?? true {
                polyline.append(c)
            }
            if let last = segments.last, last.band == band {
                segments[segments.count - 1] = RouteSegment(coordinates: last.coordinates + sub.dropFirst(), band: band)
            } else {
                segments.append(RouteSegment(coordinates: sub, band: band))
            }

            let name = g.streetName(edge: p.edge) ?? L10n.unnamedPath
            if let last = stepsRaw.last, last.name == name {
                stepsRaw[stepsRaw.count - 1] = (name, last.length + pl, last.pts + sub.dropFirst())
            } else {
                stepsRaw.append((name, pl, sub))
            }
        }

        // Drop zero-length pieces' steps (a start exactly on a junction) and turn the rest into maneuvers.
        let nonTrivial = stepsRaw.filter { $0.length > 0.5 }
        let kept = nonTrivial.isEmpty ? stepsRaw : nonTrivial
        var steps: [TurnStep] = []
        for (i, s) in kept.enumerated() {
            var m: TurnStep.Maneuver = .start
            if i > 0, let inB = Self.lastBearing(kept[i - 1].pts), let outB = Self.firstBearing(s.pts) {
                var delta = outB - inB
                while delta > 180 { delta -= 360 }
                while delta <= -180 { delta += 360 }
                let a = abs(delta)
                m = a < 30 ? .straight : a > 150 ? .uTurn : (delta < 0 ? .left : .right)
            }
            steps.append(TurnStep(maneuver: m, street: s.name, lengthMetres: s.length,
                                  coordinate: s.pts.first ?? polyline.first!))
        }

        return RouteResult(pieces: pieces, lengthMetres: length, cost: cost, exposure: exposure,
                           higherRiskStretches: stretches, polyline: polyline, segments: segments, steps: steps)
    }

    private static func firstBearing(_ pts: [CLLocationCoordinate2D]) -> Double? {
        for i in 1..<max(pts.count, 1) where Geometry.distance(pts[0], pts[i]) > 1 {
            return Geometry.bearing(pts[0], pts[i])
        }
        return nil
    }

    private static func lastBearing(_ pts: [CLLocationCoordinate2D]) -> Double? {
        guard let end = pts.last else { return nil }
        for i in stride(from: pts.count - 2, through: 0, by: -1) where Geometry.distance(pts[i], end) > 1 {
            return Geometry.bearing(pts[i], end)
        }
        return nil
    }
}

/// Binary min-heap ordered by (f, d, node), the same order as the reference's heapq tuples.
struct AStarHeap {
    struct Item { let f: Double; let d: Double; let node: Int32 }
    private var items: [Item] = []

    var isEmpty: Bool { items.isEmpty }

    @inline(__always) private static func less(_ a: Item, _ b: Item) -> Bool {
        if a.f != b.f { return a.f < b.f }
        if a.d != b.d { return a.d < b.d }
        return a.node < b.node
    }

    mutating func push(f: Double, d: Double, node: Int32) {
        items.append(Item(f: f, d: d, node: node))
        var i = items.count - 1
        while i > 0 {
            let p = (i - 1) / 2
            if !Self.less(items[i], items[p]) { break }
            items.swapAt(p, i); i = p
        }
    }

    mutating func pop() -> Item? {
        guard !items.isEmpty else { return nil }
        let top = items[0]
        let last = items.removeLast()
        if !items.isEmpty {
            items[0] = last
            var i = 0
            let c = items.count
            while true {
                let l = 2 * i + 1, r = l + 1
                var m = i
                if l < c && Self.less(items[l], items[m]) { m = l }
                if r < c && Self.less(items[r], items[m]) { m = r }
                if m == i { break }
                items.swapAt(m, i); i = m
            }
        }
        return top
    }
}
