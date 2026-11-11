package com.example.mana_bike_v001

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** เธรดสำหรับงานฐานข้อมูล (ทำทีละงาน ไม่บล็อก UI) */
object Io {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
}

/** แจ้งหน้าจอที่เปิดอยู่ให้โหลดข้อมูลใหม่เมื่อมียอดเข้า */
object TransactionEvents {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun register(l: () -> Unit) { listeners.add(l) }
    fun unregister(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { main.post { listeners.forEach { it() } } }
}

/** เดือน/ปีที่เลือกอยู่ (ค.ศ.) ใช้ร่วมกันระหว่างหน้าภาพรวมและหน้าประวัติ */
object AppState {
    /**
     * สิ่งที่เลือกดูในหน้าประวัติ (ปี ค.ศ., เดือน, วัน) โดยวัน = 0 คือดูทั้งเดือน
     * null = ทั้งเดือนของเดือนปัจจุบัน (ค่าเริ่มต้น)
     */
    var histDate: Triple<Int, Int, Int>? = null

    fun currentMonth(): Triple<Int, Int, Int> {
        val t = today()
        return Triple(t.first, t.second, 0)
    }

    fun today(): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    var year: Int = Calendar.getInstance().get(Calendar.YEAR)
    var month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
}

object Fmt {
    private val thaiMonths = arrayOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    )

    private val thaiMonthsShort = arrayOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    )
    private val thaiWeekdays = arrayOf("อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์")

    /** รูปแบบ "dd/MM/yyyy" (ค.ศ.) ใช้ค้นหาในฐานข้อมูล */
    fun dayKey(year: Int, month: Int, day: Int): String =
        String.format(Locale.US, "%02d/%02d/%04d", day, month, year)

    /** เช่น "7 พฤศจิกายน 2569" */
    fun dateLabel(year: Int, month: Int, day: Int): String =
        "$day ${thaiMonths[month - 1]} ${year + 543}"

    /** เช่น "วันศุกร์" */
    fun weekdayName(year: Int, month: Int, day: Int): String {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, day) }
        return "วัน" + thaiWeekdays[c.get(Calendar.DAY_OF_WEEK) - 1]
    }

    fun dayShort(month: Int, day: Int): String = "$day ${thaiMonthsShort[month - 1]}"

    fun dayTitle(year: Int, month: Int, day: Int): String {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, day) }
        val weekday = thaiWeekdays[c.get(Calendar.DAY_OF_WEEK) - 1]
        return "วัน$weekday" + "ที่ $day ${thaiMonths[month - 1]} ${year + 543}"
    }

    fun money(v: Double): String = "฿" + String.format(Locale.US, "%,.2f", v)
    fun moneyShort(v: Double): String = "฿" + String.format(Locale.US, "%,.0f", v)
    fun monthKey(year: Int, month: Int): String = String.format(Locale.US, "%02d/%04d", month, year)
    fun monthTitle(year: Int, month: Int): String = "${thaiMonths[month - 1]} ${year + 543}"

    fun isFuture(year: Int, month: Int): Boolean {
        val now = Calendar.getInstance()
        val ny = now.get(Calendar.YEAR)
        val nm = now.get(Calendar.MONTH) + 1
        return year > ny || (year == ny && month > nm)
    }

    /** จำนวนวันที่ใช้หารยอดเฉลี่ย: เดือนปัจจุบันนับถึงวันนี้ เดือนที่ผ่านมาแล้วนับทั้งเดือน */
    fun daysForAverage(year: Int, month: Int): Int {
        val now = Calendar.getInstance()
        if (year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) + 1) {
            return now.get(Calendar.DAY_OF_MONTH)
        }
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
}

object Nav {
    fun setup(activity: Activity, nav: BottomNavigationView, current: Int) {
        nav.selectedItemId = current
        nav.setOnItemSelectedListener { item ->
            val target = item.itemId
            if (target != current) {
                val cls = when (target) {
                    R.id.nav_history -> HistoryActivity::class.java
                    R.id.nav_settings -> SettingsActivity::class.java
                    else -> MainActivity::class.java
                }
                val intent = Intent(activity, cls).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (target == R.id.nav_overview) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.startActivity(intent)
                if (current != R.id.nav_overview && target != R.id.nav_overview) activity.finish()
            }
            false // ไม่เปลี่ยนไฮไลต์ในหน้านี้ เพราะกำลังย้ายหน้า
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("mana_settings", Context.MODE_PRIVATE)

    // แยกไฟล์ต่างหาก ไม่ถูกสำรองขึ้นบัญชี Google (โหมดตัวอย่างไม่ควรตามไปเครื่องใหม่)
    private val runtime = context.applicationContext
        .getSharedPreferences("mana_runtime", Context.MODE_PRIVATE)

    var demoMode: Boolean
        get() = runtime.getBoolean("demo_mode", false)
        set(v) { runtime.edit().putBoolean("demo_mode", v).apply() }

    var captureEnabled: Boolean
        get() = prefs.getBoolean("capture_enabled", true)
        set(v) { prefs.edit().putBoolean("capture_enabled", v).apply() }

    // ---- บัญชีผู้ใช้และสถานะซิงก์ (เก็บในไฟล์ที่ไม่ถูกสำรองขึ้น Google) ----
    var ownerUid: String?
        get() = runtime.getString("owner_uid", null)
        set(v) { runtime.edit().putString("owner_uid", v).apply() }

    var lastPullMillis: Long
        get() = runtime.getLong("last_pull", 0L)
        set(v) { runtime.edit().putLong("last_pull", v).apply() }

    var lastSyncAt: Long
        get() = runtime.getLong("last_sync_at", 0L)
        set(v) { runtime.edit().putLong("last_sync_at", v).apply() }

    var lastSyncError: String?
        get() = runtime.getString("last_sync_error", null)
        set(v) { runtime.edit().putString("last_sync_error", v).apply() }

    var profileName: String
        get() = runtime.getString("profile_name", "") ?: ""
        set(v) { runtime.edit().putString("profile_name", v).apply() }

    var profileShop: String
        get() = runtime.getString("profile_shop", "") ?: ""
        set(v) { runtime.edit().putString("profile_shop", v).apply() }

    var profilePhone: String
        get() = runtime.getString("profile_phone", "") ?: ""
        set(v) { runtime.edit().putString("profile_phone", v).apply() }

    fun clearAccount() {
        runtime.edit()
            .remove("owner_uid").remove("last_pull").remove("last_sync_at").remove("last_sync_error")
            .remove("profile_name").remove("profile_shop").remove("profile_phone")
            .putBoolean("demo_mode", false)
            .apply()
    }

    var voiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", true)
        set(v) { prefs.edit().putBoolean("voice_enabled", v).apply() }

    fun isBankEnabled(bank: Bank): Boolean =
        prefs.getBoolean("bank_${bank.key}", bank != Bank.GSB)

    fun setBankEnabled(bank: Bank, enabled: Boolean) {
        prefs.edit().putBoolean("bank_${bank.key}", enabled).apply()
    }
}
