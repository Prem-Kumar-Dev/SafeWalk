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
            .font(.jakarta(9, .medium, relativeTo: .caption2))
            .foregroundStyle(SW.ink2)
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
            HStack(spacing: 10) {
                Button { searching = true } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "magnifyingglass").font(.system(size: 16, weight: .semibold)).foregroundStyle(SW.accent)
                        Text(L10n.searchHint).font(.swBody).foregroundStyle(SW.ink2)
                        Spacer()
                    }
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .frame(minHeight: 52)
                    .background(SW.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.searchHint)
                Button { showSettings = true } label: {
                    Image(systemName: "slider.horizontal.3").font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(SW.ink)
                        .frame(width: 52, height: 52)
                        .background(SW.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
                }
                .buttonStyle(.plain)
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
            if model.mapOffline {
                Notice(text: L10n.s("map_offline"), icon: "wifi.slash")
            }
            if case .failed(let why) = model.loadState {
                Notice(text: L10n.f("data_failed", why), icon: "exclamationmark.triangle")
            }
            if let m = model.routeMessage {
                Notice(text: m, icon: "info.circle")
            }
            Spacer()
            HStack {
                Spacer()
                Button { model.recenter() } label: {
                    Image(systemName: "location.fill").font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(SW.accent)
                        .frame(width: 52, height: 52)
                        .background(SW.card, in: Circle())
                        .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.s("search_my_location"))
            }
            .padding(.bottom, model.droppedPin == nil ? 18 : 4)
        }
        .padding(.horizontal)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let pin = model.droppedPin {
                PinActions(model: model, coordinate: pin)
            } else if !model.recents.isEmpty {
                RecentSheet(model: model)
            }
        }
        .sheet(isPresented: $searching) { SearchView(model: model, purpose: .destination) }
        .sheet(isPresented: $showSettings, onDismiss: { model.updateCheckMessage = nil }) { SettingsView(model: model) }
    }
}

struct Notice: View {
    let text: String
    let icon: String
    var body: some View {
        Label(text, systemImage: icon)
            .font(.jakarta(13, .medium, relativeTo: .footnote))
            .foregroundStyle(SW.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(SW.blush, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// "Walk here" / "Start here" after a long-press.
struct PinActions: View {
    @ObservedObject var model: AppModel
    let coordinate: CLLocationCoordinate2D

    var body: some View {
        BottomCard {
            HStack(alignment: .top) {
                SheetTitle(title: L10n.s("search_dropped_pin"))
                Spacer()
                Button { model.droppedPin = nil } label: {
                    Image(systemName: "xmark").font(.system(size: 14, weight: .bold)).foregroundStyle(SW.ink2)
                        .frame(width: 32, height: 32).background(SW.sand, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.s("done"))
            }
            HStack(spacing: 12) {
                Button {
                    model.droppedPin = nil
                    model.planWalk(to: model.pinPlace(coordinate))
                } label: { Label(L10n.s("pin_walk_here"), systemImage: "figure.walk") }
                    .buttonStyle(CoralButtonStyle())
                Button {
                    model.droppedPin = nil
                    model.setStart(model.pinPlace(coordinate))
                } label: { Text(L10n.s("pin_start_here")) }
                    .buttonStyle(QuietButtonStyle())
            }
            .padding(.top, 20)
        }
    }
}

/// Up to three recent places under the map; tapping one plans a walk there.
struct RecentSheet: View {
    @ObservedObject var model: AppModel

    var body: some View {
        BottomCard {
            Text(L10n.s("search_recent")).font(.swSection).foregroundStyle(SW.ink2)
            ForEach(model.recents.prefix(3)) { p in
                Button { model.planWalk(to: p) } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "clock").font(.system(size: 14, weight: .semibold)).foregroundStyle(SW.ink2)
                            .frame(width: 36, height: 36).background(SW.sand, in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.title).font(.swBody).foregroundStyle(SW.ink).lineLimit(1)
                            if let sub = p.subtitle, !sub.isEmpty {
                                Text(sub).font(.swCaption).foregroundStyle(SW.ink2).lineLimit(1)
                            }
                        }
                        Spacer()
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }
}
