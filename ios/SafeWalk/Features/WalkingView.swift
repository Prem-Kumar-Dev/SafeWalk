import SwiftUI

/// Walking mode (spec 01 §5, 03 §6).
struct WalkingView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var session: WalkSession
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 10) {
            instruction
            Spacer()
            HStack(alignment: .bottom) {
                Button {
                    // tel: opens the dialer with 911 filled in; the user still taps Call.
                    if let url = URL(string: "tel://911") { openURL(url) }
                } label: {
                    Label(L10n.s("call_911"), systemImage: "phone.fill")
                        .font(.headline)
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(Color.red, in: Capsule())
                        .foregroundStyle(.white)
                }
                .accessibilityLabel(L10n.s("call_911"))
                Spacer()
                Button { model.recenter() } label: {
                    Image(systemName: "location.north.line.fill").font(.title3)
                        .frame(width: 48, height: 48)
                        .background(.regularMaterial, in: Circle())
                }
                .accessibilityLabel(L10n.s("search_my_location"))
            }
            footer
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
        .alert(L10n.s("off_route_prompt"), isPresented: Binding(get: { session.offRoute }, set: { if !$0 { session.dismissOffRoute() } })) {
            Button(L10n.s("off_route_yes")) { model.rerouteFromHere() }
            Button(L10n.s("location_not_now"), role: .cancel) { session.dismissOffRoute() }
        }
        .alert(L10n.s("arrived"), isPresented: Binding(get: { session.arrived }, set: { _ in })) {
            Button(L10n.s("done")) { model.endWalking() }
        }
    }

    @ViewBuilder
    private var instruction: some View {
        if let step = session.nextStep ?? session.route.steps[safe: session.stepIndex] {
            instructionCard(step)
        }
    }

    private func instructionCard(_ step: TurnStep) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                Image(systemName: icon(step.maneuver)).font(.title)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.instruction(step)).font(.title3.weight(.semibold))
                    if let m = session.metresToNextStep {
                        Text(model.units.format(m)).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }

    private var footer: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(L10n.f("walk_left_to_go", model.units.format(session.remainingMetres), session.remainingMinutes))
                    .font(.headline.monospacedDigit())
                Text(L10n.s("route_fine_print")).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            Button(L10n.s("end")) { model.endWalking() }
                .buttonStyle(.borderedProminent)
                .tint(.secondary)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
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
