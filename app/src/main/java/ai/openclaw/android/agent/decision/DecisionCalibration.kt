package ai.openclaw.android.agent.decision

import android.util.Log

/**
 * 端侧决策模式的离线校准。
 *
 * 为什么必须做：M0 探针首跑 5 条里判错 1 条（淘宝红包被判成「高价值」），而且
 * **从来没输出过 A（无价值）** —— 这是典型的选项位置偏置。相比生成路径，
 * 决策模式的失效是静默的：拿不到 logits 就没有置信度，判错了也不知道。
 * 所以校准必须做成「随时能跑、跑完出数字」的工具，而不是依赖人写 prompt 时手感。
 *
 * 跑法：adb shell am broadcast -a ai.openclaw.android.TEST_DECISION_CALIB（tag = DecisionCalib）
 *
 * 输出三组数字：
 * 1. **一致率**：预测档 vs 人工标注档，原序一轮 + 选项置换一轮各算一次；
 * 2. **预测分布**：三档各被选中多少次 —— 某一档长期为 0 就说明存在系统性偏置，
 *    此时一致率再高也不能信（比如全判「一般」也能在某个分布上拿到不错的一致率）；
 * 3. **位置稳定性**：同一条样本在两种选项顺序下是否选同一档 —— 拿不到概率分布时，
 *    这是最接近「置信度」的可用指标（见 DecisionProtocols 的注释）。
 */
object DecisionCalibration {

    private const val TAG = "DecisionCalib"

    /**
     * 必须与生产同表 —— 直接引用 [NotificationValueDecision]，不做任何本地副本。
     *
     * 历史教训：v1 校准曾把「淘宝限时秒杀」判成高价值，而当时一致率仍有 81%，
     * 是混淆矩阵而非一致率指出了这个偏置。所以每次改表都要重跑并对比这两处。
     */
    val OPTIONS = NotificationValueDecision.OPTIONS

    val QUESTION = NotificationValueDecision.QUESTION

    /**
     * 档位简称，报告用 —— **必须与 NotificationValueDecision.OPTIONS 顺序对齐**（v3：高/无/一般）。
     * 选项重排时这里与 SAMPLES 的 expected 索引是仅有的两处需要同步换算的地方。
     */
    private val LEVEL_NAMES = listOf("高", "无", "一般")

    /** 与 OPTIONS 顺序对齐的档位索引。**改选项顺序必须同步这里**，否则校准报告整体错位。 */
    private const val TIER_HIGH = 0
    private const val TIER_NONE = 1
    private const val TIER_NORMAL = 2

    data class Sample(
        /** 与生产同构：`来源App | 标题 | 正文` */
        val text: String,
        /** 人工标注：TIER_HIGH / TIER_NONE / TIER_NORMAL（与 OPTIONS 顺序对齐） */
        val expected: Int,
    )

    /**
     * 标注集：三档各 7 条，覆盖生产最常见的通知形态。
     *
     * 标注口径统一为「用户是否需要知道这件事」，而非「用户是否会点开」——
     * 后者会把秒杀广告带偏到「一般」。
     */
    val SAMPLES: List<Sample> = listOf(
        // ---- 无价值 ----
        Sample("淘宝 | 限时秒杀 | 全场一折起，快来抢购，错过再等一年", TIER_NONE),
        Sample("拼多多 | 砍价提醒 | 你的好友邀请你帮忙砍一刀，立即参与", TIER_NONE),
        Sample("梦幻西游 | 每日签到 | 今日签到奖励已到账，明日再来可领双倍奖励", TIER_NONE),
        Sample("京东 | 新品首发 | 某某新品首发预约开启，立即预约享优先购买权", TIER_NONE),
        Sample("清理大师 | 清理提醒 | 手机垃圾过多，建议立即清理释放 2GB 空间", TIER_NONE),
        Sample("WiFi万能钥匙 | 免费WiFi | 附近发现 20 个免费WiFi，一键连接", TIER_NONE),
        Sample("唯品会 | 优惠券 | 您有一张 50 元优惠券即将过期，快来使用", TIER_NONE),

        // ---- 一般价值 ----
        Sample("微信 | 张三 | 晚上一起吃饭吗？", TIER_NORMAL),
        Sample("今日头条 | 热点速览 | 本地发生一件大事，点击查看详细报道", TIER_NORMAL),
        Sample("QQ | 群消息 | 李同学在班级群里 @ 了你", TIER_NORMAL),
        Sample("应用商店 | 更新提醒 | 有 3 个应用有新版本可供更新", TIER_NORMAL),
        Sample("网易云音乐 | 新歌推送 | 你关注的歌手发布了全新专辑", TIER_NORMAL),
        Sample("QQ邮箱 | 订阅邮件 | 本周行业周报已送达，点击查看全文", TIER_NORMAL),
        Sample("知乎 | 内容推荐 | 根据你最近的浏览，推荐你关注这个问题", TIER_NORMAL),

        // ---- 高价值 ----
        Sample("招商银行 | 账户变动 | 您尾号 8821 的账户支出人民币 128.00 元", TIER_HIGH),
        Sample("中国移动 | 验证码 | 您的登录验证码是 482910，5 分钟内有效", TIER_HIGH),
        Sample("顺丰速运 | 快递通知 | 您的快递已到达丰巢快递柜，取件码 8832", TIER_HIGH),
        Sample("系统日历 | 会议提醒 | 项目评审会议将于 14:00 开始", TIER_HIGH),
        Sample("电话 | 未接来电 | 您有 1 个未接来电，来自 李四", TIER_HIGH),
        Sample("钉钉 | 工作通知 | 张三邀请你参加 14:00 的项目评审会议", TIER_HIGH),
        Sample("12306 | 出行提醒 | 您购买的 G101 次列车将于明日 8:00 发车", TIER_HIGH),
    )

    /** 单轮硬截止：引擎阻塞调用本身打不断，超时只让「报告能落地、日志能指出卡在哪」 */
    private val ROUND_TIMEOUT_MS = 45_000L

    private suspend fun withRoundTimeout(
        timeoutMs: Long,
        block: suspend () -> List<DecisionResult>,
    ): List<DecisionResult> = try {
        kotlinx.coroutines.withTimeout(timeoutMs) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Log.e(TAG, "单轮超过 ${timeoutMs}ms 未完成 → 该轮结果作废。看上面 batch[i] 日志可定位卡在第几条")
        emptyList()
    }

    suspend fun run(runner: OnDeviceDecisionRunner, perRoundTimeoutMs: Long = 60_000L): String {
        val sb = StringBuilder()
        val items = SAMPLES.mapIndexed { i, s -> "s$i" to s.text }

        // ---- Round 1：原顺序 ----
        // 引擎调用是阻塞的，挂起时协程超时打不断；加这层是为了至少不让报告永远不落地，
        // 配合下面 Log 能看出是「整体没跑完」还是「某一条卡住」。
        val t1Start = System.currentTimeMillis()
        val r1 = withRoundTimeout(ROUND_TIMEOUT_MS) {
            runner.decideBatch(QUESTION, OPTIONS, items, timeoutMs = perRoundTimeoutMs)
        }
        val t1 = System.currentTimeMillis() - t1Start

        // ---- Round 2：选项顺序循环右移一位 ----
        val permuted = DecisionPrompts.permute(OPTIONS)
        val t2Start = System.currentTimeMillis()
        val r2 = withRoundTimeout(ROUND_TIMEOUT_MS) {
            runner.decideBatch(QUESTION, permuted, items, timeoutMs = perRoundTimeoutMs)
        }
        val t2 = System.currentTimeMillis() - t2Start

        // 轮次作废时结果列表是空的，这里按样本数补齐，避免报告本身数组越界
        val pred1 = List(SAMPLES.size) { i -> r1.getOrNull(i)?.chosenIndex }
        val pred2 = List(SAMPLES.size) { i ->
            r2.getOrNull(i)?.chosenIndex?.let { idx -> DecisionPrompts.unpermuteIndex(idx, OPTIONS.size) }
        }
        val expected = SAMPLES.map { it.expected }

        // ---- 逐条结果 ----
        sb.appendLine("=== 决策模式校准报告（${SAMPLES.size} 条标注集）===")
        sb.appendLine()
        sb.appendLine("逐条：[期望 / 原序预测 / 置换预测]")
        for (i in SAMPLES.indices) {
            val e = expected[i]
            val p1 = pred1[i]
            val p2 = pred2[i]
            val ok1 = if (p1 == e) "✓" else "✗"
            val ok2 = if (p2 == e) "✓" else "✗"
            sb.appendLine(
                "${String.format("%02d", i + 1)} [" +
                    "${LEVEL_NAMES[e]}/" +
                    "${p1?.let { LEVEL_NAMES[it] } ?: "?"}$ok1/" +
                    "${p2?.let { LEVEL_NAMES[it] } ?: "?"}$ok2] " +
                    SAMPLES[i].text.take(46)
            )
        }
        sb.appendLine()

        // ---- 一致率 ----
        val hit1 = pred1.indices.count { pred1[it] == expected[it] }
        val hit2 = pred2.indices.count { pred2[it] == expected[it] }
        val valid1 = pred1.count { it != null }
        val valid2 = pred2.count { it != null }
        sb.appendLine("一致率：原序 ${pct(hit1, SAMPLES.size)}  置换序 ${pct(hit2, SAMPLES.size)}")
        sb.appendLine("可解析：原序 $valid1/${SAMPLES.size}  置换序 $valid2/${SAMPLES.size}（解析不出 = 模型没吐合法字母）")
        sb.appendLine()

        // ---- 预测分布：暴露「某一档永远选不到」 ----
        sb.appendLine("预测分布（对照：标注集 ${dist(expected)}）")
        sb.appendLine("  原序   ${dist(pred1)}")
        sb.appendLine("  置换序 ${dist(pred2)}")
        val minCount = LEVEL_NAMES.indices.minOf { l ->
            pred1.count { it == l }.coerceAtMost(pred2.count { it == l })
        }
        if (minCount == 0) {
            sb.appendLine("  ⚠ 存在至少一档从未被选中 → 选项偏置未消除，一致率不可信")
        }
        sb.appendLine()

        // ---- 位置稳定性 ----
        val stable = pred1.indices.count { pred1[it] != null && pred1[it] == pred2[it] }
        sb.appendLine("位置稳定性：同一条在两种选项顺序下选择一致 $stable/${SAMPLES.size}（${pct(stable, SAMPLES.size)}）")
        sb.appendLine("  这是拿不到 logits 时最接近置信度的指标；低于 70% 说明该决策表不可用于生产。")
        sb.appendLine()

        // ---- 混淆矩阵 ----
        sb.appendLine("混淆矩阵（行=期望，列=原序预测，单位：条）")
        sb.appendLine("          " + LEVEL_NAMES.joinToString("  ") { "预测${it}" })
        for (e in LEVEL_NAMES.indices) {
            val row = LEVEL_NAMES.indices.map { p ->
                pred1.indices.count { expected[it] == e && pred1[it] == p }
            }
            sb.appendLine("期望${LEVEL_NAMES[e]}      ${row.joinToString("       ") { String.format("%2d", it) }}")
        }
        sb.appendLine()

        // ---- 延迟 ----
        val perItem1 = if (SAMPLES.isNotEmpty()) t1 / SAMPLES.size else 0
        val perItem2 = if (SAMPLES.isNotEmpty()) t2 / SAMPLES.size else 0
        sb.appendLine("延迟：原序 ${t1}ms（${perItem1}ms/条）  置换序 ${t2}ms（${perItem2}ms/条）")

        val report = sb.toString()
        Log.i(TAG, "\n$report")
        return report
    }

    private fun dist(preds: List<Int?>): String = LEVEL_NAMES.indices.joinToString("  ") { l ->
        "${LEVEL_NAMES[l]}=${preds.count { it == l }}"
    }

    private fun pct(hit: Int, total: Int): String =
        if (total == 0) "n/a" else String.format("%.1f%%", hit * 100.0 / total)
}
