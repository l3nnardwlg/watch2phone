import Foundation
import XCTest
@testable import BridgeCore

final class BridgeCoreTests: XCTestCase {
    func testSharedWireVectors() throws {
        let root = (0..<6).reduce(URL(fileURLWithPath: #filePath)) { url, _ in
            url.deletingLastPathComponent()
        }
        let file = root.appendingPathComponent("packages/protocol/vectors.tsv")
        let rows = try String(contentsOf: file, encoding: .utf8).split(whereSeparator: \.isNewline)
        XCTAssertGreaterThan(rows.count, 0)
        for row in rows {
            let fields = row.split(separator: "\t")
            let hex = Array(fields[1])
            let bytes = try Data(stride(from: 0, to: hex.count, by: 2).map { index in
                try XCTUnwrap(UInt8(String(hex[index...index + 1]), radix: 16))
            })
            if fields[0] == "invalid" {
                XCTAssertNil(Frame(decoding: bytes), String(row))
                continue
            }
            let frame = try XCTUnwrap(Frame(decoding: bytes), String(row))
            XCTAssertEqual(frame.opcode, fields[0] == "request" ? .request : .reply)
            XCTAssertEqual(frame.transaction, UInt16(fields[2]))
            XCTAssertEqual(frame.text, String(fields[3]))
            XCTAssertEqual(frame.encoded, bytes)
            if frame.opcode == .request {
                XCTAssertEqual(Frame(request: frame.text, transaction: frame.transaction)?.encoded, bytes)
            }
        }
    }

    func testReplyMatchesDirectionTransactionAndBytes() throws {
        let request = try XCTUnwrap(Frame(request: "Ping", transaction: 1))
        XCTAssertFalse(request.answers(request))
        XCTAssertTrue(try XCTUnwrap(Frame(decoding: Data([1, 2, 0, 1] + Array("Ping".utf8)))).answers(request))
        XCTAssertFalse(try XCTUnwrap(Frame(decoding: Data([1, 2, 0, 2] + Array("Ping".utf8)))).answers(request))
        XCTAssertFalse(try XCTUnwrap(Frame(decoding: Data([1, 2, 0, 1] + Array("Pong".utf8)))).answers(request))
        XCTAssertNil(Frame(request: "", transaction: 1))
        XCTAssertNil(Frame(request: String(repeating: "a", count: 17), transaction: 1))
    }

    func testExchangeAndCancellationIgnoreOutOfOrderCallbacks() {
        var state = LinkState.idle
        XCTAssertFalse(state.apply(.connected))
        for event: LinkState.Event in [.connect, .connected, .discovered, .send, .written, .reply] {
            XCTAssertTrue(state.apply(event))
        }
        XCTAssertEqual(state, .ready)
        XCTAssertTrue(state.apply(.send))
        XCTAssertTrue(state.apply(.cancel))
        XCTAssertFalse(state.apply(.written))
        XCTAssertFalse(state.apply(.connect))
        XCTAssertTrue(state.apply(.ended))
        XCTAssertTrue(state.apply(.connect))
    }
}
