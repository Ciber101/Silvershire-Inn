package com.silvershireinn.app.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import android.widget.Toast
import com.silvershireinn.app.R
import com.silvershireinn.app.databinding.ActivityMainBinding
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.network.WebSocketService
import com.silvershireinn.app.ui.identity.CreateIdentityActivity
import com.silvershireinn.app.ui.lock.LockScreenActivity
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.utils.BatteryOptimizationHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.AppUpdateHelper
import coil.load
import com.silvershireinn.app.utils.VideoBackgroundHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var updateCheckDone = false
    private val updateAvailableReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val url = intent?.getStringExtra("url") ?: return
            if (isFinishing || isDestroyed) return
            // The same update window as at start-up, with the download's progress in it
            AppUpdateHelper.showUpdateDialog(this@MainActivity, url)
        }
    }

    // ── Login error receiver ─────────────────────────────────────────────────
    private val loginErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: "Authentication failed."
            if (isFinishing || isDestroyed) return
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Login failed")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ ->
                    // Stop any active reconnect loop before clearing credentials — otherwise
                    // the background WebSocket keeps retrying with stale params and the next
                    // login attempt in LoginActivity sees it as "Connecting" and never fires.
                    WebSocketManager.disconnect()
                    PreferenceManager.clearAccountData()
                    startActivity(Intent(this@MainActivity, CreateIdentityActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }

    // ── Account deleted by admin receiver ────────────────────────────────────
    private val accountDeletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message")
                ?: "Your account has been deleted by the server administrator."
            if (isFinishing || isDestroyed) return
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Account deleted")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ ->
                    // Stop reconnecting and remove the deleted account's chats from this phone
                    // too (only the login was cleared before).
                    WebSocketManager.disconnect()
                    val app = applicationContext
                    Thread { com.silvershireinn.app.utils.AccountData.wipeLocal(app) }.start()
                    PreferenceManager.clearAccountData()
                    startActivity(Intent(this@MainActivity, CreateIdentityActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }

    // Auto-lock: checked every 30 s, but only while this screen is in front. It used to keep
    // running behind an open chat, locking you out while you were actively chatting.
    private val autoLogoutHandler = Handler(Looper.getMainLooper())
    private val autoLogoutRunnable = object : Runnable {
        override fun run() {
            if (com.silvershireinn.app.utils.AppLock.gate(this@MainActivity, null)) return
            autoLogoutHandler.postDelayed(this, 30_000L)
        }
    }

    // Build the list of permissions to request at first launch
    private val permissionsToRequest: Array<String>
        get() {
            // Only notifications up front. The camera and microphone are asked for the first
            // time you use them (with context), and photo access is not needed at all: the
            // phone's photo picker hands over just what you pick. Asking for all your photos
            // and videos on first launch was unnecessary and off-putting.
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
            // Android 9 and older need this to save photos / files to the phone.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
            return list.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Mark as requested regardless of outcome; user can grant later via Settings
            PreferenceManager.permissionsRequested = true
        }

    // ── Video background ──────────────────────────────────────────────────────

    private var videoBgHelper: VideoBackgroundHelper? = null

    /** Called when the active tab changes — swaps in that tab's background (video or image). */
    private fun applyTabVideoBg(tabKey: String) {
        // Video takes priority; fall back to image
        val videoPath = PreferenceManager.getTabVideoBg(tabKey)
        val imagePath = PreferenceManager.getTabImageBg(tabKey)
        if (videoPath != null) {
            binding.ivTabImageBg.visibility = android.view.View.GONE
            videoBgHelper?.applyPath(videoPath)
        } else if (imagePath != null && java.io.File(imagePath).exists()) {
            videoBgHelper?.applyPath(null)
            binding.ivTabImageBg.visibility = android.view.View.VISIBLE
            binding.ivTabImageBg.load(java.io.File(imagePath))
        } else {
            videoBgHelper?.applyPath(null)
            binding.ivTabImageBg.visibility = android.view.View.GONE
        }
        applyUiOpacity()
    }

    /** Reload the background for the current tab (called after user changes the setting). */
    fun refreshCurrentTabVideoBg() {
        val current = currentTabKey() ?: return
        applyTabVideoBg(current)
    }

    /** Apply the uiOpacity preference as alpha on the main content container. */
    fun applyUiOpacity() {
        val opacity = PreferenceManager.uiOpacity / 100f
        binding.layoutMainContent.alpha = opacity
    }

    private fun currentTabKey(): String? = when (binding.bottomNav.selectedItemId) {
        R.id.chatsFragment    -> "chats"
        R.id.contactsFragment -> "contacts"
        R.id.profileFragment  -> "profile"
        else                  -> null
    }

    // ── Tab & nav-position metadata ───────────────────────────────────────────

    private data class TabInfo(val id: Int, val iconRes: Int, val titleRes: Int)

    private val TAB_INFOS = mapOf(
        "chats"    to TabInfo(R.id.chatsFragment,    R.drawable.ic_chat,     R.string.chats),
        "contacts" to TabInfo(R.id.contactsFragment, R.drawable.ic_contacts, R.string.contacts),
        "profile"  to TabInfo(R.id.profileFragment,  R.drawable.ic_profile,  R.string.profile),
    )

    /** Rebuild the BottomNav menu in the user's saved tab order. */
    fun applyTabOrder() {
        val order = PreferenceManager.tabOrder.split(",").map { it.trim() }
        val menu = binding.bottomNav.menu
        menu.clear()
        for ((index, key) in order.withIndex()) {
            val info = TAB_INFOS[key] ?: continue
            menu.add(0, info.id, index, info.titleRes)
                .setIcon(info.iconRes)
        }
    }

    /**
     * Apply the user's chosen accent color to BottomNav tints and all accent-colored elements.
     *
     * @param oldAccent The accent that was previously active. Pass this when the user is
     *                  actively changing their accent so elements already tinted with the
     *                  old accent (not just gold) are also updated. Defaults to GOLD so
     *                  cold-start / onResume calls always work correctly.
     */
    fun applyAccentColor(oldAccent: Int = AccentColorHelper.GOLD) {
        val color = PreferenceManager.accentColorInt()

        // BottomNavigationView — use checked/unchecked state list
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(color, 0xFF888888.toInt())
        val csl = ColorStateList(states, colors)
        binding.bottomNav.itemIconTintList = csl
        binding.bottomNav.itemTextColor    = csl

        // Walk the entire window view tree and re-tint every accent-colored element.
        AccentColorHelper.apply(binding.root, color, oldAccent)
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)
    }

    /** Move the BottomNav to the top or bottom of the inner content LinearLayout. */
    fun applyNavPosition() {
        val root = binding.layoutMainContent
        val nav  = binding.bottomNav
        root.removeView(nav)
        if (PreferenceManager.navBarAtTop) root.addView(nav, 0) else root.addView(nav)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        // Apply the user's UI font scale preference so ALL text in the app (except chat
        // bubbles, which use their own sp setting) scales with the slider.
        // uiFontSize: 0=Small → 0.85×, 1=Medium → 1.0×, 2=Large → 1.15×
        val fontScale = when (PreferenceManager.uiFontSize) {
            0    -> 0.85f
            2    -> 1.15f
            else -> 1.0f
        }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.fontScale = fontScale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTabOrder()

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        // ── Window insets — keep content/nav above system bars ──────────────────
        // Apply top/side insets to the content container so the fragment area starts
        // below the status bar. Apply bottom inset ONLY to the BottomNavigationView
        // so its background extends to the edge of the screen without affecting the
        // fragment area height. This prevents the "doubled nav bar height" bug that
        // occurs when Material's BottomNavigationView also applies insets internally.
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutMainContent) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Only top/side — bottom is handled by bottomNav directly below
            view.setPadding(bars.left, bars.top, bars.right, 0)
            windowInsets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Pad the nav bar so its items sit above the system nav buttons
            view.setPadding(0, 0, 0, bars.bottom)
            windowInsets
        }

        applyNavPosition()
        applyAccentColor()

        // Set up tab video background
        videoBgHelper = VideoBackgroundHelper(this, binding.svTabVideoBg)
        applyTabVideoBg("chats")   // default tab on launch

        // Switch video when the user navigates between tabs; clear contacts badge on tab visit
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val tabKey = when (destination.id) {
                R.id.chatsFragment    -> "chats"
                R.id.contactsFragment -> "contacts"
                R.id.profileFragment  -> "profile"
                else                  -> null
            }
            if (tabKey != null) applyTabVideoBg(tabKey)
            // Clear the friend-request badge when the user visits the contacts tab
            if (destination.id == R.id.contactsFragment) {
                binding.bottomNav.removeBadge(R.id.contactsFragment)
            }
            // Re-apply accent color and font after navigation: the newly-inflated fragment's
            // views are added to the hierarchy AFTER onResume(), so applyAccentColor() in
            // onResume() misses them. Using post{} ensures the fragment finishes its layout pass
            // before FontHelper walks the tree.
            binding.root.post { applyAccentColor() }
        }

        // Show a BottomNav badge + toast when a friend request arrives while on another tab
        lifecycleScope.launch {
            WebSocketManager.events.collect { json ->
                if (json.optString("type") == "friend_request") {
                    val from = json.optString("from")
                    if (from.isNotBlank()) {
                        // Badge the contacts tab so the user knows to check
                        val badge = binding.bottomNav.getOrCreateBadge(R.id.contactsFragment)
                        badge.isVisible = true
                        // Toast so it's visible regardless of which tab is active
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                            Toast.makeText(
                                this@MainActivity,
                                "Friend request from $from",
                                Toast.LENGTH_LONG
                            ).show()
                    }
                }
            }
        }

        startService(Intent(this, WebSocketService::class.java))

        // Request critical permissions once, on first launch into the main app
        if (!PreferenceManager.permissionsRequested) {
            val perms = permissionsToRequest
            if (perms.isEmpty()) PreferenceManager.permissionsRequested = true
            else permissionLauncher.launch(perms)
        }

        // Handle deep-link extras from notifications (e.g. open_tab=contacts from friend request)
        handleOpenTabIntent(intent)

    }

    override fun onPause() {
        super.onPause()
        autoLogoutHandler.removeCallbacks(autoLogoutRunnable)
    }

    override fun onStart() {
        super.onStart()
        val updFilter = IntentFilter("com.silvershireinn.UPDATE_AVAILABLE")
        // (receivers below show dialogs; they are guarded against a closing screen)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateAvailableReceiver, updFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateAvailableReceiver, updFilter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(loginErrorReceiver, IntentFilter("com.silvershireinn.LOGIN_ERROR"), RECEIVER_NOT_EXPORTED)
            registerReceiver(accountDeletedReceiver, IntentFilter("com.silvershireinn.ACCOUNT_DELETED"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(loginErrorReceiver, IntentFilter("com.silvershireinn.LOGIN_ERROR"))
            registerReceiver(accountDeletedReceiver, IntentFilter("com.silvershireinn.ACCOUNT_DELETED"))
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(loginErrorReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(accountDeletedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(updateAvailableReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // Locked (opened from a notification, or idle too long): show the lock screen first.
        if (com.silvershireinn.app.utils.AppLock.gate(this, null)) return
        autoLogoutHandler.removeCallbacks(autoLogoutRunnable)
        autoLogoutHandler.postDelayed(autoLogoutRunnable, 30_000L)
        // Re-dispatch window insets after screen unlock — ensures the bottom nav
        // never gets stuck behind the home/back buttons.
        ViewCompat.requestApplyInsets(binding.root)
        applyAccentColor()
        applyUiOpacity()
        // Re-apply the current tab's background — the nav-destination listener only
        // fires on tab *switches*, so returning from a settings screen that changed
        // the current tab's background wouldn't refresh it without this call.
        refreshCurrentTabVideoBg()
        // If settings were just restored from the server (e.g. after a reinstall+login),
        // recreate the Activity so all fragments re-read their prefs from the new values.
        if (PreferenceManager.settingsJustRestored) {
            PreferenceManager.settingsJustRestored = false  // clear BEFORE recreate to avoid loop
            recreate()
            return
        }
        // If the WebSocket is disconnected when the user returns to the app, kick off an
        // immediate reconnect instead of waiting for the scheduled backoff timer (up to 30s).
        // onStartCommand() is a no-op when already Connected, so this is safe to call always.
        if (PreferenceManager.serverPasswordRejected) {
            askNewServerPassword()
        } else if (WebSocketManager.state.value != WebSocketManager.ConnectionState.Connected) {
            startService(Intent(this, WebSocketService::class.java))
        }

        maybePromptBatteryExemption()
        if (!updateCheckDone && PreferenceManager.isSetupComplete) {
            updateCheckDone = true
            lifecycleScope.launch {
                try { AppUpdateHelper.checkAndPrompt(this@MainActivity) } catch (_: Exception) {}
            }
        }
    }

    private var serverPwDialog: AlertDialog? = null

    /** The server refused the saved server password (changed on the server PC): ask for the new one. */
    private fun askNewServerPassword() {
        if (serverPwDialog?.isShowing == true || isFinishing) return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "New server password"
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        serverPwDialog = AlertDialog.Builder(this)
            .setTitle("Server password changed")
            .setMessage("The server no longer accepts the saved server password. Enter the new one to reconnect.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Connect") { _, _ ->
                val pw = input.text?.toString()?.trim().orEmpty()
                if (pw.isEmpty()) { window.decorView.post { askNewServerPassword() }; return@setPositiveButton }
                PreferenceManager.serverPassword = pw
                PreferenceManager.serverPasswordRejected = false
                startService(Intent(this, WebSocketService::class.java))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * One-time prompt asking the user to exempt the app from battery optimization. This is
     * the single biggest factor in message notifications surviving Doze/sleep. Shown once
     * (tracked by a device-local pref); the user can re-trigger it later from Settings.
     */
    private fun maybePromptBatteryExemption() {
        if (!PreferenceManager.isSetupComplete) return
        if (PreferenceManager.batteryOptPromptShown) return
        // Already exempt — record it and don't nag.
        if (BatteryOptimizationHelper.isIgnoring(this)) {
            PreferenceManager.batteryOptPromptShown = true
            return
        }
        // Don't stack on top of the first-launch runtime-permission dialog.
        if (!PreferenceManager.permissionsRequested) return
        PreferenceManager.batteryOptPromptShown = true
        AlertDialog.Builder(this)
            .setTitle("Keep notifications working")
            .setMessage(
                "To make sure you always receive messages, even when the app is in the " +
                "background or your phone is asleep, allow Silvershire Inn to ignore battery " +
                "optimization.\n\nWithout this, Android can stop your messages after a while."
            )
            .setPositiveButton("Allow") { _, _ -> BatteryOptimizationHelper.requestExemption(this) }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        com.silvershireinn.app.utils.AppLock.noteActivity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenTabIntent(intent)
    }

    /** Navigate to a specific tab if the launching intent requests it. */
    private fun handleOpenTabIntent(intent: Intent?) {
        when (intent?.getStringExtra("open_tab")) {
            "contacts" -> binding.bottomNav.selectedItemId = R.id.contactsFragment
            "chats"    -> binding.bottomNav.selectedItemId = R.id.chatsFragment
            "profile"  -> binding.bottomNav.selectedItemId = R.id.profileFragment
        }
    }


    override fun onDestroy() {
        autoLogoutHandler.removeCallbacks(autoLogoutRunnable)
        videoBgHelper?.release()
        super.onDestroy()
    }
}