package com.silvershireinn.app.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.silvershireinn.app.databinding.ActivitySplashBinding
import com.silvershireinn.app.ui.identity.CreateIdentityActivity
import com.silvershireinn.app.ui.lock.LockScreenActivity
import com.silvershireinn.app.ui.main.MainActivity
import com.silvershireinn.app.ui.setup.ServerSetupActivity
import com.silvershireinn.app.utils.PreferenceManager

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            binding.tvSplashTitle.text = com.silvershireinn.app.utils.BrandingHelper.appLabel()
            binding.tvSplashTagline.text = com.silvershireinn.app.utils.BrandingHelper.splashText()
            com.silvershireinn.app.utils.BrandingHelper.applyTo(this, binding.tvSplashTitle, binding.ivSplashLogo, binding.tvSplashTagline)
        } catch (_: Exception) {}

        binding.logoContainer.alpha = 0f
        binding.logoContainer.animate()
            .alpha(1f)
            .setDuration(350)
            .withEndAction { navigateAfterDelay() }
            .start()
    }

    private fun navigateAfterDelay() {
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed   // user backed out: don't open anything
            val next = when {
                !PreferenceManager.isSetupComplete -> ServerSetupActivity::class.java
                // Use username as the "account exists" gate — isRegistered can be false
                // if the app was killed before register_ok arrived, but the username was
                // already chosen, so we should still proceed to MainActivity (not re-setup).
                PreferenceManager.username.isBlank() -> CreateIdentityActivity::class.java
                com.silvershireinn.app.utils.PanicLock.locked -> com.silvershireinn.app.ui.lock.PanicLockActivity::class.java
                PreferenceManager.requirePasswordOnOpen && hasLockCredentials() -> LockScreenActivity::class.java
                !PreferenceManager.autoLogin -> com.silvershireinn.app.ui.setup.LoginActivity::class.java
                else -> MainActivity::class.java
            }
            // No lock configured: nothing to unlock (MainActivity's gate passes).
            startActivity(Intent(this, next))
            finish()
        }, 150L)
    }

    /** Returns true if at least one lock credential is configured. */
    private fun hasLockCredentials(): Boolean {
        return PreferenceManager.passwordHash.isNotBlank() ||
               (PreferenceManager.pinEnabled && PreferenceManager.pinHash.isNotBlank()) ||
               PreferenceManager.biometricEnabled
    }
}