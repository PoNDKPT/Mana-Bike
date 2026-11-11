package com.example.mana_bike_v001

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * ประกาศยอดเงินเข้าเป็นเสียงภาษาไทยด้วย Text-to-Speech ของเครื่อง
 *
 * - ปกติใช้ช่องเสียงสายเรียกเข้า/แจ้งเตือน (ระดับเสียงตามปุ่มเสียงเรียกเข้า ไม่ใช่เสียงสื่อ)
 * - ถ้ามีอุปกรณ์เสียงภายนอกต่ออยู่ (ลำโพง/หูฟังบลูทูธ สาย USB/AUX) จะสลับไปใช้ช่องเสียงสื่อ
 *   เพราะเสียงเรียกเข้า/แจ้งเตือนของ Android จะไม่ออกไปที่ลำโพงที่ต่ออยู่
 * - ก่อนพูดจะขอ Audio Focus แบบชั่วคราว เพื่อให้แอปอื่นที่เล่นเสียงอยู่ (เพลง/วิดีโอ) หยุดชั่วคราว
 *   และคืน focus เมื่อพูดจบ เพื่อให้เสียงเดิมเล่นต่อ
 */
object Speaker {
    private const val FOCUS_SAFETY_MS = 20_000L

    /** ความเร็วในการพูด: 1.0 = ปกติ, มากกว่า 1.0 = เร็วขึ้น (ถ้าอยากให้พูดเร็วขึ้นลองช่วง 1.1 - 1.3) */
    private const val SPEECH_RATE = 1.0f

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var thaiOk = false
    private val pending = mutableListOf<Pair<String, (() -> Unit)?>>()

    private val phoneAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val externalAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** ช่องเสียงที่ใช้กับรอบการพูดปัจจุบัน */
    private var attrs: AudioAttributes = phoneAttrs

    private var focusRequest: AudioFocusRequest? = null
    private var activeUtterances = 0
    private var justRestarted = false
    private val safetyRelease = Runnable { activeUtterances = 0; abandonFocus() }

    fun announcement(amount: Double): String {
        val baht = amount.toLong()
        val satang = Math.round((amount - baht) * 100).toInt()
        return if (satang == 0) "ได้รับเงิน $baht บาท" else "ได้รับเงิน $baht บาท $satang สตางค์"
    }

    /** เตรียมเอนจินเสียงไว้ล่วงหน้า เพื่อให้ประกาศแรกหลังเปิดแอป/ปลุกแอปไม่ต้องรอโหลดเอนจิน */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        main.post {
            appContext = app
            if (tts == null) start(app)
        }
    }

    /** @param onUnavailable เรียกบนเธรดหลักเมื่อพูดไม่ได้ (ไม่มีเสียงไทย/ไม่มีเอนจิน TTS) */
    fun speak(context: Context, text: String, onUnavailable: (() -> Unit)? = null) {
        val app = context.applicationContext
        main.post {
            appContext = app
            if (initialized) {
                play(text, onUnavailable)
            } else {
                pending.add(text to onUnavailable)
                if (tts == null) start(app)
            }
        }
    }

    private fun play(text: String, onUnavailable: (() -> Unit)?) {
        val engine = tts
        if (engine != null && thaiOk) {
            // เลือกช่องเสียงใหม่ทุกครั้ง เผื่อเพิ่งต่อ/ถอดลำโพง (ถ้ายังถือ focus อยู่จากคิวก่อนหน้า ไม่เปลี่ยนกลางคัน)
            if (focusRequest == null) attrs = if (externalOutputConnected()) externalAttrs else phoneAttrs
            engine.setAudioAttributes(attrs)
            requestFocus()
            activeUtterances++
            // กันกรณีเอนจินไม่แจ้งว่าพูดจบ: คืน focus เองหลังเวลาที่กำหนด เพื่อไม่ให้เพลงค้างหยุดตลอด
            main.removeCallbacks(safetyRelease)
            main.postDelayed(safetyRelease, FOCUS_SAFETY_MS)
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, "mana_" + System.nanoTime())
            if (result == TextToSpeech.SUCCESS) {
                justRestarted = false
            } else {
                utteranceFinished()
                if (!justRestarted) {
                    // เอนจินเสียงอาจถูกระบบปิดไปแล้ว: สร้างใหม่แล้วพูดซ้ำ 1 ครั้ง (ไม่ต้องรอผู้ใช้เปิดแอปใหม่)
                    justRestarted = true
                    engine.shutdown()
                    tts = null
                    initialized = false
                    thaiOk = false
                    pending.add(text to onUnavailable)
                    appContext?.let { start(it) }
                } else {
                    onUnavailable?.invoke()
                }
            }
        } else {
            onUnavailable?.invoke()
        }
    }

    private fun isWarm(id: String?) = id?.startsWith("warm_") == true

    /** สั่งสังเคราะห์เสียงเงียบ (ระดับเสียง 0) เพื่อให้เอนจินโหลดเสียงไทยไว้ก่อน ไม่ขอ focus จึงไม่รบกวนเพลง */
    private fun warmVoice(engine: TextToSpeech) {
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }
        engine.setAudioAttributes(phoneAttrs)
        engine.speak("ก", TextToSpeech.QUEUE_ADD, params, "warm_" + System.nanoTime())
    }

    private fun start(app: Context) {
        tts = TextToSpeech(app) { status -> main.post { onReady(status) } }
    }

    private fun onReady(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            engine?.shutdown()
            tts = null
            initialized = false
            thaiOk = false
            pending.forEach { it.second?.invoke() }
            pending.clear()
            return
        }
        val result = engine.setLanguage(Locale.forLanguageTag("th-TH"))
        thaiOk = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setSpeechRate(SPEECH_RATE)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (!isWarm(utteranceId)) main.post { utteranceFinished() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (!isWarm(utteranceId)) main.post { utteranceFinished() } }
            override fun onError(utteranceId: String?, errorCode: Int) { if (!isWarm(utteranceId)) main.post { utteranceFinished() } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (!isWarm(utteranceId)) main.post { utteranceFinished() } }
        })
        initialized = true
        if (thaiOk) warmVoice(engine)
        val queued = pending.toList()
        pending.clear()
        queued.forEach { play(it.first, it.second) }
    }

    // ---------------- ตรวจลำโพง/หูฟังที่ต่ออยู่ ----------------

    private fun externalOutputConnected(): Boolean {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HEARING_AID
        )
        if (Build.VERSION.SDK_INT >= 31) {
            types.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            types.add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    // ---------------- Audio Focus ----------------

    /** ขอ focus แบบชั่วคราว (ไม่ใช่แค่ลดเสียง) เพื่อให้เพลง/วิดีโอหยุดระหว่างประกาศ */
    private fun requestFocus() {
        if (focusRequest != null) return // ถือ focus อยู่แล้ว (กำลังพูดต่อคิว)
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        // ถ้าขอไม่ได้ (เช่นกำลังคุยโทรศัพท์) ก็ยังสั่งพูดต่อไป
        am.requestAudioFocus(req)
        focusRequest = req
    }

    private fun utteranceFinished() {
        if (activeUtterances > 0) activeUtterances--
        if (activeUtterances == 0) abandonFocus()
    }

    private fun abandonFocus() {
        main.removeCallbacks(safetyRelease)
        val req = focusRequest ?: return
        focusRequest = null
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.abandonAudioFocusRequest(req)
    }
}
