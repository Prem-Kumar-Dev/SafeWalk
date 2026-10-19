import CoreLocation
import Foundation

// Readers for the data files in app-spec/02-data-files.md (format version 1).
// reference/safewalk_format.py is the source of truth for every byte.

enum SafeWalkFormat {
    static let version = 1
    static let slotCount = 12
    static let noName: UInt16 = 0xFFFF
    static let metresPerDegLat = 110_950.0
    static func metresPerDegLon(atLat lat: Double) -> Double { 111_320.0 * cos(lat * .pi / 180) }
}

enum DataError: LocalizedError, Equatable {
    case malformed(String)
    case download(String)
    case appTooOld
    case mismatch

    var errorDescription: String? {
        switch self {
        case .malformed(let m): return "The map data is damaged (\(m))."
        case .download(let m): return "Couldn't download the map data (\(m))."
        case .appTooOld: return L10n.updateApp
        case .mismatch: return "The forecast doesn't match the street map."
        }
    }
}

// MARK: - manifest.json

struct Manifest: Codable, Equatable {
    struct FileRef: Codable, Equatable {
        let path: String
        let bytes: Int
        let raw_bytes: Int
        let sha256: String
        let graph_id: String?
        let week_start: String?
        let valid_until: String?
    }
    struct Model: Codable, Equatable {
        let name: String
        let version: String
        let trained_through: String?
    }

    let format_version: Int
    let min_app_format: Int
    let city: String
    let display_name: String
    let timezone: String
    let bbox: [Double]          // min_lat, min_lon, max_lat, max_lon
    let generated_at: String?
    let graph: FileRef
    let risk: FileRef
    let model: Model?

    var bounds: GeoBounds { GeoBounds(minLat: bbox[0], minLon: bbox[1], maxLat: bbox[2], maxLon: bbox[3]) }
}

struct GeoBounds: Equatable, Sendable {
    var minLat, minLon, maxLat, maxLon: Double

    var center: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2)
    }

    /// Whether `c` is inside the box grown by `metres` on every side.
    func contains(_ c: CLLocationCoordinate2D, margin metres: Double = 0) -> Bool {
        let dLat = metres / SafeWalkFormat.metresPerDegLat
        let dLon = metres / SafeWalkFormat.metresPerDegLon(atLat: center.latitude)
        return c.latitude >= minLat - dLat && c.latitude <= maxLat + dLat
            && c.longitude >= minLon - dLon && c.longitude <= maxLon + dLon
    }
}

// MARK: - Byte reader

/// Little-endian sequential reader over decompressed bytes.
struct ByteReader {
    let data: Data
    private(set) var offset = 0

    init(_ data: Data) { self.data = data }

    var remaining: Int { data.count - offset }

    mutating func bytes(_ n: Int) throws -> Data {
        guard n >= 0, remaining >= n else { throw DataError.malformed("file is too short") }
        let start = data.startIndex + offset
        offset += n
        return data.subdata(in: start..<(start + n))
    }

    mutating func scalar<T: FixedWidthInteger>(_: T.Type) throws -> T {
        let b = try bytes(MemoryLayout<T>.size)
        var v: T = 0
        withUnsafeMutableBytes(of: &v) { _ = b.copyBytes(to: $0) }
        return T(littleEndian: v)
    }

    mutating func array<T: FixedWidthInteger>(_: T.Type, count: Int) throws -> [T] {
        let b = try bytes(count * MemoryLayout<T>.size)
        let out = [T](unsafeUninitializedCapacity: count) { buf, n in
            _ = b.copyBytes(to: buf)
            n = count
        }
        return out.map { T(littleEndian: $0) }
    }

    mutating func floats(count: Int) throws -> [Float] {
        try array(UInt32.self, count: count).map { Float(bitPattern: $0) }
    }

    mutating func align(_ k: Int) throws { _ = try bytes((k - offset % k) % k) }
}

// MARK: - Street graph (SWG1)

struct StreetGraph: Sendable {
    let graphID: String
    let bounds: GeoBounds
    let nodeLat: [Double]
    let nodeLon: [Double]
    let edgeU: [UInt32]
    let edgeV: [UInt32]
    let length: [Double]        // metres, from the file (never recomputed)
    let shapeOffsets: [UInt32]  // edgeCount + 1
    let shapeLat: [Double]
    let shapeLon: [Double]
    let nameIndex: [UInt16]
    let roadClass: [UInt8]
    let names: [String]

    // Derived at load
    let adjOffsets: [UInt32]    // nodeCount + 1, CSR into adjEdges
    let adjEdges: [UInt32]      // each undirected edge twice, in edge-index order per node

    var nodeCount: Int { nodeLat.count }
    var edgeCount: Int { edgeU.count }

    /// Decodes an inflated graph file. `graphID` is the first 16 hex chars of SHA-256 of `raw`.
    init(raw: Data, graphID: String) throws {
        var r = ByteReader(raw)
        guard try r.bytes(4) == Data("SWG1".utf8) else { throw DataError.malformed("not a graph file") }
        guard try r.scalar(UInt16.self) == SafeWalkFormat.version else { throw DataError.appTooOld }
        _ = try r.scalar(UInt16.self)                       // flags
        let n = Int(try r.scalar(UInt32.self)), e = Int(try r.scalar(UInt32.self))
        let ns = Int(try r.scalar(UInt32.self)), nn = Int(try r.scalar(UInt32.self))
        let nb = Int(try r.scalar(UInt32.self))
        let bb = try r.array(Int32.self, count: 4).map { Double($0) / 1e6 }

        let nodes = try r.array(Int32.self, count: 2 * n)
        let eu = try r.array(UInt32.self, count: e)
        let ev = try r.array(UInt32.self, count: e)
        let len = try r.floats(count: e).map { Double($0) }
        let off = try r.array(UInt32.self, count: e + 1)
        let shape = try r.array(Int32.self, count: 2 * ns)
        let nameIdx = try r.array(UInt16.self, count: e)
        let cls = try r.array(UInt8.self, count: e)
        try r.align(4)
        let namesData = try r.bytes(nb)
        guard r.remaining == 0 else { throw DataError.malformed("graph file has trailing bytes") }

        guard eu.allSatisfy({ Int($0) < n }), ev.allSatisfy({ Int($0) < n }) else {
            throw DataError.malformed("edge refers to a missing node")
        }
        guard off.first == 0, Int(off.last ?? 0) == ns, zip(off, off.dropFirst()).allSatisfy({ $0 <= $1 }) else {
            throw DataError.malformed("shape offsets")
        }
        let names = nn == 0 ? [] : (String(data: namesData, encoding: .utf8) ?? "")
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard names.count == nn else { throw DataError.malformed("street names") }

        self.graphID = graphID
        self.bounds = GeoBounds(minLat: bb[0], minLon: bb[1], maxLat: bb[2], maxLon: bb[3])
        self.nodeLat = stride(from: 0, to: 2 * n, by: 2).map { Double(nodes[$0]) / 1e6 }
        self.nodeLon = stride(from: 1, to: 2 * n, by: 2).map { Double(nodes[$0]) / 1e6 }
        self.edgeU = eu; self.edgeV = ev; self.length = len; self.shapeOffsets = off
        self.shapeLat = stride(from: 0, to: 2 * ns, by: 2).map { Double(shape[$0]) / 1e6 }
        self.shapeLon = stride(from: 1, to: 2 * ns, by: 2).map { Double(shape[$0]) / 1e6 }
        self.nameIndex = nameIdx; self.roadClass = cls; self.names = names

        var deg = [UInt32](repeating: 0, count: n + 1)
        for i in 0..<e { deg[Int(eu[i]) + 1] += 1; deg[Int(ev[i]) + 1] += 1 }
        for i in 0..<n { deg[i + 1] += deg[i] }
        var fill = Array(deg[0..<n])
        var adj = [UInt32](repeating: 0, count: 2 * e)
        for i in 0..<e {
            let u = Int(eu[i]), v = Int(ev[i])
            adj[Int(fill[u])] = UInt32(i); fill[u] += 1
            adj[Int(fill[v])] = UInt32(i); fill[v] += 1
        }
        self.adjOffsets = deg
        self.adjEdges = adj
    }

    func other(_ e: Int, from n: Int) -> Int { Int(edgeU[e]) == n ? Int(edgeV[e]) : Int(edgeU[e]) }

    func coordinate(ofNode n: Int) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: nodeLat[n], longitude: nodeLon[n])
    }

    /// Edge polyline from `edgeU` to `edgeV`, both junctions included.
    func points(edge e: Int) -> [CLLocationCoordinate2D] {
        var pts = [coordinate(ofNode: Int(edgeU[e]))]
        for i in Int(shapeOffsets[e])..<Int(shapeOffsets[e + 1]) {
            pts.append(CLLocationCoordinate2D(latitude: shapeLat[i], longitude: shapeLon[i]))
        }
        pts.append(coordinate(ofNode: Int(edgeV[e])))
        return pts
    }

    func streetName(edge e: Int) -> String? {
        let i = nameIndex[e]
        guard i != SafeWalkFormat.noName, Int(i) < names.count, !names[Int(i)].isEmpty else { return nil }
        return names[Int(i)]
    }
}

// MARK: - Risk file (SWR1)

struct RiskTable: Sendable {
    let graphID: String
    let weekStart: Date          // midnight UTC of the Monday, as stored (days since 1970-01-01)
    let scale: Double
    let edgeCount: Int
    let elevated: [UInt16]       // per slot, stored units
    let high: [UInt16]
    let veryHigh: [UInt16]
    let values: [UInt16]         // edge-major: e * 12 + slot

    init(raw: Data) throws {
        var r = ByteReader(raw)
        guard try r.bytes(4) == Data("SWR1".utf8) else { throw DataError.malformed("not a risk file") }
        guard try r.scalar(UInt16.self) == SafeWalkFormat.version else { throw DataError.appTooOld }
        guard try r.scalar(UInt16.self) == SafeWalkFormat.slotCount else { throw DataError.malformed("slot count") }
        let e = Int(try r.scalar(UInt32.self))
        let gid = String(data: try r.bytes(16), encoding: .ascii) ?? ""
        let days = try r.scalar(UInt32.self)
        let scale = try r.scalar(UInt16.self)
        _ = try r.bytes(2)
        let thr = try r.array(UInt16.self, count: 3 * SafeWalkFormat.slotCount)
        let values = try r.array(UInt16.self, count: e * SafeWalkFormat.slotCount)
        guard r.remaining == 0 else { throw DataError.malformed("risk file size") }
        guard scale > 0 else { throw DataError.malformed("risk scale") }

        let s = SafeWalkFormat.slotCount
        self.graphID = gid
        self.weekStart = Date(timeIntervalSince1970: Double(days) * 86_400)
        self.scale = Double(scale)
        self.edgeCount = e
        self.elevated = Array(thr[0..<s]); self.high = Array(thr[s..<(2 * s)]); self.veryHigh = Array(thr[(2 * s)...])
        self.values = values
    }

    /// Multiple of the city average for that slot (1.0 = average).
    @inline(__always) func relative(edge e: Int, slot: Int) -> Double {
        Double(values[e * SafeWalkFormat.slotCount + slot]) / scale
    }

    @inline(__always) func stored(edge e: Int, slot: Int) -> UInt16 { values[e * SafeWalkFormat.slotCount + slot] }

    /// Colour band along a route (spec 03 §5): 0 normal, 1 elevated, 2 high or above.
    func band(edge e: Int, slot: Int) -> Int {
        let v = stored(edge: e, slot: slot)
        if v >= high[slot] { return 2 }
        if v >= elevated[slot] { return 1 }
        return 0
    }
}

/// A matched graph + risk pair; never mixed across graph ids (spec 02, "How an app updates").
struct CityData: Sendable {
    let manifest: Manifest
    let graph: StreetGraph
    let risk: RiskTable?          // nil when no risk file matches: route Direct only
    let snapIndex: SnapIndex

    init(manifest: Manifest, graph: StreetGraph, risk: RiskTable?) {
        self.manifest = manifest
        self.graph = graph
        if let risk, risk.graphID == graph.graphID, risk.edgeCount == graph.edgeCount {
            self.risk = risk
        } else {
            self.risk = nil
        }
        self.snapIndex = SnapIndex(graph: graph, bounds: manifest.bounds)
    }

    /// Age of the forecast in whole days, measured from its Monday.
    func forecastAgeDays(now: Date = Date()) -> Int? {
        guard let risk else { return nil }
        return Int(floor(now.timeIntervalSince(risk.weekStart) / 86_400))
    }
}
