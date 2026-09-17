package ai.openclaw.android

import android.app.Application
import android.content.Context
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.prefetch.PrefetchWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tencent.bugly.BuglyStrategy
import com.tencent.bugly.crashreport.CrashReport
import ai.openclaw.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class OpenClawApplication : Application() {
    lateinit var permissionManager: PermissionManager
        private set

    override fun onCreate() {
        super.onCreate()
        permissionManager = PermissionManager(this)
        initKoin()
        initBugly()
        registerPrefetchWorker()
    }

    /**
     * 启动 Koin 容器。
     *
     * ⚠️ 此前全项目从未调用 startKoin，`di/AppModule.kt` 里的定义全部是死代码：
     * - 所有对象都在 MainActivity / GatewayManager 里手动 new，出现多份实例；
     * - MemoryMaintenanceWorker / UserProfileBuilderWorker 用 KoinPlatform.getKoin().get<>()，
     *   Koin 未启动时必抛异常，又被 catch → Result.retry() 吞掉，导致后台任务永久静默重试。
     * 这里统一在 Application 启动，所有 single 均为懒加载，不增加启动耗时。
     */
    private fun initKoin() {
        startKoin {
            androidLogger()
            androidContext(this@OpenClawApplication)
            modules(appModule)
        }
    }

    /**
     * 注册预采集定时任务
     */
    private fun registerPrefetchWorker() {
        val workRequest = PeriodicWorkRequestBuilder<PrefetchWorker>(30, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "prefetch_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * 初始化 Bugly 崩溃报告
     * - Debug 包：开启详细日志，方便本地调试
     * - Release 包：关闭日志输出，仅静默上报
     * - AppID 从 BuildConfig 读取，通过 local.properties 配置
     */
    private fun initBugly() {
        val appId = if (BuildConfig.DEBUG) {
            BuildConfig.BUGLY_APP_ID_DEBUG
        } else {
            BuildConfig.BUGLY_APP_ID_RELEASE
        }

        // 占位符检测：未配置真实 AppID 时跳过初始化
        if (appId.startsWith("placeholder") || appId.isBlank()) {
            android.util.Log.w(
                TAG,
                "Bugly AppID 未配置，跳过初始化。" +
                "请在 local.properties 中设置 BUGLY_APP_ID_DEBUG / BUGLY_APP_ID_RELEASE"
            )
            return
        }

        val strategy = BuglyStrategy().apply {
            // 应用版本号
            setAppVersion(BuildConfig.VERSION_NAME)

            // 渠道标识
            setAppChannel(if (BuildConfig.DEBUG) "debug" else "release")

            // 自定义设备标识（使用 Android ID，避免 IMEI 隐私问题）
            try {
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                setDeviceID(androidId?.takeLast(16) ?: "unknown")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to get device identifier for Bugly", e)
            }

            // Debug 包开启日志上传，Release 关闭
            setBuglyLogUpload(BuildConfig.DEBUG)

            // 开启 ANR 监控
            setEnableANRCrashMonitor(true)

            // 开启 Native Crash 采集
            setEnableNativeCrashMonitor(true)
        }

        // Use reflection to explicitly call initCrashReport(Context, String, boolean, BuglyStrategy)
        // This avoids Kotlin overload resolution preferring the UserStrategy version
        val initMethod = CrashReport::class.java.getMethod(
            "initCrashReport",
            Context::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            BuglyStrategy::class.java
        )
        initMethod.invoke(null, applicationContext, appId, BuildConfig.DEBUG, strategy)

        android.util.Log.i(TAG, "Bugly initialized (appId: ${appId.take(3)}***, debug: ${BuildConfig.DEBUG})")

        // 设置自定义标签（用于 Bugly 控制台筛选）
        CrashReport.setUserId("openclaw-${if (BuildConfig.DEBUG) "debug" else "release"}")
        CrashReport.putUserData(this, "app_version", BuildConfig.VERSION_NAME)
        CrashReport.putUserData(this, "version_code", BuildConfig.VERSION_CODE.toString())
    }

    companion object {
        private const val TAG = "OpenClawApplication"
    }
}

fun Context.permissionManager(): PermissionManager =
    (applicationContext as OpenClawApplication).permissionManager
