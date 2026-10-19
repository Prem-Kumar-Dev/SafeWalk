import CoreLocation
import XCTest
@testable import SafeWalk

/// Runs every case in app-spec/fixtures/expected_routes.json (copied into Fixtures/).
/// Tolerances from the file's note: lengths 0.5 m, fractions 1e-4.
final class GoldenRouteTests: XCTestCase {
    private struct Golden: Decodable {
        struct Case: Decodable {
            struct LatLon: Decodable { let lat: Double; let lon: Double }
            struct Snap: Decodable { let edge: Int; let frac: Double; let dist_m: Double }
            struct Route: Decodable { let edges: [[Double]]; let length_m: Double; let cost: Double; let exposure: Double }
            let label: String
            let start: LatLon
            let end: LatLon?
            let local_time: String?
            let slot: Int?
            let snap_start: Snap?
            let snap_end: Snap?
            let routes: [String: Route?]?
        }
        let cases: [Case]
    }

    private static var fixtures: URL {
        Bundle(for: GoldenRouteTests.self).resourceURL!.appendingPathComponent("Fixtures", isDirectory: true)
    }

    private var data: CityData!
    private var golden: Golden!

    override func setUpWithError() throws {
        data = try DataStore.load(folder: Self.fixtures)
        golden = try JSONDecoder().decode(Golden.self, from: Data(contentsOf: Self.fixtures.appendingPathComponent("expected_routes.json")))
    }

    func testFilesLoadAndMatch() throws {
        XCTAssertEqual(data.graph.graphID, data.manifest.graph.graph_id)
        let risk = try XCTUnwrap(data.risk, "risk file must match the graph")
        XCTAssertEqual(risk.edgeCount, data.graph.edgeCount)
        XCTAssertEqual(risk.scale, 100)
        for s in 0..<12 {
            XCTAssertLessThanOrEqual(risk.elevated[s], risk.high[s])
            XCTAssertLessThanOrEqual(risk.high[s], risk.veryHigh[s])
        }
        // City average is 1.0 per slot (length-weighted).
        for s in 0..<12 {
            var num = 0.0, den = 0.0
            for e in 0..<data.graph.edgeCount { num += data.graph.length[e] * risk.relative(edge: e, slot: s); den += data.graph.length[e] }
            XCTAssertEqual(num / den, 1.0, accuracy: 0.02)
        }
    }

    func testTimeSlots() throws {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeSlot.chicago
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        for c in golden.cases {
            guard let t = c.local_time, let slot = c.slot else { continue }
            let date = try XCTUnwrap(f.date(from: t))
            XCTAssertEqual(TimeSlot.at(date).index, slot, c.label)
        }
    }

    func testSnapping() throws {
        let router = Router(data: data)
        for c in golden.cases {
            let a = CLLocationCoordinate2D(latitude: c.start.lat, longitude: c.start.lon)
            guard let expected = c.snap_start else {
                if case .success(let s) = router.snap(a) { XCTFail("\(c.label): should not snap, got \(s)") }
                continue
            }
            try check(router.snap(a).get(), expected, c.label + " start")
            if let e = c.end, let se = c.snap_end {
                try check(router.snap(CLLocationCoordinate2D(latitude: e.lat, longitude: e.lon)).get(), se, c.label + " end")
            }
        }
    }

    func testRoutes() throws {
        let router = Router(data: data)
        let levels: [String: Double] = ["direct": 0, "balanced": 0.5, "lowest_risk": 1.0]
        for c in golden.cases {
            guard let routes = c.routes, let e = c.end, let slot = c.slot else { continue }
            let sa = try router.snap(CLLocationCoordinate2D(latitude: c.start.lat, longitude: c.start.lon)).get()
            let sb = try router.snap(CLLocationCoordinate2D(latitude: e.lat, longitude: e.lon)).get()
            for (name, expectedOpt) in routes {
                let label = "\(c.label) / \(name)"
                let lam = try XCTUnwrap(levels[name], label)
                let got = router.route(from: sa, to: sb, slot: slot, lambda: lam)
                guard let expected = expectedOpt else { XCTAssertNil(got, label); continue }
                let r = try XCTUnwrap(got, label)
                XCTAssertEqual(r.pieces.count, expected.edges.count, "\(label): piece count")
                for (p, x) in zip(r.pieces, expected.edges) {
                    XCTAssertEqual(p.edge, Int(x[0]), "\(label): edge")
                    XCTAssertEqual(p.fromFrac, x[1], accuracy: 1e-4, "\(label): from fraction")
                    XCTAssertEqual(p.toFrac, x[2], accuracy: 1e-4, "\(label): to fraction")
                }
                XCTAssertEqual(r.lengthMetres, expected.length_m, accuracy: 0.5, "\(label): length")
                XCTAssertEqual(r.cost, expected.cost, accuracy: 0.5, "\(label): cost")
                XCTAssertEqual(r.exposure, expected.exposure, accuracy: 0.5, "\(label): exposure")
                XCTAssertFalse(r.polyline.isEmpty, label)
                XCTAssertFalse(r.steps.isEmpty, label)
            }
        }
    }

    func testComparisonRules() throws {
        let router = Router(data: data)
        guard let c = golden.cases.first(where: { $0.label.hasPrefix("near corner") }), let e = c.end else { return }
        let a = CLLocationCoordinate2D(latitude: c.start.lat, longitude: c.start.lon)
        let b = CLLocationCoordinate2D(latitude: e.lat, longitude: e.lon)
        let night = TimeSlot(weekend: false, window: 5)
        let cmp = try router.compare(from: a, to: b, slot: night, preference: .balanced).get()
        let lower = try XCTUnwrap(cmp.lowerRisk, "night trip should offer a lower-risk route")
        XCTAssertGreaterThanOrEqual(cmp.riskCutPercent, 10)
        XCTAssertGreaterThan(lower.lengthMetres, cmp.direct.lengthMetres)
        let direct = try router.compare(from: a, to: b, slot: night, preference: .direct).get()
        XCTAssertNil(direct.lowerRisk, "Direct shows one route")
        // Same point twice: "You're already there."
        XCTAssertEqual(router.compare(from: a, to: a, slot: night, preference: .balanced).failureValue, .alreadyThere)
    }

    private func check(_ s: SnapPoint, _ x: Golden.Case.Snap, _ label: String) throws {
        XCTAssertEqual(s.edge, x.edge, "\(label): edge")
        XCTAssertEqual(s.frac, x.frac, accuracy: 1e-4, "\(label): frac")
        XCTAssertEqual(s.distance, x.dist_m, accuracy: 0.01, "\(label): distance")
    }
}

private extension Result {
    var failureValue: Failure? { if case .failure(let f) = self { return f }; return nil }
}
