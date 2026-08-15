package com.echo.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.echo.android.MainActivity
import com.echo.android.R

object Notifications {
    private const val CHANNEL_ID = "echo_screenshot"

    /** 截图翻译通知：点击带着图片 uri 打开 MainActivity 自动翻译 */
    fun showScreenshot(
        context: Context,
        uri: Uri,
    ) {
        ensureChannel(context)
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                uri.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Echo 检测到新截图")
                .setContentText("点击翻译")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(uri.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "截图翻译",
                NotificationManager.IMPORTANCE_HIGH,
            )
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
