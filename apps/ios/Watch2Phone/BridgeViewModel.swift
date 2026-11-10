import Foundation
import SwiftUI

struct Diagnostic: Identifiable {
    let id = UUID()
    let time = Date()
    let message: String
    let isError: Bool
}

@MainActor
final class BridgeViewModel: ObservableObject {
    @Published private(set) var radioAvailable = false
    @Published private(set) var radioStatus = "Starting Bluetooth…"
    @Published private(set) var scanning = false
    @Published private(set) var link: LinkState = .idle
    @Published private(set) var devices: [NearbyDevice] = []
    @Published private(set) var watchBattery: Int?
    @Published private(set) var signalStrength: Int?
    @Published private(set) var notificationAuthorization: Bool?
    @Published private(set) var response: String?
    @Published private(set) var error: String?
    @Published private(set) var diagnostics: [Diagnostic] = []
    @Published var bridgeOnly = true
    @Published var shareNotifications = false
    @Published var text = "Ping"

    private let transport: any BridgeTransport
    private var transaction: UInt16 = 0
    private var pending: Frame?

    init(transport: any BridgeTransport) {
        self.transport = transport
        transport.onEvent = { [weak self] event in self?.receive(event) }
    }

    var canSend: Bool {
        link == .ready && (1...Frame.maximumTextBytes).contains(text.utf8.count)
    }

    func activate() { transport.activate() }

    func toggleScan() {
        if scanning {
            transport.stopScan()
        } else {
            error = nil
            transport.startScan(bridgeOnly: bridgeOnly)
        }
    }

    func connect(_ id: UUID) {
        error = nil
        response = nil
        transport.connect(to: id, shareNotifications: shareNotifications)
    }

    func send() {
        guard canSend, let frame = Frame(request: text, transaction: transaction) else { return }
        transaction &+= 1
        pending = frame
        error = nil
        response = nil
        transport.send(frame)
    }

    func disconnect() { transport.disconnect() }

    func setForeground(_ foreground: Bool) { transport.setForeground(foreground) }

    private func receive(_ event: TransportEvent) {
        switch event {
        case let .radio(available, status):
            radioAvailable = available
            radioStatus = status
        case let .scan(active):
            scanning = active
        case let .devices(devices):
            self.devices = devices
        case let .state(state):
            link = state
            if state == .idle || state == .disconnecting {
                pending = nil
                watchBattery = nil
                signalStrength = nil
            }
        case let .batteryLevel(level):
            watchBattery = level
        case let .signalStrength(rssi):
            signalStrength = rssi
        case let .notificationAuthorization(authorized):
            notificationAuthorization = authorized
        case let .frame(frame):
            guard let pending, frame.answers(pending) else {
                record("Reply did not match the pending test request. Reconnect to retry.", isError: true)
                transport.disconnect()
                return
            }
            self.pending = nil
            response = frame.text
            record("Verified reply for test transaction #\(frame.transaction).", isError: false)
        case let .diagnostic(message, isError):
            record(message, isError: isError)
        }
    }

    private func record(_ message: String, isError: Bool) {
        if isError { error = message }
        diagnostics.insert(Diagnostic(message: message, isError: isError), at: 0)
        if diagnostics.count > 100 { diagnostics.removeLast() }
    }
}
