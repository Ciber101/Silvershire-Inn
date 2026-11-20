package com.silvershireinn.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import com.silvershireinn.app.R
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.databinding.ActivityLockScreenBinding
import com.silvershireinn.app.ui.main.MainActivity
import com.silvershireinn.app.util.HapticUtil
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.hide
import com.silvershireinn.app.utils.show
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lock screen that supports sequential multi-factor authentication.
 *
 * If multiple factors are enabled the user must pass each one in order:
 *   1. Text password  (if requirePasswordOnOpen && passwordHash set)
 *   2. PIN            (if pinEnabled && pinHash set)
 *   3. Biometric      (if biometricEnabled && hardware present)
 *
 * All three enabled → must pass all three before accessing the app.
 * Only biometric enabled → biometric only (no fallback shown by default).
 * Etc.
 */
class LockScreenActivity : AppCompatActivity() {

    companion object {
        /** Screen to open after unlocking (e.g. the chat a notification pointed at). */
        const val EXTRA_RETURN = "return_intent"
    }

    private lateinit var binding: ActivityLockScreenBinding
    private var lockoutTimer: CountDownTimer? = null

    // ── PIN state ─────────────────────────────────────────────────────────────
    private val pinBuffer  = StringBuilder()
    private val maxPinLen  = 6
    private val pinDots: List<View> by lazy {
        listOf(binding.pinDot1, binding.pinDot2, binding.pinDot3,
               binding.pinDot4, binding.pinDot5, binding.pinDot6)
    }

    // ── Auth step machine ─────────────────────────────────────────────────────
    enum class Step { PASSWORD, PIN, BIOMETRIC }

    /** Ordered list of steps the user must pass through, derived from their settings. */
    private lateinit var steps: List<Step>
    private var stepIndex = 0

    private val currentStep get() = steps.getOrNull(stepIndex)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        steps = buildStepList()
        if (steps.isEmpty()) {
            // Nothing configured — just go straight to main
            proceedToMain()
            return
        }

        checkLockout()
        wireButtons()
        showCurrentStep()
    }

    // ── Build ordered step list ───────────────────────────────────────────────

    private fun buildStepList(): List<Step> {
        val list = mutableListOf<Step>()
        val hasPassword  = PreferenceManager.requirePasswordOnOpen &&
                           PreferenceManager.passwordHash.isNotBlank()
        val hasPin       = PreferenceManager.pinEnabled &&
                           PreferenceManager.pinHash.isNotBlank()
        val hasBiometric = PreferenceManager.biometricEnabled &&
                           BiometricManager.from(this)
                               .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                               BiometricManager.BIOMETRIC_SUCCESS

        // Fingerprint / PIN on: they unlock by themselves (the fingerprint prompt opens right
        // away); the password is only added in front when "Also ask for password" is on.
        // Neither: the password.
        if (hasPin || hasBiometric) {
            if (hasPassword && PreferenceManager.lockUsePassword) list.add(Step.PASSWORD)
            if (hasPin)       list.add(Step.PIN)
            if (hasBiometric) list.add(Step.BIOMETRIC)
        } else if (hasPassword) list.add(Step.PASSWORD)
        return list
    }

    // ── Show / hide the right panel for the current step ─────────────────────

    private fun showCurrentStep() {
        when (currentStep) {
            Step.PASSWORD -> showPasswordStep()
            Step.PIN      -> showPinStep()
            Step.BIOMETRIC -> showBiometricStep()
            null -> proceedToMain()   // all steps done
        }
    }

    private fun showPasswordStep() {
        binding.layoutPinMode.visibility = View.GONE
        binding.tilPassword.visibility   = View.VISIBLE
        binding.btnUnlock.visibility     = View.VISIBLE
        binding.btnBiometric.hide()
        updateStepLabel()
    }

    private fun showPinStep() {
        pinBuffer.clear()
        refreshPinDots()
        binding.layoutPinMode.visibility = View.VISIBLE
        binding.tilPassword.visibility   = View.GONE
        binding.btnUnlock.visibility     = View.GONE
        // Hide "Use Password" button within multi-step — user can't skip steps
        binding.btnPinUsePassword.visibility = View.GONE
        binding.btnBiometric.hide()
        updateStepLabel()
    }

    private fun showBiometricStep() {
        binding.layoutPinMode.visibility = View.GONE
        binding.tilPassword.visibility   = View.GONE
        binding.btnUnlock.visibility     = View.GONE
        binding.btnBiometric.show()
        updateStepLabel()
        // Open the fingerprint prompt right away, once the screen is up (shown during
        // onCreate it was sometimes swallowed, leaving you to tap the button).
        binding.root.post { if (!isFinishing && currentStep == Step.BIOMETRIC) showBiometricPrompt() }
    }

    /** Fingerprint declined: unlock with the account password instead. */
    private fun usePasswordInstead() {
        steps = steps.map { if (it == Step.BIOMETRIC) Step.PASSWORD else it }.distinct()
        stepIndex = steps.indexOf(Step.PASSWORD).coerceAtLeast(0)
        showCurrentStep()
    }

    /** Show "Step 1 of 3" style indicator when there are multiple steps. */
    private fun updateStepLabel() {
        if (steps.size > 1) {
            val label = when (currentStep) {
                Step.PASSWORD  -> "Step ${stepIndex + 1} of ${steps.size}: Password"
                Step.PIN       -> "Step ${stepIndex + 1} of ${steps.size}: PIN"
                Step.BIOMETRIC -> "Step ${stepIndex + 1} of ${steps.size}: Biometric"
                null           -> ""
            }
            binding.tvLockoutTimer.text = label
            binding.tvLockoutTimer.visibility = View.VISIBLE
        }
    }

    // ── Button wiring ─────────────────────────────────────────────────────────

    private fun wireButtons() {
        // Password unlock button
        binding.btnUnlock.setOnClickListener { HapticUtil.vibrate(this); attemptPasswordUnlock() }
        // Keyboard "done" unlocks too.
        binding.etPassword.setOnEditorActionListener { _, _, _ -> attemptPasswordUnlock(); true }

        // PIN digit buttons
        listOf(
            binding.btnPin1 to "1", binding.btnPin2 to "2", binding.btnPin3 to "3",
            binding.btnPin4 to "4", binding.btnPin5 to "5", binding.btnPin6 to "6",
            binding.btnPin7 to "7", binding.btnPin8 to "8", binding.btnPin9 to "9",
            binding.btnPin0 to "0"
        ).forEach { (btn, digit) ->
            btn.setOnClickListener { HapticUtil.vibrate(this); appendPinDigit(digit) }
        }

        binding.btnPinDelete.setOnClickListener { HapticUtil.vibrate(this); deletePinDigit() }
        binding.btnPinConfirm.setOnClickListener {
            HapticUtil.vibrate(this)
            if (pinBuffer.length < 4) toast("PIN must be at least 4 digits")
            else verifyPin(pinBuffer.toString())
        }

        // Biometric tap button
        binding.btnBiometric.setOnClickListener { HapticUtil.vibrate(this); showBiometricPrompt() }
    }

    // ── Step advancement ──────────────────────────────────────────────────────

    /** Call this when the current step passes. Advances to the next step or finishes. */
    private fun stepPassed() {
        PreferenceManager.failedAttempts    = 0
        PreferenceManager.lockoutUntil      = 0L
        PreferenceManager.biometricFailures = 0
        stepIndex++
        showCurrentStep()
    }

    // ── Password logic ────────────────────────────────────────────────────────

    private fun attemptPasswordUnlock() {
        if (PreferenceManager.lockoutUntil > System.currentTimeMillis()) return

        val password = binding.etPassword.text.toString()
        if (password.isBlank()) { toast("Enter your password"); return }
        if (checking) return
        // The check takes a moment on slower phones: show it and ignore repeat taps.
        checking = true
        binding.btnUnlock.isEnabled = false
        binding.btnUnlock.text = "Checking..."

        Thread {
            try {
                val saltB64    = PreferenceManager.passwordSalt
                val storedHash = PreferenceManager.passwordHash
                val salt       = CryptoManager.base64ToSalt(saltB64)
                val inputHash  = CryptoManager.hashPassword(password, salt)
                val correct    = inputHash == storedHash
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    endChecking()
                    if (correct) { binding.etPassword.setText(""); stepPassed() }
                    else         handleFailedAttempt(isPin = false)
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) { endChecking(); toast("Error verifying password") } }
            }
        }.start()
    }

    private var checking = false
    private fun endChecking() {
        checking = false
        binding.btnUnlock.text = getString(R.string.unlock)
        binding.btnUnlock.isEnabled = PreferenceManager.lockoutUntil <= System.currentTimeMillis()
    }

    // ── PIN logic ─────────────────────────────────────────────────────────────

    private fun appendPinDigit(digit: String) {
        if (PreferenceManager.lockoutUntil > System.currentTimeMillis()) return
        if (pinBuffer.length >= maxPinLen) return
        pinBuffer.append(digit)
        refreshPinDots()
    }

    private fun deletePinDigit() {
        if (pinBuffer.isNotEmpty()) {
            pinBuffer.deleteCharAt(pinBuffer.length - 1)
            refreshPinDots()
        }
    }

    private fun refreshPinDots() {
        pinDots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index < pinBuffer.length) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty
            )
        }
    }

    private fun verifyPin(pin: String) {
        val stored  = PreferenceManager.pinHash
        pinBuffer.clear()
        refreshPinDots()
        if (checking) return
        checking = true
        // Salted, slow hash: checked in the background; old-style PINs are upgraded on success.
        Thread {
            val ok = try { com.silvershireinn.app.utils.PinHash.verify(pin, stored) } catch (_: Exception) { false }
            if (ok && com.silvershireinn.app.utils.PinHash.isLegacy(stored)) {
                try { PreferenceManager.pinHash = com.silvershireinn.app.utils.PinHash.hash(pin) } catch (_: Exception) {}
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                checking = false
                if (ok) stepPassed() else handleFailedAttempt(isPin = true)
            }
        }.start()
    }

    // ── Biometric ─────────────────────────────────────────────────────────────

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        // "Use password" when the account has one (fallback instead of a dead end).
        val canFallBack = PreferenceManager.passwordHash.isNotBlank() && Step.PASSWORD !in steps
        val negativeText = if (canFallBack) "Use password" else "Cancel"
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Reset biometric failure counter on success
                PreferenceManager.biometricFailures = 0
                stepPassed()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && canFallBack) {
                    usePasswordInstead(); return
                }
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    toast("Biometric: $errString")
                }
            }
            override fun onAuthenticationFailed() {
                val failures = PreferenceManager.biometricFailures + 1
                PreferenceManager.biometricFailures = failures
                val remaining = 10 - failures
                if (failures >= 10) {
                    // Too many biometric failures — fall back to password/PIN
                    PreferenceManager.biometricFailures = 0
                    activateBiometricFallback()
                } else {
                    toast("Biometric not recognized. $remaining attempts remaining.")
                }
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Silvershire")
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    /**
     * Called after 10 consecutive biometric failures.
     * Forces password (if set) and/or PIN (if set) to become active authentication factors,
     * then rebuilds the step list and restarts from the first step.
     */
    private fun activateBiometricFallback() {
        val hasPassword = PreferenceManager.passwordHash.isNotBlank()
        val hasPin      = PreferenceManager.pinHash.isNotBlank()

        if (hasPassword) PreferenceManager.requirePasswordOnOpen = true
        if (hasPin)      PreferenceManager.pinEnabled = true

        // Rebuild step list — biometric is still in list so they can retry later,
        // but password/PIN now appear first
        steps     = buildStepList()
        stepIndex = 0

        toast("Too many failed attempts. Please use ${
            when {
                hasPassword && hasPin -> "your password and PIN"
                hasPassword           -> "your password"
                hasPin                -> "your PIN"
                else                  -> "your credentials"
            }
        }.")
        showCurrentStep()
    }

    // ── Shared failure handling ───────────────────────────────────────────────

    private fun handleFailedAttempt(isPin: Boolean) {
        val attempts = PreferenceManager.failedAttempts + 1
        PreferenceManager.failedAttempts = attempts

        if (attempts >= 11) { nukeData(); return }

        val lockoutMs = when {
            attempts >= 10 -> 7_200_000L
            attempts >= 9  -> 1_800_000L
            attempts >= 7  ->   300_000L
            attempts >= 5  ->    60_000L
            else           -> 0L
        }
        if (lockoutMs > 0) {
            PreferenceManager.lockoutUntil = System.currentTimeMillis() + lockoutMs
            startLockoutTimer(lockoutMs)
        }
        val remaining = 11 - attempts
        toast("Wrong ${if (isPin) "PIN" else "password"}. $remaining attempts remaining.")
    }

    // ── Lockout timer ─────────────────────────────────────────────────────────

    private fun checkLockout() {
        val lockUntil = PreferenceManager.lockoutUntil
        if (lockUntil > System.currentTimeMillis()) {
            startLockoutTimer(lockUntil - System.currentTimeMillis())
        }
    }

    private fun startLockoutTimer(durationMs: Long) {
        binding.etPassword.isEnabled = false
        binding.btnUnlock.isEnabled  = false
        lockoutTimer?.cancel()
        lockoutTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(left: Long) {
                val s = left / 1000
                binding.tvLockoutTimer.text = "Locked: %02d:%02d".format(s / 60, s % 60)
                binding.tvLockoutTimer.show()
            }
            override fun onFinish() {
                binding.etPassword.isEnabled = true
                binding.btnUnlock.isEnabled  = true
                binding.tvLockoutTimer.hide()
            }
        }.start()
    }

    // ── Nuke + proceed ────────────────────────────────────────────────────────

    private fun nukeData() {
        // Full wipe (groups, pictures and cached media too; the old one left those), then
        // start over from setup. It used to just re-open this screen with nothing to check.
        com.silvershireinn.app.network.WebSocketManager.disconnect()
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            com.silvershireinn.app.utils.AccountData.wipeLocal(app)
            listOf(PreferenceManager.profilePicturePath, PreferenceManager.bannerPicPath).forEach { p ->
                if (p.isNotBlank()) try { java.io.File(p).delete() } catch (_: Exception) {}
            }
            PreferenceManager.clearAll()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                toast("Security wipe performed")
                startActivity(Intent(app, com.silvershireinn.app.ui.splash.SplashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }

    private fun proceedToMain() {
        com.silvershireinn.app.utils.AppLock.markUnlocked()
        // Back to where the user was going (a chat from a notification / chat head), else home.
        @Suppress("DEPRECATION")
        val ret = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RETURN, Intent::class.java)
                  else intent.getParcelableExtra(EXTRA_RETURN)
        val target = ret?.takeIf { it.component?.className != LockScreenActivity::class.java.name }
            ?: Intent(this, MainActivity::class.java)
        try { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { startActivity(Intent(this, MainActivity::class.java)) }
        finish()
    }

    override fun onDestroy() {
        lockoutTimer?.cancel()
        super.onDestroy()
    }
}
