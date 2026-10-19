import CoreLocation
import SwiftUI

struct RootView: View {
    @ObservedObject var model: AppModel
    @AppStorage("onboarding.done") private var onboarded = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if !onboarded {
            OnboardingView(model: model) { onboarded = true }
        } else {
            ZStack {
                SafeWalkMapView(model: model, dark: scheme == .dark)
                    .ignoresSafeArea()
                switch model.screen {
                case .map: MapHomeView(model: model)
                case .preview: RoutePreviewView(model: model)
                case .walking:
                    if let w = model.walk { WalkingView(model: model, session: w) }
                }
                VStack {
                    Spacer()
                    AttributionText()
                        .padding(.bottom, model.screen == .map ? 4 : 0)
                }
                .allowsHitTesting(false)
            }
            .overlay {
                if case .loading = model.loadState {
                    ProgressView(L10n.s("loading_data")).padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }
}

/// Required map credit, always on screen (spec 01 §"Free services").
struct AttributionText: View {
    var body: some View {
        Text("© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors")
            .font(.system(size: 9))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(.thinMaterial, in: Capsule())
            .accessibilityHidden(true)
    }
}

// MARK: - Map (home)

struct MapHomeView: View {
    @ObservedObject var model: AppModel
    @State private var searching = false
    @State private var showSettings = false

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Button { searching = true } label: {
                    HStack {
                        Image(systemName: "magnifyingglass")
                        Text(L10n.searchHint)
                        Spacer()
                    }
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                }
                .accessibilityLabel(L10n.searchHint)
                Button { showSettings = true } label: {
                    Image(systemName: "gearshape").font(.title3)
                        .frame(width: 46, height: 46)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                }
                .accessibilityLabel(L10n.s("settings"))
            }
            if model.userOutsideCoverage {
                Notice(text: L10n.s("outside_notice"), icon: "info.circle")
            }
            if model.forecastIsStale {
                Notice(text: L10n.s("forecast_stale"), icon: "exclamationmark.triangle")
            }
            if model.appTooOld {
                Notice(text: L10n.updateApp, icon: "arrow.down.app")
            }
            if let m = model.routeMessage {
                Notice(text: m, icon: "info.circle")
            }
            Spacer()
            HStack(alignment: .bottom) {
                if let pin = model.droppedPin { PinActions(model: model, coordinate: pin) }
                Spacer()
                Button { model.recenter() } label: {
                    Image(systemName: "location.fill").font(.title3)
                        .frame(width: 48, height: 48)
                        .background(.regularMaterial, in: Circle())
                }
                .accessibilityLabel(L10n.s("search_my_location"))
            }
            .padding(.bottom, 18)
        }
        .padding(.horizontal)
        .sheet(isPresented: $searching) { SearchView(model: model, purpose: .destination) }
        .sheet(isPresented: $showSettings) { SettingsView(model: model) }
    }
}

struct Notice: View {
    let text: String
    let icon: String
    var body: some View {
        Label(text, systemImage: icon)
            .font(.footnote)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// "Walk here" / "Start here" after a long-press.
struct PinActions: View {
    @ObservedObject var model: AppModel
    let coordinate: CLLocationCoordinate2D

    var body: some View {
        HStack(spacing: 8) {
            Button {
                model.droppedPin = nil
                model.planWalk(to: model.pinPlace(coordinate))
            } label: { Label(L10n.s("pin_walk_here"), systemImage: "figure.walk") }
                .buttonStyle(.borderedProminent)
            Button {
                model.droppedPin = nil
                model.setStart(model.pinPlace(coordinate))
            } label: { Text(L10n.s("pin_start_here")) }
                .buttonStyle(.bordered)
            Button { model.droppedPin = nil } label: { Image(systemName: "xmark") }
                .buttonStyle(.bordered)
                .accessibilityLabel(L10n.s("done"))
        }
        .padding(8)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}
