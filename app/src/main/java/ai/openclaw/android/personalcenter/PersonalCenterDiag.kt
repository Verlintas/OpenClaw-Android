package ai.openclaw.android.personalcenter

import android.content.Context
import android.util.Log
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.personalcenter.sources.CallLogSource
import ai.openclaw.android.personalcenter.sources.CalendarSource
import ai.openclaw.android.personalcenter.sources.NotificationSource
import ai.openclaw.android.personalcenter.sources.SmsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 个人中心数据源诊断。
 *
 * 背景：真机上「通知」页常年 0 条，而 combine 从未发射过（连 `Raw items: 0` 都没有），
 * 说明四个源里至少有一个在订阅后不产出。这里逐个源做 3s 超时探测，
 * 一次广播就能定位是哪个源卡住、以及通知监听到底处于哪个状态。
 *
 * 触发：adb shell am broadcast -a ai.openclaw.android.TEST_PC_DIAG（tag = PCDiag）
 */
object PersonalCenterDiag {

    private const val TAG = "PCDiag"

    suspend fun run(context: Context): String {
        val sb = StringBuilder()

        // 1) 通知链路的两个独立状态（权限 vs 服务真被绑定）
        val enabled = SmartNotificationListener.isNotificationListenerEnabled(context)
        val connected = SmartNotificationListener.isConnected.value
        sb.append("[1] listenerEnabled=$enabled connected=$connected\n")

        // 2) 内存 StateFlow 与系统实时拉取各有多少条
        val inMemory = SmartNotificationListener.notifications.value.size
        val active = SmartNotificationListener.getActiveNotificationsList().size
        sb.append("[2] stateFlow=$inMemory getActiveNotifications=$active\n")

        // 3) 逐个源探测：谁在订阅后 3s 内不发射，谁就是 combine 卡死的原因
        val sources = listOf(
            "notif" to NotificationSource(context).observe(),
            "calendar" to CalendarSource(context).observe(),
            "sms" to SmsSource(context).observe(),
            "callLog" to CallLogSource(context).observe(),
        )
        for ((name, flow) in sources) {
            val first = withTimeoutOrNull(3_000L) { flow.first() }
            val size = first?.size
            sb.append(
                if (size == null) "[3] src=$name -> 3s 内未发射（combine 会永久挂起）\n"
                else "[3] src=$name -> emit ${size} 条\n"
            )
        }

        val text = sb.toString()
        Log.d(TAG, "\n$text")
        return text
    }
}
