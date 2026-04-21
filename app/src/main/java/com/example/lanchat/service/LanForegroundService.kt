package com.example.lanchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.lanchat.R
import com.example.lanchat.data.repository.LanRepository
import com.ymr.lancomm.domain.model.ConnectionState
import com.example.lanchat.presentation.MainActivity

class LanForegroundService : Service() {
    
    private val binder = LocalBinder()
    private var repository: LanRepository? = null
    
    companion object {
        private const val TAG = "LanForegroundService"
        private const val CHANNEL_ID = "lan_service_channel"
        private const val NOTIFICATION_ID = 1001
        
        fun start(context: Context) {
            val intent = Intent(context, LanForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, LanForegroundService::class.java)
            context.stopService(intent)
        }
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): LanForegroundService = this@LanForegroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("LanChat Server", "Starting..."))
        Log.d(TAG, "Service started with foreground notification")
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Service unbound")
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        repository?.let {
            if (it.connectionState.value is ConnectionState.Connected) {
                it.disconnect()
            }
        }
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
    
    fun setRepository(repo: LanRepository) {
        repository = repo
    }
    
    fun updateNotification(title: String, status: String) {
        val notification = createNotification(title, status)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LanChat Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LanChat server service notification"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(title: String, status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}