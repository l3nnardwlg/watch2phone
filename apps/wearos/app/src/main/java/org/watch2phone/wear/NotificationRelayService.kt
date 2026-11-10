package org.watch2phone.wear

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class Watch2PhoneApplication : Application() {
    val bridge by lazy { WatchPeripheral(this) }
}

internal class NotificationRelayService : Service() {
    private val bridge get() = (application as Watch2PhoneApplication).bridge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private val radioChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF
            ) bridge.stop("Bluetooth is off. Enable it and start again.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this, radioChanges, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            bridge.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        startForeground(SESSION_NOTIFICATION, sessionNotification())
        started = true
        bridge.start(relayNotifications = true)
        scope.launch {
            bridge.status.collect {
                if (it.phase == PeripheralState.STOPPED) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(radioChanges)
        if (bridge.relaysNotifications) bridge.stop()
        super.onDestroy()
    }

    private fun sessionNotification(): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Notification sharing session", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, NotificationRelayService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("watch2phone")
            .setContentText("iPhone notification sharing session")
            .setLocalOnly(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private companion object {
        const val CHANNEL = "iphone_notification_session"
        const val STOP = "org.watch2phone.wear.STOP_RELAY"
        const val SESSION_NOTIFICATION = 1
    }
}
