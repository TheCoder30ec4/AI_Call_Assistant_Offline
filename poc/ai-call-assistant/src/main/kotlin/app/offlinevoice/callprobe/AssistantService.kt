package app.offlinevoice.callprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the assistant ready while it's switched on: connects the Shizuku helper and keeps the AI
 * models loaded, so an unanswered call is picked up at once even with the screen off. A quiet
 * ongoing notification keeps the process alive, which Android requires for background work like this.
 */
class AssistantService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        Probe.start(this)
        Probe.requestShizuku() // Reconnect the helper if it dropped.
        Probe.loadAi()
        return START_STICKY // Android restarts it if it's killed while the assistant is on.
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistant status", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("AI call assistant is on")
            .setContentText("Answers calls you don't pick up")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistantService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * Brings the assistant back after a reboot if it was left on. Shizuku still has to be restarted by
 * hand after a reboot, so the service waits for the helper before it can answer, but the AI loads.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Probe.start(context)
            if (Probe.enabled) AssistantService.start(context)
        }
    }
}
