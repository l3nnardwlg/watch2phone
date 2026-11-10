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
)

// Main-thread controller. Each session has its own callbacks and pending reply.
@SuppressLint("MissingPermission")
internal class WatchPeripheral(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val mutableStatus = MutableStateFlow(WatchStatus())
    val status = mutableStatus.asStateFlow()
    private var current: Session? = null

    fun start() {
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
            val session = Session(advertiser)
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

    private inner class Session(private val advertiser: BluetoothLeAdvertiser) {
        private var server: BluetoothGattServer? = null
        private var peer: BluetoothDevice? = null
        private var reply: ByteArray? = null
        private var advertising: AdvertiseCallback? = null
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
                        if (peer == null) {
                            // A stack connection event can belong to another app or system service.
                            // The first valid bridge write selects the peer for this session.
                            if (mutableStatus.value.phase == PeripheralState.STARTING) published()
                            peer = device
                            main.removeCallbacks(timeout)
                            stopAdvertising()
                            update(Event.CONNECT, "Exchanging test messages.")
                        }
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
            server = manager?.openGattServer(context, gatt)
            if (server?.addService(service) != true) {
                stop("Could not open the GATT server or register its service.")
                return
            }
            scheduleTimeout(10_000)
        }

        fun close() {
            main.removeCallbacks(timeout)
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
            advertising = callback
            scheduleTimeout(10_000)
            advertiser.startAdvertising(settings, data, callback)
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
