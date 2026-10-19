import SwiftUI

@main
struct SafeWalkApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() { SW.configureAppearance() }

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .font(.swBody)
                .tint(SW.accent)
                .task { await model.load() }
        }
        .onChange(of: scenePhase) { phase in
            // Spec 02: check the manifest on return to the foreground (rate-limited to 6 h in DataStore).
            if phase == .active, model.data != nil { Task { await model.checkForUpdate(force: false) } }
        }
    }
}
