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
        HStack(spacing: 6) {
            Button { model.closePreview() } label: {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold)).foregroundStyle(SW.ink)
                    .frame(width: 40, height: 40)
            }
            .accessibilityLabel(L10n.s("done"))
            VStack(spacing: 0) {
                field(L10n.s("from"), model.start?.title ?? L10n.s("choose_start"), end: false) { editing = .start }
                SW.line.frame(height: 1).padding(.leading, 24)
                field(L10n.s("to"), model.end?.title ?? L10n.searchHint, end: true) { editing = .destination }
            }
            Button { model.swapEnds() } label: {
                Image(systemName: "arrow.up.arrow.down").font(.system(size: 15, weight: .semibold)).foregroundStyle(SW.accent)
                    .frame(width: 40, height: 40)
            }
            .accessibilityLabel(L10n.s("swap"))
            .disabled(model.start == nil || model.end == nil)
        }
        .padding(8)
        .background(SW.card, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
    }

    private func field(_ label: String, _ value: String, end: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Group {
                    if end { Circle().fill(SW.coral) } else { Circle().strokeBorder(SW.coral, lineWidth: 3) }
                }
                .frame(width: 12, height: 12)
                Text(value).font(.swBody).lineLimit(1).foregroundStyle(SW.ink)
                Spacer()
            }
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label): \(value)")
    }

    // MARK: Bottom sheet

    private var sheet: some View {
        BottomCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    Text(L10n.s("leaving")).font(.jakarta(15, .semibold)).foregroundStyle(SW.ink2)
                    pill(L10n.s("leaving_now"), selected: model.departure == nil) { model.departure = nil }
                    pill(model.departure.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? (L10n.s("leaving") + "…"),
                         selected: model.departure != nil) { pickingTime = true }
                    Spacer(minLength: 0)
                }
                Picker(L10n.s("route_preference"), selection: $model.preference) {
                    ForEach(RoutePreference.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)

                if let c = model.comparison {
                    cards(c)
                } else if model.isRouting {
                    HStack { Spacer(); ProgressView().tint(SW.accent); Spacer() }.frame(height: 80)
                }
                if let m = model.routeMessage {
                    Label(m, systemImage: "info.circle").font(.jakarta(13, .medium, relativeTo: .footnote)).foregroundStyle(SW.ink)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.s("route_fine_print"))
                    if let note = model.forecastAgeNote { Text(note) }
                    if model.forecastIsStale { Text(L10n.s("forecast_stale")) }
                }
                .font(.swCaption)
                .foregroundStyle(SW.ink2)

                Button { model.startWalking() } label: {
                    Label(L10n.s("start_walking"), systemImage: "figure.walk")
                }
                .buttonStyle(CoralButtonStyle())
                .disabled(model.comparison == nil)
            }
        }
        .sheet(isPresented: $pickingTime) { DeparturePicker(model: model) }
    }

    private func pill(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.jakarta(14, .semibold))
                .foregroundStyle(selected ? SW.card : SW.ink)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(selected ? SW.ink : SW.sand, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func cards(_ c: RouteComparison) -> some View {
        // Why this route is shown, in one sentence from the router.
        Label(c.explanation.text, systemImage: "info.circle")
            .font(.jakarta(14, .medium))
            .foregroundStyle(SW.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(SW.blush, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
        }
        if c.longWalk { Text(L10n.s("route_long_walk")).font(.swBody).foregroundStyle(SW.ink) }
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
                HStack(alignment: .firstTextBaseline) {
                    Text(title).font(.swHeadline)
                    Spacer()
                    Text("\(route.minutes) min").font(.jakarta(17, .bold, relativeTo: .headline).monospacedDigit())
                }
                Text(units.format(route.lengthMetres)).font(.swCaption).foregroundStyle(SW.ink2)
                if let detail {
                    Text(detail).font(emphasiseDetail ? .jakarta(14, .semibold) : .jakarta(14)).foregroundStyle(SW.ink2)
                }
                HStack(spacing: 8) {
                    if route.higherRiskStretches > 0 { Circle().fill(SW.amber).frame(width: 8, height: 8) }
                    Text(L10n.stretches(route.higherRiskStretches)).font(.jakarta(14)).foregroundStyle(SW.ink2)
                }
            }
            .foregroundStyle(SW.ink)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(selected ? SW.blushSoft : SW.card))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(selected ? SW.coral : SW.line, lineWidth: selected ? 2 : 1))
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
