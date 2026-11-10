import SwiftUI

@MainActor
struct BridgeView: View {
    @ObservedObject var model: BridgeViewModel

    var body: some View {
        NavigationStack {
            List {
                Section("Connection") {
                    Text(model.radioStatus)
                    LabeledContent("Session", value: model.link.rawValue.capitalized)
                    if model.link == .ready || model.link == .writing || model.link == .reading {
                        LabeledContent("Watch battery", value: model.watchBattery.map { "\($0)%" } ?? "—")
                        LabeledContent("Signal strength", value: model.signalStrength.map { "\($0) dBm" } ?? "—")
                    }
                    Toggle("Share iPhone notifications", isOn: $model.shareNotifications)
                        .disabled(model.link != .idle)
                    if let authorized = model.notificationAuthorization {
                        LabeledContent("Notification access", value: authorized ? "Allowed" : "Awaiting permission")
                    } else if model.shareNotifications && model.link == .idle {
                        Text("Enable iPhone notifications on the watch before connecting.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if let error = model.error {
                        Label(error, systemImage: "exclamationmark.circle")
                            .foregroundStyle(.red)
                    }
                    if model.link != .idle {
                        Button("Disconnect", role: .destructive, action: model.disconnect)
                            .disabled(model.link == .disconnecting)
                    }
                }
                Section("Nearby devices") {
                    Toggle("Bridge service only", isOn: $model.bridgeOnly)
                        .disabled(model.scanning || model.link != .idle)
                    Button(model.scanning ? "Stop Scan" : "Scan", action: model.toggleScan)
                        .disabled(!model.radioAvailable || model.link != .idle)
                    if model.devices.isEmpty {
                        Text(model.scanning ? "Looking for BLE advertisements…" : "Start the watch app, then scan.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.devices) { device in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(device.name).font(.headline)
                            Text(device.id.uuidString)
                                .font(.caption2.monospaced())
                                .textSelection(.enabled)
                            if let rssi = device.rssi {
                                Text("\(rssi) dBm").font(.caption).foregroundStyle(.secondary)
                            }
                            if device.compatible {
                                Button("Connect") { model.connect(device.id) }
                                    .buttonStyle(.borderless)
                                    .disabled(!model.radioAvailable || model.link != .idle)
                            }
                        }
                    }
                }
                Section {
                    TextField("Test message", text: $model.text)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Text("\(model.text.utf8.count) / 16 UTF-8 bytes")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Send Test Message", action: model.send)
                        .disabled(!model.canSend)
                    if let response = model.response {
                        LabeledContent("Watch reply", value: response)
                    }
                } header: {
                    Text("Echo test")
                } footer: {
                    Text("Use disposable text. The bridge does not authenticate or encrypt test messages.")
                }
                Section("Bluetooth log") {
                    ForEach(model.diagnostics) { entry in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(entry.time, format: .dateTime.hour().minute().second())
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            Text(entry.message)
                                .font(.footnote)
                                .foregroundStyle(entry.isError ? Color.red : Color.primary)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
            .navigationTitle("watch2phone")
        }
    }
}
