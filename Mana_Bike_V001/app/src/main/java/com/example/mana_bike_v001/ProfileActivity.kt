package com.example.mana_bike_v001

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/** ดู/แก้ไขข้อมูลส่วนตัว (เปิดจากหน้าตั้งค่า) */
class ProfileActivity : AppCompatActivity() {

    private lateinit var tilName: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etShop: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var progress: View
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)

        // ให้ฟอร์มเลื่อนหนีคีย์บอร์ด/แถบระบบ
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, maxOf(ime.bottom, bars.bottom))
            insets
        }

        val user = Session.user
        if (user == null) {
            finish()
            return
        }

        tilName = findViewById(R.id.tilName)
        etName = findViewById(R.id.etName)
        etShop = findViewById(R.id.etShop)
        etPhone = findViewById(R.id.etPhone)
        etEmail = findViewById(R.id.etEmail)
        tvError = findViewById(R.id.tvError)
        progress = findViewById(R.id.progress)
        btnSave = findViewById(R.id.btnSave)

        val store = SettingsStore(this)
        etEmail.setText(user.email)
        etName.setText(user.displayName?.takeIf { it.isNotBlank() } ?: store.profileName)
        etShop.setText(store.profileShop)
        etPhone.setText(store.profilePhone)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSave.setOnClickListener { save(user) }
        findViewById<View>(R.id.btnChangePassword).setOnClickListener { sendReset(user.email) }
        loadRemote(user.uid)
    }

    /** ดึงข้อมูลล่าสุดจากคลาวด์มาเติมช่องที่ยังว่าง (เช่นหลังติดตั้งใหม่) */
    private fun loadRemote(uid: String) {
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (isFinishing || isDestroyed || !doc.exists()) return@addOnSuccessListener
                if (etName.text.isNullOrBlank()) etName.setText(doc.getString("name").orEmpty())
                if (etShop.text.isNullOrBlank()) etShop.setText(doc.getString("shopName").orEmpty())
                if (etPhone.text.isNullOrBlank()) etPhone.setText(doc.getString("phone").orEmpty())
            }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnSave.isEnabled = !busy
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun save(user: FirebaseUser) {
        val name = etName.text?.toString()?.trim().orEmpty()
        val shop = etShop.text?.toString()?.trim().orEmpty()
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        tilName.error = null
        tvError.visibility = View.GONE
        if (name.isEmpty()) {
            tilName.error = getString(R.string.err_name_required)
            return
        }

        val store = SettingsStore(this)
        store.profileName = name
        store.profileShop = shop
        store.profilePhone = phone
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())

        setBusy(true)
        var finished = false
        fun done(error: String?) {
            if (finished || isFinishing || isDestroyed) return
            finished = true
            setBusy(false)
            if (error == null) {
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                showError(getString(R.string.save_failed, error))
            }
        }

        val data = hashMapOf<String, Any>(
            "name" to name,
            "shopName" to shop,
            "phone" to phone,
            "email" to (user.email ?: ""),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance().collection("users").document(user.uid)
            .set(data, SetOptions.merge())
            .addOnCompleteListener { task ->
                done(if (task.isSuccessful) null else (task.exception?.localizedMessage ?: "-"))
            }
        Handler(Looper.getMainLooper()).postDelayed({ done("เชื่อมต่อช้าเกินไป") }, 10000)
    }

    private fun sendReset(email: String?) {
        if (email.isNullOrBlank()) return
        FirebaseAuth.getInstance().sendPasswordResetEmail(email).addOnCompleteListener { task ->
            if (isFinishing || isDestroyed) return@addOnCompleteListener
            if (task.isSuccessful) {
                Toast.makeText(this, R.string.reset_sent, Toast.LENGTH_LONG).show()
            } else {
                showError(AuthErrors.message(this, task.exception))
            }
        }
    }
}
