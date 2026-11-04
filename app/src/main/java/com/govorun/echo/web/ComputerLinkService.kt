package com.govorun.echo.web

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.govorun.echo.AppEntry
import com.govorun.echo.R

/** Only the window explicitly opened on the phone stays alive. No sticky restart and no incoming commands. */
class ComputerLinkService : Service() {
    private var generation = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("generation", 0) ?: 0
        if (intent?.action == CLOSE) {
            if (id == generation || generation == 0L) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            return START_NOT_STICKY
        }
        if (id == 0L) { stopSelf(startId); return START_NOT_STICKY }
        if (intent?.action == CLOSE_WINDOW) {
            if (id == generation) ComputerSession.close()
            return START_NOT_STICKY
        }
        generation = id
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.computer_entry), NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 0, Intent(this, ComputerLinkService::class.java).setAction(CLOSE_WINDOW).putExtra("generation", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, AppEntry.intent(this).putExtra("echo_computer", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_computer).setContentTitle(getString(R.string.computer_entry))
            .setContentText(getString(R.string.computer_notification)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.computer_close), close).build()).build()
        runCatching { this@ComputerLinkService.startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            .onFailure { android.util.Log.w("ComputerLink", "event=foreground_denied", it); ComputerSession.serviceStopped(generation); stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { ComputerSession.offer(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { ComputerSession.serviceStopped(generation); super.onDestroy() }

    companion object {
        private const val CHANNEL = "computer_window"
        private const val NOTICE = 84
        private const val CLOSE = "close_service"
        private const val CLOSE_WINDOW = "close_window"
        fun begin(context: Context, generation: Long): Boolean = runCatching {
            context.startForegroundService(Intent(context, ComputerLinkService::class.java).putExtra("generation", generation)); true
        }.getOrDefault(false)
        fun finish(context: Context, generation: Long) { runCatching { context.startService(Intent(context, ComputerLinkService::class.java).setAction(CLOSE).putExtra("generation", generation)) } }
    }
}
