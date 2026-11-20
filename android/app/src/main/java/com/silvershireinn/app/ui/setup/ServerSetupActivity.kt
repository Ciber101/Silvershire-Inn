package com.silvershireinn.app.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.silvershireinn.app.databinding.ActivityServerSetupBinding
import com.silvershireinn.app.ui.identity.CreateIdentityActivity
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit

class ServerSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerSetupBinding
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill the saved address exactly as stored (keeps https:// - stripping it used to
        // silently switch a secure address back to http on the next save).
        val saved = PreferenceManager.serverUrl
        if (saved.isNotBlank()) binding.etServerAddress.setText(saved)
        if (PreferenceManager.serverPassword.isNotBlank()) binding.etServerPassword.setText(PreferenceManager.serverPassword)

        binding.btnRegister.setOnClickListener { validateThen { startActivity(Intent(this, CreateIdentityActivity::class.java)) } }
        binding.btnSignIn.setOnClickListener { validateThen { startActivity(Intent(this, LoginActivity::class.java)) } }
    }

    /** No scheme typed: IP / localhost / explicit port = local server (http), a name = https. */
    private fun normalise(raw: String): String {
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw.trimEnd('/')
        val hostPart = raw.substringBefore('/')
        val host = hostPart.substringBefore(':')
        val local = host.equals("localhost", true) || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || hostPart.contains(':')
        return ((if (local) "http://" else "https://") + raw).trimEnd('/')
    }

    /** Checks the address, makes sure the server answers, saves it, then runs [next]. */
    private fun validateThen(next: () -> Unit) {
        if (checking) return
        val raw = binding.etServerAddress.text.toString().trim()
        val serverPwd = binding.etServerPassword.text.toString().trim()
        if (raw.isBlank()) { showError("Please enter the server address"); return }
        if (serverPwd.isBlank()) { showError("Please enter the server password"); return }
        val normalised = normalise(raw)
        val host = try { URI(normalised).host } catch (_: Exception) { null }
        if (host.isNullOrBlank()) { showError("That address doesn't look right. Use IP:PORT or a hostname."); return }

        hideError()
        setBusy(true)
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
                    client.newCall(okhttp3.Request.Builder().url("$normalised/api/version").build()).execute().use { true }
                } catch (_: Exception) { false }
            }
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            if (!reachable) {
                showError("Can't reach the server at $normalised. Check the address and that the server is running.")
                return@launch
            }
            PreferenceManager.serverUrl = normalised
            PreferenceManager.serverPassword = serverPwd
            PreferenceManager.isSetupComplete = true
            next()
        }
    }

    private fun setBusy(busy: Boolean) {
        checking = busy
        binding.btnRegister.isEnabled = !busy
        binding.btnSignIn.isEnabled = !busy
        if (busy) { binding.tvAddressError.text = "Checking the server..."; binding.tvAddressError.visibility = View.VISIBLE }
    }

    private fun showError(msg: String) {
        binding.tvAddressError.text       = msg
        binding.tvAddressError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvAddressError.visibility = View.GONE
    }
}
