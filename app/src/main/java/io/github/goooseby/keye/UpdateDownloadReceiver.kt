package io.github.goooseby.keye

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Completion never launches an activity from the background. */
class UpdateDownloadReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val prefs=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
        val id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)
        if(!prefs.getBoolean("install_when_ready",false)) return
        if(id<0 || id!=prefs.getLong("download_id",-1) || prefs.getLong("notified_download_id",-1)==id) return
        try {
            val downloads=context.getSystemService(DownloadManager::class.java)
            downloads.query(DownloadManager.Query().setFilterById(id))?.use {
                if(!it.moveToFirst() || it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=DownloadManager.STATUS_SUCCESSFUL) return
            } ?: return
            if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("app_updates","应用更新",NotificationManager.IMPORTANCE_DEFAULT))
            val open=Intent(context,MainActivity::class.java).setAction(UPDATE_ACTION).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending=PendingIntent.getActivity(context,UPDATE_NOTIFICATION_ID,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(UPDATE_NOTIFICATION_ID,NotificationCompat.Builder(context,"app_updates")
                .setSmallIcon(R.drawable.ic_update_notification).setContentTitle("课页更新已下载")
                .setContentText("点击校验并安装，无需重新下载").setContentIntent(pending).setAutoCancel(true).build())
            prefs.edit().putLong("notified_download_id",id).apply()
        } catch(_: Exception) { /* The next foreground refresh can still recover it. */ }
    }
}
