import Foundation

struct NearbyDevice: Identifiable {
    let id: UUID
    let name: String
    let rssi: Int?
    let compatible: Bool
}

enum TransportEvent {
    case radio(Bool, String)
    case scan(Bool)
    case devices([NearbyDevice])
    case state(LinkState)
    case frame(Frame)
    case diagnostic(String, isError: Bool)
}

@MainActor
protocol BridgeTransport: AnyObject {
    var onEvent: ((TransportEvent) -> Void)? { get set }
    func activate()
    func startScan(bridgeOnly: Bool)
    func stopScan()
    func connect(to id: UUID)
    func send(_ frame: Frame)
    func disconnect()
}
