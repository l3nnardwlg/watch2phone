package org.watch2phone.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.watch2phone.wear.PeripheralState.Event

internal data class WatchStatus(
    val phase: PeripheralState = PeripheralState.STOPPED,
    val detail: String = "Start to make this watch discoverable.",
    val lastEcho: String? = null,
    val notificationStatus: String? = null,
)

// Main-thread controller. Each session has its own callbacks and pending reply.
@SuppressLint("MissingPermission")
internal class WatchPeripheral(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val mutableStatus = MutableStateFlow(WatchStatus())
    val status = mutableStatus.asStateFlow()
    private var current: Session? = null
    val relaysNotifications: Boolean get() = current?.relayNotifications == true

    fun start(relayNotifications: Boolean = false) {
        if (current != null) return
        try {
            val adapter = manager?.adapter
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || adapter == null) {
                stop("Bluetooth LE is unavailable.")
                return
            }
            if (!adapter.isEnabled) {
                stop("Turn on Bluetooth and try again.")
                return
            }
            val advertiser = adapter.bluetoothLeAdvertiser
            if (!adapter.isMultipleAdvertisementSupported || advertiser == null) {
                stop("BLE peripheral advertising is unavailable on this watch.")
                return
            }
            val session = Session(advertiser, relayNotifications)
            current = session
            mutableStatus.value = WatchStatus(
                mutableStatus.value.phase.next(Event.START),
                "Publishing the test service…",
            )
            session.open()
        } catch (_: SecurityException) {
            permissionDenied()
        }
    }

    fun permissionDenied() = stop("Nearby Devices permission is required.")

    fun stop(detail: String = "Start to make this watch discoverable.") {
        val session = current
        current = null
        session?.close()
        mutableStatus.value = WatchStatus(mutableStatus.value.phase.next(Event.STOP), detail)
    }

    private inner class Session(
        private val advertiser: BluetoothLeAdvertiser,
        val relayNotifications: Boolean,
    ) {
        private var server: BluetoothGattServer? = null
        private var peer: BluetoothDevice? = null
        private var reply: ByteArray? = null
        private var advertising: AdvertiseCallback? = null
        private var ancs: AncsClient? = null
        private val timeout = Runnable {
            if (current === this) {
                val message = if (mutableStatus.value.phase == PeripheralState.STARTING) {
                    "Bluetooth service or advertising timed out."
                } else {
                    "Advertising expired. Start again to reconnect."
                }
                stop(message)
            }
        }

        private val gatt = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
                if (status != BluetoothGatt.GATT_SUCCESS || service.uuid != BridgeProtocol.serviceUuid) {
                    stop("GATT service registration failed (status $status).")
                    return@dispatch
                }
                advertise()
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = dispatch {
                if (peer == device &&
                    (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                ) {
                    stopNotifications()
                    peer = null
                    reply = null
                    update(Event.DISCONNECT, "Disconnected. Restarting advertising…")
                    advertise()
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val bytes = value.copyOf()
                dispatch {
                    // The protocol only supports acknowledged writes.
                    if (!responseNeeded) return@dispatch
                    if (characteristic.uuid == BridgeProtocol.relayUuid) {
                        controlNotifications(device, requestId, preparedWrite, offset, bytes)
                        return@dispatch
                    }
                    val frame = Frame.decode(bytes)?.reply()
                    val status = when {
                        (peer != null && device != peer) ||
                            (peer == null && advertising == null) ||
                            characteristic.uuid != BridgeProtocol.requestUuid ->
                            BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                        preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                        frame == null -> BluetoothGatt.GATT_FAILURE
                        else -> BluetoothGatt.GATT_SUCCESS
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS && frame != null) {
                        acceptPeer(device)
                        reply = frame.encode()
                        mutableStatus.value = mutableStatus.value.copy(lastEcho = frame.text)
                    }
                    respond(device, requestId, status, offset, null)
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = dispatch {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) = dispatch {
                if (characteristic.uuid == BridgeProtocol.batteryUuid) {
                    readBattery(device, requestId, offset)
                    return@dispatch
                }
                val response = reply
                val status = when {
                    device != peer || characteristic.uuid != BridgeProtocol.replyUuid ->
                        BluetoothGatt.GATT_READ_NOT_PERMITTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    response == null -> BluetoothGatt.GATT_FAILURE
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                respond(device, requestId, status, offset, response.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
        }

        fun open() {
            val service = BluetoothGattService(BridgeProtocol.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(
                BridgeProtocol.requestUuid, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ))
            service.addCharacteristic(BluetoothGattCharacteristic(
                BridgeProtocol.replyUuid, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ))
            service.addCharacteristic(BluetoothGattCharacteristic(
                BridgeProtocol.batteryUuid, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ))
            if (relayNotifications) {
                service.addCharacteristic(BluetoothGattCharacteristic(
                    BridgeProtocol.relayUuid, BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
                ))
            }
            server = manager?.openGattServer(context, gatt)
            if (server?.addService(service) != true) {
                stop("Could not open the GATT server or register its service.")
                return
            }
            scheduleTimeout(10_000)
        }

        fun close() {
            main.removeCallbacks(timeout)
            stopNotifications()
            cleanup { stopAdvertising() }
            server?.let { activeServer ->
                peer?.let { device -> cleanup { activeServer.cancelConnection(device) } }
                cleanup { activeServer.close() }
            }
            server = null
            peer = null
            reply = null
        }

        private fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (_: SecurityException) {
                // Permissions may have been revoked before teardown.
            }
        }

        private fun dispatch(action: () -> Unit) {
            main.post {
                if (current !== this) return@post
                try {
                    action()
                } catch (_: SecurityException) {
                    permissionDenied()
                }
            }
        }

        private fun published() {
            update(Event.PUBLISHED, "Ready to connect from the iPhone app.")
            scheduleTimeout(120_000)
        }

        private fun acceptPeer(device: BluetoothDevice) {
            if (peer != null) return
            // The first valid bridge operation selects the peer. A stack connection
            // event can also belong to another app or system service.
            if (mutableStatus.value.phase == PeripheralState.STARTING) published()
            peer = device
            main.removeCallbacks(timeout)
            stopAdvertising()
            update(Event.CONNECT, "Connected to watch2phone.")
        }

        private fun controlNotifications(
            device: BluetoothDevice, id: Int, prepared: Boolean, offset: Int, bytes: ByteArray,
        ) {
            val command = bytes.singleOrNull()?.toInt()
            val status = when {
                !relayNotifications || (peer != null && device != peer) ||
                    (peer == null && (advertising == null || command == 0)) ->
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                prepared -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                command != 0 && command != 1 -> BluetoothGatt.GATT_FAILURE
                else -> BluetoothGatt.GATT_SUCCESS
            }
            respond(device, id, status, offset, null)
            if (current !== this || status != BluetoothGatt.GATT_SUCCESS) return
            if (command == 0) {
                stopNotifications()
                mutableStatus.value = mutableStatus.value.copy(notificationStatus = null)
                return
            }
            acceptPeer(device)
            if (ancs != null) return
            val notifications = WatchNotifications(context)
            ancs = AncsClient(
                context,
                onStatus = { mutableStatus.value = mutableStatus.value.copy(notificationStatus = it) },
                onPreview = notifications::show,
                onRemoved = notifications::remove,
                onClear = notifications::clear,
            ).also { it.start(device) }
        }

        private fun stopNotifications() {
            ancs?.close()
            ancs = null
        }

        private fun readBattery(device: BluetoothDevice, requestId: Int, offset: Int) {
            if ((peer != null && device != peer) || (peer == null && advertising == null)) {
                respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            if (offset != 0) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level == null || level !in 0..100) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            acceptPeer(device)
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(level.toByte()))
        }

        private fun advertise() {
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = dispatch {
                    if (advertising === this) published()
                }

                override fun onStartFailure(errorCode: Int) = dispatch {
                    if (advertising === this) stop("BLE advertising failed (code $errorCode).")
                }
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(BridgeProtocol.serviceUuid))
                .build()
            val scanResponse = if (relayNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AdvertiseData.Builder().addServiceSolicitationUuid(ParcelUuid(AncsClient.SERVICE_UUID)).build()
            } else {
                null
            }
            advertising = callback
            scheduleTimeout(10_000)
            advertiser.startAdvertising(settings, data, scanResponse, callback)
        }

        private fun stopAdvertising() {
            val callback = advertising ?: return
            advertising = null
            advertiser.stopAdvertising(callback)
        }

        private fun update(event: Event, detail: String) {
            val state = mutableStatus.value.phase.next(event)
            mutableStatus.value = WatchStatus(state, detail)
        }

        private fun scheduleTimeout(millis: Long) {
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, millis)
        }

        private fun respond(device: BluetoothDevice, id: Int, status: Int, offset: Int, value: ByteArray?) {
            if (server?.sendResponse(device, id, status, offset, value) != true && device == peer) {
                stop("Could not send the GATT response. Start again to retry.")
            }
        }
    }
}
