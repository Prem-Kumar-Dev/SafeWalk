import SwiftUI

/// Route preview (spec 01 §4): trip fields on top, the route sheet at the bottom.
struct RoutePreviewView: View {
    @ObservedObject var model: AppModel
    @State private var editing: SearchView.Purpose?
    @State private var pickingTime = false

    var body: some View {
        VStack(spacing: 0) {
            tripFields
                .padding(.horizontal)
            Spacer()
            sheet
        }
        .sheet(item: Binding(get: { editing.map(EditTarget.init) }, set: { editing = $0?.purpose })) { t in
            SearchView(model: model, purpose: t.purpose)
        }
    }

    private struct EditTarget: Identifiable {
        let purpose: SearchView.Purpose
        var id: Int { purpose == .start ? 0 : 1 }
    }

    // MARK: Top

    private var tripFields: some View {
        HStack(spacing: 10) {
            Button { model.closePreview() } label: {
                Image(systemName: "chevron.left").font(.headline).frame(width: 36, height: 36)
            }
            .accessibilityLabel(L10n.s("done"))
            VStack(spacing: 0) {
                field(L10n.s("from"), model.start?.title ?? L10n.s("choose_start"), dot: .gray) { editing = .start }
                Divider()
                field(L10n.s("to"), model.end?.title ?? L10n.searchHint, dot: .accentColor) { editing = .destination }
            }
            Button { model.swapEnds() } label: {
                Image(systemName: "arrow.up.arrow.down").frame(width: 36, height: 36)
            }
            .accessibilityLabel(L10n.s("swap"))
            .disabled(model.start == nil || model.end == nil)
        }
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func field(_ label: String, _ value: String, dot: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Circle().fill(dot).frame(width: 8, height: 8)
                Text(value).lineLimit(1).foregroundStyle(.primary)
                Spacer()
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("\(label): \(value)")
    }

    // MARK: Bottom sheet

    private var sheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(L10n.s("leaving")).font(.subheadline).foregroundStyle(.secondary)
                Spacer()
                Menu {
                    Button(L10n.s("leaving_now")) { model.departure = nil }
                    Button(L10n.s("leaving") + "…") { pickingTime = true }
                } label: {
                    Text(model.departure.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? L10n.s("leaving_now"))
                        .font(.subheadline.weight(.semibold))
                }
            }
            Picker(L10n.s("route_preference"), selection: $model.preference) {
                ForEach(RoutePreference.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)

            if let c = model.comparison {
                cards(c)
            } else if model.isRouting {
                HStack { Spacer(); ProgressView(); Spacer() }.frame(height: 80)
            }
            if let m = model.routeMessage {
                Label(m, systemImage: "info.circle").font(.footnote)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.s("route_fine_print"))
                if let note = model.forecastAgeNote { Text(note) }
                if model.forecastIsStale { Text(L10n.s("forecast_stale")) }
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Button { model.startWalking() } label: {
                Text(L10n.s("start_walking")).font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.comparison == nil)
        }
        .padding()
        .padding(.bottom, 12)
        .background(.regularMaterial, in: UnevenRoundedRectangleShape())
        .sheet(isPresented: $pickingTime) { DeparturePicker(model: model) }
    }

    @ViewBuilder
    private func cards(_ c: RouteComparison) -> some View {
        if let lower = c.lowerRisk {
            RouteCard(title: L10n.s("route_lower_risk_title"), route: lower, units: model.units,
                      detail: "\(L10n.riskCut(c.riskCutPercent)) · \(L10n.extraTime(c.extraMinutes))",
                      emphasiseDetail: c.extraPercent > 25,
                      selected: model.choice == .lowerRisk) { model.choice = .lowerRisk }
            RouteCard(title: L10n.s("route_direct_title"), route: c.direct, units: model.units, detail: nil,
                      emphasiseDetail: false, selected: model.choice == .direct) { model.choice = .direct }
        } else {
            RouteCard(title: L10n.s("route_direct_title"), route: c.direct, units: model.units, detail: nil,
                      emphasiseDetail: false, selected: true) { model.choice = .direct }
            if c.preference != .direct && model.data?.risk != nil {
                Text(L10n.s("route_direct_is_best")).font(.footnote)
            }
        }
        if c.longWalk { Text(L10n.s("route_long_walk")).font(.footnote) }
    }
}

struct RouteCard: View {
    let title: String
    let route: RouteResult
    let units: Units
    let detail: String?
    let emphasiseDetail: Bool
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(title).font(.headline)
                    Spacer()
                    Text("\(units.format(route.lengthMetres)) · \(route.minutes) min").font(.subheadline.monospacedDigit())
                }
                if let detail {
                    Text(detail).font(emphasiseDetail ? .subheadline.weight(.semibold) : .subheadline)
                }
                Text(L10n.stretches(route.higherRiskStretches)).font(.footnote).foregroundStyle(.secondary)
            }
            .foregroundStyle(.primary)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(selected ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground)))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Color.accentColor : .clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct DeparturePicker: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var date = Date()

    var body: some View {
        NavigationStack {
            DatePicker(L10n.s("leaving"), selection: $date, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                .datePickerStyle(.graphical)
                .padding()
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.s("done")) { model.departure = date; dismiss() }
                    }
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.s("leaving_now")) { model.departure = nil; dismiss() }
                    }
                }
        }
        .presentationDetents([.medium, .large])
        .onAppear { date = model.departure ?? Date() }
    }
}

/// Top-rounded sheet background (iOS 16 has no UnevenRoundedRectangle).
struct UnevenRoundedRectangleShape: Shape {
    var radius: CGFloat = 20
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}
