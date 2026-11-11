package com.example.mana_bike_v001

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var tilName: TextInputLayout
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilConfirm: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etShop: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var progress: View
    private lateinit var btnRegister: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_register)

        // ให้ฟอร์มเลื่อนหนีคีย์บอร์ด/แถบระบบ
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, maxOf(ime.bottom, bars.bottom))
            insets
        }

        tilName = findViewById(R.id.tilName)
        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        tilConfirm = findViewById(R.id.tilConfirm)
        etName = findViewById(R.id.etName)
        etShop = findViewById(R.id.etShop)
        etPhone = findViewById(R.id.etPhone)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirm = findViewById(R.id.etConfirm)
        tvError = findViewById(R.id.tvError)
        progress = findViewById(R.id.progress)
        btnRegister = findViewById(R.id.btnRegister)

        btnRegister.setOnClickListener { attemptRegister() }
        findViewById<View>(R.id.tvGoLogin).setOnClickListener { finish() }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnRegister.isEnabled = !busy
    }

    private fun showError(message: String) {
        tvError.setTextColor(getColor(R.color.mana_error))
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun attemptRegister() {
        val name = etName.text?.toString()?.trim().orEmpty()
        val shop = etShop.text?.toString()?.trim().orEmpty()
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        val confirm = etConfirm.text?.toString().orEmpty()

        tilName.error = null
        tilEmail.error = null
        tilPassword.error = null
        tilConfirm.error = null
        tvError.visibility = View.GONE

        var ok = true
        if (name.isEmpty()) {
            tilName.error = getString(R.string.err_name_required)
            ok = false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = getString(R.string.err_email_invalid)
            ok = false
        }
        if (password.length < 6) {
            tilPassword.error = getString(R.string.err_password_short)
            ok = false
        }
        if (confirm != password) {
            tilConfirm.error = getString(R.string.err_password_mismatch)
            ok = false
        }
        if (!ok) return

        setBusy(true)
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (isFinishing || isDestroyed) return@addOnCompleteListener
                val user = task.result?.user
                if (task.isSuccessful && user != null) {
                    saveProfileThenEnter(user.uid, name, shop, phone, email)
                } else {
                    setBusy(false)
                    showError(AuthErrors.message(this, task.exception))
                }
            }
    }

    /** บันทึกข้อมูลส่วนตัว (ชื่อใน Auth + เอกสารใน Firestore) แล้วเตรียมข้อมูลและเข้าแอป */
    private fun saveProfileThenEnter(uid: String, name: String, shop: String, phone: String, email: String) {
        val store = SettingsStore(this)
        store.profileName = name
        store.profileShop = shop
        store.profilePhone = phone

        Session.user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())

        var proceeded = false
        fun proceed() {
            if (proceeded || isFinishing || isDestroyed) return
            proceeded = true
            SyncManager.syncNow(this) {
                if (isFinishing || isDestroyed) return@syncNow
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }

        val profile = hashMapOf<String, Any>(
            "name" to name,
            "shopName" to shop,
            "phone" to phone,
            "email" to email,
            "createdAt" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance().collection("users").document(uid).set(profile)
            .addOnCompleteListener { proceed() }
        // กันค้างถ้าเชื่อมต่อ Firestore ไม่ได้: เข้าแอปต่อ (ข้อมูลส่วนตัวแก้ไขภายหลังได้)
        Handler(Looper.getMainLooper()).postDelayed({ proceed() }, 8000)
    }
}
