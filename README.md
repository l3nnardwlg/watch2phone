# watch2phone

**Your Watch. Any Phone.**

watch2phone is an open-source project exploring how smartwatches and phones from different ecosystems can exchange data directly. The first target is an already configured Samsung Galaxy Watch running Wear OS and an iPhone.

## Phase 1

The iPhone app scans for nearby BLE devices and displays their name, local identifier and RSSI when available. It connects to devices advertising the watch2phone service and discovers the request and reply characteristics. The watch app publishes the service and echoes short UTF-8 test messages. Both apps expose session status, and the phone shows replies, errors and a bounded Bluetooth log.

The Wear OS debug APK and Android lint passed on Windows. The Swift and Kotlin protocol and state tests passed against the same wire vectors. **An Xcode build of the iPhone app and a physical watch-to-iPhone exchange are pending.** [Compatibility and test status](docs/compatibility.md)

## Device targets

| Device | Status |
| --- | --- |
| Galaxy Watch4 and newer Wear OS Galaxy Watch models | First test targets. Use an already configured watch. Model and firmware compatibility are unverified. |
| Other Wear OS watches, including Xiaomi Wear OS models | Planned validation where GATT server and peripheral advertising are available. |
| Apple Watch and Android | Future feasibility research. |

## Architecture

SwiftUI and Core Bluetooth on the iPhone, Jetpack Compose for Wear OS and Android Bluetooth APIs on the watch. The phone acts as the BLE central and GATT client. The watch acts as the peripheral and GATT server. A shared wire specification defines the exchange. No server, account or cloud service is required by watch2phone.

- [Architecture](docs/architecture.md)
- [Research and reference implementations](docs/research.md)
- [GATT protocol v1](packages/protocol/README.md)
- [Roadmap](docs/roadmap.md)

## Build and install

### iPhone

Use macOS with Xcode 16 or newer and an iPhone running iOS 17 or newer.

1. Open `apps/ios/Watch2Phone.xcodeproj` in Xcode.
2. Choose the **Watch2Phone** target, enable automatic signing, and select your development team. Use a unique bundle identifier if needed.
3. Select your physical iPhone as the destination and run the app. Follow the device prompts for development deployment and Bluetooth access.

From the repository root, build without signing and run the portable Swift tests with the following commands.

```sh
xcodebuild -project apps/ios/Watch2Phone.xcodeproj -scheme Watch2Phone \
  -configuration Debug -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
swift test --package-path apps/ios/BridgeCore
```

### Wear OS

Use Android Studio or JDK 17, Android SDK Platform 35, Build Tools 34.0.0 and Platform Tools. The app targets API 35 and installs on Wear OS devices running Android API 30 or later. For the command line, set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK location.

```sh
cd apps/wearos
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows PowerShell, use `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` from `apps/wearos`.

Enable Developer options and Wireless debugging on the watch, then pair **ADB** using the address and pairing port shown on the watch. Use its separate debugging port for `adb connect`. [Android's Wear OS debugging guide](https://developer.android.com/training/wearables/get-started/debugging)

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEBUGGING_PORT
adb devices -l
adb -s WATCH_IP:DEBUGGING_PORT install -r app/build/outputs/apk/debug/app-debug.apk
```

## Physical Bluetooth test

1. Enable Bluetooth on both devices. Open watch2phone on the watch, tap **Start** and grant Nearby Devices access if prompted. Wait for **Discoverable**.
2. Open watch2phone on the iPhone. Grant Bluetooth access and tap **Scan** with **Bridge service only** enabled. Select **Connect** on the watch row and wait for **Ready**.
3. Send the default `Ping` or other disposable text of up to 16 UTF-8 bytes. Confirm that the watch displays the echo and the iPhone displays the exact text under **Watch reply**. The iPhone log should report a verified transaction. Repeat with different text.
4. Tap **Disconnect** on the iPhone. Wait for **Discoverable** on the watch, then scan again, reconnect and repeat the exchange. Tap **Stop** on the watch to end the test.
5. Record the devices, OS versions, watch firmware, both app versions, and observed results. For failures, retain the watch status and iPhone log with device identifiers redacted. [Test matrix and fallback investigation](docs/compatibility.md)

## Limitations

Keep both apps open for the experiment. The phone scans for up to 15 seconds at a time. The watch advertises at low power for up to two minutes while waiting for a test exchange and restarts advertising after the active peer disconnects. Bluetooth hardware and firmware can restrict advertising.

This release covers an unauthenticated app-to-app connection for disposable messages. Native Samsung setup, iOS notification forwarding, background restoration, health data, system synchronization and Apple Watch support remain outside Phase 1.

## Contributing

Run the checks above before opening a pull request. Include reproducible steps for Bluetooth reports and record model-specific results in [the compatibility matrix](docs/compatibility.md). Update the protocol specification and shared vectors when changing the wire format. Keep credentials, signing material and personal data out of commits and logs.

[MIT license](LICENSE) · [Third-party dependencies and licenses](docs/dependencies.md)
