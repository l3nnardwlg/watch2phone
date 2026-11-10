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
import androidx.compose.runtime.getValue
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

class MainActivity : ComponentActivity() {
    private lateinit var peripheral: WatchPeripheral
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) peripheral.start()
        } else {
            peripheral.permissionDenied()
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
        peripheral = WatchPeripheral(applicationContext)
        setContent {
            val status by peripheral.status.collectAsStateWithLifecycle()
            WatchScreen(status, onStart = ::start, onStop = { peripheral.stop() })
        }
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
        peripheral.stop()
        super.onStop()
    }

    private fun start() {
        if (hasPermissions()) peripheral.start() else permissionRequest.launch(requiredPermissions)
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
private fun WatchScreen(status: WatchStatus, onStart: () -> Unit, onStop: () -> Unit) {
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
                val stopped = status.phase == PeripheralState.STOPPED
                Button(onClick = if (stopped) onStart else onStop) {
                    Text(if (stopped) "Start" else "Stop")
                }
            }
        }
    }
}
