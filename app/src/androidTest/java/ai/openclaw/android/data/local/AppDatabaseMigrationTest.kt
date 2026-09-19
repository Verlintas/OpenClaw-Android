package ai.openclaw.android.data.local

import ai.openclaw.android.security.SecurityKeyManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机端到端验证数据库迁移链（v1 → v9）。
 *
 * 背景：v1 时代 exportSchema=false，且历史上一直开着 fallbackToDestructiveMigration()，
 * 老库升级没有迁移路径，Room 会静默清空加密库。S1 补了 MIGRATION_1_2 并去掉了破坏性迁移；
 * S2 删除了 trigger v2 引擎及 trigger_events_v2 表（v8 → v9）。
 * 这里验证：
 *  1. 迁移链能跑通且 Room 校验通过（若 schema 与实体不一致，getInstance 会抛异常）；
 *  2. v1 的老数据必须存活（若走了破坏性重建，数据必然丢失 → 断言失败）；
 *  3. 迁移后的列不能残留 DEFAULT（Room 由实体生成的建表语句不含 DEFAULT）；
 *  4. S2 废弃的 trigger_events_v2 表已被删除。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun v1_to_v9_migratesWithoutDataLossAndMatchesRoomSchema() {
        // ---- 清理：确保从 v1 冷启动 ----
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.listFiles()
            ?.filter { it.name.startsWith(AppDatabase.DATABASE_NAME) }
            ?.forEach { it.delete() }

        // DATABASE_NAME 是 const val，编译期内联，不会触发 AppDatabase 的 companion init，
        // 因此这里必须显式加载 sqlcipher 原生库（与 AppDatabase.init 保持一致）。
        System.loadLibrary("sqlcipher")
        val passphrase = SecurityKeyManager(context).getOrCreateDatabaseKey()

        // ---- 1. 手工构造 v1 形态的加密库（只有 sessions + messages）----
        val legacy = SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath, passphrase, null, null
        )
        try {
            legacy.execSQL(
                "CREATE TABLE sessions (" +
                    "sessionId TEXT NOT NULL PRIMARY KEY, name TEXT, " +
                    "createdAt INTEGER NOT NULL, lastActiveAt INTEGER NOT NULL, " +
                    "tokenCount INTEGER NOT NULL, status TEXT NOT NULL)"
            )
            // 真实 v1 库同样是 Room 依据实体生成的，因此必须带外键与索引，
            // 否则 IF NOT EXISTS 不会重建表，校验时外键/索引会不一致。
            legacy.execSQL(
                "CREATE TABLE messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "sessionId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, tokenCount INTEGER NOT NULL, " +
                    "FOREIGN KEY(sessionId) REFERENCES sessions(sessionId) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            legacy.execSQL("CREATE INDEX index_messages_sessionId ON messages(sessionId)")
            legacy.execSQL("INSERT INTO sessions VALUES ('legacy-1','迁移前会话',1000,2000,3,'ACTIVE')")
            legacy.execSQL(
                "INSERT INTO messages (sessionId, role, content, timestamp, tokenCount) " +
                    "VALUES ('legacy-1','USER','迁移前的消息',1000,5)"
            )
            legacy.execSQL("PRAGMA user_version = 1")
        } finally {
            legacy.close()
        }

        // ---- 2. Room 打开：跑完 1→2→…→7→8 并做 schema 校验 ----
        val room = AppDatabase.getInstance(context)
        val handle: SupportSQLiteDatabase = room.openHelper.readableDatabase

        assertEquals("迁移后 user_version 应为 9", 9, handle.version)

        // ---- 3. 老数据必须存活（若走了破坏性重建，这里必然失败）----
        assertEquals("v1 的 session 数据必须保留", 1, count(handle, "sessions"))
        assertEquals("v1 的 message 数据必须保留", 1, count(handle, "messages"))
        assertEquals(
            "v1 的 session 内容必须保留",
            "迁移前会话",
            firstString(handle, "SELECT name FROM sessions WHERE sessionId = 'legacy-1'")
        )
        assertEquals(
            "v1 的 message 内容必须保留",
            "迁移前的消息",
            firstString(handle, "SELECT content FROM messages WHERE sessionId = 'legacy-1'")
        )

        // ---- 4. 所有表都已建立且可查询 ----
        listOf(
            "sessions", "messages", "summaries", "memories", "memory_vectors",
            "dynamic_skills", "trigger_rules", "trigger_logs", "cached_data"
        ).forEach { table ->
            assertTrue("表 $table 应存在且可查询", count(handle, table) >= 0)
        }

        // S2：trigger_events_v2 已随 v2 引擎删除，迁移后不应再存在
        assertEquals(
            "trigger_events_v2 应已被 MIGRATION_8_9 删除",
            -1,
            countIgnoringError(handle, "trigger_events_v2")
        )

        // ---- 5. 迁移后的列不能残留 DEFAULT（Room 实体没有声明 defaultValue）----
        assertNull("memories.version 不应残留 DEFAULT", defaultOf(handle, "memories", "version"))
        assertNull("memories.accessCount 不应残留 DEFAULT", defaultOf(handle, "memories", "accessCount"))
        assertNull("dynamic_skills.category 不应残留 DEFAULT", defaultOf(handle, "dynamic_skills", "category"))
        assertNull("cached_data.hit_count 不应残留 DEFAULT", defaultOf(handle, "cached_data", "hit_count"))

        // ---- 6. 写入可用：Room 生成的 DAO 必须能正常操作迁移后的库 ----
        val newSession = ai.openclaw.android.data.model.SessionEntity(
            sessionId = "after-migration",
            name = "迁移后新建",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            tokenCount = 0,
            status = ai.openclaw.android.data.model.SessionStatus.ACTIVE
        )
        kotlinx.coroutines.runBlocking { room.sessionDao().insertSession(newSession) }
        assertEquals("迁移后应能正常写入新数据", 2, count(handle, "sessions"))
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int {
        val c = db.query("SELECT COUNT(*) FROM $table")
        return try {
            if (c.moveToFirst()) c.getInt(0) else -1
        } finally {
            c.close()
        }
    }

    /** 表不存在时返回 -1（而不是抛异常），用于断言某张表已被删除 */
    private fun countIgnoringError(db: SupportSQLiteDatabase, table: String): Int = try {
        count(db, table)
    } catch (e: Exception) {
        -1
    }

    private fun firstString(db: SupportSQLiteDatabase, sql: String): String? {
        val c = db.query(sql)
        return try {
            if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    private fun defaultOf(db: SupportSQLiteDatabase, table: String, column: String): String? {
        val c = db.query("PRAGMA table_info($table)")
        return try {
            val nameIdx = c.getColumnIndex("name")
            val dfltIdx = c.getColumnIndex("dflt_value")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return c.getString(dfltIdx)
            }
            null
        } finally {
            c.close()
        }
    }
}
