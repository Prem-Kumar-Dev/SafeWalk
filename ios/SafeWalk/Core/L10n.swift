import Foundation

/// Typed access to Localizable.strings. Keys match the Android strings file.
enum L10n {
    static func s(_ key: String) -> String { NSLocalizedString(key, comment: "") }
    static func f(_ key: String, _ args: CVarArg...) -> String { String(format: s(key), arguments: args) }

    static var welcome1: String { s("welcome_1") }
    static var welcome2: String { s("welcome_2") }
    static var welcome3: String { s("welcome_3") }
    static var disclaimerBody: String { s("disclaimer_body") }
    static var disclaimerAccept: String { s("disclaimer_accept") }

    static var searchHint: String { s("search_hint") }
    static var prefDirect: String { s("pref_direct") }
    static var prefBalanced: String { s("pref_balanced") }
    static var prefLowestRisk: String { s("pref_lowest_risk") }
    static var unnamedPath: String { s("unnamed_path") }
    static var updateApp: String { s("update_app") }

    static func riskCut(_ pct: Int) -> String { f("route_risk_cut", pct) }
    static func extraTime(_ min: Int) -> String { min <= 0 ? s("route_extra_time_zero") : f("route_extra_time", min) }
    static func stretches(_ n: Int) -> String {
        n == 0 ? s("route_none_stretches") : n == 1 ? s("route_stretches_one") : f("route_stretches", n)
    }

    static func message(for failure: RouteFailure) -> String {
        switch failure {
        case .snap(.outsideCoverage): return s("outside_coverage")
        case .snap(.tooFarFromStreet): return s("too_far_from_street")
        case .alreadyThere: return s("already_there")
        case .noRoute: return s("no_route")
        }
    }

    static func instruction(_ step: TurnStep) -> String {
        let street = step.street
        switch step.maneuver {
        case .start: return f("walk_head", street)
        case .straight: return f("walk_straight", street)
        case .left: return f("walk_left", street)
        case .right: return f("walk_right", street)
        case .uTurn: return f("walk_uturn", street)
        }
    }
}

/// Distance formatting in the user's chosen units.
enum Units: String, CaseIterable, Identifiable {
    case km, miles
    var id: String { rawValue }

    static var regionDefault: Units {
        Locale.current.measurementSystem == .metric ? .km : .miles
    }

    func format(_ metres: Double) -> String {
        let f = MeasurementFormatter()
        f.unitOptions = .providedUnit
        f.numberFormatter.maximumFractionDigits = 1
        switch self {
        case .km:
            if metres < 1000 { f.numberFormatter.maximumFractionDigits = 0; return f.string(from: Measurement(value: (metres / 10).rounded() * 10, unit: UnitLength.meters)) }
            return f.string(from: Measurement(value: metres / 1000, unit: UnitLength.kilometers))
        case .miles:
            let mi = metres / 1609.344
            if mi < 0.1 { f.numberFormatter.maximumFractionDigits = 0; return f.string(from: Measurement(value: (metres * 3.28084 / 10).rounded() * 10, unit: UnitLength.feet)) }
            return f.string(from: Measurement(value: mi, unit: UnitLength.miles))
        }
    }
}
