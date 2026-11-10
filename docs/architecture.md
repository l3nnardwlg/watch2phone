# Architecture

The first transport is foreground BLE between two installed apps. The iPhone is the GAP central and GATT client. The watch is the GAP peripheral and GATT server. The watch publishes its service before advertising. The phone discovers the service and both characteristics, writes a test frame with response, then reads the reply.

- **iPhone** — SwiftUI observes the view model, which owns UI state, the bounded diagnostic log and the test exchange. The Core Bluetooth adapter translates discovery and GATT callbacks into session events. The adapter runs on the main queue. A Swift package contains the codec and connection state transitions for host tests.
- **Wear OS** — A Compose screen observes the BLE controller through a lifecycle-aware state flow. The controller starts a GATT server for an explicitly requested session, posts callbacks onto the main thread and ignores events from stopped sessions. The first valid bridge write selects the active peer. The controller stops advertising during the exchange, clears the peer's response on disconnect and advertises again. Leaving the activity closes the session.
- **Protocol** — The [versioned specification](../packages/protocol/README.md) and shared test vectors define the boundary. The two codecs have no Bluetooth framework dependency.

There is no server, account or runtime network dependency. The UI does not interpret Samsung-specific advertisements or GATT profiles. A future hardware or role adapter can implement the phone transport interface and reuse the codec where applicable. Add battery, status, notification, health and configuration features above the session boundary after establishing capability negotiation and peer verification. Keep reconnect policy separate from message encoding.

The v1 service is unauthenticated. Every peer is untrusted and the payload is only for disposable test text. A matching UUID or successful echo cannot establish identity. Require explicit peer verification, authenticated encryption and feature-specific consent before handling personal data.
