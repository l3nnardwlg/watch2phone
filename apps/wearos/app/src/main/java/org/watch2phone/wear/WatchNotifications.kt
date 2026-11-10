package org.watch2phone.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

internal class WatchNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val displayed = LinkedHashSet<Int>()

    init {
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "iPhone notifications", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager?.activeNotifications?.filter { it.tag == TAG }?.forEach { manager.cancel(TAG, it.id) }
    }

    @SuppressLint("MissingPermission")
    fun show(event: AncsEvent, preview: AncsPreview) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = preview.title.ifBlank { "iPhone notification" }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(preview.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.message))
            .setSubText("iPhone")
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(event.silent)
            .build()
        if (event.id !in displayed && displayed.size >= 20) remove(displayed.first())
        displayed.add(event.id)
        manager?.notify(TAG, event.id, notification)
    }

    fun remove(id: Int) {
        if (displayed.remove(id)) manager?.cancel(TAG, id)
    }

    fun clear() {
        displayed.toList().forEach(::remove)
    }

    private companion object {
        const val CHANNEL = "iphone_notifications"
        const val TAG = "iphone_notification"
    }
}
