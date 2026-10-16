package vibes.launcher.angel.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat

class MemoryLockService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "memory_lock_channel"
        val channel = NotificationChannel(
            channelId,
            "Launcher Memory Lock",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Angel Launcher")
            .setContentText("Locked in active memory for instant response")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .build()

        try {
            ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            android.util.Log.w("MemoryLockService", "Could not enter foreground: ${e.message}")
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
