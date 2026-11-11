package com.example.mana_bike_v001

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

object Session {
    val user: FirebaseUser? get() = FirebaseAuth.getInstance().currentUser
    val isSignedIn: Boolean get() = user != null
}

/**
 * ซิงก์ประวัติเงินเข้ากับ Firestore (users/{uid}/transactions/{docId})
 * - ฐานข้อมูลในเครื่อง (SQLite) ยังเป็นตัวหลักที่หน้าจอใช้อ่าน
 * - ส่งขึ้น: แถวที่ synced = 0   ดึงลง: เอกสารที่ createdAt ใหม่กว่าที่ดึงล่าสุด (กันซ้ำด้วย doc_id)
 * - ข้อมูลเป็นแบบเพิ่มอย่างเดียว (ไม่มีลบ) จึงไม่ต้องจัดการข้อมูลขัดแย้ง
 * ทำงานบนเธรดของตัวเอง ไม่บล็อกการใช้ฐานข้อมูลของหน้าจอ
 */
object SyncManager {
    private const val BATCH = 400
    private const val PAGE = 500
    private const val TIMEOUT_S = 25L
    private const val PULL_OVERLAP_MS = 120_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** สั่งซิงก์เบื้องหลัง (รวมคำสั่งที่ซ้อนกันเป็นรอบเดียว) ข้ามถ้าเพิ่งซิงก์ไปไม่เกิน minIntervalMs */
    fun syncSoon(context: Context, minIntervalMs: Long = 0L) {
        val app = context.applicationContext
        if (!Session.isSignedIn) return
        if (minIntervalMs > 0 && System.currentTimeMillis() - SettingsStore(app).lastSyncAt < minIntervalMs) return
        if (!pending.compareAndSet(false, true)) return
        executor.execute {
            pending.set(false)
            runSync(app)
        }
    }

    /** ซิงก์ทันทีแล้วแจ้งผลบนเธรดหลัก (error = null คือสำเร็จ) */
    fun syncNow(context: Context, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val error = runSync(app)
            main.post { onDone(error) }
        }
    }

    fun unsyncedCount(context: Context, onResult: (Int) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val n = DatabaseHelper.getRealInstance(app).countUnsynced()
            main.post { onResult(n) }
        }
    }

    /**
     * ออกจากระบบ: พยายามส่งข้อมูลที่ค้างก่อน แล้วล้างข้อมูลในเครื่อง
     * ถ้ายังมีรายการค้างและ force = false จะไม่ออก แต่แจ้งจำนวนที่ค้าง (remaining > 0) ให้ผู้ใช้ยืนยัน
     */
    fun signOut(context: Context, force: Boolean, onResult: (remaining: Int) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            runSync(app)
            val db = DatabaseHelper.getRealInstance(app)
            val remaining = db.countUnsynced()
            if (remaining > 0 && !force) {
                main.post { onResult(remaining) }
                return@execute
            }
            db.clearAllRealData()
            SettingsStore(app).clearAccount()
            AppState.histDate = null
            FirebaseAuth.getInstance().signOut()
            main.post { onResult(0) }
        }
    }

    // ---------------- ภายใน (ทำงานบนเธรดซิงก์) ----------------

    /** @return ข้อความผิดพลาด หรือ null ถ้าสำเร็จ */
    private fun runSync(app: Context): String? {
        val user = Session.user ?: return null
        val store = SettingsStore(app)
        prepareOwner(app, user.uid)
        if (!isOnline(app)) return "ไม่มีอินเทอร์เน็ต"
        return try {
            val db = DatabaseHelper.getRealInstance(app)
            val col = FirebaseFirestore.getInstance()
                .collection("users").document(user.uid).collection("transactions")
            push(db, col)
            val pulled = pull(db, col, store)
            store.lastSyncAt = System.currentTimeMillis()
            store.lastSyncError = null
            if (pulled > 0) TransactionEvents.notifyChanged()
            null
        } catch (e: Exception) {
            val msg = friendly(e)
            store.lastSyncError = msg
            msg
        }
    }

    /**
     * ข้อมูลในเครื่องต้องเป็นของผู้ใช้ที่ล็อกอินอยู่
     * - ยังไม่มีเจ้าของ (ข้อมูลที่บันทึกก่อนมีระบบบัญชี) = เป็นของผู้ใช้คนแรกที่ล็อกอิน แล้วจะถูกอัปโหลด
     * - เจ้าของเป็นคนอื่น = ล้างข้อมูลในเครื่อง (ของคนนั้นอยู่บนคลาวด์แล้ว)
     */
    private fun prepareOwner(app: Context, uid: String) {
        val store = SettingsStore(app)
        val owner = store.ownerUid
        if (owner == uid) return
        if (owner != null) {
            DatabaseHelper.getRealInstance(app).clearAllRealData()
            store.lastPullMillis = 0L
        }
        store.ownerUid = uid
    }

    private fun push(db: DatabaseHelper, col: CollectionReference) {
        while (true) {
            val rows = db.getUnsynced(BATCH)
            if (rows.isEmpty()) return
            val batch = FirebaseFirestore.getInstance().batch()
            for (r in rows) {
                val data = hashMapOf<String, Any?>(
                    "amount" to r.amount,
                    "timestamp" to r.timestamp,
                    "bank" to r.bank,
                    "channel" to r.channel,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                batch.set(col.document(r.docId), data)
            }
            Tasks.await(batch.commit(), TIMEOUT_S, TimeUnit.SECONDS)
            db.markSynced(rows.map { it.rowId })
        }
    }

    /** @return จำนวนรายการใหม่ที่ดึงลงมา */
    private fun pull(db: DatabaseHelper, col: CollectionReference, store: SettingsStore): Int {
        var inserted = 0
        var maxMillis = store.lastPullMillis
        val from = Timestamp(Date(maxOf(0L, store.lastPullMillis - PULL_OVERLAP_MS)))
        val base = col.whereGreaterThanOrEqualTo("createdAt", from).orderBy("createdAt").limit(PAGE.toLong())

        var last: DocumentSnapshot? = null
        while (true) {
            val query = if (last == null) base else base.startAfter(last)
            val snap = Tasks.await(query.get(Source.SERVER), TIMEOUT_S, TimeUnit.SECONDS)
            val docs = snap.documents
            for (d in docs) {
                val amount = d.getDouble("amount")
                val ts = d.getLong("timestamp")
                if (amount != null && ts != null) {
                    if (db.insertFromCloud(d.id, amount, ts, d.getString("bank"), d.getString("channel"))) inserted++
                }
                val created = d.getTimestamp("createdAt")?.toDate()?.time
                if (created != null && created > maxMillis) maxMillis = created
            }
            if (docs.size < PAGE) break
            last = docs.last()
        }
        store.lastPullMillis = maxMillis
        return inserted
    }

    private fun friendly(e: Throwable): String {
        val root = if (e is ExecutionException && e.cause != null) e.cause!! else e
        return when {
            root is TimeoutException -> "เชื่อมต่อช้าเกินไป ลองใหม่อีกครั้ง"
            root is FirebaseFirestoreException -> when (root.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "ไม่มีสิทธิ์เข้าถึงฐานข้อมูล (ตรวจสอบการเปิด Firestore และกฎความปลอดภัย)"
                FirebaseFirestoreException.Code.UNAVAILABLE -> "เชื่อมต่อเซิร์ฟเวอร์ไม่ได้"
                else -> root.code.name
            }
            else -> root.localizedMessage ?: "ซิงก์ไม่สำเร็จ"
        }
    }
}
