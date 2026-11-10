package org.watch2phone.wear

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.ToggleChip

class MainActivity : ComponentActivity() {
    private val peripheral get() = (application as Watch2PhoneApplication).bridge
    private var relayRequested by mutableStateOf(false)
    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (relayRequested && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startSession()
        } else {
            peripheral.stop("Allow Nearby Devices and, for sharing, Notifications to start.")
        }
    }

    private val radioChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF
            ) {
                peripheral.stop("Bluetooth is off. Enable it and start again.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        relayRequested = savedInstanceState?.getBoolean("relayRequested") ?: false
        setContent {
            val status by peripheral.status.collectAsStateWithLifecycle()
            // The bridge stops in onStop. Give the iPhone the advertised discovery window
            // even when the watch would otherwise go idle before the first exchange.
            val awaitingPeer = status.phase == PeripheralState.STARTING ||
                status.phase == PeripheralState.ADVERTISING
            DisposableEffect(awaitingPeer) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (awaitingPeer) window.addFlags(flag)
                onDispose { window.clearFlags(flag) }
            }
            WatchScreen(status, relayRequested, { relayRequested = it }, ::start) { peripheral.stop() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("relayRequested", relayRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        // Bluetooth broadcasts can originate from a privileged process outside the system UID.
        ContextCompat.registerReceiver(
            this, radioChanges, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(radioChanges)
        if (!peripheral.relaysNotifications) peripheral.stop()
        super.onStop()
    }

    private fun start() {
        if (hasPermissions()) startSession() else permissionRequest.launch(requiredPermissions)
    }

    private fun startSession() {
        if (relayRequested) {
            ContextCompat.startForegroundService(this, Intent(this, NotificationRelayService::class.java))
        } else {
            peripheral.start()
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
private fun WatchScreen(
    status: WatchStatus, relayRequested: Boolean, onRelayChange: (Boolean) -> Unit,
    onStart: () -> Unit, onStop: () -> Unit,
) {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                val title = when (status.phase) {
                    PeripheralState.STOPPED -> "watch2phone"
                    PeripheralState.STARTING -> "Starting"
                    PeripheralState.ADVERTISING -> "Discoverable"
                    PeripheralState.CONNECTED -> "Connected"
                }
                Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
                Text(status.detail, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
                status.lastEcho?.let {
                    Text("Echo: $it", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
                }
                status.notificationStatus?.let {
                    Text(it, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
                }
                val stopped = status.phase == PeripheralState.STOPPED
                Button(onClick = if (stopped) onStart else onStop) {
                    Text(if (stopped) "Start" else "Stop")
                }
                if (stopped) {
                    ToggleChip(
                        checked = relayRequested,
                        onCheckedChange = onRelayChange,
                        label = { Text("iPhone notifications") },
                        toggleControl = { Switch(checked = relayRequested) },
                    )
                }
            }
        }
    }
}
