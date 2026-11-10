public enum LinkState: String, Equatable {
    case idle, connecting, discovering, ready, writing, reading, disconnecting

    public enum Event {
        case connect, connected, discovered, send, written, reply, cancel, ended
    }

    @discardableResult
    public mutating func apply(_ event: Event) -> Bool {
        switch (self, event) {
        case (.idle, .connect): self = .connecting
        case (.connecting, .connected): self = .discovering
        case (.discovering, .discovered), (.reading, .reply): self = .ready
        case (.ready, .send): self = .writing
        case (.writing, .written): self = .reading
        case (.idle, .cancel), (.disconnecting, .cancel): return false
        case (_, .cancel): self = .disconnecting
        case (.idle, .ended): return false
        case (_, .ended): self = .idle
        default: return false
        }
        return true
    }
}
