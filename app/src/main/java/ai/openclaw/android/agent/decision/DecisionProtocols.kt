package ai.openclaw.android.agent.decision

/**
 * 端侧决策模式（Decision Mode）的协议与提示模板。
 *
 * 背景见 docs/decision-mode-design-2026-09-22.md：
 * LiteRT-LM 0.10.0 的公开 API **不暴露 logits**（javap 全部 64 个类无 Logits/分数类型），
 * 社区 SemIf「截 logits 读分布」路线不可行；改用 `Engine.createSession()` 的低层
 * `runPrefill()` + 1 步 `runDecode()`（temperature=0 / topK=1 → 确定性 argmax）实现
 * 「prefill 决策提示、decode 一个字母」。
 *
 * 代价：拿不到概率分布，置信度改用「选项置换一致性」近似（见 [DecisionStakes]）。
 */

/** 决策的利害关系。HIGH 会触发选项置换双跑来近似置信度。 */
enum class DecisionStakes {
    /** 判错代价低、有可见兜底（通知分类、记忆门）→ 单跑 */
    LOW,

    /** 判错会漏掉必要动作（规则唤醒门、意图路由）→ 双跑一致性校验，不一致则升级 */
    HIGH,
}

data class DecisionRequest(
    /** 调用方追踪用（通知 id / 规则 id / 消息 id） */
    val id: String,
    /** 非结构化输入，< 500 token 硬上限（知识库：输入长了 prefill 优势会被吃掉） */
    val state: String,
    /** 决策问题，建议拆成窄问（知识库：宽泛问 62.6% → 拆 5 问 95.0%） */
    val question: String,
    /** 候选选项，顺序敏感（HIGH 场景会打乱重跑验一致性）。上限 8 个。 */
    val options: List<String>,
    val stakes: DecisionStakes = DecisionStakes.LOW,
)

data class DecisionResult(
    /** 命中的选项下标；null = 引擎不可用 / 输出无法解析 */
    val chosenIndex: Int?,
    /**
     * 置信度近似值：
     * - LOW 单跑 → 固定 [CONFIDENCE_SINGLE]（无分布，不做承诺）
     * - HIGH 双跑一致 → [CONFIDENCE_CONSISTENT]；不一致 → [CONFIDENCE_INCONSISTENT]
     */
    val confidence: Double,
    val latencyMs: Long,
    /** 引擎返回的原始 token，便于排查「模型不吐字母」这类问题 */
    val raw: String? = null,
) {
    val isValid: Boolean get() = chosenIndex != null

    companion object {
        const val CONFIDENCE_SINGLE = 0.7
        const val CONFIDENCE_CONSISTENT = 1.0
        const val CONFIDENCE_INCONSISTENT = 0.0

        fun unavailable(latencyMs: Long = 0, raw: String? = null) =
            DecisionResult(null, CONFIDENCE_INCONSISTENT, latencyMs, raw)
    }
}

/**
 * 通知价值三分类决策表 —— **生产路径与个人中心离线校准共用同一份**。
 *
 * 放在这里而不是各自的文件里，是为了避免「校准测的是 A 表、线上跑的是 B 表」。
 * 每次改表后跑一次 `adb shell am broadcast -a ai.openclaw.android.TEST_DECISION_CALIB`，
 * 用 `DecisionCalibration` 报告的前后对照来判断改动是否有效。
 */
object NotificationValueDecision {

    /**
     * 下标 → SmartFilter 需要的 0~1 分值（沿用 ≥0.3 保留线，只有「无价值」档会被过滤）。
     * **必须与 [OPTIONS] 按索引对齐**，v3 重排后为 [高, 无, 一般]。
     */
    val VALUES = floatArrayOf(0.9f, 0.1f, 0.5f)

    val QUESTION = "这条通知对用户的信息价值属于哪一档？营销推广一律按最低档处理。"

    /**
     * v3（选项重排实验）：顺序 [高, 无, 一般]。
     *
     * 依据 v2 校准（21 条 ×2 轮）的关键发现 —— **判据内容有效，但选项位置决定它是否被读到**：
     * 同一份带硬判据的长「无价值」描述，放在选项第一位（A）时原序一致率只有 76.2%、
     * 分布向末位「高」漂移（11/21）；放在中间位（B）时置换序一致率 90.5%、分布完美 7/7/7。
     * v1 两轮同分（81/81）排除了热身效应，差距就是选项顺序本身。
     * 因此把长描述挪到中间位（B），生产单跑直接吃 v2 置换轮验证过的顺序。
     */
    val OPTIONS = listOf(
        // v4：v3 表现出的唯一「高价值被过滤」错判是「电话 | 未接来电」被判成无价值。
        // 最小干预 —— 只把「来电与未接来电」提到描述首位（原排第 4），其余措辞不动，
        // 这样若分数变化可归因到这一处改动。
        "高价值（必须让用户看到）：来电与未接来电、验证码/登录确认、银行账户变动与支付扣款、" +
            "快递物流与取件码、日程与会议提醒、出行票务、工作协作（审批、被指派、会议邀请）",

        // 校准 v1 的失效点：只写一句「广告、营销、推广」，模型把「限时秒杀」「50 元优惠券」
        // 判成了高价值（7 条错 4 条）。v2 把判定标准和示例直接写死进选项，并且明确「一律归入本档」
        // —— 内容有效，但放第一位时模型不读（v2 原序退步到 76.2%），v3 挪到中间位。
        "无价值（应过滤）：用户不知道也完全不会有损失。以下一律归入本档，不因文案里的" +
            "「限时」「专属」「免费」而改变：限时秒杀/打折促销/清仓/满减；优惠券/红包/返现/" +
            "积分与会员福利；砍价拼团/好友邀请助力；签到打卡/每日任务/登录与升级奖励；" +
            "游戏活动、礼包、版本更新邀约；清理加速省电杀毒类系统提示；标题党资讯与猎奇推荐。" +
            "示例：「限时秒杀！全场一折起，错过再等一年」「您有一张 50 元优惠券即将过期，快来使用」" +
            "「手机垃圾过多，建议立即清理」",

        "一般价值（可留但不紧急）：熟人之间的私聊消息、真实的内容更新订阅、新闻资讯、" +
            "应用商店更新提醒、推荐阅读",
    )
}

object DecisionPrompts {
    /** 选项上限：字母协议下理论可到 26，但实际决策表 >8 就该拆问 */
    const val MAX_OPTIONS = 8

    /**
     * 渲染决策提示。
     *
     * @param options 允许传入重排后的选项集（置换一致性用），返回的下标按传入顺序解释
     */
    fun render(req: DecisionRequest, options: List<String> = req.options): String {
        val letters = options.indices.map { 'A' + it }
        val optionLines = options.mapIndexed { i, text ->
            "${letters[i]} = $text"
        }.joinToString("\n")
        return buildString {
            appendLine("You are a decision engine. Read the input and answer with EXACTLY ONE letter.")
            appendLine()
            appendLine(optionLines)
            appendLine()
            appendLine("Question: ${req.question}")
            appendLine("Do not explain. Answer with a single letter only.")
            appendLine()
            appendLine("Input:")
            appendLine(req.state)
            appendLine()
            append("Answer:")
        }
    }

    /**
     * 批量决策的公共前缀：系统说明 + 选项定义 + 问题，各条只增量 prefill 输入。
     *
     * 对应知识库验证的「并行采样器」思想：一份 state 编码被 N 个问题共享，
     * 这里手动实现为「会话内前缀复用 + 每条增量 prefill」。
     */
    fun renderBatchHeader(
        question: String,
        options: List<String>,
    ): String {
        val optionLines = options.indices.map { i -> "${'A' + i} = ${options[i]}" }.joinToString("\n")
        return buildString {
            appendLine("You are a decision engine. For each item, answer with EXACTLY ONE letter.")
            appendLine()
            appendLine(optionLines)
            appendLine()
            appendLine("Question: $question")
            appendLine("Do not explain. Answer with a single letter only.")
            appendLine()
        }
    }

    /** 批量模式下单条输入的增量片段 */
    fun renderBatchItem(index: Int, state: String): String {
        return "\nItem $index:\n$state\nAnswer:"
    }

    /**
     * 从 decode 出的文本里取答案字母。
     * 容错：模型可能吐 " A" / "A)" / "A." / "The answer is A"，取第一个落在 A..maxLetter 的大写字母。
     */
    fun parseLetter(raw: String?, optionsCount: Int): Int? {
        if (raw.isNullOrBlank()) return null
        val maxLetter = 'A' + optionsCount.coerceAtMost(MAX_OPTIONS) - 1
        for (ch in raw.trim()) {
            val upper = ch.uppercaseChar()
            if (upper in 'A'..maxLetter) return upper - 'A'
        }
        return null
    }

    /** 把选项顺序做一次确定性置换（HIGH 场景第二跑用）：简单循环右移一位 */
    fun permute(options: List<String>): List<String> =
        if (options.size < 2) options else options.takeLast(1) + options.dropLast(1)

    /**
     * [permute] 的逆映射：把「置换后选项列表里选中的下标」还原成原列表的下标。
     *
     * permuted[i] = options[(i - 1 + n) % n]，故还原公式为 (i + n - 1) % n。
     * 双跑一致性判定和校准报告都用它，避免两处各写一遍导致对不上。
     */
    fun unpermuteIndex(permutedIndex: Int, size: Int): Int =
        if (size < 2) permutedIndex else (permutedIndex + size - 1) % size
}
