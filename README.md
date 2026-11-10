# watch2phone

**Your Watch. Any Phone.**

watch2phone is an open-source project exploring how smartwatches and phones from different ecosystems can exchange data directly. The first target is an already configured Samsung Galaxy Watch running Wear OS and an iPhone.

## Phase 1

The iPhone app scans for nearby BLE devices and displays their name, local identifier and RSSI when available. It connects to devices advertising the watch2phone service and discovers the request and reply characteristics. The watch app publishes the service and echoes short UTF-8 test messages. Both apps expose session status, and the phone shows replies, errors and a bounded Bluetooth log.

While connected, the iPhone shows the watch battery in percent and the current Bluetooth signal strength in dBm. The app checks both values about every five seconds while the connection is ready.

### iPhone notification sharing

1. On the watch, enable **iPhone notifications**, tap **Start**, and grant the watch app notification permission if asked.
2. On the iPhone, enable **Share iPhone notifications** in watch2phone, then scan and connect. Confirm Bluetooth pairing and allow iOS to share notifications with the watch.
3. Generate a new iPhone notification. The watch app should report **iPhone notifications are on**, and the new notification should appear in Wear OS.

The relay uses Apple's ANCS service. It forwards new titles and message previews during the session. Tap **Stop** in the watch app or in its ongoing session notification to end sharing. The watch removes the previews when sharing ends. After a Bluetooth disconnect, reopen the iPhone app and connect again.

Both apps build, and the Swift and Kotlin protocol and state tests pass against the same wire vectors. A physical echo and reconnect passed on the Samsung SM-R930 and iPhone 18 Pro Max. [Compatibility and test status](docs/compatibility.md)

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

Use macOS with Xcode 16 or newer. For physical testing, use an iPhone running iOS 17 or newer.

Open `apps/ios/Watch2Phone.xcodeproj` in Xcode, choose the **Watch2Phone** scheme and an iPhone simulator, then press **Run** (⌘R). The simulator build does not need an Apple development team. Use a physical iPhone and Wear OS watch to test Bluetooth.

For a physical iPhone, sign in under **Xcode → Settings → Accounts**. In the **Watch2Phone** target, open **Signing & Capabilities**, enable **Automatically manage signing**, and select your team (or **Personal Team**). Change the bundle identifier if Xcode reports that it is unavailable. Then select your iPhone and press **Run**.

From the repository root, build without signing and run the portable Swift tests with the following commands.

```sh
xcodebuild -project apps/ios/Watch2Phone.xcodeproj -scheme Watch2Phone \
  -configuration Debug -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
swift test --package-path apps/ios/BridgeCore
```

### Wear OS

Use JDK 17, Android SDK Platform 35, Build Tools 34.0.0 and Platform Tools. The app targets API 35 and installs on Wear OS devices running Android API 30 or later. Open `apps/wearos` in Android Studio to configure the SDK, or set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the SDK location in your terminal. On macOS, Android Studio normally installs the SDK at `~/Library/Android/sdk`.

```sh
cd apps/wearos
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
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

The echo test remains an unauthenticated app-to-app exchange for disposable messages. Notification sharing requires a separate opt-in on both apps, an encrypted Bluetooth link, and iOS authorization. Native Samsung setup, background restoration, health data, system synchronization and Apple Watch support remain outside the current scope.

## Contributing

Run the checks above before opening a pull request. Include reproducible steps for Bluetooth reports and record model-specific results in [the compatibility matrix](docs/compatibility.md). Update the protocol specification and shared vectors when changing the wire format. Keep credentials, signing material and personal data out of commits and logs.

[MIT license](LICENSE) · [Third-party dependencies and licenses](docs/dependencies.md)
