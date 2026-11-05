package com.mangashelf.reader.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mangashelf.reader.MainActivity

/**
 * 图片工作栏的导入前台服务（v1.6.3）。
 *
 * 为什么单独建一个而不是复用漫画侧的 `ImportForegroundService`：
 * 漫画侧那个服务的通知文案、渠道、点击行为都绑着书架的导入语义，而这一期的硬约束是
 * 「漫画侧零改动」。复制一份独立服务，代价是多一个类，换来漫画链路 diff 为 0。
 *
 * 职责很小：把进程提到前台（避免切后台 / 锁屏被系统回收）+ 用通知显示进度。
 * 真正的导入仍在 `GalleryViewModel` 的协程里跑——所以即使服务被系统停掉，
 * 当前这一批的进度也不会丢；进程真被杀了，靠 `import_tasks` 表在下次启动时提示未完成。
 */
class GalleryImportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val nm = getSystemService(NotificationManager::class.java)
                runCatching {
                    nm?.notify(NOTIF_ID, buildNotification(text.ifBlank { "正在导入图片…" }))
                }
                return START_NOT_STICKY
            }

            ACTION_DONE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val nm = getSystemService(NotificationManager::class.java)
                runCatching {
                    nm?.notify(DONE_NOTIF_ID, buildDoneNotification(text.ifBlank { "图片导入完成" }))
                }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // 首次进来：必须尽快进前台，否则 Android 8+ 会抛异常
        startForegroundCompat("正在导入图片…")
        return START_STICKY
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("图片导入")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun buildDoneNotification(text: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("图片导入")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "图片导入",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "图片导入进度与结果" }
        runCatching { nm.createNotificationChannel(channel) }
    }

    companion object {
        private const val CHANNEL_ID = "gallery_import"
        private const val NOTIF_ID = 4221
        private const val DONE_NOTIF_ID = 4222
        private const val REQ_OPEN = 21

        private const val ACTION_UPDATE = "com.mangashelf.reader.GALLERY_IMPORT_UPDATE"
        private const val ACTION_STOP = "com.mangashelf.reader.GALLERY_IMPORT_STOP"
        private const val ACTION_DONE = "com.mangashelf.reader.GALLERY_IMPORT_DONE"
        private const val EXTRA_TEXT = "text"

        /** 开始导入：把进程提到前台。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GalleryImportService::class.java)
                )
            }
        }

        /** 更新进度文案。服务未在跑时静默忽略，不影响导入本身。 */
        fun update(context: Context, text: String) {
            runCatching {
                context.startService(
                    Intent(context, GalleryImportService::class.java)
                        .setAction(ACTION_UPDATE)
                        .putExtra(EXTRA_TEXT, text)
                )
            }
        }

        /** 结束：撤掉前台通知。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GalleryImportService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        /** 结束并弹一条完成通知（可点击回到 App）。 */
        fun done(context: Context, text: String) {
            runCatching {
                context.startService(
                    Intent(context, GalleryImportService::class.java)
                        .setAction(ACTION_DONE)
                        .putExtra(EXTRA_TEXT, text)
                )
            }
        }
    }
}
