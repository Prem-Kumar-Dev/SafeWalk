import CoreLocation
import Foundation

/// Where a point meets the street graph (spec 03 §2).
struct SnapPoint: Equatable, Sendable {
    let edge: Int
    /// Distance along the edge polyline from `edgeU`, as a fraction of the polyline (0 at u, 1 at v).
    let frac: Double
    /// Metres from the query point to the street.
    let distance: Double
}

enum SnapFailure: Error, Equatable {
    case outsideCoverage     // "Lanternway covers Chicago only for now."
    case tooFarFromStreet    // "Move the pin closer to a street."
}

/// Uniform 200 m buckets over the bbox; each lists edges whose polyline bounding box touches it.
struct SnapIndex: Sendable {
    static let bucketMetres = 200.0
    static let maxSnapMetres = 150.0

    private let bounds: GeoBounds
    private let originLat: Double, originLon: Double
    private let kx: Double, ky: Double
    private let cols: Int, rows: Int
    private let bucketOffsets: [UInt32]
    private let bucketEdges: [UInt32]

    init(graph g: StreetGraph, bounds: GeoBounds) {
        self.bounds = bounds
        // Grid over the bbox grown by one bucket so edge boxes at the border still land in range.
        let pad = Self.bucketMetres
        ky = SafeWalkFormat.metresPerDegLat
        kx = SafeWalkFormat.metresPerDegLon(atLat: bounds.center.latitude)
        originLat = bounds.minLat - pad / ky
        originLon = bounds.minLon - pad / kx
        cols = max(1, Int(ceil(((bounds.maxLon - bounds.minLon) * kx + 2 * pad) / Self.bucketMetres)) + 1)
        rows = max(1, Int(ceil(((bounds.maxLat - bounds.minLat) * ky + 2 * pad) / Self.bucketMetres)) + 1)

        var lists = [[UInt32]](repeating: [], count: cols * rows)
        for e in 0..<g.edgeCount {
            let pts = g.points(edge: e)
            let lats = pts.map(\.latitude), lons = pts.map(\.longitude)
            let c0 = Self.clamp(Int(floor((lons.min()! - originLon) * kx / Self.bucketMetres)), cols)
            let c1 = Self.clamp(Int(floor((lons.max()! - originLon) * kx / Self.bucketMetres)), cols)
            let r0 = Self.clamp(Int(floor((lats.min()! - originLat) * ky / Self.bucketMetres)), rows)
            let r1 = Self.clamp(Int(floor((lats.max()! - originLat) * ky / Self.bucketMetres)), rows)
            for r in r0...r1 { for c in c0...c1 { lists[r * cols + c].append(UInt32(e)) } }
        }
        var offsets = [UInt32](repeating: 0, count: cols * rows + 1)
        var flat: [UInt32] = []
        for (i, l) in lists.enumerated() { flat += l; offsets[i + 1] = UInt32(flat.count) }
        bucketOffsets = offsets
        bucketEdges = flat
    }

    private static func clamp(_ v: Int, _ n: Int) -> Int { min(max(v, 0), n - 1) }

    func snap(_ p: CLLocationCoordinate2D, in g: StreetGraph) -> Result<SnapPoint, SnapFailure> {
        guard bounds.contains(p, margin: Self.bucketMetres) else { return .failure(.outsideCoverage) }
        let c = Int(floor((p.longitude - originLon) * kx / Self.bucketMetres))
        let r = Int(floor((p.latitude - originLat) * ky / Self.bucketMetres))
        var candidates = Set<UInt32>()
        for rr in (r - 1)...(r + 1) where rr >= 0 && rr < rows {
            for cc in (c - 1)...(c + 1) where cc >= 0 && cc < cols {
                let b = rr * cols + cc
                for i in Int(bucketOffsets[b])..<Int(bucketOffsets[b + 1]) { candidates.insert(bucketEdges[i]) }
            }
        }
        // Lower edge index first so ties within 1 cm go to it, as in the reference.
        var best: SnapPoint?
        for e in candidates.sorted() {
            for s in Geometry.segmentProjections(p, onto: g.points(edge: Int(e)))
            where best == nil || s.distance < best!.distance - 0.01 {
                best = SnapPoint(edge: Int(e), frac: s.frac, distance: s.distance)
            }
        }
        guard let best, best.distance <= Self.maxSnapMetres else { return .failure(.tooFarFromStreet) }
        return .success(best)
    }
}

/// Small planar helpers matching reference/safewalk_format.py.
enum Geometry {
    /// For each straight piece of a polyline, the closest point to `p` as (fraction along the whole
    /// polyline, metres), in a local frame centred on `p`. Same arithmetic as the reference `snap`.
    static func segmentProjections(_ p: CLLocationCoordinate2D, onto pts: [CLLocationCoordinate2D]) -> [(frac: Double, distance: Double)] {
        let kx = SafeWalkFormat.metresPerDegLon(atLat: p.latitude), ky = SafeWalkFormat.metresPerDegLat
        let xy = pts.map { (($0.longitude - p.longitude) * kx, ($0.latitude - p.latitude) * ky) }
        var seg: [Double] = []
        for i in 0..<(xy.count - 1) { seg.append(hypot(xy[i + 1].0 - xy[i].0, xy[i + 1].1 - xy[i].1)) }
        let total = seg.reduce(0, +)
        let tot = total == 0 ? 1.0 : total
        var run = 0.0
        var out: [(frac: Double, distance: Double)] = []
        for i in 0..<seg.count {
            let (x0, y0) = xy[i], (x1, y1) = xy[i + 1], L = seg[i]
            let t = L == 0 ? 0.0 : max(0, min(1, -(x0 * (x1 - x0) + y0 * (y1 - y0)) / (L * L)))
            out.append(((run + t * L) / tot, hypot(x0 + t * (x1 - x0), y0 + t * (y1 - y0))))
            run += L
        }
        return out
    }

    /// Closest point on a polyline to `p`.
    static func project(_ p: CLLocationCoordinate2D, onto pts: [CLLocationCoordinate2D]) -> (frac: Double, distance: Double) {
        var best: (frac: Double, distance: Double)?
        for s in segmentProjections(p, onto: pts) where best == nil || s.distance < best!.distance - 0.01 { best = s }
        return best ?? (0, .infinity)
    }

    /// Point a fraction of the way along a polyline (reference `point_at`).
    static func point(along pts: [CLLocationCoordinate2D], frac: Double) -> CLLocationCoordinate2D {
        guard pts.count >= 2 else { return pts.first ?? CLLocationCoordinate2D() }
        let kx = SafeWalkFormat.metresPerDegLon(atLat: pts[0].latitude), ky = SafeWalkFormat.metresPerDegLat
        var seg: [Double] = []
        for i in 0..<(pts.count - 1) {
            seg.append(hypot((pts[i + 1].longitude - pts[i].longitude) * kx, (pts[i + 1].latitude - pts[i].latitude) * ky))
        }
        var goal = frac * seg.reduce(0, +)
        for i in 0..<seg.count {
            let L = seg[i]
            if goal <= L || i == seg.count - 1 {
                let t = L == 0 ? 0.0 : min(goal / L, 1.0)
                let a = pts[i], b = pts[i + 1]
                return CLLocationCoordinate2D(latitude: a.latitude + t * (b.latitude - a.latitude),
                                              longitude: a.longitude + t * (b.longitude - a.longitude))
            }
            goal -= L
        }
        return pts[pts.count - 1]
    }

    /// The part of a polyline between two fractions, in the direction from → to.
    static func subPolyline(_ pts: [CLLocationCoordinate2D], from f0: Double, to f1: Double) -> [CLLocationCoordinate2D] {
        guard pts.count >= 2 else { return pts }
        let lo = min(f0, f1), hi = max(f0, f1)
        let kx = SafeWalkFormat.metresPerDegLon(atLat: pts[0].latitude), ky = SafeWalkFormat.metresPerDegLat
        var cum = [0.0]
        for i in 0..<(pts.count - 1) {
            cum.append(cum[i] + hypot((pts[i + 1].longitude - pts[i].longitude) * kx,
                                      (pts[i + 1].latitude - pts[i].latitude) * ky))
        }
        let total = cum.last!
        var out = [point(along: pts, frac: lo)]
        if total > 0 {
            for i in 1..<(pts.count - 1) where cum[i] / total > lo && cum[i] / total < hi { out.append(pts[i]) }
        }
        out.append(point(along: pts, frac: hi))
        return f0 <= f1 ? out : out.reversed()
    }

    /// Metres between two coordinates (equirectangular; fine at walking scale).
    static func distance(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let kx = SafeWalkFormat.metresPerDegLon(atLat: (a.latitude + b.latitude) / 2)
        return hypot((b.longitude - a.longitude) * kx, (b.latitude - a.latitude) * SafeWalkFormat.metresPerDegLat)
    }

    /// Compass bearing a → b in degrees (0 = north, clockwise).
    static func bearing(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let kx = SafeWalkFormat.metresPerDegLon(atLat: a.latitude)
        let dx = (b.longitude - a.longitude) * kx, dy = (b.latitude - a.latitude) * SafeWalkFormat.metresPerDegLat
        let deg = atan2(dx, dy) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }

    /// Distance from `p` to the nearest point of a polyline, in metres.
    static func distance(from p: CLLocationCoordinate2D, toPolyline pts: [CLLocationCoordinate2D]) -> Double {
        guard pts.count > 1 else { return pts.first.map { distance(p, $0) } ?? .infinity }
        return project(p, onto: pts).distance
    }
}
