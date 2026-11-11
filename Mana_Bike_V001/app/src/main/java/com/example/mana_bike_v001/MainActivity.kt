package com.example.mana_bike_v001

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // เรียกใหม่ทุกครั้ง เพราะสลับโหมดข้อมูลตัวอย่างได้
    private val db: DatabaseHelper get() = DatabaseHelper.getInstance(this)
    private lateinit var monthButtons: List<MaterialButton>
    private lateinit var btnPrevYear: MaterialButton
    private lateinit var btnNextYear: MaterialButton
    private lateinit var tvYear: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvAvg: TextView

    private val changeListener: () -> Unit = { loadSummary() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        tvYear = findViewById(R.id.tvYear)
        tvTotal = findViewById(R.id.tvTotalAmount)
        tvMonth = findViewById(R.id.tvSelectedMonth)
        tvCount = findViewById(R.id.tvCount)
        tvAvg = findViewById(R.id.tvAvgPerDay)
        btnPrevYear = findViewById(R.id.btnPrevYear)
        btnNextYear = findViewById(R.id.btnNextYear)

        val ids = intArrayOf(
            R.id.btnMonth1, R.id.btnMonth2, R.id.btnMonth3, R.id.btnMonth4,
            R.id.btnMonth5, R.id.btnMonth6, R.id.btnMonth7, R.id.btnMonth8,
            R.id.btnMonth9, R.id.btnMonth10, R.id.btnMonth11, R.id.btnMonth12
        )
        monthButtons = ids.mapIndexed { index, id ->
            findViewById<MaterialButton>(id).also { b ->
                b.setOnClickListener { selectMonth(index + 1) }
            }
        }
        btnPrevYear.setOnClickListener { changeYear(-1) }
        btnNextYear.setOnClickListener { changeYear(+1) }

        Nav.setup(this, findViewById<BottomNavigationView>(R.id.bottomNav), R.id.nav_overview)
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
        findViewById<TextView>(R.id.tvDemoBadge).visibility =
            if (SettingsStore(this).demoMode) View.VISIBLE else View.GONE
        renderSelection()
        SyncManager.syncSoon(this, 30_000L)
    }

    private fun selectMonth(month: Int) {
        if (Fmt.isFuture(AppState.year, month)) return
        AppState.month = month
        renderSelection()
    }

    private fun changeYear(delta: Int) {
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        val newYear = AppState.year + delta
        if (newYear > nowYear || newYear < nowYear - 10) return
        AppState.year = newYear
        if (Fmt.isFuture(newYear, AppState.month)) {
            AppState.month = Calendar.getInstance().get(Calendar.MONTH) + 1
        }
        renderSelection()
    }

    private fun renderSelection() {
        val year = AppState.year
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        tvYear.text = (year + 543).toString()
        btnNextYear.isEnabled = year < nowYear
        btnPrevYear.isEnabled = year > nowYear - 10
        monthButtons.forEachIndexed { i, b ->
            val m = i + 1
            b.isEnabled = !Fmt.isFuture(year, m)
            b.isChecked = (m == AppState.month)
        }
        tvMonth.text = Fmt.monthTitle(year, AppState.month)
        loadSummary()
    }

    private fun loadSummary() {
        val year = AppState.year
        val month = AppState.month
        val key = Fmt.monthKey(year, month)
        Io.executor.execute {
            val total = db.getTotalAmountByMonth(key)
            val count = db.getCountByMonth(key)
            val avg = total / Fmt.daysForAverage(year, month)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvTotal.text = Fmt.money(total)
                tvCount.text = String.format(Locale.US, "%,d", count)
                tvAvg.text = Fmt.moneyShort(avg)
            }
        }
    }
}
