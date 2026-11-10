# watch2phone GATT v1

| Attribute | UUID | Properties |
| --- | --- | --- |
| Primary service | `ade40601-99c2-4635-b4d5-376c15c67a7e` | Advertised as a 128-bit service UUID |
| Request | `ade40602-99c2-4635-b4d5-376c15c67a7e` | Write with response |
| Reply | `ade40603-99c2-4635-b4d5-376c15c67a7e` | Read |
| Watch battery (optional) | `ade40604-99c2-4635-b4d5-376c15c67a7e` | Read |
| Notification relay control (opt-in) | `ade40605-99c2-4635-b4d5-376c15c67a7e` | Encrypted write with response |

Each request or reply operation carries exactly one frame.

| Byte offset | Meaning |
| --- | --- |
| 0 | Version, `01` |
| 1 | Opcode, `01` for request or `02` for reply |
| 2–3 | Unsigned 16-bit transaction identifier, big-endian |
| 4–19 | One to 16 bytes of strictly valid UTF-8 test text |

Frames are 5–20 bytes. The client sends one request at a time. The server validates the write, stages a reply with the same transaction identifier and original payload bytes, then acknowledges the write. The client reads the reply after acknowledgment and verifies opcode, identifier and payload bytes. Transaction identifiers may wrap.

The server rejects invalid frames, wrong-direction operations, prepared writes and nonzero offsets. A reply read before a valid write fails. Successful reads leave the reply available to the same peer until replacement or disconnect. Long reads, fragmentation and subscriptions are outside v1. An echo failure or timeout requires reconnecting before retrying. UUIDs and transaction identifiers provide routing and correlation. Use disposable text because the service does not enforce authentication or encryption.

The optional battery characteristic returns one unsigned byte from 0 to 100, representing the watch's current charge in percent. It is read at offset zero without a preceding echo write. The first successful battery read or valid echo write selects the session's peer. Older watch app versions without the optional characteristic still support the echo exchange. Signal strength is measured locally by the central and does not use a GATT characteristic.

When the watch user enables sharing, the relay control accepts one byte at offset zero: `01` starts ANCS for the active peer and `00` stops it. An accepted start can also select the session's peer. The control carries no notification data. The watch reads notification previews from Apple's separately authorized [ANCS service](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html).

Both host test suites load [shared wire vectors](vectors.tsv). Example request for transaction `0x1234` with `Ping` is `0101123450696e67`, and the expected reply is `0102123450696e67`.
