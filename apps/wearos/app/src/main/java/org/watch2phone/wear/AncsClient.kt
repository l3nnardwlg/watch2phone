package org.watch2phone.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
internal class AncsClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPreview: (AncsEvent, AncsPreview) -> Unit,
    private val onRemoved: (Int) -> Unit,
    private val onClear: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<AncsEvent>()
    private val response = ByteArrayOutputStream()
    private var connection: BluetoothGatt? = null
    private var source: BluetoothGattCharacteristic? = null
    private var control: BluetoothGattCharacteristic? = null
    private var pending: AncsEvent? = null
    private var removedWhilePending = false
    private var writeAcknowledged = false
    private var ready = false
    private var bondingRequested = false
    private val setupTimeout = Runnable { fail("Allow notification sharing on the iPhone, then reconnect.") }
    private val responseTimeout = Runnable { fail("The iPhone stopped responding to notification requests.") }
    private val retryDiscovery = Runnable { discover() }

    fun start(device: BluetoothDevice) {
        onStatus("Connecting to iPhone notifications…")
        try {
            connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (connection == null) {
                fail("Could not open the iPhone notification service.")
                return
            }
            main.postDelayed(setupTimeout, 60_000)
        } catch (_: SecurityException) {
            fail("Nearby Devices permission is required for notifications.")
        }
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        val gatt = connection
        connection = null
        source = null
        control = null
        pending = null
        ready = false
        queue.clear()
        response.reset()
        if (gatt != null) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: SecurityException) {
                // The user can revoke Bluetooth permission during teardown.
            }
        }
        onClear()
    }

    private fun fail(message: String) {
        close()
        onStatus(message)
    }

    private fun dispatch(gatt: BluetoothGatt, action: () -> Unit) {
        main.post {
            if (connection !== gatt) return@post
            try {
                action()
            } catch (_: SecurityException) {
                fail("Nearby Devices permission is required for notifications.")
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch(gatt) {
            when {
                status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED ->
                    fail("iPhone notification connection ended.")
                newState == BluetoothProfile.STATE_CONNECTED -> discover()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch(gatt) {
            if (ready) return@dispatch
            val service = gatt.getService(SERVICE_UUID)
            val notifications = service?.getCharacteristic(NOTIFICATION_SOURCE)
            val data = service?.getCharacteristic(DATA_SOURCE)
            val commands = service?.getCharacteristic(CONTROL_POINT)
            if (status == BluetoothGatt.GATT_SUCCESS && notifications != null && data != null && commands != null) {
                source = notifications
                control = commands
                if (!subscribe(gatt, data)) retry(gatt)
            } else {
                retry(gatt)
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) = dispatch(gatt) {
            if (ready) fail("The iPhone notification service changed. Reconnect to resume.")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            dispatch(gatt) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retry(gatt)
                    return@dispatch
                }
                when (descriptor.characteristic.uuid) {
                    DATA_SOURCE -> if (source?.let { subscribe(gatt, it) } != true) retry(gatt)
                    NOTIFICATION_SOURCE -> {
                        ready = true
                        main.removeCallbacks(setupTimeout)
                        onStatus("iPhone notifications are on.")
                        requestNext()
                    }
                }
            }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            dispatch(gatt) {
                if (characteristic.uuid != CONTROL_POINT || pending == null) return@dispatch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // A notification can be removed between its event and the request.
                    finishRequest()
                } else {
                    writeAcknowledged = true
                    deliverIfComplete()
                }
            }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) = receive(gatt, characteristic, value.copyOf())

        @Deprecated("Used on Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) characteristic.value?.copyOf()?.let { receive(gatt, characteristic, it) }
        }
    }

    private fun discover() {
        val gatt = connection ?: return
        try {
            if (!gatt.discoverServices()) retry(gatt)
        } catch (_: SecurityException) {
            fail("Nearby Devices permission is required for notifications.")
        }
    }

    private fun retry(gatt: BluetoothGatt) {
        if (!bondingRequested && gatt.device.bondState == BluetoothDevice.BOND_NONE) {
            bondingRequested = true
            gatt.device.createBond()
        }
        onStatus("Confirm pairing and allow notifications on the iPhone.")
        main.removeCallbacks(retryDiscovery)
        main.postDelayed(retryDiscovery, 2_000)
    }

    private fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) =
        dispatch(gatt) {
            when (characteristic.uuid) {
                NOTIFICATION_SOURCE -> AncsProtocol.event(bytes)?.let { event ->
                    when {
                        event.kind == 2 -> {
                            queue.removeAll { it.id == event.id }
                            if (pending?.id == event.id) removedWhilePending = true
                            onRemoved(event.id)
                        }
                        event.kind == 0 && !event.preExisting && queue.size < 20 -> {
                            queue.addLast(event)
                            requestNext()
                        }
                    }
                }
                DATA_SOURCE -> {
                    if (pending == null) return@dispatch
                    if (response.size() + bytes.size > AncsProtocol.MAX_RESPONSE_BYTES) {
                        fail("The iPhone returned an invalid notification preview.")
                        return@dispatch
                    }
                    response.write(bytes)
                    deliverIfComplete()
                }
            }
        }

    private fun requestNext() {
        val gatt = connection ?: return
        val characteristic = control ?: return
        if (!ready || pending != null || queue.isEmpty()) return
        val event = queue.removeFirst()
        pending = event
        removedWhilePending = false
        writeAcknowledged = false
        response.reset()
        if (write(gatt, characteristic, AncsProtocol.request(event.id))) {
            main.postDelayed(responseTimeout, 8_000)
        } else {
            fail("Could not request the iPhone notification preview.")
        }
    }

    private fun deliverIfComplete() {
        val event = pending ?: return
        if (!writeAcknowledged) return
        val preview = AncsProtocol.preview(response.toByteArray(), event.id) ?: return
        if (!removedWhilePending) onPreview(event, preview)
        finishRequest()
    }

    private fun finishRequest() {
        main.removeCallbacks(responseTimeout)
        pending = null
        response.reset()
        requestNext()
    }

    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val descriptor = characteristic.getDescriptor(CCCD) ?: return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0")
        private val NOTIFICATION_SOURCE = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd")
        private val CONTROL_POINT = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9")
        private val DATA_SOURCE = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
