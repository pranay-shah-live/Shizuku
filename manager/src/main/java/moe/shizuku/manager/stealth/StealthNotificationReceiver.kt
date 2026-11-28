package moe.shizuku.manager.stealth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.R

class StealthNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REVERT = "moe.shizuku.manager.action.REVERT_STEALTH"
        const val NOTIFICATION_ID = 54321
        private const val CHANNEL_ID = "stealth_guard_channel"

        fun showMaskingNotification(context: Context, targetPackageName: String, targetLabel: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.stealth_profiles_title),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }

            val revertIntent = Intent(context, StealthNotificationReceiver::class.java).apply {
                action = ACTION_REVERT
            }
            val revertPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                revertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = context.getString(R.string.stealth_notif_active_title, targetLabel)
            val text = context.getString(R.string.stealth_notif_active_text)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_visibility_off_outline_24)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .addAction(
                    R.drawable.ic_autorenew,
                    context.getString(R.string.stealth_action_revert),
                    revertPendingIntent
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        }

        fun cancelNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REVERT) {
            AppProfileManager.revertSettings()
            cancelNotification(context)
            Toast.makeText(context, R.string.stealth_settings_reverted, Toast.LENGTH_SHORT).show()
        }
    }
}
