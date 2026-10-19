import MapKit
import SwiftUI

/// Place search with Apple's built-in search, limited to the manifest bbox (spec 01 §3).
@MainActor
final class PlaceSearch: NSObject, ObservableObject, MKLocalSearchCompleterDelegate {
    @Published var query = "" { didSet { completer.queryFragment = query } }
    @Published private(set) var results: [MKLocalSearchCompletion] = []

    private let completer = MKLocalSearchCompleter()
    private var region: MKCoordinateRegion?

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest]
        if #available(iOS 18.0, *) { completer.regionPriority = .required }
    }

    func limit(to b: GeoBounds) {
        let r = MKCoordinateRegion(center: b.center,
                                   span: MKCoordinateSpan(latitudeDelta: b.maxLat - b.minLat, longitudeDelta: b.maxLon - b.minLon))
        region = r
        completer.region = r
    }

    func resolve(_ c: MKLocalSearchCompletion, within b: GeoBounds) async -> Place? {
        let req = MKLocalSearch.Request(completion: c)
        if let region { req.region = region }
        if #available(iOS 18.0, *) { req.regionPriority = .required }
        guard let resp = try? await MKLocalSearch(request: req).start() else { return nil }
        let items = resp.mapItems.filter { b.contains($0.placemark.coordinate, margin: 200) }
        guard let item = items.first ?? resp.mapItems.first else { return nil }
        return Place(title: c.title, subtitle: c.subtitle.isEmpty ? nil : c.subtitle, coordinate: item.placemark.coordinate)
    }

    nonisolated func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let r = completer.results
        Task { @MainActor in results = r }
    }

    nonisolated func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        Task { @MainActor in results = [] }
    }
}

struct SearchView: View {
    enum Purpose { case destination, start }

    @ObservedObject var model: AppModel
    let purpose: Purpose
    @Environment(\.dismiss) private var dismiss
    @StateObject private var search = PlaceSearch()
    @FocusState private var focused: Bool
    @State private var resolving = false

    var body: some View {
        NavigationStack {
            List {
                if search.query.isEmpty {
                    if purpose == .start {
                        Button {
                            model.useMyLocationAsStart()
                            dismiss()
                        } label: {
                            row(L10n.s("search_my_location"), nil, icon: "location.fill")
                        }
                    }
                    if !model.recents.isEmpty {
                        Section(header: Text(L10n.s("search_recent")).font(.swSection).foregroundStyle(SW.accent)) {
                            ForEach(model.recents) { p in
                                Button { choose(p) } label: { row(p.title, p.subtitle, icon: "clock") }
                            }
                        }
                    }
                } else {
                    ForEach(search.results, id: \.self) { r in
                        Button {
                            Task { await pick(r) }
                        } label: { row(r.title, r.subtitle, icon: "mappin.circle") }
                    }
                    if search.results.isEmpty {
                        Text(L10n.s("search_no_results")).font(.swBody).foregroundStyle(SW.ink2)
                    }
                }
            }
            .listStyle(.plain)
            .font(.swBody)
            .overlay { if resolving { ProgressView().tint(SW.accent) } }
            .searchable(text: $search.query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: purpose == .start ? L10n.s("choose_start") : L10n.searchHint)
            .navigationTitle(purpose == .start ? L10n.s("from") : L10n.s("to"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L10n.s("done")) { dismiss() } }
            }
        }
        .onAppear { if let b = model.data?.manifest.bounds { search.limit(to: b) } }
    }

    private func row(_ title: String, _ subtitle: String?, icon: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 15, weight: .semibold))
                .foregroundStyle(icon == "clock" ? SW.ink2 : SW.accent)
                .frame(width: 36, height: 36).background(icon == "clock" ? SW.sand : SW.blush, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.swBody).foregroundStyle(SW.ink)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.swCaption).foregroundStyle(SW.ink2)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func pick(_ r: MKLocalSearchCompletion) async {
        guard let b = model.data?.manifest.bounds else { return }
        resolving = true
        defer { resolving = false }
        if let p = await search.resolve(r, within: b) { choose(p) }
    }

    private func choose(_ p: Place) {
        switch purpose {
        case .destination: model.planWalk(to: p)
        case .start: model.setStart(p)
        }
        dismiss()
    }
}
