package com.silvershireinn.app.ui.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.databinding.ActivityLoginBinding
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.network.WebSocketService
import com.silvershireinn.app.ui.main.MainActivity
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.hide
import com.silvershireinn.app.utils.show
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Returning-user sign-in screen.
 *
 * Flow:
 *  1. User enters username + password → we store them locally (as local lock-screen creds).
 *  2. We start WebSocketService, which connects to the server and performs RSA challenge-response.
 *  3. We register a BroadcastReceiver waiting for:
 *       • com.silvershireinn.LOGIN_OK    → server accepted us → go to MainActivity
 *       • com.silvershireinn.LOGIN_ERROR → server rejected us → show error, reset state
 *  4. A 12-second timeout covers the case where the server is unreachable.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var timeoutJob: Job? = null
    private val previousUser: String = PreferenceManager.username
    @Volatile private var pendingHash: String? = null
    @Volatile private var pendingSalt: String? = null
    private var pending = false
    private var authReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(null)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.switchAutoLogin.isChecked = PreferenceManager.autoLogin
        binding.switchAutoLogin.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.autoLogin = checked
        }
        try {
            com.silvershireinn.app.utils.BrandingHelper.applyTo(
                this, binding.tvTitle, binding.ivFlame, null
            )
        } catch (_: Exception) {}

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString()

            when {
                username.isBlank()   -> { toast("Please enter your username"); return@setOnClickListener }
                password.length < 8  -> { toast("Password must be at least 8 characters"); return@setOnClickListener }
            }

            binding.btnLogin.isEnabled = false
            binding.progressLogin.show()

            Thread {
                try {
                    // Hash password for use as local lock-screen credential
                    val salt = CryptoManager.generateSalt()
                    val hash = CryptoManager.hashPassword(password, salt)

                    // The lock-screen password is only replaced once the server accepts us.
                    pendingHash = hash
                    pendingSalt = CryptoManager.saltToBase64(salt)
                    PreferenceManager.username     = username
                    PreferenceManager.isRegistered = true   // tells the service to send 'login'

                    runOnUiThread { startServerAuth() }
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.btnLogin.isEnabled = true
                        binding.progressLogin.hide()
                        toast("Sign in failed: ${e.message}")
                    }
                }
            }.start()
        }
    }

    /**
     * Registers a BroadcastReceiver for login result, starts the WebSocket service,
     * and arms a 12-second timeout coroutine.
     */
    private fun startServerAuth() {
        // Mark setup as incomplete so WebSocketService's "error" handler will broadcast
        // LOGIN_ERROR (rather than silently swallowing server errors like "User not found").
        // onLoginSuccess() sets this back to true once the server accepts us.
        PreferenceManager.isSetupComplete = false

        val filter = IntentFilter().apply {
            addAction("com.silvershireinn.LOGIN_OK")
            addAction("com.silvershireinn.LOGIN_ERROR")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.silvershireinn.LOGIN_OK"    -> onLoginSuccess()
                    "com.silvershireinn.LOGIN_ERROR" -> {
                        val msg = intent.getStringExtra("message")
                            ?: "Login failed. Check your username and try again."
                        onLoginFailure(msg)
                    }
                }
            }
        }
        authReceiver = receiver
        pending = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        // Start service — it will connect and send login; server replies with login_ok / error
        startService(Intent(this, WebSocketService::class.java))

        // Fallback: give up after 12 seconds if the server never replies
        timeoutJob = lifecycleScope.launch {
            delay(12_000L)
            onLoginFailure("Server did not respond. Make sure the server is running and try again.")
        }
    }

    private fun onLoginSuccess() {
        pending = false
        timeoutJob?.cancel()
        pendingHash?.let { PreferenceManager.passwordHash = it }
        pendingSalt?.let { PreferenceManager.passwordSalt = it }
        // Signed in as a different account: drop the old account's chats and contacts.
        val newUser = PreferenceManager.username
        if (previousUser.isNotBlank() && previousUser != newUser) {
            Thread { /* then resync the new account from the server */ com.silvershireinn.app.utils.AccountData.wipeLocal(applicationContext); com.silvershireinn.app.network.WebSocketManager.reconnectNow(true) }.start()
        }
        unregisterAuthReceiver()
        PreferenceManager.isSetupComplete = true
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun onLoginFailure(message: String) {
        timeoutJob?.cancel()
        unregisterAuthReceiver()
        resetLoginState()
        binding.btnLogin.isEnabled = true
        binding.progressLogin.hide()
        toast(message)
    }

    /** Clears credentials so a failed attempt leaves no stale state. */
    private fun resetLoginState() {
        // Disconnect first so any background reconnect loop doesn't keep sending
        // login messages with the just-cleared username on the next attempt.
        WebSocketManager.disconnect()
        pending = false
        // Keep the server setup (address + password were already verified); only the
        // account sign-in is undone. Clearing it sent people back to the server screen.
        PreferenceManager.username        = previousUser
        PreferenceManager.isRegistered    = previousUser.isNotBlank()
        PreferenceManager.isSetupComplete = true
    }

    private fun unregisterAuthReceiver() {
        authReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            authReceiver = null
        }
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        unregisterAuthReceiver()
        if (pending) resetLoginState()   // backed out while signing in
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
