package com.silvershireinn.app.ui.identity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.databinding.ActivityCreateIdentityBinding
import com.silvershireinn.app.network.WebSocketService
import com.silvershireinn.app.ui.main.MainActivity
import com.silvershireinn.app.ui.setup.LoginActivity
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.hide
import com.silvershireinn.app.utils.show
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * New-account creation screen.
 *
 * Flow:
 *  1. User enters username + password → we store credentials locally.
 *  2. We start WebSocketService with isRegistered=false, so it sends a 'register' message.
 *  3. Server responds with 'register_ok' → WebSocketService broadcasts com.silvershireinn.LOGIN_OK.
 *  4. We navigate to MainActivity only after receiving that broadcast.
 *  5. A 15-second timeout covers unreachable servers.
 */
class CreateIdentityActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateIdentityBinding

    private var timeoutJob: Job? = null
    /** Account signed in before this screen (restored if creating a new one fails). */
    private val previousUser: String = PreferenceManager.username
    private var pending = false
    private var authReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateIdentityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(null)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePasswordStrength(s.toString())
            }
        })

        binding.btnGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.btnCreate.setOnClickListener {
            val username  = binding.etUsername.text.toString().trim()
            val password  = binding.etPassword.text.toString()
            val confirm   = binding.etConfirmPassword.text.toString()
            val serverPwd = PreferenceManager.serverPassword

            when {
                username.isBlank()      -> toast("Username cannot be blank")
                password.length < 8     -> toast("Password must be at least 8 characters")
                password != confirm     -> toast("Passwords do not match")
                else                    -> createAccount(username, password, serverPwd)
            }
        }
    }

    private fun updatePasswordStrength(password: String) {
        val strength = when {
            password.length >= 16 && password.any { it.isDigit() } && password.any { !it.isLetterOrDigit() } -> 3
            password.length >= 10 && (password.any { it.isDigit() } || password.any { !it.isLetterOrDigit() }) -> 2
            password.length >= 8 -> 1
            else -> 0
        }
        binding.strengthIndicator.progress = strength * 33
        binding.tvStrengthLabel.text = when (strength) {
            0    -> if (password.isEmpty()) "" else "Too short (8+ characters)"
            1    -> "Fair"
            2    -> "Strong"
            else -> "Very Strong"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun createAccount(username: String, password: String, serverPwd: String) {
        binding.btnCreate.isEnabled = false
        binding.progressCreate.show()

        Thread {
            try {
                // Old account data is NOT wiped here any more: it used to be erased before the
                // server even accepted the new account, so a failed attempt lost everything.

                val salt = CryptoManager.generateSalt()
                val hash = CryptoManager.hashPassword(password, salt)

                PreferenceManager.username       = username
                PreferenceManager.serverPassword = serverPwd
                PreferenceManager.passwordHash   = hash
                PreferenceManager.passwordSalt   = CryptoManager.saltToBase64(salt)
                PreferenceManager.isRegistered   = false   // tells the service to send 'register'
                // Reset setup flag so that server errors during registration are surfaced
                // to the user via the LOGIN_ERROR broadcast instead of being silently
                // suppressed (they are suppressed when isSetupComplete=true so that normal
                // reconnect failures while already inside the app don't force logout).
                // Also disconnect any stale WebSocket so onStartCommand() does a fresh connect.
                PreferenceManager.isSetupComplete = false
                WebSocketManager.disconnect()

                runOnUiThread { startServerRegistration() }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnCreate.isEnabled = true
                    binding.progressCreate.hide()
                    toast("Failed to create account: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * Registers a BroadcastReceiver for registration result, starts the WebSocket service,
     * and arms a 15-second timeout coroutine.
     */
    private fun startServerRegistration() {
        val filter = IntentFilter().apply {
            addAction("com.silvershireinn.LOGIN_OK")
            addAction("com.silvershireinn.LOGIN_ERROR")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.silvershireinn.LOGIN_OK"    -> onRegistrationSuccess()
                    "com.silvershireinn.LOGIN_ERROR" -> {
                        val msg = intent.getStringExtra("message")
                            ?: "Registration failed. Try a different username."
                        onRegistrationFailure(msg)
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

        // Start service — isRegistered=false causes it to send 'register'; server replies register_ok
        startService(Intent(this, WebSocketService::class.java))

        // Fallback: give up after 15 seconds
        timeoutJob = lifecycleScope.launch {
            delay(15_000L)
            onRegistrationFailure("Server did not respond. Make sure the server is running and try again.")
        }
    }

    /** Different account than before: clear the previous account's local data (after success only). */
    private fun wipePreviousAccountData() = com.silvershireinn.app.utils.AccountData.wipeLocal(applicationContext)

    private fun onRegistrationSuccess() {
        timeoutJob?.cancel()
        unregisterAuthReceiver()
        pending = false
        val newUser = PreferenceManager.username
        if (previousUser.isNotBlank() && previousUser != newUser) {
            Thread { /* then resync the new account from the server */ wipePreviousAccountData(); com.silvershireinn.app.network.WebSocketManager.reconnectNow(true) }.start()
        }
        PreferenceManager.isSetupComplete = true
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun onRegistrationFailure(message: String) {
        timeoutJob?.cancel()
        unregisterAuthReceiver()
        restorePreviousState()
        binding.btnCreate.isEnabled = true
        binding.progressCreate.hide()
        toast(message)
    }

    /** Failed / abandoned: go back to exactly how things were (server setup stays). */
    private fun restorePreviousState() {
        pending = false
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
        if (pending) { WebSocketManager.disconnect(); restorePreviousState() }
        super.onDestroy()
    }
}
