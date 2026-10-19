import CoreLocation
import XCTest
@testable import SafeWalk

/// Same cases and wording as android/core ExplanationTest, so both apps explain routes identically.
private struct FirstCases: Decodable {
    struct Point: Decodable { let lat: Double; let lon: Double }
    struct Case: Decodable { let start: Point; let end: Point? }
    let cases: [Case]
}

final class RouteExplanationTests: XCTestCase {
    private func block(_ edge: Int, _ street: String?) -> HigherRiskBlock { HigherRiskBlock(edge: edge, street: street, metres: 100) }

    func testSentences() {
        XCTAssertEqual(RouteExplanation(kind: .lowerRisk, blocks: 3, street: "S State St", allOnStreet: true,
                                        riskCutPercent: 40, longerPercent: 4).text,
                       "Avoids 3 higher-risk blocks on S State St, 4% longer.")
        XCTAssertEqual(RouteExplanation(kind: .lowerRisk, blocks: 5, street: "S State St", allOnStreet: false,
                                        riskCutPercent: 40, longerPercent: 12).text,
                       "Avoids 5 higher-risk blocks, mostly on S State St, 12% longer.")
        XCTAssertEqual(RouteExplanation(kind: .lowerRisk, blocks: 1, riskCutPercent: 15, longerPercent: 0).text,
                       "Avoids 1 higher-risk block, about the same distance.")
        XCTAssertEqual(RouteExplanation(kind: .lowerRisk, riskCutPercent: 23, longerPercent: 6).text,
                       "Takes lower-risk streets for 23% lower predicted risk, 6% longer.")
        XCTAssertEqual(RouteExplanation(kind: .directIsLowerRisk).text,
                       "The direct route is already the lower-risk choice at this time.")
        XCTAssertEqual(RouteExplanation(kind: .direct, blocks: 2, street: "W Madison St", allOnStreet: true).text,
                       "Shortest route; passes 2 higher-risk blocks on W Madison St.")
        XCTAssertEqual(RouteExplanation(kind: .direct).text, "Shortest route, with no higher-risk blocks at this time.")
        XCTAssertEqual(RouteExplanation(kind: .noForecast).text,
                       "Shortest route. No risk forecast is loaded, so streets aren't compared.")
    }

    func testMainStreetNeedsMoreThanHalf() {
        XCTAssertNil(RouteExplanation.mainStreet([block(1, "A St"), block(2, "B St")]).street)
        let mostly = RouteExplanation.mainStreet([block(1, "A St"), block(2, "A St"), block(3, nil)])
        XCTAssertEqual(mostly.street, "A St"); XCTAssertFalse(mostly.all)
        let all = RouteExplanation.mainStreet([block(1, "A St"), block(2, "A St")])
        XCTAssertEqual(all.street, "A St"); XCTAssertTrue(all.all)
        XCTAssertNil(RouteExplanation.mainStreet([]).street)
    }

    func testFixtureTrips() throws {
        let folder = Bundle(for: Self.self).resourceURL!.appendingPathComponent("Fixtures", isDirectory: true)
        let data = try DataStore.load(folder: folder)
        // First case of expected_routes.json ("near corner to near corner").
        let golden = try JSONDecoder().decode(FirstCases.self, from: Data(contentsOf: folder.appendingPathComponent("expected_routes.json")))
        let c = golden.cases[0], e = try XCTUnwrap(c.end)
        let a = CLLocationCoordinate2D(latitude: c.start.lat, longitude: c.start.lon)
        let b = CLLocationCoordinate2D(latitude: e.lat, longitude: e.lon)
        let night = TimeSlot(weekend: false, window: 5)

        let router = Router(data: data)
        let balanced = try router.compare(from: a, to: b, slot: night, preference: .balanced).get()
        XCTAssertEqual(balanced.explanation.text, "Avoids 1 higher-risk block on W Adams St, 6% longer.")
        XCTAssertEqual(balanced.direct.higherRiskStretches, balanced.direct.higherRiskBlocks.count)

        let direct = try router.compare(from: a, to: b, slot: night, preference: .direct).get()
        XCTAssertEqual(direct.explanation.text, "Shortest route; passes 1 higher-risk block on W Adams St.")

        let noRisk = CityData(manifest: data.manifest, graph: data.graph, risk: nil)
        let none = try Router(data: noRisk).compare(from: a, to: b, slot: night, preference: .balanced).get()
        XCTAssertEqual(none.explanation.kind, .noForecast)
    }
}
