# watch2phone GATT v1

| Attribute | UUID | Properties |
| --- | --- | --- |
| Primary service | `ade40601-99c2-4635-b4d5-376c15c67a7e` | Advertised as a 128-bit service UUID |
| Request | `ade40602-99c2-4635-b4d5-376c15c67a7e` | Write with response |
| Reply | `ade40603-99c2-4635-b4d5-376c15c67a7e` | Read |

Each characteristic operation carries exactly one frame.

| Byte offset | Meaning |
| --- | --- |
| 0 | Version, `01` |
| 1 | Opcode, `01` for request or `02` for reply |
| 2–3 | Unsigned 16-bit transaction identifier, big-endian |
| 4–19 | One to 16 bytes of strictly valid UTF-8 test text |

Frames are 5–20 bytes. The client sends one request at a time. The server validates the write, stages a reply with the same transaction identifier and original payload bytes, then acknowledges the write. The client reads the reply after acknowledgment and verifies opcode, identifier and payload bytes. Transaction identifiers may wrap.

The server rejects invalid frames, wrong-direction operations, prepared writes and nonzero offsets. A read before a valid write fails. Successful reads leave the reply available to the same peer until replacement or disconnect. Long reads, fragmentation and subscriptions are outside v1. An exchange failure or timeout requires reconnecting before retrying. UUIDs and transaction identifiers provide routing and correlation. Use disposable text because the service does not enforce authentication or encryption.

Both host test suites load [shared wire vectors](vectors.tsv). Example request for transaction `0x1234` with `Ping` is `0101123450696e67`, and the expected reply is `0102123450696e67`.
