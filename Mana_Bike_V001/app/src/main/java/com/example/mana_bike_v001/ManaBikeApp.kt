package com.example.mana_bike_v001

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.appcompat.app.AppCompatDelegate

class ManaBikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // แอปออกแบบเป็นธีมสว่างเท่านั้น
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // เอาโหมดข้อมูลตัวอย่างออกจากหน้าตั้งค่าแล้ว: ปิดค่าที่อาจค้างมาจากเวอร์ชันก่อน ไม่ให้แอปติดอยู่ในโหมดนี้
        val settings = SettingsStore(this)
        settings.demoMode = false

        // เตรียมเอนจินเสียงไว้ก่อน ให้ประกาศเงินเข้าครั้งแรกเร็วขึ้น
        if (settings.voiceEnabled) Speaker.warmUp(this)

        // เมื่อมีอินเทอร์เน็ต (รวมตอนแอปถูกปลุกให้ทำงานเบื้องหลัง) ให้ซิงก์ข้อมูลที่ค้าง
        val cm = getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SyncManager.syncSoon(this@ManaBikeApp)
            }
        })
    }
}
