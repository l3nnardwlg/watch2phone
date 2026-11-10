import BridgeCore
import CoreBluetooth
import Foundation

@MainActor
final class CoreBluetoothTransport: NSObject, BridgeTransport {
    var onEvent: ((TransportEvent) -> Void)?

    private let serviceID = CBUUID(string: Frame.serviceUUID)
    private let requestID = CBUUID(string: Frame.requestUUID)
    private let replyID = CBUUID(string: Frame.replyUUID)
    private var central: CBCentralManager?
    private var devices: [UUID: NearbyDevice] = [:]
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var active: CBPeripheral?
    private var request: CBCharacteristic?
    private var reply: CBCharacteristic?
    private var link: LinkState = .idle
    private var scanDeadline: Task<Void, Never>?
    private var operationDeadline: Task<Void, Never>?

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

    func connect(to id: UUID) {
        guard let central, central.state == .poweredOn,
              let peripheral = peripherals[id], devices[id]?.compatible == true,
              transition(.connect) else { return }
        stopScan()
        active = peripheral
        peripheral.delegate = self
        central.connect(peripheral)
        log("Connecting to \(devices[id]?.name ?? "watch2phone").")
        scheduleTimeout(seconds: 15, message: "Connection or GATT discovery timed out.")
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
        log("Disconnecting.")
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
        active?.delegate = nil
        active = nil
        request = nil
        reply = nil
        transition(.ended)
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
        peripheral.discoverServices([serviceID])
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
        peripheral.discoverCharacteristics([requestID, replyID], for: service)
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
        cancelTimeout()
        transition(.discovered)
        log("Discovered request and reply characteristics. Ready to send.")
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard active === peripheral, characteristic === request, link == .writing, let reply else { return }
        if let error {
            fail("Write failed: \(error.localizedDescription)")
            return
        }
        transition(.written)
        peripheral.readValue(for: reply)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard active === peripheral, characteristic === reply, link == .reading else { return }
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
