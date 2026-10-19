import SwiftUI

// Welcome, Settings, About, Privacy and the disclaimer (spec 01 §1, §6–8).

struct OnboardingView: View {
    @ObservedObject var model: AppModel
    let finish: () -> Void
    @State private var page = 0

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            switch page {
            case 0: welcome("point.topleft.down.to.point.bottomright.curvepath", L10n.welcome1)
            case 1: welcome("arrow.triangle.branch", L10n.welcome2)
            case 2: welcome("iphone", L10n.welcome3)
            case 3:
                DisclaimerCard()
            default:
                VStack(spacing: 16) {
                    Image(systemName: "location.circle").font(.system(size: 56)).foregroundStyle(Color.accentColor)
                    Text(L10n.s("location_reason")).font(.title3).multilineTextAlignment(.center)
                }
            }
            Spacer()
            bottomButtons
        }
        .padding(24)
    }

    @ViewBuilder
    private var bottomButtons: some View {
        switch page {
        case 0...2:
            Button(L10n.s("next")) { page += 1 }.buttonStyle(.borderedProminent).controlSize(.large)
        case 3:
            Button(L10n.disclaimerAccept) {
                UserDefaults.standard.set(true, forKey: "disclaimer.accepted")
                page += 1
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
        default:
            VStack(spacing: 12) {
                Button(L10n.s("location_allow")) {
                    model.location.requestPermission()
                    finish()
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
                Button(L10n.s("location_not_now")) { finish() }
            }
        }
    }

    private func welcome(_ symbol: String, _ text: String) -> some View {
        VStack(spacing: 20) {
            Image(systemName: symbol).font(.system(size: 64)).foregroundStyle(Color.accentColor)
                .accessibilityHidden(true)
            Text(text).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
        }
    }
}

struct DisclaimerCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(L10n.s("disclaimer"), systemImage: "exclamationmark.shield").font(.headline)
            Text(L10n.disclaimerBody)
        }
        .padding(20)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(L10n.s("route_preference"), selection: $model.preference) {
                        ForEach(RoutePreference.allCases) { Text($0.label).tag($0) }
                    }
                    Picker(L10n.s("settings_units"), selection: $model.units) {
                        Text(L10n.s("units_km")).tag(Units.km)
                        Text(L10n.s("units_miles")).tag(Units.miles)
                    }
                    Button(L10n.s("settings_clear_recent"), role: .destructive) { model.clearRecents() }
                        .disabled(model.recents.isEmpty)
                }
                Section(L10n.s("settings_data")) {
                    if let week = model.forecastWeekLabel {
                        Text(L10n.f("data_status", week, updatedText))
                    }
                    if model.appTooOld { Text(L10n.updateApp).foregroundStyle(.secondary) }
                    Button(model.checkingForUpdate ? L10n.s("data_checking") : L10n.s("data_check")) {
                        Task { await model.checkForUpdate(force: true) }
                    }
                    .disabled(model.checkingForUpdate)
                }
                Section {
                    NavigationLink(L10n.s("about")) { AboutView(model: model) }
                    NavigationLink(L10n.s("privacy")) { TextPage(title: L10n.s("privacy"), text: L10n.s("privacy_body")) }
                    NavigationLink(L10n.s("disclaimer")) { TextPage(title: L10n.s("disclaimer"), text: L10n.disclaimerBody) }
                }
            }
            .navigationTitle(L10n.s("settings"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L10n.s("done")) { dismiss() } } }
        }
    }

    private var updatedText: String {
        guard let d = model.lastUpdated else { return "—" }
        return RelativeDateTimeFormatter().localizedString(for: d, relativeTo: Date())
    }
}

struct AboutView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L10n.s("about_body"))
                Text(L10n.s("about_sources")).font(.headline)
                Text(L10n.s("about_sources_body")).font(.subheadline)
                if let m = model.data?.manifest.model, let week = model.forecastWeekLabel {
                    Text(L10n.f("about_model", m.version, week)).font(.footnote).foregroundStyle(.secondary)
                }
                Link(L10n.s("about_feedback"), destination: AppInfo.feedbackURL)
            }
            .padding()
        }
        .navigationTitle(L10n.s("about"))
    }
}

struct TextPage: View {
    let title: String
    let text: String
    var body: some View {
        ScrollView { Text(text).padding().frame(maxWidth: .infinity, alignment: .leading) }
            .navigationTitle(title)
    }
}

enum AppInfo {
    /// Replace with your support address before release.
    static let feedbackURL = URL(string: "mailto:feedback@example.com?subject=SafeWalk")!
}
