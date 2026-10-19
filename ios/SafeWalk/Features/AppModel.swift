import Combine
import CoreLocation
import Foundation
import SwiftUI
import UIKit

/// A trip end: a search result, a dropped pin, or the user's location.
struct Place: Codable, Equatable, Identifiable {
    var id: String { "\(title)|\(latitude)|\(longitude)" }
    var title: String
    var subtitle: String?
    var latitude: Double
    var longitude: Double
    var isCurrentLocation = false

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }

    init(title: String, subtitle: String? = nil, coordinate: CLLocationCoordinate2D, isCurrentLocation: Bool = false) {
        self.title = title; self.subtitle = subtitle
        latitude = coordinate.latitude; longitude = coordinate.longitude
        self.isCurrentLocation = isCurrentLocation
    }
}

enum Screen: Equatable { case map, preview, walking }
enum RouteChoice: Equatable { case lowerRisk, direct }

@MainActor
final class AppModel: ObservableObject {
    enum LoadState: Equatable { case loading, ready, failed(String) }

    @Published private(set) var loadState: LoadState = .loading
    @Published private(set) var data: CityData?
    @Published private(set) var appTooOld = false
    @Published private(set) var checkingForUpdate = false

    @Published var screen: Screen = .map
    @Published var start: Place? { didSet { if start != oldValue { recompute() } } }
    @Published var end: Place? { didSet { if end != oldValue { recompute() } } }
    /// nil = leave now.
    @Published var departure: Date? { didSet { if departure != oldValue { refreshSlot() } } }
    @Published private(set) var slot: TimeSlot = .now { didSet { if slot != oldValue { recompute() } } }
    @Published var preference: RoutePreference {
        didSet {
            UserDefaults.standard.set(preference.rawValue, forKey: "route.preference")
            if preference != oldValue { recompute() }
        }
    }
    @Published var units: Units { didSet { UserDefaults.standard.set(units.rawValue, forKey: "units") } }

    @Published private(set) var comparison: RouteComparison?
    @Published private(set) var routeMessage: String?
    @Published private(set) var isRouting = false
    @Published var choice: RouteChoice = .lowerRisk

    /// Long-press point waiting for "Walk here" / "Start here".
    @Published var droppedPin: CLLocationCoordinate2D?
    @Published private(set) var recents: [Place] = []
    /// Bumped to ask the map to frame the current trip.
    @Published private(set) var fitRequest = 0
    @Published private(set) var recenterRequest = 0

    @Published var walk: WalkSession?

    let location = LocationService()
    private var routeTask: Task<Void, Never>?
    private var clock: AnyCancellable?
    private var bag: Set<AnyCancellable> = []

    init() {
        let d = UserDefaults.standard
        preference = RoutePreference(rawValue: d.string(forKey: "route.preference") ?? "") ?? .balanced
        units = Units(rawValue: d.string(forKey: "units") ?? "") ?? .regionDefault
        if let raw = d.data(forKey: "recents"), let r = try? JSONDecoder().decode([Place].self, from: raw) { recents = r }
        // Forward location changes so views observing the model redraw.
        location.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        location.$location.compactMap { $0 }.sink { [weak self] loc in self?.walk?.update(with: loc) }.store(in: &bag)
    }

    // MARK: Data

    func load() async {
        guard data == nil else { return }
        do {
            let d = try await Task.detached(priority: .userInitiated) { try await DataStore.shared.loadCurrent() }.value
            data = d
            loadState = .ready
            clock = Timer.publish(every: 30, on: .main, in: .common).autoconnect().sink { [weak self] _ in self?.refreshSlot() }
            refreshSlot()
            location.start()
        } catch {
            loadState = .failed(error.localizedDescription)
        }
        await checkForUpdate(force: false)
    }

    /// On launch and on return to the foreground (rate-limited inside the store).
    func checkForUpdate(force: Bool) async {
        checkingForUpdate = true
        defer { checkingForUpdate = false }
        switch await DataStore.shared.checkForUpdate(force: force) {
        case .updated:
            let reload = Task.detached(priority: .utility) { try await DataStore.shared.loadCurrent() }
            if let d = try? await reload.value {
                data = d
                loadState = .ready
                recompute()
            }
        case .appTooOld: appTooOld = true
        case .upToDate, .failed: break
        }
    }

    var lastUpdated: Date? { UserDefaults.standard.object(forKey: "data.lastUpdated") as? Date }

    var forecastWeekLabel: String? {
        guard let w = data?.risk?.weekStart else { return nil }
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("d MMM")
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: w)
    }

    /// "Forecast from the week of …" from 14 days old; out-of-date banner from 56 (spec 02, "Old forecasts").
    var forecastAgeNote: String? {
        guard let age = data?.forecastAgeDays(), age >= 14, age < 56, let w = forecastWeekLabel else { return nil }
        return L10n.f("forecast_age", w)
    }
    var forecastIsStale: Bool { (data?.forecastAgeDays() ?? 0) >= 56 }

    var userOutsideCoverage: Bool {
        guard let c = location.coordinate, let d = data else { return false }
        return !d.manifest.bounds.contains(c)
    }

    // MARK: Time slot

    private func refreshSlot() {
        let s = TimeSlot.at(departure ?? Date())
        if s != slot { slot = s }
    }

    // MARK: Trip

    /// Search result or "Walk here": destination, starting from the user's location when known.
    func planWalk(to place: Place) {
        remember(place)
        if start == nil || start?.isCurrentLocation == true {
            if let c = location.coordinate {
                start = Place(title: L10n.s("search_my_location"), coordinate: c, isCurrentLocation: true)
            } else {
                start = nil
            }
        }
        end = place
        choice = .lowerRisk
        screen = .preview
        fitRequest += 1
    }

    func setStart(_ place: Place) {
        if !place.isCurrentLocation { remember(place) }
        start = place
        if end != nil { screen = .preview; fitRequest += 1 }
    }

    func useMyLocationAsStart() {
        guard let c = location.coordinate else {
            routeMessage = L10n.s("location_off")
            return
        }
        setStart(Place(title: L10n.s("search_my_location"), coordinate: c, isCurrentLocation: true))
    }

    func pinPlace(_ c: CLLocationCoordinate2D) -> Place {
        Place(title: L10n.s("search_dropped_pin"), subtitle: String(format: "%.5f, %.5f", c.latitude, c.longitude), coordinate: c)
    }

    func swapEnds() {
        guard let a = start, let b = end else { return }
        start = b
        end = a
    }

    func closePreview() {
        screen = .map
        end = nil
        comparison = nil
        routeMessage = nil
        if start?.isCurrentLocation == false { start = nil }
    }

    private func remember(_ p: Place) {
        guard !p.isCurrentLocation else { return }
        recents.removeAll { $0.title == p.title && abs($0.latitude - p.latitude) < 1e-5 && abs($0.longitude - p.longitude) < 1e-5 }
        recents.insert(p, at: 0)
        if recents.count > 20 { recents.removeLast(recents.count - 20) }
        saveRecents()
    }

    func clearRecents() { recents = []; saveRecents() }

    private func saveRecents() {
        UserDefaults.standard.set(try? JSONEncoder().encode(recents), forKey: "recents")
    }

    // MARK: Routing

    private func recompute() {
        routeTask?.cancel()
        routeMessage = nil
        guard let d = data, let a = start, let b = end else {
            comparison = nil
            isRouting = false
            return
        }
        let slot = slot, pref = preference
        let from = a.coordinate, to = b.coordinate
        isRouting = true
        routeTask = Task {
            let result = await Task.detached(priority: .userInitiated) {
                Router(data: d).compare(from: from, to: to, slot: slot, preference: pref)
            }.value
            guard !Task.isCancelled else { return }
            isRouting = false
            switch result {
            case .success(let c):
                comparison = c
                if d.risk == nil && pref != .direct { routeMessage = L10n.s("route_no_forecast") }
            case .failure(let f):
                comparison = nil
                routeMessage = L10n.message(for: f)
            }
        }
    }

    var selectedRoute: RouteResult? {
        guard let c = comparison else { return nil }
        return choice == .direct ? c.direct : c.recommended
    }

    // MARK: Walking

    func startWalking() {
        guard let r = selectedRoute, let dest = end else { return }
        walk = WalkSession(route: r, destination: dest.coordinate)
        screen = .walking
        location.startHeading()
        UIApplication.shared.isIdleTimerDisabled = true
        recenterRequest += 1
    }

    func endWalking() {
        walk = nil
        location.stopHeading()
        UIApplication.shared.isIdleTimerDisabled = false
        closePreview()
    }

    /// "Find a new route from here": same preference, slot for the current time.
    func rerouteFromHere() {
        guard let c = location.coordinate else { return }
        departure = nil
        start = Place(title: L10n.s("search_my_location"), coordinate: c, isCurrentLocation: true)
        walk?.dismissOffRoute()
        routeTask?.cancel()
        guard let d = data, let b = end else { return }
        let slot = TimeSlot.now, pref = preference
        Task {
            let result = await Task.detached { Router(data: d).compare(from: c, to: b.coordinate, slot: slot, preference: pref) }.value
            if case .success(let cmp) = result {
                comparison = cmp
                walk = WalkSession(route: choice == .direct ? cmp.direct : cmp.recommended, destination: b.coordinate,
                                   startedAt: walk?.startedAt ?? Date())
            }
        }
    }

    func recenter() { recenterRequest += 1 }
}

/// Progress along a route while walking (spec 03 §6).
@MainActor
final class WalkSession: ObservableObject {
    static let offRouteMetres = 40.0
    static let offRouteSeconds = 15.0
    static let arrivedMetres = 25.0

    let route: RouteResult
    let destination: CLLocationCoordinate2D
    @Published private(set) var stepIndex = 0
    @Published private(set) var metresToNextStep: Double?
    @Published private(set) var remainingMetres: Double
    @Published private(set) var offRoute = false
    @Published private(set) var arrived = false
    /// Distance from the route at the last fix, for the off-route banner.
    @Published private(set) var offRouteMetres = 0.0
    /// For the arrived summary: walking time and arrival clock time.
    let startedAt: Date
    @Published private(set) var arrivedAt: Date?
    private var offSince: Date?
    private var promptDismissedAt: Date?

    init(route: RouteResult, destination: CLLocationCoordinate2D, startedAt: Date = Date()) {
        self.route = route
        self.destination = destination
        self.startedAt = startedAt
        remainingMetres = route.lengthMetres
    }

    var remainingMinutes: Int { Int((remainingMetres / 1.3 / 60).rounded()) }

    var nextStep: TurnStep? {
        let i = stepIndex + 1
        return i < route.steps.count ? route.steps[i] : nil
    }

    func update(with loc: CLLocation) {
        guard !arrived else { return }
        let p = loc.coordinate
        if Geometry.distance(p, destination) <= Self.arrivedMetres { arrived = true; arrivedAt = Date(); offRoute = false; return }

        let proj = Geometry.project(p, onto: route.polyline)
        offRouteMetres = proj.distance
        remainingMetres = max(0, route.lengthMetres * (1 - proj.frac))

        // Current step: the last one whose start is behind us along the route.
        let walked = route.lengthMetres * proj.frac
        var acc = 0.0, idx = 0
        for (i, s) in route.steps.enumerated() {
            if walked >= acc - 1 { idx = i }
            acc += s.lengthMetres
        }
        stepIndex = idx
        let nextStart = route.steps.prefix(idx + 1).reduce(0) { $0 + $1.lengthMetres }
        metresToNextStep = idx + 1 < route.steps.count ? max(0, nextStart - walked) : nil

        if proj.distance > Self.offRouteMetres {
            if offSince == nil { offSince = Date() }
            let snoozed = promptDismissedAt.map { Date().timeIntervalSince($0) < 60 } ?? false
            if !snoozed, let since = offSince, Date().timeIntervalSince(since) >= Self.offRouteSeconds { offRoute = true }
        } else {
            offSince = nil
            offRoute = false
        }
    }

    /// "Not now": hide the prompt for a minute; never reroute silently.
    func dismissOffRoute() {
        offRoute = false
        offSince = nil
        promptDismissedAt = Date()
    }
}
