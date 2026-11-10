import Foundation

public struct Frame: Equatable {
    public static let serviceUUID = "ade40601-99c2-4635-b4d5-376c15c67a7e"
    public static let requestUUID = "ade40602-99c2-4635-b4d5-376c15c67a7e"
    public static let replyUUID = "ade40603-99c2-4635-b4d5-376c15c67a7e"
    public static let batteryUUID = "ade40604-99c2-4635-b4d5-376c15c67a7e"
    public static let relayUUID = "ade40605-99c2-4635-b4d5-376c15c67a7e"
    public static let maximumTextBytes = 16

    public enum Opcode: UInt8 {
        case request = 1
        case reply = 2
    }

    public let opcode: Opcode
    public let transaction: UInt16
    public let payload: Data

    public var text: String { String(decoding: payload, as: UTF8.self) }

    public init?(request text: String, transaction: UInt16) {
        let payload = Data(text.utf8)
        guard (1...Self.maximumTextBytes).contains(payload.count) else { return nil }
        self.opcode = .request
        self.transaction = transaction
        self.payload = payload
    }

    public init?(decoding data: Data) {
        guard (5...20).contains(data.count) else { return nil }
        let bytes = Array(data)
        let payload = Data(bytes.dropFirst(4))
        guard bytes[0] == 1, let opcode = Opcode(rawValue: bytes[1]),
              String(data: payload, encoding: .utf8) != nil else { return nil }
        self.opcode = opcode
        self.transaction = (UInt16(bytes[2]) << 8) | UInt16(bytes[3])
        self.payload = payload
    }

    public var encoded: Data {
        var bytes = Data([1, opcode.rawValue, UInt8(transaction >> 8), UInt8(truncatingIfNeeded: transaction)])
        bytes.append(payload)
        return bytes
    }

    public func answers(_ request: Frame) -> Bool {
        opcode == .reply && request.opcode == .request
            && transaction == request.transaction && payload == request.payload
    }
}
