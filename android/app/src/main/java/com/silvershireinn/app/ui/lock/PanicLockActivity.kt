package com.silvershireinn.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.silvershireinn.app.R
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.ui.main.MainActivity
import com.silvershireinn.app.utils.PanicLock
import com.silvershireinn.app.utils.PanicVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown while the account is panic-locked (on every device, until the unlock phrase). The
 * phrase can be typed (no button needed: it's checked as you type) or said out loud when
 * "Listen out loud" is on. The SERVER checks it; PIN / password / fingerprint are not offered.
 * Back sends the app to the background.
 */
class PanicLockActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private var checkJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots / recent-apps preview of anything behind this.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (!PanicLock.locked) { openApp(); return }

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(ContextCompat.getColor(this@PanicLockActivity, R.color.color_background))
        }
        val title = TextView(this).apply {
            text = "Locked"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@PanicLockActivity, R.color.color_gold))
        }
        input = EditText(this).apply {
            hint = "Unlock word or phrase"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(this@PanicLockActivity, R.color.color_text_primary))
            setHintTextColor(ContextCompat.getColor(this@PanicLockActivity, R.color.color_text_secondary))
            setOnEditorActionListener { _, _, _ -> check(true); true }
            // Checked as you type (after a short pause), like the lock phrase.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { check(false) }
            })
        }
        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = dp(20)
            setTextColor(ContextCompat.getColor(this@PanicLockActivity, R.color.color_text_secondary))
        }
        val btn = Button(this).apply { text = "Unlock"; setOnClickListener { check(true) } }
        root.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        PanicLock.unlockListener = { ok ->
            runOnUiThread {
                if (ok) openApp()
                else if (!isFinishing) { status.setTextColor(ContextCompat.getColor(this, R.color.color_error)); status.text = "Not the unlock phrase" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!PanicLock.locked) { openApp(); return }
        PanicVoice.lockScreenOpen = true
        PanicVoice.refresh(this)
        status.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        status.text = if (PanicLock.listenOutLoud && PanicVoice.hasMicPermission(this)) "Listening" else ""
    }

    override fun onPause() {
        super.onPause()
        PanicVoice.lockScreenOpen = false
        PanicVoice.refresh(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (PanicLock.unlockListener != null) PanicLock.unlockListener = null
    }

    private fun check(now: Boolean) {
        val text = input.text?.toString().orEmpty()
        checkJob?.cancel()
        if (text.isBlank()) return
        checkJob = lifecycleScope.launch {
            if (!now) delay(700)                       // wait for a pause in typing
            val proofs = withContext(Dispatchers.Default) { PanicLock.unlockProofs(text) }
            if (proofs.isEmpty()) { if (now) status.text = "Not the unlock phrase"; return@launch }
            if (now) status.text = "Checking..."
            var reached = withContext(Dispatchers.IO) { PanicLock.requestUnlock(proofs) }
            var tries = 0
            while (!reached && PanicLock.locked && tries++ < 20) {
                status.text = "Can't reach the server. Trying again..."
                delay(3000)
                reached = withContext(Dispatchers.IO) { PanicLock.requestUnlock(proofs) }
            }        }
    }

    private fun openApp() {
        PanicVoice.lockScreenOpen = false
        com.silvershireinn.app.utils.AppLock.markUnlocked()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }
}
