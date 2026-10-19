import SwiftUI

/// Walking mode (spec 01 §5, 03 §6): dark next-turn banner on top, sheet at the bottom.
/// Off-route and arrival replace the sheet's content rather than popping an alert.
struct WalkingView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var session: WalkSession

    var body: some View {
        VStack(spacing: 10) {
            banner
            Spacer()
            if !session.arrived {
                HStack {
                    Spacer()
                    Button { model.recenter() } label: {
                        Image(systemName: "location.north.line.fill").font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(SW.accent)
                            .frame(width: 52, height: 52)
                            .background(SW.card, in: Circle())
                            .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.s("search_my_location"))
                }
                .padding(.horizontal)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            BottomCard {
                if session.arrived { arrivedSheet }
                else if session.offRoute { offRouteSheet }
                else { walkingSheet }
            }
        }
        // VoiceOver users hear each new turn, the off-route warning and arrival without touching the screen.
        .onChange(of: session.stepIndex) { _ in
            if let step = session.nextStep { announce(L10n.instruction(step)) }
        }
        .onChange(of: session.offRoute) { off in
            if off { announce(L10n.s("off_route_title")) }
        }
        .onChange(of: session.arrived) { done in
            if done { announce(L10n.s("arrived")) }
        }
    }

    private func announce(_ text: String) {
        guard UIAccessibility.isVoiceOverRunning else { return }
        UIAccessibility.post(notification: .announcement, argument: text)
    }

    // MARK: Top

    @ViewBuilder
    private var banner: some View {
        if session.arrived {
            EmptyView()
        } else if session.offRoute {
            DarkBanner(title: L10n.s("off_route_title"),
                       subtitle: L10n.f("off_route_distance", model.units.format(session.offRouteMetres)),
                       symbol: "exclamationmark.triangle.fill", badge: SW.amber, badgeInk: Color(UIColor(hex: 0x231C1E)))
                .padding(.horizontal)
        } else if let step = session.nextStep ?? session.route.steps[safe: session.stepIndex] {
            // distance to the turn leads; the instruction sits under it
            if let m = session.metresToNextStep {
                DarkBanner(title: model.units.format(m), subtitle: L10n.instruction(step),
                           symbol: icon(step.maneuver), badge: SW.coral, badgeInk: SW.coralInk,
                           badgeRadius: 14, titleFont: .jakarta(24, .bold, relativeTo: .title2))
                    .padding(.horizontal)
            } else {
                DarkBanner(title: L10n.instruction(step), subtitle: nil,
                           symbol: icon(step.maneuver), badge: SW.coral, badgeInk: SW.coralInk, badgeRadius: 14)
                    .padding(.horizontal)
            }
        }
    }

    // MARK: Bottom

    private var walkingSheet: some View {
        let lower = model.choice == .lowerRisk && model.comparison?.lowerRisk != nil
        let arrive = Date().addingTimeInterval(Double(session.remainingMinutes) * 60).formatted(date: .omitted, time: .shortened)
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(L10n.f("walk_left_to_go", model.units.format(session.remainingMetres), session.remainingMinutes))
                    .font(.swTitle.monospacedDigit()).foregroundStyle(SW.ink)
                Spacer()
                Text(model.preference.label).font(.jakarta(11, .semibold)).foregroundStyle(SW.accent)
                    .padding(.horizontal, 10).padding(.vertical, 4).background(SW.blush, in: Capsule())
            }
            Text(L10n.f("walk_arrive_at", L10n.s(lower ? "route_lower_risk_title" : "route_direct_title"), arrive))
                .font(.swCaption).foregroundStyle(SW.ink2)
            Text(L10n.s("route_fine_print")).font(.swCaption).foregroundStyle(SW.ink2)
            HStack(spacing: 12) {
                Call911Button().layoutPriority(1)
                Button(L10n.s("end")) { model.endWalking() }.buttonStyle(QuietButtonStyle()).frame(width: 110)
            }
            .padding(.top, 16)
        }
    }

    private var offRouteSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            SheetTitle(title: L10n.s("off_route_heading"), subtitle: L10n.s("off_route_ask"))
            HStack(spacing: 12) {
                Button(L10n.s("off_route_yes")) { model.rerouteFromHere() }.buttonStyle(CoralButtonStyle())
                Button(L10n.s("location_not_now")) { session.dismissOffRoute() }.buttonStyle(QuietButtonStyle())
            }
            .padding(.top, 8)
            Call911Button()
        }
    }

    private var arrivedSheet: some View {
        let end = session.arrivedAt ?? Date()
        let minutes = max(1, Int(end.timeIntervalSince(session.startedAt) / 60))
        return VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 16) {
                Image(systemName: "checkmark").font(.system(size: 24, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 56, height: 56).background(SW.success, in: Circle())
                    .accessibilityHidden(true)
                SheetTitle(title: L10n.s("arrived"), subtitle: model.end?.title)
            }
            HStack(spacing: 12) {
                stat(model.units.format(session.route.lengthMetres), L10n.s("arrived_walked"))
                stat("\(minutes) min", L10n.s("arrived_on_foot"))
                stat(end.formatted(date: .omitted, time: .shortened), L10n.s("arrived_at"))
            }
            Button(L10n.s("back_to_map")) { model.endWalking() }.buttonStyle(CoralButtonStyle())
        }
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.jakarta(17, .semibold, relativeTo: .headline)).foregroundStyle(SW.ink)
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.swCaption).foregroundStyle(SW.ink2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(SW.blushSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func icon(_ m: TurnStep.Maneuver) -> String {
        switch m {
        case .start, .straight: return "arrow.up"
        case .left: return "arrow.turn.up.left"
        case .right: return "arrow.turn.up.right"
        case .uTurn: return "arrow.uturn.down"
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
