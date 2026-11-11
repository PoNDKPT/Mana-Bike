package com.example.mana_bike_v001

import java.util.Calendar
import java.util.Random

/** ข้อมูลสมมติสำหรับดูการแสดงผล (ไม่เกี่ยวกับข้อมูลจริง) */
object DemoData {
    private val amounts = doubleArrayOf(
        20.0, 35.0, 50.0, 60.0, 80.0, 100.0, 120.0, 150.0, 199.0,
        250.0, 300.0, 450.0, 500.0, 890.0, 1200.0, 1500.0, 2500.0
    )
    private val sources = listOf(
        "kbank" to "notif:com.kasikorn.retail.mbanking.wap",
        "scb" to "notif:com.scb.phone",
        "bbl" to "notif:com.bbl.mobilebanking",
        "ktb" to "notif:ktbcs.netbank",
        "kbank" to "notif:jp.naver.line.android",
        "scb" to "sms"
    )

    /**
     * สร้างข้อมูล 2 ปีเต็มก่อนหน้า + ปีนี้ถึงวันนี้
     * วันนี้ 23 รายการ เมื่อวาน 12 รายการ (เพื่อดูรายการยาวๆ) วันอื่นสุ่ม 0-20 รายการ
     */
    fun generate(): List<BackupRow> {
        val rnd = Random(2569L)
        val now = Calendar.getInstance()
        val nowMillis = now.timeInMillis
        val day = Calendar.getInstance().apply {
            set(Calendar.YEAR, now.get(Calendar.YEAR) - 2)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val todayKey = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
        val yesterdayKey = yesterday.get(Calendar.YEAR) * 1000 + yesterday.get(Calendar.DAY_OF_YEAR)

        val rows = ArrayList<BackupRow>()
        while (day.timeInMillis <= nowMillis) {
            val key = day.get(Calendar.YEAR) * 1000 + day.get(Calendar.DAY_OF_YEAR)
            val dow = day.get(Calendar.DAY_OF_WEEK)
            val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            val isToday = key == todayKey
            val count = when {
                isToday -> 23
                key == yesterdayKey -> 12
                rnd.nextInt(14) == 0 -> 0
                weekend -> 6 + rnd.nextInt(15)
                else -> 2 + rnd.nextInt(10)
            }
            val dayStart = day.timeInMillis
            repeat(count) {
                val ts: Long = if (isToday) {
                    val span = (nowMillis - dayStart - 60_000L).coerceAtLeast(1L)
                    dayStart + 60_000L + (rnd.nextDouble() * span).toLong()
                } else {
                    dayStart + (8 * 60L + rnd.nextInt(13 * 60)) * 60_000L + rnd.nextInt(60) * 1000L
                }
                val src = sources[rnd.nextInt(sources.size)]
                rows.add(BackupRow(amounts[rnd.nextInt(amounts.size)], ts, src.first, src.second))
            }
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return rows
    }
}
