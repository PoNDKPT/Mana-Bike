package com.example.mana_bike_v001

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** แถวข้อมูลสำหรับสำรอง/นำเข้า */
data class BackupRow(
    val amount: Double,
    val timestamp: Long,
    val bank: String?,
    val channel: String?
)

/** แถวที่รอส่งขึ้นคลาวด์ */
data class SyncRow(
    val rowId: Long,
    val docId: String,
    val amount: Double,
    val timestamp: Long,
    val bank: String?,
    val channel: String?
)

class DatabaseHelper private constructor(context: Context, private val dbName: String) :
    SQLiteOpenHelper(context.applicationContext, dbName, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "shop_income.db"
        const val DEMO_DATABASE_NAME = "demo_income.db"
        private const val DATABASE_VERSION = 3

        private const val T = "transactions"
        private const val COL_ID = "id"
        private const val COL_AMOUNT = "amount"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_BANK = "bank"
        private const val COL_CHANNEL = "channel"
        private const val COL_FINGERPRINT = "fingerprint"
        private const val COL_DOC_ID = "doc_id"
        private const val COL_SYNCED = "synced"

        /** ข้อความเดิมที่แจ้งเตือนซ้ำ/อัปเดตภายในช่วงนี้ ถือว่าเป็นรายการเดียวกัน */
        private const val REPOST_WINDOW_MS = 60_000L

        /** ยอดเท่ากัน ธนาคารเดียวกัน แต่มาคนละที่มา (แอปธนาคาร/LINE/SMS) ภายในช่วงนี้ ถือว่าเป็นรายการเดียวกัน */
        private const val CROSS_CHANNEL_WINDOW_MS = 120_000L

        private const val MONTH_FILTER =
            "strftime('%m/%Y', $COL_TIMESTAMP / 1000, 'unixepoch', 'localtime') = ?"

        private const val DAY_FILTER =
            "strftime('%d/%m/%Y', $COL_TIMESTAMP / 1000, 'unixepoch', 'localtime') = ?"

        private var real: DatabaseHelper? = null
        private var demo: DatabaseHelper? = null

        /** ฐานข้อมูลจริง (ใช้กับการดักจับยอดและการสำรองข้อมูลเสมอ) */
        @Synchronized
        fun getRealInstance(context: Context): DatabaseHelper =
            real ?: DatabaseHelper(context, DATABASE_NAME).also { real = it }

        /** ฐานข้อมูลข้อมูลตัวอย่าง แยกไฟล์จากข้อมูลจริง */
        @Synchronized
        fun getDemoInstance(context: Context): DatabaseHelper =
            demo ?: DatabaseHelper(context, DEMO_DATABASE_NAME).also { demo = it }

        /** ฐานข้อมูลที่หน้าจอใช้แสดงผล: โหมดตัวอย่าง = ข้อมูลสมมติ, ปกติ = ข้อมูลจริง */
        fun getInstance(context: Context): DatabaseHelper =
            if (SettingsStore(context).demoMode) getDemoInstance(context) else getRealInstance(context)
    }

    init {
        // ไม่ใช้ WAL เพื่อให้ฐานข้อมูลเป็นไฟล์เดียว สำรองข้อมูลอัตโนมัติได้ครบ
        setWriteAheadLoggingEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AMOUNT REAL NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_BANK TEXT,
                $COL_CHANNEL TEXT,
                $COL_FINGERPRINT TEXT,
                $COL_DOC_ID TEXT,
                $COL_SYNCED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        createBaseIndexes(db)
        createDocIndex(db)
    }

    /** ห้ามลบตาราง เพราะข้อมูลของผู้ใช้ต้องอยู่ต่อเมื่ออัปเดตแอป */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $T ADD COLUMN $COL_BANK TEXT")
            db.execSQL("ALTER TABLE $T ADD COLUMN $COL_CHANNEL TEXT")
            db.execSQL("ALTER TABLE $T ADD COLUMN $COL_FINGERPRINT TEXT")
            createBaseIndexes(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $T ADD COLUMN $COL_DOC_ID TEXT")
            db.execSQL("ALTER TABLE $T ADD COLUMN $COL_SYNCED INTEGER NOT NULL DEFAULT 0")
            // รายการเดิมทุกแถวได้รหัสเอกสารสำหรับซิงก์ขึ้นคลาวด์
            db.execSQL("UPDATE $T SET $COL_DOC_ID = lower(hex(randomblob(16))) WHERE $COL_DOC_ID IS NULL")
            createDocIndex(db)
        }
    }

    private fun createBaseIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON $T($COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_fingerprint ON $T($COL_FINGERPRINT)")
    }

    private fun createDocIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_doc ON $T($COL_DOC_ID)")
    }

    private fun newDocId(): String = UUID.randomUUID().toString()

    /** เพิ่มยอดเงินด้วยมือ (เวลาปัจจุบัน) */
    fun insertTransaction(amount: Double): Long {
        val values = ContentValues().apply {
            put(COL_AMOUNT, amount)
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_DOC_ID, newDocId())
        }
        return writableDatabase.insert(T, null, values)
    }

    /**
     * เพิ่มยอดที่ดักจับได้จากข้อความธนาคาร พร้อมกันรายการซ้ำ
     * @return true ถ้าบันทึกจริง, false ถ้าเป็นรายการซ้ำ
     */
    @Synchronized
    fun insertCaptured(
        amount: Double,
        bank: String,
        channel: String,
        fingerprint: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val db = writableDatabase

        db.rawQuery(
            "SELECT 1 FROM $T WHERE $COL_FINGERPRINT = ? AND $COL_TIMESTAMP > ? LIMIT 1",
            arrayOf(fingerprint, (now - REPOST_WINDOW_MS).toString())
        ).use { if (it.moveToFirst()) return false }

        db.rawQuery(
            "SELECT 1 FROM $T WHERE ABS($COL_AMOUNT - ?) < 0.005 AND $COL_BANK = ? " +
                "AND $COL_CHANNEL <> ? AND $COL_TIMESTAMP > ? LIMIT 1",
            arrayOf(amount.toString(), bank, channel, (now - CROSS_CHANNEL_WINDOW_MS).toString())
        ).use { if (it.moveToFirst()) return false }

        val values = ContentValues().apply {
            put(COL_AMOUNT, amount)
            put(COL_TIMESTAMP, now)
            put(COL_BANK, bank)
            put(COL_CHANNEL, channel)
            put(COL_FINGERPRINT, fingerprint)
            put(COL_DOC_ID, newDocId())
        }
        return db.insert(T, null, values) != -1L
    }

    private fun queryItems(filter: String, arg: String, limit: Int, offset: Int): List<TransactionItem> {
        val list = mutableListOf<TransactionItem>()
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
        // limit/offset เป็นตัวเลข Int ฝังใน SQL ได้อย่างปลอดภัย (limit = -1 คือไม่จำกัด)
        readableDatabase.rawQuery(
            "SELECT $COL_ID, $COL_AMOUNT, $COL_TIMESTAMP, $COL_BANK, $COL_CHANNEL FROM $T " +
                "WHERE $filter ORDER BY $COL_TIMESTAMP DESC, $COL_ID DESC LIMIT $limit OFFSET $offset",
            arrayOf(arg)
        ).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(2)
                list.add(
                    TransactionItem(
                        c.getInt(0), c.getDouble(1), ts, fmt.format(Date(ts)),
                        if (c.isNull(3)) null else c.getString(3),
                        if (c.isNull(4)) null else c.getString(4)
                    )
                )
            }
        }
        return list
    }

    /** @param monthYear "MM/yyyy" (ค.ศ.) เรียงใหม่ไปเก่า แบ่งหน้าด้วย limit/offset */
    fun getTransactionsByMonth(monthYear: String, limit: Int = -1, offset: Int = 0): List<TransactionItem> =
        queryItems(MONTH_FILTER, monthYear, limit, offset)

    /** @param dayKey "dd/MM/yyyy" (ค.ศ.) เรียงใหม่ไปเก่า แบ่งหน้าด้วย limit/offset */
    fun getTransactionsByDay(dayKey: String, limit: Int = -1, offset: Int = 0): List<TransactionItem> =
        queryItems(DAY_FILTER, dayKey, limit, offset)

    fun getTotalAmountByDay(dayKey: String): Double =
        readableDatabase.rawQuery(
            "SELECT SUM($COL_AMOUNT) FROM $T WHERE $DAY_FILTER", arrayOf(dayKey)
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    fun getCountByDay(dayKey: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T WHERE $DAY_FILTER", arrayOf(dayKey)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** ปี (ค.ศ.) ที่มีข้อมูล เรียงใหม่ไปเก่า */
    fun getYearsWithData(): List<Int> {
        val years = mutableListOf<Int>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT CAST(strftime('%Y', $COL_TIMESTAMP / 1000, 'unixepoch', 'localtime') AS INTEGER) AS y " +
                "FROM $T ORDER BY y DESC",
            null
        ).use { c -> while (c.moveToNext()) years.add(c.getInt(0)) }
        return years
    }

    /** วันที่ (ค.ศ.) ที่มีรายการ เก็บเป็นเลข yyyyMMdd เช่น 20261107 */
    fun getDaysWithData(): LongArray {
        val days = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT CAST(strftime('%Y%m%d', $COL_TIMESTAMP / 1000, 'unixepoch', 'localtime') AS INTEGER) " +
                "FROM $T",
            null
        ).use { c -> while (c.moveToNext()) days.add(c.getLong(0)) }
        return days.toLongArray()
    }

    // ---------------- ซิงก์กับคลาวด์ ----------------

    /** รายการที่ยังไม่ได้ส่งขึ้นคลาวด์ (เก่าสุดก่อน) */
    fun getUnsynced(limit: Int): List<SyncRow> {
        val rows = mutableListOf<SyncRow>()
        readableDatabase.rawQuery(
            "SELECT $COL_ID, $COL_DOC_ID, $COL_AMOUNT, $COL_TIMESTAMP, $COL_BANK, $COL_CHANNEL FROM $T " +
                "WHERE $COL_SYNCED = 0 AND $COL_DOC_ID IS NOT NULL ORDER BY $COL_ID LIMIT $limit",
            null
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    SyncRow(
                        c.getLong(0), c.getString(1), c.getDouble(2), c.getLong(3),
                        if (c.isNull(4)) null else c.getString(4),
                        if (c.isNull(5)) null else c.getString(5)
                    )
                )
            }
        }
        return rows
    }

    fun markSynced(rowIds: List<Long>) {
        if (rowIds.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE $T SET $COL_SYNCED = 1 WHERE $COL_ID IN (${rowIds.joinToString(",")})"
        )
    }

    fun countUnsynced(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T WHERE $COL_SYNCED = 0", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** เพิ่มรายการที่ดึงมาจากคลาวด์ ข้ามถ้ามีรหัสเอกสารนี้อยู่แล้ว @return true ถ้าเพิ่มใหม่ */
    fun insertFromCloud(docId: String, amount: Double, timestamp: Long, bank: String?, channel: String?): Boolean {
        val v = ContentValues().apply {
            put(COL_AMOUNT, amount)
            put(COL_TIMESTAMP, timestamp)
            put(COL_BANK, bank)
            put(COL_CHANNEL, channel)
            put(COL_DOC_ID, docId)
            put(COL_SYNCED, 1)
        }
        return writableDatabase.insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** ใช้ตอนออกจากระบบเท่านั้น: ล้างข้อมูลในเครื่อง (ข้อมูลบนคลาวด์ไม่ถูกแตะ) */
    @Synchronized
    fun clearAllRealData() {
        check(dbName == DATABASE_NAME) { "ใช้ได้กับฐานข้อมูลจริงเท่านั้น" }
        writableDatabase.delete(T, null, null)
    }

    /** ใช้กับฐานข้อมูลตัวอย่างเท่านั้น: แทนที่ข้อมูลทั้งหมดด้วยข้อมูลสมมติ */
    @Synchronized
    fun replaceAllForDemo(rows: List<BackupRow>) {
        check(dbName == DEMO_DATABASE_NAME) { "ใช้ได้กับฐานข้อมูลตัวอย่างเท่านั้น" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T, null, null)
            val stmt = db.compileStatement(
                "INSERT INTO $T ($COL_AMOUNT, $COL_TIMESTAMP, $COL_BANK, $COL_CHANNEL) VALUES (?, ?, ?, ?)"
            )
            for (r in rows) {
                stmt.clearBindings()
                stmt.bindDouble(1, r.amount)
                stmt.bindLong(2, r.timestamp)
                r.bank?.let { stmt.bindString(3, it) }
                r.channel?.let { stmt.bindString(4, it) }
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getTotalAmountByMonth(monthYear: String): Double =
        readableDatabase.rawQuery(
            "SELECT SUM($COL_AMOUNT) FROM $T WHERE $MONTH_FILTER", arrayOf(monthYear)
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    fun getCountByMonth(monthYear: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T WHERE $MONTH_FILTER", arrayOf(monthYear)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun getAllForBackup(): List<BackupRow> {
        val rows = mutableListOf<BackupRow>()
        readableDatabase.rawQuery(
            "SELECT $COL_AMOUNT, $COL_TIMESTAMP, $COL_BANK, $COL_CHANNEL FROM $T ORDER BY $COL_TIMESTAMP ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    BackupRow(
                        c.getDouble(0), c.getLong(1),
                        if (c.isNull(2)) null else c.getString(2),
                        if (c.isNull(3)) null else c.getString(3)
                    )
                )
            }
        }
        return rows
    }

    /** นำเข้าข้อมูลสำรอง ข้ามรายการที่ยอดและเวลาตรงกับที่มีอยู่แล้ว @return จำนวนที่เพิ่มจริง */
    @Synchronized
    fun importRows(rows: List<BackupRow>): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val exists = db.rawQuery(
                    "SELECT 1 FROM $T WHERE $COL_TIMESTAMP = ? AND ABS($COL_AMOUNT - ?) < 0.005 LIMIT 1",
                    arrayOf(r.timestamp.toString(), r.amount.toString())
                ).use { it.moveToFirst() }
                if (exists) continue
                val v = ContentValues().apply {
                    put(COL_AMOUNT, r.amount)
                    put(COL_TIMESTAMP, r.timestamp)
                    put(COL_BANK, r.bank)
                    put(COL_CHANNEL, r.channel)
                    put(COL_DOC_ID, newDocId())
                }
                if (db.insert(T, null, v) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }
}
