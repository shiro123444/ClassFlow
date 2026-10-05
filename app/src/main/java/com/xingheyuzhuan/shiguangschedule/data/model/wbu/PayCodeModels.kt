package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 一卡通「校园码 / 付款码」的一种支付方式。
 *
 * 对应 `GET /berserker-app/ykt/tsm/codebarPayinfo` 返回数组里的一项：
 * 一个账号下可能同时有校园卡、电子账户、签约银行卡，页面按 [id] 记住上次选中的那一种。
 */
data class CampusPayMethod(
    /** 支付方式自增 id（平台下发的稳定标识，用于记住上次选择）。 */
    val id: Int,
    /** 显示名，如「校园卡」「电子账户」。 */
    val name: String,
    /** 类型：`CARD`（校园卡）/ `ACCOUNT`（电子账户）/ `BANKCARD`（签约银行卡）。 */
    val code: String,
    /** 取码时回传的账号标识。 */
    val account: String,
    /** 取码时回传的支付账号。 */
    val payacc: String,
    /** 取码时回传的支付类型。 */
    val paytype: String,
    /** `1` = 可用；`0` = 不可用（挂失 / 冻结 / 过期 / 未签约…，由 [notShowType] 说明）。 */
    val status: Int,
    /** `1` = 该方式走脱机码（本地离线生成），原生页面暂不接管，需回落到官方页面。 */
    val voucherStatus: Int,
    /** 余额（元）。仅校园卡与电子账户有值（银行卡无余额语义）。 */
    val balance: Double? = null,
    /** 签约银行卡尾号（仅 `BANKCARD`）。 */
    val bankCardTail: String? = null,
    /** 不可用原因码：`lostflag` / `freezeflag` / `expdate` / `buildBankCard`。 */
    val notShowType: String? = null,
    /** 不可用原因附带的办理地址（官方页面链接）。 */
    val website: String? = null
) {
    /** 这张卡/账户当前是否可用（可用才会去取码）。 */
    val isAvailable: Boolean get() = status == 1

    /** 是否需要脱机码（页面无法原生展示）。 */
    val needsVoucherCode: Boolean get() = voucherStatus == 1
}

/**
 * 一批付款码。
 *
 * 平台一次下发若干个码（通常 10 个），页面展示队首，按 [expiresSeconds] 逐个向前推进；
 * 顺序消费可以避免同一张码被两个收银台同时扫到。
 */
data class PayCodeBatch(
    /** 单个码的有效秒数。 */
    val expiresSeconds: Int,
    /** 码列表；页面始终展示第一个，用掉后丢弃。 */
    val codes: List<String>
)

/**
 * 付款码轮换队列（一卡通 H5「校园码」同款节奏的纯逻辑实现）。
 *
 * 规则（与平台 `campusCode` 页面一致）：
 * - 一次取 [PayCodeBatch.codes] 个码，**队首**是当前要展示的码；
 * - 每 [effectiveSlotSeconds] 秒（H5 默认 60s，取 `min(60, 服务端 expires)`）前进一格；
 * - 前进时顺手丢掉「按取码时刻算已超出有效期」的码 —— 第 i 个码（自取码时刻 0 起算）的失效时刻是
 *   `取码时刻 + expires × (i + 1 + pad)`，其中 `pad = max(0, 10 - 队列长度)`，是 H5 对不足 10 个码的补偿；
 * - 队列被走空 / 全部过期 → [advance] 返回 null，调用方重新取一批。
 *
 * 抽成纯类是为了能单测「取码 → 连续翻码 → 过期重取」这条时间轴，
 * 页面与 ViewModel 只关心「现在展示哪个码、还剩几秒」。
 */
class PayCodeQueue(
    codes: List<String>,
    /** 服务端下发的单码有效秒数。 */
    val expiresSeconds: Int,
    /** 取码完成的时刻（毫秒时间戳）。 */
    private val fetchedAtMs: Long,
    /** 翻码节奏（秒）；H5 默认 60，可用 `codeRefreshTime` 覆盖。 */
    slotSeconds: Int = DEFAULT_SLOT_SECONDS,
    /** 当前时刻（毫秒）；单测注入假时钟。 */
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** H5 `codeRefreshTime` 的默认值：每 60 秒翻一格。 */
        const val DEFAULT_SLOT_SECONDS = 60

        /** H5 里 `10 - barcode.length` 的基准长度，用于不足 10 个码时的有效期补偿。 */
        private const val REFERENCE_CODE_COUNT = 10
    }

    private val pending: ArrayDeque<String> = ArrayDeque(codes)

    /**
     * 实际翻码间隔：不能慢于单码有效期，否则会把已经失效的码摆在屏幕上。
     */
    val effectiveSlotSeconds: Int =
        minOf(slotSeconds.coerceAtLeast(1), expiresSeconds.coerceAtLeast(1))

    private var nextAdvanceAtMs: Long = fetchedAtMs + effectiveSlotSeconds * 1000L

    /** 当前展示的码；null 表示这批已用尽（需要重新取码）。 */
    val current: String? get() = pending.firstOrNull()

    /** 这批码是否已经用完。 */
    val isExhausted: Boolean get() = pending.isEmpty()

    /** 当前码还能展示多少秒（0 = 该翻页了）。 */
    fun secondsLeft(): Int {
        val remainMs = nextAdvanceAtMs - nowMs()
        return if (remainMs <= 0) 0 else ((remainMs + 999) / 1000).toInt()
    }

    /**
     * 前进一格：丢弃当前码，再跳过所有已过期的码。
     *
     * @return 新的当前码；null 表示整批已用尽，调用方应重新取码。
     */
    fun advance(): String? {
        val now = nowMs()
        if (pending.isNotEmpty()) pending.removeFirst()
        dropExpired(now)
        nextAdvanceAtMs = now + effectiveSlotSeconds * 1000L
        return current
    }

    /** 丢掉落队已超有效期的前缀。 */
    private fun dropExpired(now: Long) {
        while (pending.isNotEmpty()) {
            // 与 H5 一致：pad 随「剩余长度」逐格重算 —— 丢一个码，后面每个码的有效期就多一格，
            // 所以必须每轮重新取，否则一次调用会把整批码全部丢光。
            val pad = (REFERENCE_CODE_COUNT - pending.size).coerceAtLeast(0)
            val deadline = fetchedAtMs + expiresSeconds.coerceAtLeast(1).toLong() *
                (1 + pad) * 1000L
            if (deadline <= now) pending.removeFirst() else break
        }
    }
}
