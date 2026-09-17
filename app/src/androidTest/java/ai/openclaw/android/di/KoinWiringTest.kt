package ai.openclaw.android.di

import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.data.local.BM25Index
import ai.openclaw.android.domain.memory.EmbeddingService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.mp.KoinPlatform

/**
 * 验证 S1 修复：Koin 容器必须已在 Application 中启动。
 *
 * 此前全项目从未调用 startKoin：
 * - `KoinPlatform.getKoin()` 会抛 IllegalStateException；
 * - MemoryMaintenanceWorker / UserProfileBuilderWorker 正是用它取依赖，异常被
 *   `catch → Result.retry()` 吞掉，表现为后台任务永久静默重试。
 * 这里直接断言 Worker 需要的三个依赖都能解析，且 AppDatabase 与单例是同一个实例
 * （即不会出现"Koin 一套 + 手动 new 一套"的双份运行时）。
 */
@RunWith(AndroidJUnit4::class)
class KoinWiringTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun koinIsStarted_andWorkerDependenciesResolve() {
        val koin = KoinPlatform.getKoin()

        val db = koin.get<AppDatabase>()
        assertNotNull("Koin 必须能提供 AppDatabase", db)
        assertSame(
            "Koin 提供的 AppDatabase 必须是同一个单例",
            AppDatabase.getInstance(context),
            db
        )

        assertNotNull("Koin 必须能提供 EmbeddingService", koin.get<EmbeddingService>())
        assertNotNull("Koin 必须能提供 BM25Index", koin.get<BM25Index>())
    }
}
