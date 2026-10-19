import SwiftUI

// Welcome, Settings, About, Privacy and the disclaimer (spec 01 §1, §6–8).

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section(header: header(L10n.s("section_routes"))) {
                    Picker(selection: $model.preference) {
                        ForEach(RoutePreference.allCases) { Text($0.label).tag($0) }
                    } label: { row("point.topleft.down.to.point.bottomright.curvepath", L10n.s("route_preference")) }
                    Picker(selection: $model.units) {
                        Text(L10n.s("units_km")).tag(Units.km)
                        Text(L10n.s("units_miles")).tag(Units.miles)
                    } label: { row("ruler", L10n.s("settings_units")) }
                }
                Section(header: header(L10n.s("section_phone"))) {
                    Button(role: .destructive) { model.clearRecents() } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "trash").font(.system(size: 15, weight: .semibold)).foregroundStyle(SW.emergency)
                                .frame(width: 34, height: 34)
                                .background(SW.blush, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Text(L10n.s("settings_clear_recent")).foregroundStyle(SW.emergency)
                        }
                    }
                    .disabled(model.recents.isEmpty)
                }
                Section(header: header(L10n.s("settings_data"))) {
                    VStack(alignment: .leading, spacing: 14) {
                        if let week = model.forecastWeekLabel {
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                Circle().fill(model.appTooOld ? SW.amber : SW.success).frame(width: 10, height: 10)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.f("forecast_week", week)).font(.jakarta(15, .semibold))
                                    Text(updatedText).font(.swCaption).foregroundStyle(SW.ink2)
                                }
                            }
                        }
                        if model.appTooOld { Text(L10n.updateApp).font(.swCaption).foregroundStyle(SW.emergency) }
                        Button {
                            Task { await model.checkForUpdate(force: true) }
                        } label: {
                            Label(model.checkingForUpdate ? L10n.s("data_checking") : L10n.s("data_check"),
                                  systemImage: "arrow.clockwise")
                                .font(.jakarta(16, .semibold))
                                .foregroundStyle(SW.accent)
                                .frame(maxWidth: .infinity, minHeight: 46)
                                .overlay(Capsule().strokeBorder(SW.line, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .disabled(model.checkingForUpdate)
                    }
                    .padding(.vertical, 6)
                }
                Section(header: header(L10n.s("section_about"))) {
                    NavigationLink { AboutView(model: model) } label: { row("info.circle", L10n.s("about")) }
                    NavigationLink { TextPage(title: L10n.s("privacy"), text: L10n.s("privacy_body")) } label: {
                        row("lock", L10n.s("privacy"))
                    }
                    NavigationLink { TextPage(title: L10n.s("disclaimer"), text: L10n.disclaimerBody) } label: {
                        row("doc.text", L10n.s("disclaimer"))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(SW.paper)
            .font(.swBody)
            .navigationTitle(L10n.s("settings"))
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { dismiss() } label: {
                        HStack(spacing: 4) { Image(systemName: "chevron.left"); Text(L10n.s("map")) }
                            .font(.jakarta(16, .medium)).foregroundStyle(SW.accent)
                    }
                    .accessibilityLabel(L10n.s("done"))
                }
            }
        }
    }

    private func header(_ text: String) -> some View {
        Text(text.uppercased()).font(.jakarta(12, .semibold, relativeTo: .caption)).foregroundStyle(SW.ink2)
    }

    private func row(_ symbol: String, _ title: String) -> some View {
        HStack(spacing: 14) {
            IconWell(symbol: symbol)
            Text(title).foregroundStyle(SW.ink)
        }
    }

    private var updatedText: String {
        guard let d = model.lastUpdated else { return "—" }
        return L10n.f("data_updated", RelativeDateTimeFormatter().localizedString(for: d, relativeTo: Date()))
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
