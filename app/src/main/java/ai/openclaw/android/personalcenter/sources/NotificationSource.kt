package ai.openclaw.android.personalcenter.sources

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.personalcenter.ImportanceCalculator
import ai.openclaw.android.personalcenter.models.CenterItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 全局 PendingIntent requestCode 计数器 — 三源共用，避免碰撞
 */
private val requestCodeCounter = java.util.concurrent.atomic.AtomicInteger(10000)
internal fun nextRequestCode(): Int = requestCodeCounter.incrementAndGet()

/**
 * 通知数据源 — 包装现有 SmartNotificationListener
 */
class NotificationSource(private val context: Context) {

    private val TAG = "NotificationSource"

    fun observe(): Flow<List<CenterItem>> = callbackFlow {
        // 订阅即主动拉取一次，对齐 SmsSource / CalendarSource 的 callbackFlow 模式。
        // 旧实现是纯被动 flow { collect StateFlow }，导致权限补授后重新进入页面也无法自愈。
        // 注意：未授予「通知使用权」时服务实例为 null，这里是 no-op，需配合 UI 侧的权限提示。
        SmartNotificationListener.refreshFromSystem()

        SmartNotificationListener.notifications.collect { notifications ->
            val items = notifications.map { notification ->
                val openIntent = createOpenIntent(context, notification.packageName)
                val item = CenterItem(
                    id = "notif_${notification.id}",
                    source = ItemSource.NOTIFICATION,
                    sourceApp = getAppName(notification.packageName),
                    importance = 0f,
                    title = notification.title,
                    body = notification.text,
                    timestamp = notification.timestamp,
                    isRead = notification.isRead,
                    openIntent = openIntent,
                )
                item.copy(importance = ImportanceCalculator.calculate(item))
            }
            trySend(items)
        }
        awaitClose { }
    }

    private fun createOpenIntent(context: Context, packageName: String): PendingIntent? {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    nextRequestCode(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "No launch intent for $packageName: ${e.message}")
            null
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
