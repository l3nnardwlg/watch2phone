# Compatibility and verification

| Check | Status |
| --- | --- |
| Wear OS debug APK and lint | Passed on macOS and Windows with Android SDK Platform 35 and Gradle 8.10.2. |
| Kotlin protocol and peripheral state tests | Passed on the host JVM. |
| Swift protocol and link state tests | Passed on macOS and Windows. |
| iPhone app build | Unsigned device build and simulator build passed with Xcode 27.2. App launched on an iPhone 18 Pro simulator. |
| Physical discovery, GATT discovery, echo and reconnect | Pending. No physical watch and iPhone were available for the implementation check. |

| Hardware target | Prerequisite | Result |
| --- | --- | --- |
| Samsung Galaxy Watch4–8 series and newer Wear OS Galaxy Watch models with iPhone | Watch already configured and capable of connectable BLE advertising. iPhone on iOS 17 or later. | Pending per model and firmware. |
| Xiaomi and other Wear OS watches with iPhone | Android API 30 or later, BLE advertising and GATT server support. | Planned. |
| Apple Watch with Android | Public API feasibility investigation. | Future research. |

For each run, record the watch model and firmware, iPhone model and iOS version, app build revisions, existing watch setup, and the following observations.

- Advertiser capability and start result, including any Android error code.
- Service UUID discovery with the iPhone filter enabled. Try the unfiltered scan as a diagnostic when needed.
- Connection and GATT characteristic discovery. Confirm that the iPhone reaches **Ready** and both devices display the exact echoed text.
- A second exchange, a 16-byte message, and a disconnect/reconnect round.
- Behavior when a Bluetooth radio turns off or when a user stops or leaves the watch app. Reopen the watch app and start a new session to retry.

If the watch cannot advertise, capture the failure before testing the [role-reversed transport hypothesis](research.md#open-hardware-questions). If reconnect fails before any test message was accepted, stop and restart watch advertising and record that distinction. A device's appearance in an unfiltered scan alone does not verify bridge support.
