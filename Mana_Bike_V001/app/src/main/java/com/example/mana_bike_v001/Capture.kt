package com.example.mana_bike_v001

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

enum class Bank(
    val key: String,
    val label: String,
    val packages: Set<String>,
    val keywords: List<String>
) {
    KBANK("kbank", "กสิกรไทย", setOf("com.kasikorn.retail.mbanking.wap"), listOf("k plus", "kplus", "kbank", "กสิกร")),
    SCB("scb", "ไทยพาณิชย์", setOf("com.scb.phone"), listOf("scb", "ไทยพาณิชย์")),
    BBL("bbl", "กรุงเทพ", setOf("com.bbl.mobilebanking"), listOf("bualuang", "bbl", "บัวหลวง", "กรุงเทพ")),
    KTB("ktb", "กรุงไทย", setOf("ktbcs.netbank", "com.ktb.customer.qr"), listOf("krungthai", "ktb", "กรุงไทย", "เป๋าตัง", "paotang")),
    GSB("gsb", "ออมสิน", emptySet(), listOf("mymo", "gsb", "ออมสิน"));

    companion object {
        /**
         * ระบุธนาคารจากชื่อแพ็กเกจ หรือจากชื่อแอป/หัวข้อแจ้งเตือน/ชื่อผู้ส่ง SMS
         * (ไม่ดูเนื้อหาข้อความ เพื่อไม่ให้แอปอื่นที่พูดถึงธนาคารถูกนับมั่ว)
         */
        fun detect(packageName: String?, label: String, titleOrSender: String): Bank? {
            if (packageName != null) {
                values().firstOrNull { packageName in it.packages }?.let { return it }
            }
            val hay = "$label $titleOrSender".lowercase(Locale.ROOT)
            return values().firstOrNull { b -> b.keywords.any { hay.contains(it) } }
        }
    }
}

object BankMessageParser {
    private val INCOMING = listOf(
        "เงินเข้า", "รับเงิน", "รับโอน", "เงินโอนเข้า", "ฝากเงิน", "รับชำระ",
        "received", "deposit", "credited"
    )
    private val OUTGOING = listOf(
        "เงินออก", "โอนเงินออก", "โอนเงินให้", "โอนเงินไป", "ผู้รับเงิน", "ผู้รับชำระ", "ชำระ", "จ่าย", "ถอน", "หักบัญชี",
        "paid", "withdraw", "debit"
    )

    /** คำบอกทิศทางทั้งหมด: (คำ, เป็นเงินเข้าไหม) */
    private val DIRECTION_WORDS = INCOMING.map { it to true } + OUTGOING.map { it to false }
    private val BALANCE_HINTS = listOf("คงเหลือ", "ยอดเงิน", "ยอดใช้ได้", "ค่าธรรมเนียม", "balance", "fee")

    private val SUFFIX = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:บาท|THB|Baht)""", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("""(?:฿|THB)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    private data class Hit(val index: Int, val value: Double)

    private fun findAmounts(text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (re in listOf(SUFFIX, PREFIX)) {
            for (m in re.findAll(text)) {
                val g = m.groups[1] ?: continue
                val v = g.value.replace(",", "").toDoubleOrNull() ?: continue
                if (v > 0) hits.add(Hit(g.range.first, v))
            }
        }
        return hits.sortedBy { it.index }
    }

    /** คืนยอดเงินเข้าถ้าข้อความเป็นการรับเงิน ไม่ใช่ให้คืน null */
    fun parseIncoming(text: String): Double? {
        val lower = text.replace('\n', ' ').lowercase(Locale.ROOT)
        // ทิศทางตัดสินจากคำบอกทิศทางที่ขึ้นก่อนในข้อความ ถ้าเริ่มตำแหน่งเดียวกันให้คำที่ยาวกว่าชนะ
        // ("ผู้รับชำระ" ชนะ "รับชำระ", "รับชำระ" ชนะ "ชำระ" ที่อยู่ข้างใน)
        // ponytail: ใช้คำแรกของข้อความ ถ้าธนาคารไหนขึ้นต้นข้อความเงินออกด้วยคำเงินเข้า ให้เปลี่ยนไปดูคำที่ใกล้ยอดเงินแทน
        val first = DIRECTION_WORDS
            .mapNotNull { (word, incoming) ->
                lower.indexOf(word).takeIf { it >= 0 }?.let { Triple(it, word.length, incoming) }
            }
            .minWithOrNull(compareBy<Triple<Int, Int, Boolean>> { it.first }.thenByDescending { it.second })
            ?: return null
        if (!first.third) return null
        val keywordIndex = first.first

        // หาตัวเลขจาก lower เพื่อให้ตำแหน่งตรงกับที่ใช้ตัดดูคำข้างหน้า (regex ไม่สนตัวพิมพ์อยู่แล้ว)
        val amounts = findAmounts(lower).filterNot { hit ->
            val before = lower.substring(maxOf(0, hit.index - 20), hit.index)
            BALANCE_HINTS.any { before.contains(it) }
        }
        if (amounts.isEmpty()) return null
        return (amounts.firstOrNull { it.index >= keywordIndex } ?: amounts.first()).value
    }
}

object CaptureProcessor {
    /** @param done เรียกเมื่อทำงานเสร็จ (ใช้กับ BroadcastReceiver.goAsync) */
    fun handle(context: Context, channel: String, bank: Bank, text: String, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        Io.executor.execute {
            try {
                if (!Session.isSignedIn) return@execute // ยังไม่ได้ล็อกอิน = ไม่บันทึก
                val settings = SettingsStore(app)
                if (!settings.captureEnabled || !settings.isBankEnabled(bank)) return@execute
                val amount = BankMessageParser.parseIncoming(text) ?: return@execute
                val fingerprint = sha1(bank.key + "|" + text)
                val saved = DatabaseHelper.getRealInstance(app)
                    .insertCaptured(amount, bank.key, channel, fingerprint)
                if (saved) {
                    // พูดก่อนเป็นอย่างแรก ส่วนรีเฟรชหน้าจอ/ซิงก์ค่อยทำตามหลัง
                    if (settings.voiceEnabled) Speaker.speak(app, Speaker.announcement(amount))
                    TransactionEvents.notifyChanged()
                    SyncManager.syncSoon(app)
                }
            } finally {
                done?.invoke()
            }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
