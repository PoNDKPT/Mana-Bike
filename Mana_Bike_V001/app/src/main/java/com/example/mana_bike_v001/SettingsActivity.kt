package com.example.mana_bike_v001

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var tvNotif: TextView
    private lateinit var btnSms: MaterialButton

    private val smsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
                openAppSettings()
            }
            updateStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        store = SettingsStore(this)
        tvNotif = findViewById(R.id.tvNotificationStatus)
        btnSms = findViewById(R.id.btnGrantSms)

        val master = findViewById<MaterialSwitch>(R.id.switchCapture)
        master.isChecked = store.captureEnabled
        master.setOnCheckedChangeListener { _, checked -> store.captureEnabled = checked }

        val voice = findViewById<MaterialSwitch>(R.id.switchVoice)
        voice.isChecked = store.voiceEnabled
        voice.setOnCheckedChangeListener { _, checked -> store.voiceEnabled = checked }

        val bankSwitches = mapOf(
            Bank.KBANK to findViewById<MaterialSwitch>(R.id.switch_kbank),
            Bank.SCB to findViewById<MaterialSwitch>(R.id.switch_scb),
            Bank.BBL to findViewById<MaterialSwitch>(R.id.switch_bbl),
            Bank.KTB to findViewById<MaterialSwitch>(R.id.switch_ktb),
            Bank.GSB to findViewById<MaterialSwitch>(R.id.switch_gsb)
        )
        bankSwitches.forEach { (bank, sw) ->
            sw.isChecked = store.isBankEnabled(bank)
            sw.setOnCheckedChangeListener { _, checked -> store.setBankEnabled(bank, checked) }
        }

        tvNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnSms.setOnClickListener { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }

        findViewById<MaterialButton>(R.id.btnTestCapture).setOnClickListener { showTest() }

        setupAccount()
        Nav.setup(this, findViewById<BottomNavigationView>(R.id.bottomNav), R.id.nav_settings)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshAccount()
    }

    private fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isSmsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateStatus() {
        tvNotif.text = getString(
            if (isNotificationAccessGranted()) R.string.perm_allowed else R.string.perm_tap_to_allow
        )
        val smsOk = isSmsGranted()
        btnSms.text = getString(if (smsOk) R.string.perm_allowed else R.string.perm_grant)
        btnSms.isEnabled = !smsOk
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun showTest() {
        // ทดสอบเสียงพูดด้วย (ไม่บันทึกลงฐานข้อมูล)
        Speaker.speak(this, Speaker.announcement(350.0)) {
            Toast.makeText(this, R.string.tts_missing, Toast.LENGTH_LONG).show()
        }
        fun onOff(v: Boolean) = getString(if (v) R.string.status_on else R.string.status_off)
        val banks = Bank.values().filter { store.isBankEnabled(it) }.joinToString(", ") { it.label }
        val sample = BankMessageParser.parseIncoming("เงินเข้า 350.00 บาท")?.let { Fmt.money(it) } ?: "-"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.test_title)
            .setMessage(
                getString(
                    R.string.test_body,
                    onOff(store.captureEnabled),
                    onOff(isNotificationAccessGranted()),
                    onOff(isSmsGranted()),
                    banks.ifEmpty { "-" },
                    sample
                )
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------------- ข้อมูลส่วนตัว / ออกจากระบบ ----------------

    private lateinit var tvInitial: TextView
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileShop: TextView

    private fun setupAccount() {
        tvInitial = findViewById(R.id.tvProfileInitial)
        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileShop = findViewById(R.id.tvProfileShop)

        findViewById<MaterialButton>(R.id.btnEditProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_title)
                .setMessage(R.string.logout_msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_logout) { _, _ -> doLogout(false) }
                .show()
        }
    }

    private fun refreshAccount() {
        val user = Session.user
        val store = SettingsStore(this)
        val name = user?.displayName?.takeIf { it.isNotBlank() }
            ?: store.profileName.ifBlank { user?.email?.substringBefore('@').orEmpty() }
        tvProfileName.text = name
        tvInitial.text = name.take(1).uppercase()
        tvProfileEmail.text = user?.email.orEmpty()
        tvProfileShop.text = store.profileShop
        tvProfileShop.visibility = if (store.profileShop.isBlank()) View.GONE else View.VISIBLE
    }

    /** ลองส่งข้อมูลที่ค้างก่อน ถ้ายังค้างอยู่จะถามยืนยันก่อนออก (force = true คือยืนยันแล้ว) */
    private fun doLogout(force: Boolean) {
        Toast.makeText(this, R.string.logging_out, Toast.LENGTH_SHORT).show()
        SyncManager.signOut(this, force) { remaining ->
            if (isFinishing || isDestroyed) return@signOut
            if (remaining > 0) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.logout_title)
                    .setMessage(getString(R.string.logout_unsynced_fmt, remaining))
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setPositiveButton(R.string.btn_logout_anyway) { _, _ -> doLogout(true) }
                    .show()
            } else {
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }
}
