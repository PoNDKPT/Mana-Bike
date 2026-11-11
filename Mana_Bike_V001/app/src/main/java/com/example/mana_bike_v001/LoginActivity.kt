package com.example.mana_bike_v001

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
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

class LoginActivity : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var tvError: TextView
    private lateinit var progress: View
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        // ให้ฟอร์มเลื่อนหนีคีย์บอร์ด/แถบระบบ
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, maxOf(ime.bottom, bars.bottom))
            insets
        }

        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        tvError = findViewById(R.id.tvError)
        progress = findViewById(R.id.progress)
        btnLogin = findViewById(R.id.btnLogin)

        btnLogin.setOnClickListener { attemptLogin() }
        findViewById<View>(R.id.tvForgot).setOnClickListener { sendReset() }
        findViewById<View>(R.id.tvGoRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !busy
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun attemptLogin() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        tilEmail.error = null
        tilPassword.error = null
        tvError.visibility = View.GONE

        var ok = true
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = getString(R.string.err_email_invalid)
            ok = false
        }
        if (password.isEmpty()) {
            tilPassword.error = getString(R.string.err_password_required)
            ok = false
        }
        if (!ok) return

        setBusy(true)
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (isFinishing || isDestroyed) return@addOnCompleteListener
                if (task.isSuccessful) {
                    afterSignIn()
                } else {
                    setBusy(false)
                    showError(AuthErrors.message(this, task.exception))
                }
            }
    }

    /** เตรียมข้อมูลของบัญชีนี้ในเครื่อง (ล้างของคนอื่น/อัปโหลดข้อมูลเดิม/ดึงประวัติจากคลาวด์) ก่อนเข้าแอป */
    private fun afterSignIn() {
        tvError.setText(R.string.syncing)
        tvError.setTextColor(getColor(R.color.mana_muted))
        tvError.visibility = View.VISIBLE
        SyncManager.syncNow(this) {
            if (isFinishing || isDestroyed) return@syncNow
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }

    private fun sendReset() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = getString(R.string.reset_need_email)
            return
        }
        tilEmail.error = null
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
