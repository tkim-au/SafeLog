package com.safelog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the process (and so the vehicle listeners and the trip being recorded) alive while the driver uses
 * navigation in front. Shows one quiet ongoing notification. Nothing is shown or said from here.
 */
class DriveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행 기록", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("세이프로그")
                .setContentText("잘한 운전을 기록하고 있어요")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
            (application as SafeLogApplication).live.startVehicle()
        } catch (t: Throwable) {
            Log.w("SafeLog", "foreground service not started: $t")       // the app still works while it is open
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "drive"
        private const val ID = 7
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DriveService::class.java)) }
                .onFailure { Log.w("SafeLog", "startForegroundService: $it") }
        }
    }
}
