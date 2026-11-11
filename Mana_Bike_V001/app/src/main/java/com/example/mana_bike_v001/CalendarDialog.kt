package com.example.mana_bike_v001

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/**
 * ปฏิทินเลือกดูประวัติ
 * - ปุ่ม ‹ › เลื่อนเดือน (เลื่อนแล้วล้างวันที่เลือก)
 * - แตะชื่อเดือนด้านบนเพื่อเปิดแผงเลือกปี/เดือน (เลือกปีก่อนๆ ได้)
 * - แตะวันเพื่อเลือก (แตะซ้ำเพื่อยกเลิก)
 * - กดตกลง: ถ้าเลือกวันไว้ = ดูวันนั้น, ถ้าไม่ได้เลือกวัน = ดูทั้งเดือนที่แสดงอยู่ (day = 0)
 * - วันที่มีรายการเป็นสีส้มอ่อน
 */
class CalendarDialog(
    private val activity: AppCompatActivity,
    private val minYear: Int,
    daysWithData: LongArray,
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    private val onResult: (year: Int, month: Int, day: Int) -> Unit
) {
    private val daySet: Set<Long> = daysWithData.toHashSet()
    private var year = initialYear
    private var month = initialMonth
    private var selected: Triple<Int, Int, Int>? =
        if (initialDay > 0) Triple(initialYear, initialMonth, initialDay) else null

    private lateinit var tvMonth: TextView
    private lateinit var btnPrev: View
    private lateinit var btnNext: View
    private lateinit var dayPanel: View
    private lateinit var monthPanel: View
    private lateinit var tvPanelYear: TextView
    private lateinit var btnYearPrev: View
    private lateinit var btnYearNext: View
    private val cells = ArrayList<TextView>(42)
    private val monthButtons = ArrayList<MaterialButton>(12)

    private var offset = 0
    private var daysInMonth = 30
    private var panelOpen = false
    private var panelYear = initialYear

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun color(res: Int): Int = ContextCompat.getColor(activity, res)

    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_calendar, null)
        tvMonth = view.findViewById(R.id.calMonth)
        btnPrev = view.findViewById(R.id.calPrev)
        btnNext = view.findViewById(R.id.calNext)
        dayPanel = view.findViewById(R.id.calDayPanel)
        monthPanel = view.findViewById(R.id.calMonthPanel)
        tvPanelYear = view.findViewById(R.id.calYear)
        btnYearPrev = view.findViewById(R.id.calYearPrev)
        btnYearNext = view.findViewById(R.id.calYearNext)
        val grid = view.findViewById<GridLayout>(R.id.calGrid)

        for (i in 0 until 42) {
            val cell = TextView(activity)
            cell.gravity = Gravity.CENTER
            cell.textSize = 14f
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = dp(40)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            cell.layoutParams = lp
            cell.setOnClickListener { onCellClick(i) }
            grid.addView(cell)
            cells.add(cell)
        }

        val monthIds = intArrayOf(
            R.id.calM1, R.id.calM2, R.id.calM3, R.id.calM4, R.id.calM5, R.id.calM6,
            R.id.calM7, R.id.calM8, R.id.calM9, R.id.calM10, R.id.calM11, R.id.calM12
        )
        monthIds.forEachIndexed { i, id ->
            val b = view.findViewById<MaterialButton>(id)
            b.setOnClickListener { chooseMonth(panelYear, i + 1) }
            monthButtons.add(b)
        }

        btnPrev.setOnClickListener { shift(-1) }
        btnNext.setOnClickListener { shift(+1) }
        tvMonth.setOnClickListener { setPanelOpen(!panelOpen) }
        btnYearPrev.setOnClickListener { panelYear--; renderMonthPanel() }
        btnYearNext.setOnClickListener { panelYear++; renderMonthPanel() }
        render()

        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val sel = selected
                if (sel != null) onResult(sel.first, sel.second, sel.third) else onResult(year, month, 0)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------------- เลื่อนเดือน / เลือกเดือน-ปี ----------------

    private fun shift(delta: Int) {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1 + delta, 1) }
        val ny = c.get(Calendar.YEAR)
        val nm = c.get(Calendar.MONTH) + 1
        if (Fmt.isFuture(ny, nm) || ny < minYear) return
        year = ny
        month = nm
        selected = null
        render()
    }

    private fun setPanelOpen(open: Boolean) {
        panelOpen = open
        dayPanel.visibility = if (open) View.GONE else View.VISIBLE
        monthPanel.visibility = if (open) View.VISIBLE else View.GONE
        btnPrev.visibility = if (open) View.INVISIBLE else View.VISIBLE
        btnNext.visibility = if (open) View.INVISIBLE else View.VISIBLE
        if (open) {
            panelYear = year
            renderMonthPanel()
        }
    }

    private fun chooseMonth(y: Int, m: Int) {
        if (Fmt.isFuture(y, m)) return
        year = y
        month = m
        selected = null
        setPanelOpen(false)
        render()
    }

    private fun renderMonthPanel() {
        val nowYear = AppState.today().first
        tvPanelYear.text = (panelYear + 543).toString()
        btnYearPrev.isEnabled = panelYear > minYear
        btnYearPrev.alpha = if (panelYear > minYear) 1f else 0.4f
        btnYearNext.isEnabled = panelYear < nowYear
        btnYearNext.alpha = if (panelYear < nowYear) 1f else 0.4f
        monthButtons.forEachIndexed { i, b ->
            val m = i + 1
            b.isEnabled = !Fmt.isFuture(panelYear, m)
            b.isChecked = (panelYear == year && m == month)
        }
    }

    // ---------------- วันในเดือน ----------------

    private fun dateKey(y: Int, m: Int, d: Int): Long = y * 10000L + m * 100L + d

    private fun isFutureDay(d: Int): Boolean {
        val t = AppState.today()
        return dateKey(year, month, d) > dateKey(t.first, t.second, t.third)
    }

    private fun onCellClick(index: Int) {
        val day = index - offset + 1
        if (day < 1 || day > daysInMonth || isFutureDay(day)) return
        val t = Triple(year, month, day)
        selected = if (selected == t) null else t
        render()
    }

    private fun render() {
        tvMonth.text = Fmt.monthTitle(year, month) + " ▾"

        val today = AppState.today()
        val isCurrentMonth = year == today.first && month == today.second
        btnNext.isEnabled = !isCurrentMonth
        btnNext.alpha = if (isCurrentMonth) 0.4f else 1f
        val atStart = year <= minYear && month == 1
        btnPrev.isEnabled = !atStart
        btnPrev.alpha = if (atStart) 0.4f else 1f

        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        offset = c.get(Calendar.DAY_OF_WEEK) - 1 // อาทิตย์ = 0
        daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (i in 0 until 42) {
            val cell = cells[i]
            val day = i - offset + 1
            if (day < 1 || day > daysInMonth) {
                cell.visibility = View.INVISIBLE
                continue
            }
            cell.visibility = View.VISIBLE
            cell.text = day.toString()
            cell.contentDescription = Fmt.dateLabel(year, month, day)

            val future = isFutureDay(day)
            val isSelected = selected == Triple(year, month, day)
            val hasData = dateKey(year, month, day) in daySet
            val isToday = Triple(year, month, day) == today

            var fill = Color.TRANSPARENT
            var textColor = color(R.color.mana_navy)
            var bold = false
            when {
                future -> textColor = color(R.color.mana_disabled)
                isSelected -> {
                    fill = color(R.color.mana_blue)
                    textColor = color(R.color.white)
                    bold = true
                }
                hasData -> {
                    fill = color(R.color.mana_day_data_bg)
                    textColor = color(R.color.mana_day_data_text)
                }
            }
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dp(20).toFloat()
            bg.setColor(fill)
            if (isToday && !isSelected) bg.setStroke(dp(1), color(R.color.mana_navy))
            cell.background = bg
            cell.setTextColor(textColor)
            cell.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            cell.isEnabled = !future
        }
    }
}
