import SwiftUI

@main
@MainActor
struct Watch2PhoneApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = BridgeViewModel(transport: CoreBluetoothTransport())

    var body: some Scene {
        WindowGroup {
            BridgeView(model: model)
                .task { model.activate() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { model.setForeground(false) }
                    if phase == .active { model.setForeground(true) }
                }
        }
    }
}
