package ai.openclaw.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.openclaw.android.data.model.CachedDataEntity
import ai.openclaw.android.data.model.DynamicSkillEntity
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryVectorEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.data.model.SummaryEntity
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.data.model.SessionStatus
import ai.openclaw.android.security.SecurityKeyManager
import ai.openclaw.android.trigger.models.TriggerRule
import ai.openclaw.android.trigger.models.TriggerLog
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import ai.openclaw.android.trigger.v2.models.TriggerEventEntity
import ai.openclaw.android.trigger.v2.dao.TriggerEventDao
import ai.openclaw.android.data.dao.CachedDataDao
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class, MemoryEntity::class, MemoryVectorEntity::class, DynamicSkillEntity::class, TriggerRule::class, TriggerLog::class, TriggerEventEntity::class, CachedDataEntity::class],
    version = 8,
    // 开启 schema 导出（输出到 app/schemas），后续迁移可校验、可自动生成
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryVectorDao(): MemoryVectorDao
    abstract fun memoryFtsDao(): MemoryFtsDao
    abstract fun dynamicSkillDao(): DynamicSkillDao
    abstract fun triggerRuleDao(): TriggerRuleDao
    abstract fun triggerLogDao(): TriggerLogDao
    abstract fun triggerEventDao(): TriggerEventDao
    abstract fun cachedDataDao(): CachedDataDao

    companion object {
        const val DATABASE_NAME = "openclaw_database"
        private const val TAG = "AppDatabase"

        init {
            System.loadLibrary("sqlcipher")
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    buildDatabase(context).also { db ->
                        // 强制打开：Room 是懒打开的，迁移与 schema 校验发生在首次打开时。
                        // 必须在这里触发，下面的兜底 catch 才接得到迁移失败——否则异常
                        // 散落在各 DAO 调用点，INSTANCE 留下坏实例且永不自愈。
                        // 调用方均在后台线程（MainActivity IO scope / Service / Worker）。
                        db.openHelper.writableDatabase
                        INSTANCE = db
                    }
                } catch (e: Exception) {
                    // 迁移/校验失败时 Room 会抛 IllegalStateException。此前用
                    // fallbackToDestructiveMigration() 让它静默清空加密库（用户数据全丢、无日志）。
                    // 这里改为显式兜底：上报 + 重建，保证 App 可启动，且失败原因可追溯。
                    android.util.Log.e(TAG, "Failed to open database, recreating: ${e.message}", e)
                    com.tencent.bugly.crashreport.CrashReport.postCatchedException(e)
                    context.applicationContext.deleteDatabase(DATABASE_NAME)
                    buildDatabase(context).also { db ->
                        db.openHelper.writableDatabase
                        INSTANCE = db
                    }
                }
            }
        }

        /**
         * 判断表中是否已存在某列——用于让 ALTER 类迁移可重复执行（幂等）。
         * v1 时代未导出 schema，无法确知线上库的实际形态，因此所有迁移都写成幂等形式。
         */
        private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            val cursor = db.query("PRAGMA table_info($table)")
            return try {
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
                false
            } finally {
                cursor.close()
            }
        }

        /**
         * v1 → v2：补齐 v2 期应有的全部表。
         *
         * 背景：v1 时代 exportSchema=false、且历史上一直开着 fallbackToDestructiveMigration()，
         * 导致 v1 老库升级到 v8 时没有迁移路径，Room 会**静默清空加密库**（会话/记忆/技能全丢）。
         * 这里补上 1→2，全部语句用 IF NOT EXISTS 写成幂等形式——无论线上库实际已有哪些表，
         * 都不会报错、也不会丢数据。后续 2→8 的迁移同样保持幂等。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sessions (" +
                        "sessionId TEXT NOT NULL, " +
                        "name TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "lastActiveAt INTEGER NOT NULL, " +
                        "tokenCount INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "PRIMARY KEY(sessionId)" +
                        ")"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS messages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "tokenCount INTEGER NOT NULL, " +
                        "FOREIGN KEY(sessionId) REFERENCES sessions(sessionId) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS summaries (" +
                        "sessionId TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "messageRangeStart INTEGER NOT NULL, " +
                        "messageRangeEnd INTEGER NOT NULL, " +
                        "compressedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(sessionId)" +
                        ")"
                )

                // v2 形态的 memories：不含 version（version 由 2→3 追加）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "memoryType TEXT NOT NULL, " +
                        "priority INTEGER NOT NULL, " +
                        "source TEXT, " +
                        "tags TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "lastAccessedAt INTEGER NOT NULL, " +
                        // 不带 DEFAULT：Room 由实体生成的建表语句里没有 DEFAULT，
                        // 迁移后的结构必须与 Room 预期逐字一致，否则校验失败。
                        "accessCount INTEGER NOT NULL" +
                        ")"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_vectors (" +
                        "memoryId INTEGER NOT NULL, " +
                        "vector TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(memoryId)" +
                        ")"
                )
            }
        }

        /**
         * v2 → v3：memories 增加 version 列。
         *
         * 这里**不使用** `ALTER TABLE ... ADD COLUMN version INTEGER NOT NULL DEFAULT 1`：
         * SQLite 给 NOT NULL 列加列必须带 DEFAULT，而这个 DEFAULT 会留在表结构里；
         * Room 的实体声明（`version: Int = 1`）并不会生成 DEFAULT，迁移后校验 schema 时
         * 会判定 default 不一致而抛 IllegalStateException。改为重建表，得到与
         * app/schemas/.../8.json 完全一致的结构（无 DEFAULT）。
         *
         * v2 形态 = v8 形态去掉 version（3→8 的迁移都没有再改动 memories），因此列清单是确定的。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 幂等：列已存在则跳过，避免重复执行崩溃
                if (columnExists(db, "memories", "version")) return

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "memoryType TEXT NOT NULL, " +
                        "priority INTEGER NOT NULL, " +
                        "source TEXT, " +
                        "tags TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "lastAccessedAt INTEGER NOT NULL, " +
                        "accessCount INTEGER NOT NULL, " +
                        "version INTEGER NOT NULL" +
                        ")"
                )
                db.execSQL(
                    "INSERT INTO memories_new " +
                        "(id, content, memoryType, priority, source, tags, createdAt, lastAccessedAt, accessCount, version) " +
                        "SELECT id, content, memoryType, priority, source, tags, createdAt, lastAccessedAt, accessCount, 1 " +
                        "FROM memories"
                )
                db.execSQL("DROP TABLE memories")
                db.execSQL("ALTER TABLE memories_new RENAME TO memories")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create dynamic_skills with ALL columns to avoid migration validation issues.
                // 去掉所有 DEFAULT：Room 由实体生成的建表语句不含 DEFAULT（见 app/schemas/.../8.json），
                // 迁移后结构必须与之逐字一致，否则 Room 校验 schema 会失败。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dynamic_skills (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "description TEXT NOT NULL, " +
                    "version TEXT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "instructions TEXT NOT NULL, " +
                    "script TEXT NOT NULL, " +
                    "toolsJson TEXT NOT NULL, " +
                    "permissions TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "lastUsedAt INTEGER NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "approvalPrefsJson TEXT NOT NULL" +
                    ")"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: MIGRATION_3_4 already created dynamic_skills with all columns
                // This migration just bumps the version for Room schema validation
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create trigger_rules table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trigger_rules (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "filtersJson TEXT NOT NULL, " +
                    "actionJson TEXT NOT NULL, " +
                    "cooldownMs INTEGER NOT NULL, " +
                    "scheduleCron TEXT, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL" +
                    ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_source ON trigger_rules(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_enabled ON trigger_rules(enabled)")

                // Create trigger_logs table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trigger_logs (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "ruleId TEXT NOT NULL, " +
                    "eventId TEXT NOT NULL, " +
                    "executedAt INTEGER NOT NULL, " +
                    "actionType TEXT NOT NULL, " +
                    "success INTEGER NOT NULL, " +
                    "error TEXT, " +
                    "result TEXT" +
                    ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_logs_ruleId ON trigger_logs(ruleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_logs_executedAt ON trigger_logs(executedAt)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create trigger_events_v2 table for AI-driven trigger decision logging
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trigger_events_v2 (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "triggerId TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "context TEXT NOT NULL, " +
                    "decision TEXT NOT NULL, " +
                    "userFeedback TEXT NOT NULL, " +
                    "success INTEGER NOT NULL, " +
                    "result TEXT, " +
                    "error TEXT" +
                    ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_events_v2_triggerId ON trigger_events_v2(triggerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_events_v2_timestamp ON trigger_events_v2(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_events_v2_decision ON trigger_events_v2(decision)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_events_v2_userFeedback ON trigger_events_v2(userFeedback)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cached_data (" +
                            "id TEXT PRIMARY KEY NOT NULL, " +
                            "type TEXT NOT NULL, " +
                            "query_key TEXT NOT NULL, " +
                            "data_json TEXT NOT NULL, " +
                            "fetched_at INTEGER NOT NULL, " +
                            "expires_at INTEGER NOT NULL, " +
                            "source TEXT NOT NULL, " +
                            "hit_count INTEGER NOT NULL" +
                            ")"
                )
            }
        }

        @Suppress("DEPRECATION")
        private fun buildDatabase(context: Context): AppDatabase {
            val keyManager = SecurityKeyManager(context)
            val passphrase = keyManager.getOrCreateDatabaseKey()
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                )
                // 只在降级时允许重建（降级本就无法保留数据）；升级路径必须显式提供 Migration，
                // 缺失时由 getInstance() 兜底并记录，而不再由 Room 静默清库。
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL(
                            "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(content, tags)"
                        )
                    }
                })
                .build()
        }
    }
}
