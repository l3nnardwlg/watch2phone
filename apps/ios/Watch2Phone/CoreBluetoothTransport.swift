import CoreBluetooth
import Foundation

@MainActor
final class CoreBluetoothTransport: NSObject, BridgeTransport {
    var onEvent: ((TransportEvent) -> Void)?

    private let serviceID = CBUUID(string: Frame.serviceUUID)
    private let requestID = CBUUID(string: Frame.requestUUID)
    private let replyID = CBUUID(string: Frame.replyUUID)
    private let batteryID = CBUUID(string: Frame.batteryUUID)
    private let relayID = CBUUID(string: Frame.relayUUID)
    private var central: CBCentralManager?
    private var devices: [UUID: NearbyDevice] = [:]
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var active: CBPeripheral?
    private var request: CBCharacteristic?
    private var reply: CBCharacteristic?
    private var battery: CBCharacteristic?
    private var batteryReadPending = false
    private var relay: CBCharacteristic?
    private var wantsRelay = false
    private var relayStartPending = false
    private var relayStopPending = false
    private var foreground = true
    private var link: LinkState = .idle
    private var scanDeadline: Task<Void, Never>?
    private var operationDeadline: Task<Void, Never>?
    private var statusPolling: Task<Void, Never>?

    func activate() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func startScan(bridgeOnly: Bool) {
        guard let central, central.state == .poweredOn, link == .idle, !central.isScanning else { return }
        clearDevices()
        central.scanForPeripherals(withServices: bridgeOnly ? [serviceID] : nil)
        onEvent?(.scan(true))
        log(bridgeOnly ? "Scanning for the watch2phone service." : "Scanning for all nearby BLE advertisements.")
        scanDeadline = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(15))
                try Task.checkCancellation()
                self?.stopScan()
            } catch {}
        }
    }

    func stopScan() {
        scanDeadline?.cancel()
        scanDeadline = nil
        guard let central, central.state == .poweredOn, central.isScanning else { return }
        central.stopScan()
        onEvent?(.scan(false))
        log("Scan stopped.")
    }

    func connect(to id: UUID, shareNotifications: Bool) {
        guard let central, central.state == .poweredOn,
              let peripheral = peripherals[id], devices[id]?.compatible == true,
              transition(.connect) else { return }
        stopScan()
        active = peripheral
        wantsRelay = shareNotifications
        peripheral.delegate = self
        let options = shareNotifications ? [CBConnectPeripheralOptionRequiresANCS: true] : nil
        central.connect(peripheral, options: options)
        log("Connecting to \(devices[id]?.name ?? "watch2phone").")
        scheduleTimeout(seconds: shareNotifications ? 60 : 15, message: "The watch did not connect. Open watch2phone on the watch, tap Start if needed, then scan again.")
    }

    func setForeground(_ foreground: Bool) {
        self.foreground = foreground
        if foreground {
            if link == .ready || link == .writing || link == .reading { startStatusPolling() }
        } else {
            stopScan()
            stopStatusPolling()
            if !wantsRelay { disconnect() }
        }
    }

    func send(_ frame: Frame) {
        guard let active, let request, frame.opcode == .request, link == .ready else { return }
        guard frame.encoded.count <= active.maximumWriteValueLength(for: .withResponse) else {
            fail("The negotiated write size is too small.")
            return
        }
        transition(.send)
        active.writeValue(frame.encoded, for: request, type: .withResponse)
        log("Writing test transaction #\(frame.transaction).")
        scheduleTimeout(seconds: 8, message: "Test exchange timed out.")
    }

    func disconnect() {
        guard let active, transition(.cancel) else { return }
        cancelTimeout()
        stopStatusPolling()
        log("Disconnecting.")
        if let relay, active.state == .connected {
            relayStopPending = true
            if !relayStartPending { active.writeValue(Data([0]), for: relay, type: .withResponse) }
            operationDeadline = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(3)) } catch { return }
                self?.log("Could not confirm sharing stopped. Use Stop on the watch.", isError: true)
                self?.cancelConnection()
            }
            return
        }
        cancelConnection()
    }

    private func cancelConnection() {
        cancelTimeout()
        guard let active else { return }
        if let central, central.state == .poweredOn {
            central.cancelPeripheralConnection(active)
        } else {
            finish()
        }
    }

    private func clearDevices() {
        devices.removeAll()
        peripherals.removeAll()
        onEvent?(.devices([]))
    }

    @discardableResult
    private func transition(_ event: LinkState.Event) -> Bool {
        guard link.apply(event) else { return false }
        onEvent?(.state(link))
        return true
    }

    private func log(_ message: String, isError: Bool = false) {
        onEvent?(.diagnostic(message, isError: isError))
    }

    private func fail(_ message: String) {
        log(message, isError: true)
        disconnect()
    }

    private func scheduleTimeout(seconds: Int, message: String) {
        cancelTimeout()
        operationDeadline = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(seconds))
                try Task.checkCancellation()
                self?.fail(message)
            } catch {}
        }
    }

    private func cancelTimeout() {
        operationDeadline?.cancel()
        operationDeadline = nil
    }

    private func finish() {
        cancelTimeout()
        stopStatusPolling()
        active?.delegate = nil
        active = nil
        request = nil
        reply = nil
        battery = nil
        batteryReadPending = false
        relay = nil
        wantsRelay = false
        relayStartPending = false
        relayStopPending = false
        onEvent?(.notificationAuthorization(nil))
        transition(.ended)
    }

    private func startStatusPolling() {
        stopStatusPolling()
        guard foreground else { return }
        statusPolling = Task { [weak self] in
            while !Task.isCancelled, self != nil {
                self?.refreshWatchStatus()
                do {
                    try await Task.sleep(for: .seconds(5))
                } catch {
                    return
                }
            }
        }
    }

    private func stopStatusPolling() {
        statusPolling?.cancel()
        statusPolling = nil
    }

    private func refreshWatchStatus() {
        guard let active, active.state == .connected, link == .ready else { return }
        active.readRSSI()
        if let battery, !batteryReadPending {
            batteryReadPending = true
            active.readValue(for: battery)
        }
    }

    private func finishDiscovery() {
        guard transition(.discovered) else { return }
        cancelTimeout()
        log("Discovered request and reply characteristics. Ready to send.")
        startStatusPolling()
    }
}

extension CoreBluetoothTransport: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let status: String
        switch central.state {
        case .poweredOn: status = "Bluetooth is on."
        case .poweredOff: status = "Turn on Bluetooth in Settings."
        case .unauthorized: status = "Allow Bluetooth access for watch2phone in Settings."
        case .unsupported: status = "Bluetooth LE is unavailable on this device."
        case .resetting: status = "Bluetooth is resetting."
        case .unknown: status = "Waiting for Bluetooth."
        @unknown default: status = "Bluetooth is unavailable."
        }
        let ready = central.state == .poweredOn
        onEvent?(.radio(ready, status))
        log(status, isError: central.state == .unauthorized || central.state == .unsupported)
        if !ready {
            stopScan()
            onEvent?(.scan(false))
            clearDevices()
            finish()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard central.isScanning, link == .idle else { return }
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let connectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue ?? true
        let compatible = connectable && services.contains(serviceID)
        let id = peripheral.identifier
        let name = advertisedName ?? peripheral.name ?? (compatible ? "watch2phone" : "Unnamed BLE device")
        devices[id] = NearbyDevice(
            id: id, name: name, rssi: RSSI.intValue == 127 ? nil : RSSI.intValue, compatible: compatible
        )
        peripherals[id] = peripheral
        onEvent?(.devices(devices.values.sorted {
            if $0.compatible != $1.compatible { return $0.compatible }
            return ($0.rssi ?? Int.min, $0.id.uuidString) > ($1.rssi ?? Int.min, $1.id.uuidString)
        }))
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard active === peripheral, transition(.connected) else { return }
        log("Connected. Discovering the bridge service.")
        scheduleTimeout(seconds: 10, message: "Connected to the watch, but the bridge service did not respond.")
        peripheral.discoverServices([serviceID])
    }

    func centralManager(_ central: CBCentralManager, didUpdateANCSAuthorizationFor peripheral: CBPeripheral) {
        guard active === peripheral, wantsRelay, link != .disconnecting else { return }
        onEvent?(.notificationAuthorization(peripheral.ancsAuthorized))
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard active === peripheral else { return }
        if link != .disconnecting {
            log(error?.localizedDescription ?? "Could not connect to the watch.", isError: true)
        }
        finish()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard active === peripheral else { return }
        log(error?.localizedDescription ?? "Disconnected.", isError: error != nil)
        finish()
    }
}

extension CoreBluetoothTransport: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard active === peripheral, link == .discovering else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else {
            fail(error?.localizedDescription ?? "The watch2phone service was not found.")
            return
        }
        log("Discovered service \(service.uuid.uuidString).")
        var ids = [requestID, replyID, batteryID]
        if wantsRelay { ids.append(relayID) }
        peripheral.discoverCharacteristics(ids, for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?
    ) {
        guard active === peripheral, link == .discovering, service.uuid == serviceID else { return }
        guard error == nil,
              let request = service.characteristics?.first(where: { $0.uuid == requestID && $0.properties.contains(.write) }),
              let reply = service.characteristics?.first(where: { $0.uuid == replyID && $0.properties.contains(.read) }) else {
            fail(error?.localizedDescription ?? "The service is missing the expected write and read characteristics.")
            return
        }
        self.request = request
        self.reply = reply
        battery = service.characteristics?.first { $0.uuid == batteryID && $0.properties.contains(.read) }
        if wantsRelay {
            relay = service.characteristics?.first { $0.uuid == relayID && $0.properties.contains(.write) }
            if let relay {
                onEvent?(.notificationAuthorization(peripheral.ancsAuthorized))
                relayStartPending = true
                scheduleTimeout(seconds: 60, message: "Notification setup timed out. Confirm Bluetooth pairing and reconnect.")
                peripheral.writeValue(Data([1]), for: relay, type: .withResponse)
                return
            } else {
                wantsRelay = false
                onEvent?(.notificationAuthorization(nil))
                log("Enable iPhone notifications in the watch app and reconnect.", isError: true)
                if !foreground { disconnect() }
            }
        }
        finishDiscovery()
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard active === peripheral, link != .disconnecting else { return }
        let value = RSSI.intValue
        onEvent?(.signalStrength(error == nil && value != 127 ? value : nil))
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard active === peripheral else { return }
        if characteristic === relay {
            if relayStartPending {
                relayStartPending = false
                if relayStopPending {
                    peripheral.writeValue(Data([0]), for: characteristic, type: .withResponse)
                } else {
                    if let error {
                        wantsRelay = false
                        onEvent?(.notificationAuthorization(nil))
                        log("Could not start sharing: \(error.localizedDescription)", isError: true)
                        if !foreground { disconnect() }
                    }
                    finishDiscovery()
                }
            } else if relayStopPending {
                relayStopPending = false
                if let error {
                    log("Could not stop sharing: \(error.localizedDescription). Use Stop on the watch.", isError: true)
                }
                cancelConnection()
            }
            return
        }
        guard characteristic === request, link == .writing, let reply else { return }
        if let error {
            fail("Write failed: \(error.localizedDescription)")
            return
        }
        transition(.written)
        peripheral.readValue(for: reply)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard active === peripheral, link != .disconnecting else { return }
        if characteristic === battery {
            batteryReadPending = false
            let percent = characteristic.value.flatMap { data in
                data.count == 1 ? data.first : nil
            }
            if error == nil, let percent, percent <= 100 {
                onEvent?(.batteryLevel(Int(percent)))
            } else {
                onEvent?(.batteryLevel(nil))
            }
            return
        }
        guard characteristic === reply, link == .reading else { return }
        guard error == nil, let value = characteristic.value, let frame = Frame(decoding: value) else {
            fail(error?.localizedDescription ?? "The watch returned an invalid frame.")
            return
        }
        cancelTimeout()
        onEvent?(.frame(frame))
        // The view model can cancel synchronously if the frame fails correlation.
        transition(.reply)
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard active === peripheral, link != .disconnecting,
              invalidatedServices.contains(where: { $0.uuid == serviceID }) else { return }
        fail("The watch changed its GATT service. Reconnect to discover it again.")
    }
}
