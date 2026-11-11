package com.example.mana_bike_v001

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Calendar

/**
 * หน้าประวัติ
 * - ค่าเริ่มต้นคือทั้งเดือนปัจจุบัน
 * - แตะปุ่มด้านบนเพื่อเปิดปฏิทิน: เลื่อนเดือนด้วย ‹ › แล้วกดตกลงเพื่อดูทั้งเดือน
 *   หรือแตะเลือกวันก่อนกดตกลงเพื่อดูเฉพาะวันนั้น (วันที่มีรายการเป็นสีส้มอ่อน)
 */
class HistoryActivity : AppCompatActivity() {

    // เรียกใหม่ทุกครั้ง เพราะสลับโหมดข้อมูลตัวอย่างได้
    private val db: DatabaseHelper get() = DatabaseHelper.getInstance(this)
    private val adapter = TransactionAdapter()

    private lateinit var tvDate: TextView
    private lateinit var tvScope: TextView
    private lateinit var tvLink: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvDemoBadge: TextView
    private lateinit var rv: RecyclerView

    private val changeListener: () -> Unit = { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)

        tvDate = findViewById(R.id.tvHistoryDate)
        tvScope = findViewById(R.id.tvHistoryScope)
        tvLink = findViewById(R.id.tvHistoryClear)
        tvTotal = findViewById(R.id.tvHistoryTotal)
        tvCount = findViewById(R.id.tvHistoryCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvDemoBadge = findViewById(R.id.tvDemoBadge)
        rv = findViewById(R.id.rvTransactions)
        rv.adapter = adapter

        tvDate.setOnClickListener { openCalendar() }
        tvLink.setOnClickListener {
            // วัน -> ทั้งเดือนของวันนั้น / เดือนอื่น -> กลับไปเดือนปัจจุบัน
            val sel = AppState.histDate
            if (sel != null && sel.third > 0) {
                setMonth(sel.first, sel.second)
            } else {
                AppState.histDate = null
                load()
            }
        }
        Nav.setup(this, findViewById<BottomNavigationView>(R.id.bottomNav), R.id.nav_history)
    }

    override fun onStart() {
        super.onStart()
        TransactionEvents.register(changeListener)
    }

    override fun onStop() {
        TransactionEvents.unregister(changeListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        tvDemoBadge.visibility = if (SettingsStore(this).demoMode) View.VISIBLE else View.GONE
        load()
        SyncManager.syncSoon(this, 30_000L)
    }

    /** ตั้งให้แสดงทั้งเดือน (เดือนปัจจุบันเก็บเป็น null = ค่าเริ่มต้น) */
    private fun setMonth(year: Int, month: Int) {
        val picked = Triple(year, month, 0)
        AppState.histDate = if (picked == AppState.currentMonth()) null else picked
        load()
    }

    /** ดึงปีแรกที่มีข้อมูลและวันที่มีรายการ แล้วเปิดปฏิทิน */
    private fun openCalendar() {
        Io.executor.execute {
            val database = db
            val nowYear = Calendar.getInstance().get(Calendar.YEAR)
            val firstYear = minOf(database.getYearsWithData().minOrNull() ?: nowYear, nowYear - 10)
            val days = database.getDaysWithData()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val (y, m, d) = AppState.histDate ?: AppState.currentMonth()
                CalendarDialog(this, firstYear, days, y, m, d) { ry, rm, rd ->
                    if (rd > 0) {
                        AppState.histDate = Triple(ry, rm, rd)
                        load()
                    } else {
                        setMonth(ry, rm)
                    }
                }.show()
            }
        }
    }

    private fun load() {
        val selected = AppState.histDate ?: AppState.currentMonth()
        val (y, m, d) = selected
        val dayMode = d > 0
        val today = AppState.today()
        val isCurrentMonth = y == today.first && m == today.second

        tvDate.text = (if (dayMode) Fmt.dateLabel(y, m, d) else Fmt.monthTitle(y, m)) + " ▾"
        tvScope.text = if (dayMode) {
            Fmt.weekdayName(y, m, d) +
                (if (Triple(y, m, d) == today) " " + getString(R.string.today_suffix) else "")
        } else {
            getString(R.string.scope_month) +
                (if (isCurrentMonth) " " + getString(R.string.this_month_suffix) else "")
        }
        when {
            dayMode -> {
                tvLink.setText(R.string.history_view_month)
                tvLink.visibility = View.VISIBLE
            }
            !isCurrentMonth -> {
                tvLink.setText(R.string.history_back_month)
                tvLink.visibility = View.VISIBLE
            }
            else -> tvLink.visibility = View.INVISIBLE
        }

        Io.executor.execute {
            val database = db
            val items = if (dayMode) {
                database.getTransactionsByDay(Fmt.dayKey(y, m, d))
            } else {
                database.getTransactionsByMonth(Fmt.monthKey(y, m))
            }
            val total = items.sumOf { it.amount }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if ((AppState.histDate ?: AppState.currentMonth()) != selected) return@runOnUiThread
                adapter.submitList(items) { rv.scrollToPosition(0) }
                tvTotal.text = Fmt.money(total)
                tvCount.text = getString(R.string.history_count_fmt, items.size)
                tvEmpty.setText(if (dayMode) R.string.history_empty_day else R.string.history_empty)
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
