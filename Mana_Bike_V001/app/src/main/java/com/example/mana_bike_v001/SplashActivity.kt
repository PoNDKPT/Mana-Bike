package com.example.mana_bike_v001

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (Session.isSignedIn) MainActivity::class.java else LoginActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, 1500)
    }
}
