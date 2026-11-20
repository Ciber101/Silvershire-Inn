package com.silvershireinn.app.network

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.crypto.CryptoManager
import com.silvershireinn.app.notifications.NotificationHelper
import com.silvershireinn.app.repository.ContactRepository
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.GroupRepository
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.ui.call.CallActivity
import com.silvershireinn.app.ui.call.VideoCallActivity
import com.silvershireinn.app.ui.chathead.ChatHeadService
import com.silvershireinn.app.utils.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class WebSocketService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)
    private lateinit var messageRepo: MessageRepository
    private lateinit var contactRepo: ContactRepository
    private lateinit var groupRepo: GroupRepository

    /**
     * SharedPreferences listener that auto-pushes any syncable setting change to the server.
     * Held as a field so it isn't garbage-collected (WeakReference issue).
     */
    private var settingsPushJob: Job? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null &&
            PreferenceManager.isSyncableKey(key) &&
            !PreferenceManager.isApplyingRemoteSettings
        ) {
            // Debounced: dragging a color picker or a reset writes many keys at once and each
            // used to push the whole settings blob to the server (and to every other device).
            settingsPushJob?.cancel()
            settingsPushJob = scope.launch {
                delay(600)
                WebSocketManager.sendSettings(PreferenceManager.toSettingsJson())
            }
        }
    }

    /** One connect-preparation at a time (key check / key generation can take seconds). */
    private val preparingConnect = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        const val SERVICE_ID = 1

        /**
         * Emits (name, available) pairs when the server responds to a check_username request.
         * ProfileFragment collects from this to find out if a desired username is free.
         * extraBufferCapacity=8 so the emit never suspends even if no collector is active yet.
         */
        val usernameCheckResults = MutableSharedFlow<Pair<String, Boolean>>(
            replay = 0,
            extraBufferCapacity = 8
        )

        @Volatile private var instance: WebSocketService? = null
        /** Microphone / camera in use by a call right now. */
        @Volatile private var callMic = false
        @Volatile private var callCam = false

        /**
         * Tell Android this service is using the microphone (and camera, for video) for a
         * call. Without it, Android 11+ cut the microphone as soon as you left the call screen
         * (home button, another app): the other person suddenly heard nothing.
         */
        fun setCallMedia(mic: Boolean, camera: Boolean) {
            if (callMic == mic && callCam == camera) return
            if (mic && !callMic) com.silvershireinn.app.utils.PanicVoice.pauseForMic()
            if (!mic && callMic) com.silvershireinn.app.utils.PanicVoice.resumeAfterMic(com.silvershireinn.app.SilvershireApp.instance)
            callMic = mic; callCam = camera
            val svc = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { svc.startForegroundCompat() }
        }

        /** Background panic listening switched on / off: update the service's declared types. */
        fun refreshForegroundTypes() {
            val svc = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { svc.startForegroundCompat() }
        }
    }

    /** Current connection status shown in the ongoing foreground-service notification. */
    @Volatile private var serviceStatusLabel: String = "Connected and secure"

    /** Default-network callback so we can reconnect the instant connectivity returns. */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        messageRepo = MessageRepository(SilvershireApp.database)
        contactRepo = ContactRepository(SilvershireApp.database)
        groupRepo   = GroupRepository(SilvershireApp.database)
        startForegroundCompat()
        setupCallEndedCallback()
        listenForEvents()
        observeConnectionForNotification()
        registerNetworkCallback()
        startPendingFlushLoop()
        // Arm the periodic watchdog so we recover even if the OS kills this service.
        WatchdogReceiver.schedule(this)
        // Register settings auto-push listener
        PreferenceManager.registerListener(prefsListener)
    }

    /**
     * Enter the foreground with the correct service type for the API level.
     *
     * Android 14+ runs as SPECIAL_USE instead of DATA_SYNC: on Android 15 a dataSync
     * foreground service is force-stopped after 6 hours per day, which silently kills the
     * connection (and therefore notifications) after long uptime — the reported bug.
     * SPECIAL_USE has no such time limit.
     *
     * Must be called from onCreate AND at the top of onStartCommand, because every
     * startForegroundService() (including the watchdog's) must be matched by a
     * startForeground() within ~5 s or the OS kills us.
     */
    private fun startForegroundCompat() {
        val notification = NotificationHelper.buildServiceNotification(this, serviceStatusLabel)
        // During a call also declare microphone / camera use (only types we hold the
        // permission for, or Android refuses the whole call).
        var media = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val panicMic = com.silvershireinn.app.utils.PanicLock.listenOutLoud && com.silvershireinn.app.utils.PanicLock.listenInBackground &&
                com.silvershireinn.app.utils.PanicLock.isSet()
            if ((callMic || panicMic) && granted(android.Manifest.permission.RECORD_AUDIO))
                media = media or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (callCam && granted(android.Manifest.permission.CAMERA))
                media = media or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(
                        SERVICE_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or media
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(
                        SERVICE_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or media
                    )
                else ->
                    startForeground(SERVICE_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("WebSocketService", "startForeground failed: ${e.message}")
            try { startForeground(SERVICE_ID, notification) } catch (_: Exception) {}
        }
    }

    /** Keep the ongoing notification text in sync with the connection state. */
    private fun observeConnectionForNotification() {
        scope.launch {
            WebSocketManager.state.collect { st ->
                serviceStatusLabel = when (st) {
                    WebSocketManager.ConnectionState.Connected  -> "Connected and secure"
                    WebSocketManager.ConnectionState.Connecting -> "Connecting…"
                    else                                        -> "Reconnecting…"
                }
                try {
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm?.notify(
                        SERVICE_ID,
                        NotificationHelper.buildServiceNotification(this@WebSocketService, serviceStatusLabel)
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /** Reconnect immediately when the network becomes available (no waiting for backoff). */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                private var lastNetwork: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    val prev = lastNetwork
                    lastNetwork = network
                    if (WebSocketManager.state.value != WebSocketManager.ConnectionState.Connected) {
                        WebSocketManager.reconnectNow()
                    } else if (prev != null && prev != network) {
                        // Wi-Fi <-> mobile data switch: the old socket is dead but still looks
                        // connected until the next ping times out (up to a minute of silently
                        // lost sends). Reconnect on the new network right away.
                        WebSocketManager.reconnectNow(force = true)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {}
    }

    private fun setupCallEndedCallback() {
        fun makeTimestamp() = com.silvershireinn.app.utils.nowIso()

        // One log entry per call, from my point of view: who started it, how it ended and
        // whether it was video. Incoming calls I answered used to be logged as outgoing,
        // declining was logged as missed, and video calls were labeled "Voice call".
        fun log(remoteUser: String, durationSecs: Int, reason: String, incoming: Boolean, video: Boolean) {
            val me = PreferenceManager.username
            val direction = when (reason) {
                "MISSED" -> "missed"
                "REJECTED" -> "rejected"
                "DECLINED" -> "declined"
                else -> if (incoming) "incoming" else "outgoing"
            }
            scope.launch {
                messageRepo.insertCallMessage(
                    from         = if (incoming) remoteUser else me,
                    to           = if (incoming) me else remoteUser,
                    isSent       = !incoming,
                    direction    = if (video) "$direction:video" else direction,
                    durationSecs = durationSecs,
                    timestamp    = makeTimestamp()
                )
            }
        }
        CallManager.onCallEnded = { u, d, r, inc -> log(u, d, r.name, inc, video = false) }
        VideoCallManager.onCallEnded = { u, d, r, inc -> log(u, d, r.name, inc, video = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground state on EVERY start (including watchdog-triggered restarts).
        // Each startForegroundService() must be matched by a startForeground() within ~5 s or
        // the OS kills us — so this must run before the early-return below.
        startForegroundCompat()
        // Keep the watchdog armed (cheap; replaces the pending alarm).
        WatchdogReceiver.schedule(this)

        // If already connected, do nothing. MainActivity calls startService() on every
        // onCreate (to keep the service alive), but we must not trigger a second
        // WebSocketManager.connect() — that would cause a race where the new connection's
        // RSA handshake isn't complete yet when the user tries to search, making the
        // server-side username null and causing the search to hang forever.
        if (WebSocketManager.state.value == WebSocketManager.ConnectionState.Connected) {
            return START_STICKY
        }

        // Server password was refused: every retry counts toward the server's 24 h address
        // ban, so wait until the user enters the new one (MainActivity asks).
        if (PreferenceManager.serverPasswordRejected) return START_STICKY

        val serverUrl = PreferenceManager.serverUrl
        val serverPassword = PreferenceManager.serverPassword
        val username = PreferenceManager.username
        val isRegistered = PreferenceManager.isRegistered

        // The key check below does real RSA work (and may generate a key pair, seconds on a
        // cheap phone). It ran on the main thread here, freezing the app on every service start.
        if (serverUrl.isNotBlank() && username.isNotBlank() && preparingConnect.compareAndSet(false, true)) scope.launch {
          try {
            // Always send the current RSA public key — not just during initial registration.
            // This ensures that after a reinstall (where AndroidKeyStore may be cleared and a
            // new keypair generated) the server and all contacts get the updated public key.
            //
            // ensureConsistentRsaKeyPair() validates that the stored public and private keys are
            // a genuine matching pair (test encrypt/decrypt round-trip). If they are mismatched
            // — e.g. one was regenerated at a different time, causing "key mismatch" on login —
            // it deletes both keys and returns false so we generate a fresh consistent pair here.
            val publicKey = if (CryptoManager.ensureConsistentRsaKeyPair()) {
                CryptoManager.getRsaPublicKey()?.let {
                    CryptoManager.publicKeyToBase64(it)
                } ?: CryptoManager.publicKeyToBase64(CryptoManager.generateRsaKeyPair().public)
            } else {
                CryptoManager.publicKeyToBase64(CryptoManager.generateRsaKeyPair().public)
            }

            WebSocketManager.connect(
                WebSocketManager.ConnectParams(
                    serverUrl = serverUrl,
                    serverPassword = serverPassword,
                    username = username,
                    isRegistering = !isRegistered,
                    publicKey = publicKey
                )
            )
          } finally { preparingConnect.set(false) }
        }
        return START_STICKY
    }

    private fun listenForEvents() {
        scope.launch {
            WebSocketManager.events.collect { json ->
                handleEvent(json)
            }
        }
    }

    private suspend fun handleEvent(json: JSONObject) {
        when (json.optString("type")) {
            "auth_challenge" -> {
                // Server issued an RSA challenge — sign the nonce with our private key
                // using SHA256withRSA and return the signature to prove key ownership.
                // This replaces the old OAEP decrypt approach and has no cross-provider
                // parameter issues between Node.js and BouncyCastle.
                val nonce = json.optString("nonce")
                if (nonce.isNotBlank()) {
                    val signature = CryptoManager.signRsaChallenge(nonce)
                    if (signature != null) {
                        WebSocketManager.sendRaw(JSONObject().apply {
                            put("type", "auth_response")
                            put("signature", signature)
                        }.toString())
                    } else {
                        // Signing failed — private key missing or corrupt.
                        android.util.Log.e("WebSocketService", "RSA challenge signing failed — private key missing or corrupt?")
                        // Only broadcast LOGIN_ERROR during the initial login/register flow.
                        // If the user is already inside the app (isSetupComplete = true) and
                        // the WebSocket reconnects with a transient key issue, broadcasting
                        // LOGIN_ERROR would wipe the account and show a scary dialog — instead
                        // let the reconnect loop retry; the keys should be stable after Fix #11.
                        if (!PreferenceManager.isSetupComplete) {
                            withContext(Dispatchers.Main) {
                                sendBroadcast(android.content.Intent("com.silvershireinn.LOGIN_ERROR").apply {
                                    putExtra("message", "Authentication failed: key error. You may need to create a new account.")
                                    `package` = packageName
                                })
                            }
                        }
                    }
                }
            }
            "login_error" -> {
                // This account's key is on another device (or the server refused a new key):
                // stop retrying (repeated refusals would get this phone's address banned) and
                // say why, even when already set up (other login errors stay quiet then).
                if (json.optString("code") == "key_mismatch") {
                    WebSocketManager.disconnect()
                    val m = json.optString("message", "Sign-in blocked: this account's key is on another device.")
                    withContext(Dispatchers.Main) {
                        try {
                            android.widget.Toast.makeText(this@WebSocketService, m, android.widget.Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {}
                        if (PreferenceManager.isSetupComplete) NotificationHelper.showMessageNotification(this@WebSocketService, "Silvershire", m)
                    }
                }
                // Server rejected our authentication — show a user-visible error.
                // Guard with isSetupComplete for the same reason as auth_challenge above:
                // reconnect failures while already inside the app should not force logout.
                val message = json.optString("message", "Login failed. Please check your credentials.")
                if (!PreferenceManager.isSetupComplete) withContext(Dispatchers.Main) {
                    sendBroadcast(android.content.Intent("com.silvershireinn.LOGIN_ERROR").apply {
                        putExtra("message", message)
                        `package` = packageName
                    })
                }
            }
            "error" -> {
                if (json.optString("message") == "Wrong server password" && PreferenceManager.isSetupComplete) {
                    // The server password was changed on the server PC. Stop retrying and ask.
                    PreferenceManager.serverPasswordRejected = true
                    WebSocketManager.disconnect()
                    val m = "The server password changed. Open the app and enter the new one."
                    withContext(Dispatchers.Main) {
                        try { android.widget.Toast.makeText(this@WebSocketService, m, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                        NotificationHelper.showMessageNotification(this@WebSocketService, "Silvershire", m)
                    }
                    return
                }
                // Generic server error (e.g. "User not found" during login).
                // Only broadcast LOGIN_ERROR while still in the auth flow — once the user is
                // in the app (isSetupComplete=true), MainActivity's loginErrorReceiver would
                // misinterpret any server error as a login failure and log the user out.
                if (!PreferenceManager.isSetupComplete) {
                    val message = json.optString("message", "Login failed. Please check your credentials.")
                    withContext(Dispatchers.Main) {
                        sendBroadcast(android.content.Intent("com.silvershireinn.LOGIN_ERROR").apply {
                            putExtra("message", message)
                            `package` = packageName
                        })
                    }
                }
            }
            "register_ok" -> {
                json.optString("httpToken").takeIf { it.isNotBlank() }?.let { PreferenceManager.httpToken = it }
                PreferenceManager.shareUrl = if (json.isNull("shareUrl")) "" else json.optString("shareUrl", "")
                PreferenceManager.isRegistered = true
                WebSocketManager.isAuthenticated = true
                // Broadcast so CreateIdentityActivity knows registration was accepted by the server
                withContext(Dispatchers.Main) {
                    sendBroadcast(android.content.Intent("com.silvershireinn.LOGIN_OK").apply {
                        `package` = packageName
                    })
                }
            }
            "login_ok" -> {
                // Invite link: the server's public download page (null while it doesn't know one)
                PreferenceManager.shareUrl = if (json.isNull("shareUrl")) "" else json.optString("shareUrl", "")
                // Per-device token for uploads / downloads (replaces the shared password there).
                json.optString("httpToken").takeIf { it.isNotBlank() }?.let { PreferenceManager.httpToken = it }
                // Profile frame (border) and the saved collection come from the account
                if (json.has("profileFrame")) com.silvershireinn.app.utils.ProfileFrames.myFrame = json.optString("profileFrame", "none")
                json.optJSONArray("savedFrames")?.let { a -> com.silvershireinn.app.utils.ProfileFrames.savedFrames = (0 until a.length()).map { a.optString(it) } }
                // Server now knows who we are on THIS socket; sends are accepted from here on.
                WebSocketManager.isAuthenticated = true
                // Friends / group members who are panic-locked right now: their chats stay closed here.
                json.optJSONArray("lockedPeers")?.let { arr ->
                    com.silvershireinn.app.utils.PeerLocks.setAll((0 until arr.length()).map { arr.optString(it) })
                }
                // Panic phrases come from the account (set once, every device). A locked account
                // stays locked here too (fresh installs included); a lock made offline goes out now.
                if (json.has("panic")) com.silvershireinn.app.utils.PanicLock.applyServerConfig(json.optJSONObject("panic"))
                val acctLocked = json.optBoolean("panicLocked", false)
                when {
                    com.silvershireinn.app.utils.PanicLock.hasPendingLock() -> com.silvershireinn.app.utils.PanicLock.sendPendingLock()
                    acctLocked -> withContext(Dispatchers.Main) { com.silvershireinn.app.utils.PanicLock.lockNow(this@WebSocketService, fromServer = true) }
                    com.silvershireinn.app.utils.PanicLock.locked -> com.silvershireinn.app.utils.PanicLock.onAccountUnlocked(this@WebSocketService, alreadySignedIn = true)
                }
                if (acctLocked || com.silvershireinn.app.utils.PanicLock.locked) {
                    // Signed in, but locked: setup screens may finish (the lock screen takes over).
                    withContext(Dispatchers.Main) {
                        sendBroadcast(android.content.Intent("com.silvershireinn.LOGIN_OK").apply { `package` = packageName })
                    }
                    return
                }
                // Report whether the app is in use right now ("online" = actually using it).
                com.silvershireinn.app.SilvershireApp.reportUse(force = true)
                // Send any saved crash reports so problems on this phone can be fixed.
                scope.launch { com.silvershireinn.app.utils.CrashGuard.uploadPending(this@WebSocketService) }
                val serverDisplay = json.optString("displayName", "")
                val signupName = json.optString("signupName", "")
                withContext(Dispatchers.Main) {
                    if (serverDisplay.isNotBlank()) PreferenceManager.myDisplayName = serverDisplay
                    if (signupName.isNotBlank() && PreferenceManager.signupUsername.isBlank())
                        PreferenceManager.signupUsername = signupName
                }
                // Restore settings from server if available
                val settingsJson = json.optJSONObject("settings")
                if (settingsJson != null) {
                    withContext(Dispatchers.Main) {
                        val before = PreferenceManager.toSettingsJson().toString()
                        PreferenceManager.applySettingsJson(settingsJson)
                        // Recreate MainActivity only if something actually changed. This was set
                        // on EVERY reconnect, so the whole app flashed and rebuilt each time it
                        // came back to the foreground after a network blip.
                        if (PreferenceManager.toSettingsJson().toString() != before)
                            PreferenceManager.settingsJustRestored = true
                    }
                }
                // Top-level avatarShape on login_ok (only set when present; do not invent)
                if (json.has("avatarShape") && !json.isNull("avatarShape")) {
                    val ash = json.optString("avatarShape", "").trim()
                    if (ash.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            PreferenceManager.chatHeadShape =
                                com.silvershireinn.app.utils.AvatarShape.fromServer(ash).prefKey()
                        }
                    }
                }
                // Re-assert own presence from local/server settings after login
                withContext(Dispatchers.Main) {
                    val pm = PreferenceManager.presenceMode
                    WebSocketManager.sendPresence(pm)
                }
                // Signal LoginActivity that server authentication succeeded
                withContext(Dispatchers.Main) {
                    sendBroadcast(android.content.Intent("com.silvershireinn.LOGIN_OK").apply {
                        `package` = packageName
                    })
                }
                // Always re-download own profile pic on login_ok — this ensures the profile
                // picture shows up after logout/re-login without needing an app restart.
                // The download is quick (local server) and fixes the race condition where
                // ProfileFragment renders before the async download completes.
                val serverHasPic = json.optBoolean("hasPic", false)
                val serverHasBanner = json.optBoolean("hasBanner", false)
                // In the background: these downloads used to hold up every event behind them
                // (contacts, history, new messages) on each reconnect.
                scope.launch {
                if (serverHasPic) {
                    val localPath = FileRepository.downloadOwnProfilePic(this@WebSocketService)
                    if (localPath != null) {
                        withContext(Dispatchers.Main) {
                            PreferenceManager.profilePicturePath = localPath
                        }
                        // Notify ProfileFragment (and any other UI) to refresh the avatar
                        sendBroadcast(android.content.Intent("com.silvershireinn.PROFILE_PIC_RESTORED").apply {
                            `package` = packageName
                        })
                    }
                } else {
                    // Server has no pic — clear any stale local path so initials show correctly
                    withContext(Dispatchers.Main) {
                        PreferenceManager.profilePicturePath = ""
                    }
                }
                // Restore own banner pic if missing locally
                if (serverHasBanner && !java.io.File(PreferenceManager.bannerPicPath).exists()) {
                    val localPath = FileRepository.downloadOwnBannerPic(this@WebSocketService)
                    if (localPath != null) {
                        withContext(Dispatchers.Main) {
                            PreferenceManager.bannerPicPath = localPath
                        }
                    }
                }
                }
                // Resend anything the server never acked (1:1 and group, text and attachments).
                flushPendingOutgoing(minAgeMs = 0L)
            }

            "username_change_ok" -> {
                val newName = json.optString("newName")
                if (newName.isNotBlank()) {
                    PreferenceManager.username = newName
                    // Keep reconnect params in sync so re-connections use the new name
                    WebSocketManager.updateUsername(newName)
                }
            }
            "username_check_result" -> {
                // Server response to a check_username request — forward to ProfileFragment via SharedFlow
                val name      = json.optString("name")
                val available = json.optBoolean("available")
                if (name.isNotBlank()) {
                    scope.launch { usernameCheckResults.emit(Pair(name, available)) }
                }
            }
            "username_change_error" -> { /* ignore — ProfileFragment handles this via a shared flow or toast */ }
            "contact_renamed" -> {
                val oldName = json.optString("oldName")
                val newName = json.optString("newName")
                if (oldName.isNotBlank() && newName.isNotBlank()) {
                    scope.launch { contactRepo.renameContact(oldName, newName) }
                }
            }
            "contacts_list" -> {
                val arr        = json.optJSONArray("contacts") ?: return
                val me         = PreferenceManager.username
                val deleted    = PreferenceManager.getDeletedContacts()
                val pkMap      = json.optJSONObject("publicKeys")
                val picMap     = json.optJSONObject("contactPics")
                val bannerMap  = json.optJSONObject("contactBanners")
                val namesMap   = json.optJSONObject("displayNames")
                val shapeMap   = json.optJSONObject("contactAvatarShapes")
                val frameMap   = json.optJSONObject("contactFrames")

                val serverContacts    = mutableSetOf<String>()
                val contactsWithPics  = mutableListOf<String>()
                val contactsWithBanners = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isBlank() || name == me) continue
                    serverContacts.add(name)
                    if (name in deleted) {
                        WebSocketManager.sendRemoveContact(name)
                    } else {
                        contactRepo.insertOrUpdateContact(name)
                        pkMap?.optString(name)?.takeIf { it.isNotBlank() }?.let { pk ->
                            SilvershireApp.database.contactDao().updatePublicKey(name, pk)
                        }
                        val hasPic    = picMap?.optBoolean(name, false) ?: false
                        val hasBanner = bannerMap?.optBoolean(name, false) ?: false
                        if (hasPic)    contactsWithPics.add(name)
                        if (hasBanner) contactsWithBanners.add(name)
                        val dn = namesMap?.optString(name, "") ?: ""
                        if (dn.isNotBlank()) PreferenceManager.setContactDisplayName(name, dn)
                        val ash = shapeMap?.optString(name, "") ?: ""
                        if (ash.isNotBlank()) PreferenceManager.setContactAvatarShape(name, ash)
                        if (frameMap != null) com.silvershireinn.app.utils.ProfileFrames.setContactFrame(name, frameMap.optString(name, "none"))
                    }
                }
                PreferenceManager.notifyNamesChanged()

                val localConfirmed = contactRepo.getAllConfirmedContacts()
                // An empty list while we have friends locally is far more likely a server
                // hiccup (users file not loaded yet) than every friend removing us at once;
                // removals also arrive as contact_removed_by_peer. Don't wipe on that.
                if (serverContacts.isNotEmpty() || localConfirmed.isEmpty()) {
                    for (contact in localConfirmed) {
                        if (contact.username !in serverContacts) contactRepo.deleteContact(contact.username)
                    }
                }

                // After syncing contacts, request message history for each confirmed contact
                // so chats appear on new devices. Existing devices skip duplicates by messageId.
                for (contactName in serverContacts) {
                    scope.launch {
                        WebSocketManager.sendRaw(org.json.JSONObject().apply {
                            put("type", "history_request")
                            put("to", contactName)
                            // Only what we're missing (newest local message, minus an hour of
                            // margin). It re-downloaded up to 200 messages per friend on EVERY
                            // reconnect. Older servers ignore "since" and send everything.
                            historySince(contactName)?.let { put("since", it) }
                        }.toString())
                    }
                }

                // Request history only for groups that still have an active conversation
                // (guards against orphaned groups whose conversation was deleted reappearing)
                scope.launch {
                    val groups = groupRepo.getGroups().first()
                    for (g in groups) {
                        val conv = SilvershireApp.database.conversationDao().getConversation(g.groupId)
                        if (conv != null) {
                            WebSocketManager.requestGroupHistory(g.groupId, historySince(g.groupId))
                        }
                        // No "orphan" deletion here any more: a missing conversation row is not
                        // proof we left the group (an update used to wipe conversations, which then
                        // deleted every group and silently dropped all later group messages).
                        // Membership is reconciled against the server's groups_list instead.
                    }
                }

                contactsWithPics.forEach { contactName ->
                    scope.launch {
                        val localPath = FileRepository.downloadContactProfilePic(
                            this@WebSocketService, contactName
                        )
                        if (localPath != null) {
                            ChatHeadService.refreshContactAvatar(this@WebSocketService, contactName)
                            // Notify all open adapters (ContactAdapter, ConversationAdapter, ChatActivity)
                            val picIntent = android.content.Intent("com.silvershireinn.PROFILE_UPDATED").apply {
                                putExtra("username", contactName)
                                putExtra("picPath",  localPath)
                                `package` = packageName
                            }
                            sendBroadcast(picIntent)
                        }
                    }
                }

                // Download banner pics for contacts who have them (no chat head refresh needed)
                contactsWithBanners.forEach { contactName ->
                    scope.launch {
                        FileRepository.downloadContactBannerPic(this@WebSocketService, contactName)
                    }
                }
            }
            "online_status" -> {
                val user     = json.optString("name")
                val online   = json.optBoolean("online")
                val lastSeen = if (json.has("lastSeen")) json.optString("lastSeen") else null
                val presenceProvided = json.has("presence") || json.has("mode")
                val presence = when {
                    json.has("presence") && !json.isNull("presence") -> json.optString("presence")
                    json.has("mode") && !json.isNull("mode") -> json.optString("mode")
                    else -> null
                }
                if (user.isNotBlank()) {
                    contactRepo.setOnlineStatus(user, online)
                    OnlineStatusManager.setStatus(
                        user, online, lastSeen, presence, presenceProvided
                    )
                }
            }
            "presence_ok" -> {
                val pm = OnlineStatusManager.normalizePresenceMode(
                    json.optString("presence").ifBlank { json.optString("mode") }
                )
                if (pm != null) {
                    withContext(Dispatchers.Main) {
                        PreferenceManager.isApplyingRemoteSettings = true
                        try {
                            PreferenceManager.presenceMode = pm
                        } finally {
                            PreferenceManager.isApplyingRemoteSettings = false
                        }
                    }
                }
            }
            "private_message" -> {
                val from      = json.optString("from")
                val rawPayload = json.optString("payload")
                val timestamp  = json.optString("timestamp")
                val messageId  = json.optString("messageId")
                // Already have it (server resend after a dropped ack, or a reconnect replay):
                // just confirm delivery again. Re-inserting used to mark it unread again and
                // fire a second notification and chat-head bump.
                if (from.isNotBlank() && messageId.isNotBlank() &&
                    SilvershireApp.database.messageDao().getMessageById(messageId) != null) {
                    WebSocketManager.sendDeliveryReceipt(PreferenceManager.username, from, messageId)
                    return
                }
                if (from.isNotBlank() && messageId.isNotBlank()) {
                    // Attempt E2E decryption — gracefully falls back to plaintext for
                    // legacy messages or messages from contacts without the new client.
                    // If decryption fails (null), show a friendly placeholder instead of
                    // raw JSON. This typically means the sender encrypted with an old public
                    // key — asking them to resend after both parties reconnect will fix it.
                    val payload = if (CryptoManager.hasRsaKeyPair()) {
                        val decrypted = CryptoManager.decryptIfE2E(rawPayload)
                        if (decrypted == null) {
                            // Decryption failed — sender likely has our stale public key.
                            // Immediately request their fresh key so we push our updated key
                            // to the server, which will re-broadcast it to the sender.
                            // This auto-heals after the next message exchange.
                            scope.launch {
                                WebSocketManager.sendRaw(org.json.JSONObject().apply {
                                    put("type",   "get_public_key")
                                    put("from",   PreferenceManager.username)
                                    put("target", from)
                                }.toString())
                            }
                            "🔒 Message couldn't be decrypted, your key was refreshed automatically. Ask your contact to resend this message."
                        } else decrypted
                    } else {
                        rawPayload
                    }

                    // Check if this is an attachment payload
                    val attJson = try {
                        val p = org.json.JSONObject(payload)
                        p.optJSONObject("_att")
                    } catch (_: Exception) { null }

                    if (attJson != null) {
                        com.silvershireinn.app.utils.MediaKeys.learn(attJson)
                        val fileId   = attJson.optString("fileId")
                        val fileName = attJson.optString("name")
                        val mimeType = attJson.optString("mime")
                        val fileSize = attJson.optLong("size")
                        messageRepo.insertIncomingAttachment(
                            from, PreferenceManager.username,
                            fileId, fileName, mimeType, fileSize,
                            timestamp, messageId
                        )
                        // Their message means they saw our earlier sends — gold ticks.
                        if (timestamp.isNotBlank()) {
                            messageRepo.markOutgoingReadUpTo(from, timestamp)
                        }
                        // Tell sender we received it (second grey check on their side)
                        WebSocketManager.sendDeliveryReceipt(
                            PreferenceManager.username, from, messageId
                        )

                        // Pre-cache received videos so the first open is instant.
                        precacheVideo(fileId, mimeType, fileSize)
                        // Suppress notif only when this conversation is visibly open
                        // (chat screen resumed OR chat-head overlay for this contact at bottom).
                        val overlayOpen = com.silvershireinn.app.ui.chathead.ChatHeadService
                            .overlayContactId == from
                        val overlayAtBottom = com.silvershireinn.app.ui.chathead.ChatHeadService.overlayAtBottom
                        val viewingChat = com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact == from
                        val suppressNotif = viewingChat || (overlayOpen && overlayAtBottom)
                        // "Photo" / "Video" / "Voice message" instead of a raw file name.
                        val attPreview = com.silvershireinn.app.utils.attachmentPreview(mimeType, fileName)
                        if (!suppressNotif) {
                            NotificationHelper.showMessageNotification(
                                this@WebSocketService, from, attPreview
                            )
                        }
                        // Show/bump chat-head badge for incoming attachments — same logic as text messages
                        val atBottom = com.silvershireinn.app.ui.chathead.ChatHeadService.overlayAtBottom
                        if (PreferenceManager.chatHeads && canDrawOverlay() &&
                            com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact != from) {
                            ChatHeadService.show(this@WebSocketService, from, attPreview)
                        } else if (overlayOpen && !atBottom) {
                            ChatHeadService.show(this@WebSocketService, from, attPreview)
                        }
                    } else {
                        var textPayload = payload
                        if (from == "Silvershire Updates" && textPayload.contains("/download")) {
                            val base = FileRepository.publicHttpBase()
                            textPayload = textPayload
                                .replace("/download/silvershire-inn.apk", "$base/download/silvershire-inn.apk")
                            sendBroadcast(android.content.Intent("com.silvershireinn.UPDATE_AVAILABLE").apply {
                                putExtra("from", from)
                                putExtra("url", "$base/download/silvershire-inn.apk")
                                putExtra("text", textPayload)
                                `package` = packageName
                            })
                        }
                        messageRepo.insertIncoming(from, PreferenceManager.username, textPayload, timestamp, messageId)
                        // Their message means they saw our earlier sends — gold ticks.
                        if (timestamp.isNotBlank()) {
                            messageRepo.markOutgoingReadUpTo(from, timestamp)
                        }
                        // Tell sender we received it (second grey check on their side)
                        WebSocketManager.sendDeliveryReceipt(
                            PreferenceManager.username, from, messageId
                        )

                        val preview = if (textPayload.length > 60) textPayload.take(60) + "..." else textPayload
                        val overlayOpen = com.silvershireinn.app.ui.chathead.ChatHeadService
                            .overlayContactId == from
                        val overlayAtBottom = com.silvershireinn.app.ui.chathead.ChatHeadService.overlayAtBottom
                        val viewingChat = com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact == from
                        // App in foreground on a DIFFERENT screen still gets a notif.
                        // Only suppress when this exact conversation is on screen.
                        val suppressNotif = viewingChat || (overlayOpen && overlayAtBottom)
                        if (!suppressNotif) {
                            NotificationHelper.showMessageNotification(this@WebSocketService, from, preview)
                        }
                        // Chat head badge logic:
                        // - Overlay closed: normal behaviour (show if chat heads enabled + not active chat)
                        // - Overlay open + at bottom: suppress (user can see the message)
                        // - Overlay open + scrolled up: show badge so user knows something arrived
                        val atBottom = com.silvershireinn.app.ui.chathead.ChatHeadService.overlayAtBottom
                        if (PreferenceManager.chatHeads && canDrawOverlay() &&
                            com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact != from) {
                            ChatHeadService.show(this@WebSocketService, from, preview)
                        } else if (overlayOpen && !atBottom) {
                            // Scrolled up in overlay — bump the badge so user notices
                            ChatHeadService.show(this@WebSocketService, from, preview)
                        }
                    }
                }
            }

            "public_key_response" -> {
                // Response to a get_public_key request — store for E2E encryption
                val name = json.optString("name")
                val pk   = json.optString("publicKey").takeIf { it.isNotBlank() }
                if (name.isNotBlank() && pk != null) {
                    SilvershireApp.database.contactDao().updatePublicKey(name, pk)
                }
            }
            "public_key_updated" -> {
                // A mutual contact reconnected with a new keypair (e.g. after reinstall).
                // Update our local DB so the next message we send is encrypted with the
                // correct new key, preventing decryption failures on their end.
                val name = json.optString("name")
                val pk   = json.optString("publicKey").takeIf { it.isNotBlank() }
                if (name.isNotBlank() && pk != null) {
                    SilvershireApp.database.contactDao().updatePublicKey(name, pk)
                    android.util.Log.d("WebSocketService", "Updated public key for $name after key rotation")
                }
            }
            "file_shared" -> {
                // Server notifies us when someone uploads via HTTP.
                // The app also sends a private_message with _att payload, which the handler
                // above already processes — so we only need to show a notification here
                // if we missed the private_message (e.g. app was just opening).
                // DB insertion is intentionally skipped to avoid duplicates.
                // Nothing to do: the sender's _att private_message (handled above) brings the
                // attachment and its notification. Notifying here too gave a duplicate
                // notification for every photo / video / voice note, even with the chat open.
                // Same as the web client.
            }
            "friend_request" -> {
                val from = json.optString("from")
                // Someone you blocked: decline quietly (they used to be able to keep asking).
                if (from.isNotBlank() && from in PreferenceManager.getBlockedUsers()) {
                    WebSocketManager.sendFriendResponse(PreferenceManager.username, from, false)
                    return
                }
                if (from.isNotBlank()) {
                    contactRepo.addFriendRequest(from)
                    com.silvershireinn.app.notifications.NotificationHelper
                        .showFriendRequestNotification(this@WebSocketService, from)
                }
            }
            "friend_accepted" -> {
                val from = json.optString("name")   // server uses "name", not "from"
                if (from.isNotBlank()) {
                    contactRepo.confirmFriend(from)
                    // Store their public key so we can start E2E-encrypting messages right away
                    val pk = json.optString("publicKey").takeIf { it.isNotBlank() }
                    if (pk != null) {
                        SilvershireApp.database.contactDao().updatePublicKey(from, pk)
                    }
                    // Dismiss the friend request notification — it's been resolved
                    com.silvershireinn.app.notifications.NotificationHelper
                        .cancelFriendRequestNotification(this@WebSocketService)
                }
            }
            "friend_declined" -> {
                // The other user declined our friend request - clean up the pending entry
                val from = json.optString("name")
                if (from.isNotBlank()) contactRepo.deleteContact(from)
            }
            "contact_removed_by_peer" -> {
                // The other user deleted us as a contact (or panic-wiped their account).
                // End any active call with this user, then wipe conversation + contact.
                val from = json.optString("name")
                if (from.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        // If we're on a call with the wiped user, hang up immediately.
                        if (CallManager.remoteUser == from &&
                            CallManager.state != CallManager.State.IDLE) {
                            CallManager.endCall()
                        }
                        if (VideoCallManager.remoteUser == from &&
                            VideoCallManager.state != VideoCallManager.State.IDLE) {
                            VideoCallManager.endCall()
                        }
                    }
                    scope.launch {
                        messageRepo.deleteConversation(from)
                        contactRepo.deleteContact(from)
                    }
                    // Broadcast so any open ChatActivity with this contact closes itself.
                    withContext(Dispatchers.Main) {
                        sendBroadcast(android.content.Intent("com.silvershireinn.CONTACT_REMOVED").apply {
                            putExtra("username", from)
                            `package` = packageName
                        })
                    }
                }
            }

            // ── Panic wipe response ───────────────────────────────────────────
            "wipe_complete" -> {
                // Server confirmed the wipe. End any active call first so the other
                // party gets a clean hang-up signal, then clear ALL local account data.
                withContext(Dispatchers.Main) {
                    if (CallManager.state != CallManager.State.IDLE)      CallManager.endCall()
                    if (VideoCallManager.state != VideoCallManager.State.IDLE) VideoCallManager.endCall()
                }
                clearLocalAccountData()
                withContext(Dispatchers.Main) {
                    val intent = android.content.Intent(
                        this@WebSocketService,
                        com.silvershireinn.app.ui.identity.CreateIdentityActivity::class.java
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                 android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                }
            }

            // ── Blocked login (account was wiped) ─────────────────────────────
            "account_wiped" -> {
                // Server rejected login because this account was wiped.
                // Clear stale local state so the user can create a new account.
                withContext(Dispatchers.Main) {
                    if (CallManager.state != CallManager.State.IDLE)      CallManager.endCall()
                    if (VideoCallManager.state != VideoCallManager.State.IDLE) VideoCallManager.endCall()
                }
                clearLocalAccountData()
                withContext(Dispatchers.Main) {
                    val intent = android.content.Intent(
                        this@WebSocketService,
                        com.silvershireinn.app.ui.identity.CreateIdentityActivity::class.java
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                 android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("error", "This account has been permanently wiped.")
                    }
                    startActivity(intent)
                }
            }

            // ── Admin account deletion ────────────────────────────────────────
            "account_deleted" -> {
                // Admin manually deleted this account. Show a popup via broadcast,
                // which MainActivity listens for and shows as an AlertDialog.
                val message = json.optString(
                    "message",
                    "Your account has been deleted by the server administrator."
                )
                // Let MainActivity show the dialog; it will call clearAccountData() and redirect
                withContext(Dispatchers.Main) {
                    sendBroadcast(android.content.Intent("com.silvershireinn.ACCOUNT_DELETED").apply {
                        putExtra("message", message)
                        `package` = packageName
                    })
                }
            }

            // ── Server history sync (new device login) ────────────────────────
            "history_response" -> {
                val withUser = json.optString("with").ifBlank { json.optString("withUser") }
                val msgs = json.optJSONArray("messages") ?: return
                if (withUser.isBlank()) return
                val me = PreferenceManager.username
                // Newest of THEIR messages any of my devices confirmed receiving. Anything newer
                // reached the server while all my devices were offline: it is new to me (unread,
                // notification, delivery tick). Older servers don't send it: old behavior.
                val clearedBefore = PreferenceManager.clearedAt(withUser)
                val selfDelivered = if (json.has("selfLastDeliveredTs") && !json.isNull("selfLastDeliveredTs"))
                    com.silvershireinn.app.utils.isoToMillis(json.optString("selfLastDeliveredTs")) else null
                scope.launch {
                    var latestTs: String? = null
                    var latestPreview: String? = null
                    var insertedAny = false
                    val missed = mutableListOf<Pair<String, String>>()   // messageId to preview
                    for (i in 0 until msgs.length()) {
                        val m = msgs.optJSONObject(i) ?: continue
                        val messageId = m.optString("messageId").takeIf { it.isNotBlank() } ?: continue
                        val from      = m.optString("from")
                        val payload   = m.optString("payload")
                        val timestamp = m.optString("timestamp")
                        if (from.isBlank() || payload.isBlank()) continue
                        val isSent = from == me
                        val to     = if (isSent) withUser else me
                        // Cleared / deleted on this phone: don't bring it back.
                        if ((com.silvershireinn.app.utils.isoToMillis(timestamp) ?: Long.MAX_VALUE) <= clearedBefore) continue

                        // Already stored: skip BEFORE decrypting. Every reconnect replays up to 200
                        // messages per friend and each RSA decrypt is slow on a cheap phone. The one
                        // exception is an old bad row (attachment stored as raw JSON text).
                        val existing = messageRepo.getMessageById(messageId)
                        if (existing != null && (existing.attachmentId != null ||
                                !existing.encryptedPayload.trimStart().startsWith("{"))) continue

                        val decrypted = if (CryptoManager.hasRsaKeyPair()) {
                            CryptoManager.decryptIfE2E(payload, preferSelf = isSent) ?: "\uD83D\uDD12 Encrypted message"
                        } else payload

                        // Check if this is an attachment payload (e.g. {"_att":{...}})
                        val attJson = try {
                            org.json.JSONObject(decrypted).optJSONObject("_att")
                        } catch (_: Exception) { null }
                        if (existing != null && attJson == null) continue

                        val preview = if (attJson != null) {
                            com.silvershireinn.app.utils.MediaKeys.learn(attJson)
                            val fileId   = attJson.optString("fileId")
                            val fileName = attJson.optString("name")
                            val mimeType = attJson.optString("mime")
                            val fileSize = attJson.optLong("size")
                            messageRepo.insertHistoricalAttachment(
                                from, to, fileId, fileName, mimeType, fileSize,
                                timestamp, messageId, isSent, updateConversation = false
                            )
                        } else {
                            messageRepo.insertHistorical(
                                from, to, decrypted, timestamp, messageId, isSent,
                                updateConversation = false
                            )
                        }
                        insertedAny = true
                        if (existing == null && !isSent && selfDelivered != null) {
                            val t = com.silvershireinn.app.utils.isoToMillis(timestamp)
                            if (t != null && t > selfDelivered) {
                                SilvershireApp.database.messageDao().markIncomingUnread(messageId)
                                missed += messageId to (if (attJson != null)
                                    com.silvershireinn.app.utils.attachmentPreview(attJson.optString("mime"), attJson.optString("name"))
                                else if (decrypted.length > 60) decrypted.take(60) + "..." else decrypted)
                            }
                        }
                        if (latestTs == null || timestamp >= latestTs!!) {
                            latestTs      = timestamp
                            latestPreview = preview
                        }
                    }
                    if (insertedAny && latestTs != null && latestPreview != null) {
                        messageRepo.applyHistoryConversationPreview(withUser, latestPreview!!, latestTs!!)
                    }
                    if (missed.isNotEmpty()) {
                        for ((mid, _) in missed) WebSocketManager.sendDeliveryReceipt(me, withUser, mid)
                        val viewing = com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact == withUser ||
                            (ChatHeadService.overlayContactId == withUser && ChatHeadService.overlayAtBottom)
                        if (!viewing) {
                            // Shade keeps the last few; one sound for the batch (burst throttle).
                            for ((_, pv) in missed.takeLast(4)) {
                                NotificationHelper.showMessageNotification(this@WebSocketService, withUser, pv)
                            }
                            if (PreferenceManager.chatHeads && canDrawOverlay()) {
                                ChatHeadService.show(this@WebSocketService, withUser, missed.last().second)
                            }
                        }
                    }

                // Restore delivery/read ticks from server peerReceiptSnapshot (web parity).
                    fun peerField(vararg keys: String): String {
                        for (k in keys) {
                            val v = json.optString(k)
                            if (v.isNotBlank() && v != "null") return v
                        }
                        return ""
                    }
                    suspend fun resolveCutoffTs(id: String, ts: String): String? {
                        if (ts.isNotBlank()) return ts
                        if (id.isNotBlank()) {
                            return messageRepo.getMessageById(id)?.timestamp?.takeIf { it.isNotBlank() }
                        }
                        return null
                    }
                    val peerDelivId = peerField("peerLastDeliveredId", "peer_last_delivered_id")
                    val peerDelivTs = peerField("peerLastDeliveredTs", "peer_last_delivered_ts")
                    val peerReadId  = peerField("peerLastReadId", "peer_last_read_id")
                    val peerReadTs  = peerField("peerLastReadTs", "peer_last_read_ts")
                    resolveCutoffTs(peerDelivId, peerDelivTs)?.let { cutoff ->
                        messageRepo.markDeliveredUpTo(withUser, cutoff)
                    }
                    resolveCutoffTs(peerReadId, peerReadTs)?.let { cutoff ->
                        messageRepo.markOutgoingReadUpTo(withUser, cutoff)
                    }
                }
            }
            // ── Multi-device echo ──────────────────────────────────────────────
            // A message WE sent from ANOTHER device (web client) — the server echoes
            // it to our other devices so every device shows the full conversation.
            // Same shape as private_message but from == us; insert as an outgoing
            // message. Payload includes "keySelf" so we can decrypt our own message.
            "message_echo" -> {
                val from      = json.optString("from")
                val to        = json.optString("to")
                val payload   = json.optString("payload")
                val timestamp = json.optString("timestamp")
                    .ifBlank { com.silvershireinn.app.utils.nowIso() }
                val messageId = json.optString("messageId")
                val me        = PreferenceManager.username
                // Group echo: a group message WE sent from another device (web). These used to
                // be ignored, so web-sent group messages never showed on the phone.
                val echoGroupId = json.optString("groupId")
                if (echoGroupId.isNotBlank()) {
                    if (from != me || payload.isBlank() || messageId.isBlank()) return
                    scope.launch {
                        if (messageRepo.getMessageById(messageId) != null) return@launch
                        val g = groupRepo.getGroup(echoGroupId) ?: return@launch
                        messageRepo.insertGroupHistorical(from, echoGroupId, g.name, groupPlain(payload),
                            timestamp, messageId, isSent = true)
                    }
                    return
                }
                // 1:1 echo
                if (from != me || to.isBlank() || payload.isBlank() || messageId.isBlank()) return
                scope.launch {
                    // Dedupe — this device may already have it (e.g. via history sync)
                    if (messageRepo.getMessageById(messageId) != null) return@launch

                    val decrypted = if (CryptoManager.hasRsaKeyPair()) {
                        CryptoManager.decryptIfE2E(payload, preferSelf = true) ?: "🔒 Encrypted message"
                    } else payload

                    val attJson = try {
                        org.json.JSONObject(decrypted).optJSONObject("_att")
                    } catch (_: Exception) { null }

                    if (attJson != null) {
                        com.silvershireinn.app.utils.MediaKeys.learn(attJson)
                        messageRepo.insertHistoricalAttachment(
                            from, to,
                            attJson.optString("fileId"),
                            attJson.optString("name"),
                            attJson.optString("mime"),
                            attJson.optLong("size"),
                            timestamp, messageId, isSent = true
                        )
                    } else {
                        messageRepo.insertHistorical(from, to, decrypted, timestamp, messageId, isSent = true)
                    }
                }
            }

            // Server confirms our message was relayed → first checkmark (✓ sent)
            "message_sent" -> {
                val messageId = json.optString("messageId")
                if (messageId.isNotBlank()) {
                    messageRepo.markServerAck(messageId)
                    pendingAttempts.remove(messageId)
                    // Adopt the server's clock so every device orders the chat identically.
                    val serverTs = json.optString("timestamp")
                    if (serverTs.isNotBlank()) messageRepo.applyServerTimestamp(messageId, serverTs)
                }
            }
            // Recipient device confirmed it got the message -> second grey check
            "message_delivered" -> {
                val messageId = json.optString("messageId")
                if (messageId.isNotBlank()) messageRepo.markDelivered(messageId)
            }
            // Recipient told us they read it → second checkmark (✓✓ read)
            "conversation_read" -> {
                // Another of MY devices read this chat — clear notif + chat-head badge.
                val withUser = json.optString("with").ifBlank { json.optString("to") }
                if (withUser.isNotBlank()) {
                    scope.launch {
                        messageRepo.markAllRead(withUser)
                        withContext(Dispatchers.Main) {
                            NotificationHelper.cancelMessageNotifications(this@WebSocketService, withUser)
                            // Clear the badge only. Removing the whole head here made it vanish
                            // whenever this chat was read on another device.
                            ChatHeadService.clearUnread(this@WebSocketService, withUser)
                        }
                    }
                }
            }
            "read_receipt" -> {
                val messageId = json.optString("messageId")
                if (messageId.isNotBlank()) messageRepo.markRead(messageId)
            }
            "message_deleted" -> {
                val messageId = json.optString("messageId")
                if (messageId.isNotBlank()) messageRepo.markDeleted(messageId)
            }

            // ── Incoming profile picture update ───────────────────────────────
            "profile_frame_ok" -> {
                com.silvershireinn.app.utils.ProfileFrames.myFrame = json.optString("frame", "none")
                framesChanged(PreferenceManager.username)
            }
            "saved_frames" -> {
                json.optJSONArray("frames")?.let { a -> com.silvershireinn.app.utils.ProfileFrames.savedFrames = (0 until a.length()).map { a.optString(it) } }
                sendBroadcast(android.content.Intent("com.silvershireinn.FRAMES_CHANGED").apply { `package` = packageName })
            }
            "profile_updated" -> {
                val updatedUser = json.optString("name")
                if (updatedUser.isNotBlank() && json.has("frame")) {
                    // A border change only: no picture to download
                    if (updatedUser == PreferenceManager.username) com.silvershireinn.app.utils.ProfileFrames.myFrame = json.optString("frame", "none")
                    else com.silvershireinn.app.utils.ProfileFrames.setContactFrame(updatedUser, json.optString("frame", "none"))
                    framesChanged(updatedUser)
                    if (!json.has("hasPic") && !json.has("displayName") && !json.has("avatarShape")) return
                }
                if (updatedUser.isNotBlank()) {
                    val dn = json.optString("displayName", "")
                    if (dn.isNotBlank()) {
                        PreferenceManager.setContactDisplayName(updatedUser, dn)
                        PreferenceManager.notifyNamesChanged(updatedUser)
                    }
                    if (json.has("avatarShape") && !json.isNull("avatarShape")) {
                        val ash = json.optString("avatarShape", "").trim()
                        if (ash.isNotBlank()) {
                            PreferenceManager.setContactAvatarShape(updatedUser, ash)
                            if (updatedUser == PreferenceManager.username) {
                                PreferenceManager.chatHeadShape =
                                    com.silvershireinn.app.utils.AvatarShape.fromServer(ash).prefKey()
                            }
                            ChatHeadService.refreshContactAvatar(this@WebSocketService, updatedUser)
                            val shapeIntent = android.content.Intent("com.silvershireinn.PROFILE_UPDATED").apply {
                                putExtra("username", updatedUser)
                                putExtra("avatarShape", ash)
                                `package` = packageName
                            }
                            sendBroadcast(shapeIntent)
                        }
                    }
                    // Download the new profile pic in the background (may be shape-only update)
                    scope.launch {
                        val localPath = FileRepository.downloadContactProfilePic(
                            this@WebSocketService, updatedUser
                        )
                        if (localPath != null) {
                            ChatHeadService.refreshContactAvatar(this@WebSocketService, updatedUser)
                            val broadcastIntent = android.content.Intent("com.silvershireinn.PROFILE_UPDATED").apply {
                                putExtra("username", updatedUser)
                                putExtra("picPath", localPath)
                                `package` = packageName
                            }
                            sendBroadcast(broadcastIntent)
                        }
                    }
                }
            }

            // ── Incoming banner picture update ────────────────────────────────
            "banner_updated" -> {
                val updatedUser = json.optString("name")
                if (updatedUser.isNotBlank()) {
                    scope.launch {
                        val localPath = FileRepository.downloadContactBannerPic(
                            this@WebSocketService, updatedUser
                        )
                        if (localPath != null) {
                            val broadcastIntent = android.content.Intent("com.silvershireinn.BANNER_UPDATED").apply {
                                putExtra("username", updatedUser)
                                putExtra("bannerPath", localPath)
                                `package` = packageName
                            }
                            sendBroadcast(broadcastIntent)
                        }
                    }
                }
            }

            // ── Call signaling ────────────────────────────────────────────────
            "call_offer", "call_accept", "call_reject", "call_end" -> {
                val msgType = json.optString("type")
                val from    = json.optString("from")
                if (from.isNotBlank()) {
                    CallManager.handleSignal(json)
                    // Ring only if this offer is now OUR incoming call. A second caller (busy:
                    // auto-declined) used to get a ringing screen too, and their hang-up
                    // cancelled the notification of the call that was really ringing.
                    val ringingThis = CallManager.state == CallManager.State.INCOMING && CallManager.remoteUser == from
                    when (msgType) {
                        "call_offer" -> if (ringingThis && !com.silvershireinn.app.utils.PanicLock.locked) {
                            // Show the full-screen notification (works on lock-screen / background)
                            NotificationHelper.showIncomingCallNotification(this@WebSocketService, from)
                            // Also attempt direct activity launch — succeeds when the app is
                            // in the foreground or SYSTEM_ALERT_WINDOW (overlay) is granted.
                            try {
                                startActivity(CallActivity.incomingIntent(this@WebSocketService, from))
                            } catch (_: Exception) { /* notification handles it as fallback */ }
                        }
                        "call_end", "call_reject" -> {
                            // Cancel the incoming call notification if that call is gone
                            if (CallManager.state == CallManager.State.IDLE)
                                NotificationHelper.cancelCallNotification(this@WebSocketService)
                        }
                    }
                }
            }

            // ── Real-time call connection (WebRTC offer / answer / candidates) ──
            "rtc_signal" -> {
                if (VideoCallManager.isBusy()) VideoCallManager.handleRtcSignal(json)
                else CallManager.handleRtcSignal(json)
            }
            "call_config" -> {
                CallManager.handleSignal(json); VideoCallManager.handleSignal(json)
            }
            "call_answered_elsewhere" -> {
                // Answered / declined on another of my devices: stop ringing here.
                CallManager.handleSignal(json); VideoCallManager.handleSignal(json)
                NotificationHelper.cancelCallNotification(this@WebSocketService)
            }

            // ── Audio frame relay ─────────────────────────────────────────────
            "audio_frame" -> {
                val data = json.optString("data")
                if (data.isNotBlank()) CallManager.handleAudioFrame(data)
            }

            // ── Video call signaling ───────────────────────────────────────────
            "video_call_offer", "video_call_accept", "video_call_reject", "video_call_end" -> {
                val msgType = json.optString("type")
                val from    = json.optString("from")
                if (from.isNotBlank()) {
                    VideoCallManager.handleSignal(json)
                    val ringingThis = VideoCallManager.state == VideoCallManager.State.INCOMING && VideoCallManager.remoteUser == from
                    when (msgType) {
                        "video_call_offer" -> if (ringingThis && !com.silvershireinn.app.utils.PanicLock.locked) {
                            // Always fire the full-screen notification (handles lock-screen & bg)
                            NotificationHelper.showIncomingVideoCallNotification(this@WebSocketService, from)
                            // Additionally try a direct Activity launch — this works when the app is
                            // in the foreground OR when SYSTEM_ALERT_WINDOW is granted (chat heads).
                            // canDrawOverlay() being true means we can start activities from the bg.
                            try {
                                startActivity(VideoCallActivity.incomingIntent(this@WebSocketService, from))
                            } catch (_: Exception) { /* not allowed from background — notification handles it */ }
                        }
                        "video_call_end", "video_call_reject" -> {
                            if (VideoCallManager.state == VideoCallManager.State.IDLE)
                                NotificationHelper.cancelCallNotification(this@WebSocketService)
                        }
                    }
                }
            }

            // ── Video frame relay ─────────────────────────────────────────────
            "video_frame" -> {
                val data = json.optString("data")
                if (data.isNotBlank()) VideoCallManager.handleVideoFrame(data)
            }

            // ── Video audio frame relay ───────────────────────────────────────
            "video_audio_frame" -> {
                val data = json.optString("data")
                if (data.isNotBlank()) VideoCallManager.handleAudioFrame(data)
            }

            // ── Group chat ────────────────────────────────────────────────────

            // Server tells us (and all members) a group was created / we were added
            "group_created" -> {
                val groupId   = json.optString("groupId")
                val groupName = json.optString("name")
                val createdBy = json.optString("createdBy")
                // (was local time labelled "Z", which misordered new groups in the chat list)
                val createdAt = json.optString("timestamp").ifBlank { com.silvershireinn.app.utils.nowIso() }
                val membersArr = json.optJSONArray("members") ?: org.json.JSONArray()
                if (groupId.isBlank()) return
                com.silvershireinn.app.utils.GroupKeys.update(groupId, json.optJSONObject("memberKeys"))
                val members = (0 until membersArr.length()).map { membersArr.optString(it) }.filter { it.isNotBlank() }
                val group = com.silvershireinn.app.model.Group(
                    groupId    = groupId,
                    name       = groupName,
                    membersJson = org.json.JSONArray(members).toString(),
                    createdBy  = createdBy,
                    createdAt  = createdAt
                )
                groupRepo.upsertGroup(group)
                // Ensure a conversation entry exists so it appears in the chats list
                SilvershireApp.database.conversationDao().insertConversation(
                    com.silvershireinn.app.model.Conversation(
                        contactUsername      = groupId,
                        lastMessagePreview   = "Group created",
                        lastMessageTimestamp = createdAt,
                        unreadCount          = 0,
                        groupName            = groupName
                    )
                )
                // Request history in case we're catching up on an existing group
                scope.launch { WebSocketManager.requestGroupHistory(groupId) }
            }

            // Server's list of groups we belong to (sent at login). Restores groups this
            // device lost and forgets groups we are no longer a member of.
            "groups_list" -> {
                val arr = json.optJSONArray("groups") ?: return
                val serverIds = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val gid = g.optString("groupId")
                    if (gid.isBlank()) continue
                    serverIds += gid
                    com.silvershireinn.app.utils.GroupKeys.update(gid, g.optJSONObject("memberKeys"))
                    val name = g.optString("name").ifBlank { "Group" }
                    val membersJson = (g.optJSONArray("members") ?: org.json.JSONArray()).toString()
                    val createdAt = com.silvershireinn.app.utils.canonicalTs(g.optString("createdAt"))
                    val existing = groupRepo.getGroup(gid)
                    groupRepo.upsertGroup(
                        existing?.copy(name = name, membersJson = membersJson)
                            ?: com.silvershireinn.app.model.Group(
                                groupId = gid, name = name, membersJson = membersJson,
                                createdBy = g.optString("createdBy"), createdAt = createdAt)
                    )
                    val convDao = SilvershireApp.database.conversationDao()
                    if (convDao.getConversation(gid) == null) {
                        convDao.insertConversation(
                            com.silvershireinn.app.model.Conversation(gid, "", createdAt, 0, name))
                        WebSocketManager.requestGroupHistory(gid)
                    }
                }
                // Left on another device / removed: drop the group row (keep any local messages).
                // An empty list while we have groups is treated as a server hiccup, not "left all".
                val localGroups = groupRepo.getGroups().first()
                if (serverIds.isNotEmpty()) {
                    for (g in localGroups) {
                        if (g.groupId !in serverIds) groupRepo.deleteGroup(g.groupId)
                    }
                }
            }

            // Server notifies remaining members when someone leaves — update local group record
            // ── A friend used their panic phrase: close their chats here, open them again later ──
            "peer_locked" -> {
                val name = json.optString("name")
                if (name.isNotBlank()) {
                    com.silvershireinn.app.utils.PeerLocks.lock(name)
                    try { NotificationHelper.cancelMessageNotifications(this@WebSocketService, name) } catch (_: Exception) {}
                    try { com.silvershireinn.app.ui.chathead.ChatHeadService.dismiss(this@WebSocketService, name) } catch (_: Exception) {}
                }
            }
            "peer_unlocked" -> {
                val name = json.optString("name")
                if (name.isNotBlank()) {
                    com.silvershireinn.app.utils.PeerLocks.unlock(name)
                    // Fetch everything the server held back while they were locked.
                    WebSocketManager.sendRaw(org.json.JSONObject().put("type", "history_request").put("to", name).toString())
                    scope.launch {
                        for (g in groupRepo.getGroups().first()) if (g.membersJson.contains("\"" + name + "\"")) WebSocketManager.requestGroupHistory(g.groupId)
                    }
                }
            }
            // ── Panic phrase (account lock) ──
            "panic_config" -> com.silvershireinn.app.utils.PanicLock.applyServerConfig(json.optJSONObject("panic"))
            "account_locked" -> withContext(Dispatchers.Main) {
                com.silvershireinn.app.utils.PanicLock.lockNow(this@WebSocketService, fromServer = true)
            }
            "account_unlocked" -> withContext(Dispatchers.Main) {
                com.silvershireinn.app.utils.PanicLock.onAccountUnlocked(this@WebSocketService)
            }
            "panic_unlock_result" -> if (!json.optBoolean("ok")) com.silvershireinn.app.utils.PanicLock.unlockListener?.invoke(false)
            "panic_set_result" -> com.silvershireinn.app.utils.PanicLock.setListener?.invoke(json.optBoolean("ok"), json.optString("error").ifBlank { null })
            "group_keys" -> {
                com.silvershireinn.app.utils.GroupKeys.update(json.optString("groupId").ifBlank { null }, json.optJSONObject("keys"))
            }
            "group_updated" -> {
                val groupId = json.optString("groupId")
                val arr     = json.optJSONArray("members") ?: return
                if (groupId.isBlank()) return
                com.silvershireinn.app.utils.GroupKeys.update(groupId, json.optJSONObject("memberKeys"))
                val membersJson = arr.toString()
                scope.launch {
                    val existing = groupRepo.getGroup(groupId) ?: return@launch
                    groupRepo.upsertGroup(existing.copy(membersJson = membersJson))
                    // Broadcast so any open ChatActivity can refresh its subtitle
                    sendBroadcast(android.content.Intent("com.silvershireinn.GROUP_UPDATED")
                        .putExtra("groupId", groupId).setPackage(packageName))
                }
            }

            // Incoming message from another group member
            "group_message" -> {
                val groupId   = json.optString("groupId")
                val from      = json.optString("from")
                val payload   = groupPlain(json.optString("payload"))
                val timestamp = json.optString("timestamp")
                val messageId = json.optString("messageId")
                if (groupId.isBlank() || messageId.isBlank()) return
                val group = groupRepo.getGroup(groupId) ?: return
                // Skip our own echo — server only relays to OTHER members, but be safe
                if (from.isNotBlank() && from == PreferenceManager.username) return
                if (messageRepo.getMessageById(messageId) != null) return
                messageRepo.insertGroupIncoming(from, groupId, group.name, payload, timestamp, messageId)
                // Pre-cache received group videos so the first open is instant.
                try {
                    org.json.JSONObject(payload).optJSONObject("_att")?.let { att ->
                        precacheVideo(att.optString("fileId"), att.optString("mime"), att.optLong("size"))
                    }
                } catch (_: Exception) {}
                // Body for MessagingStyle Person+text; chat-head keeps "from: body" for skimability
                val body = try {
                    val j = org.json.JSONObject(payload)
                    when {
                        j.has("_sys") -> j.optString("_sys", "")
                        j.has("_att") -> j.optJSONObject("_att").let { a ->
                            com.silvershireinn.app.utils.attachmentPreview(a?.optString("mime"), a?.optString("name")) }
                        else          -> if (payload.length > 40) payload.take(40) + "…" else payload
                    }
                } catch (_: Exception) {
                    if (payload.length > 40) payload.take(40) + "…" else payload
                }
                val preview = if (from.isNotBlank()) "${PreferenceManager.displayNameFor(from)}: $body" else body
                // Don't show notification or chat head for silent system messages
                if (from.isNotBlank()) {
                    val overlayOpen = com.silvershireinn.app.ui.chathead.ChatHeadService
                        .overlayContactId == groupId
                    val atBottom = com.silvershireinn.app.ui.chathead.ChatHeadService.overlayAtBottom
                    val viewingChat = com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact == groupId
                    val suppressNotif = viewingChat || (overlayOpen && atBottom)
                    if (!suppressNotif) {
                        NotificationHelper.showGroupMessageNotification(
                            this@WebSocketService, groupId, group.name, from, body
                        )
                    }
                    if (PreferenceManager.chatHeads && canDrawOverlay() &&
                            com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact != groupId) {
                        com.silvershireinn.app.ui.chathead.ChatHeadService.showGroup(this@WebSocketService, groupId, group.name, preview)
                    } else if (overlayOpen && !atBottom) {
                        com.silvershireinn.app.ui.chathead.ChatHeadService.showGroup(this@WebSocketService, groupId, group.name, preview)
                    }
                }
            }

            // Full history replay for a group
            "group_history_response" -> {
                val groupId = json.optString("groupId")
                val msgs    = json.optJSONArray("messages") ?: return
                if (groupId.isBlank()) return
                val group = groupRepo.getGroup(groupId) ?: return
                val me = PreferenceManager.username
                val groupClearedBefore = PreferenceManager.clearedAt(groupId)
                scope.launch {
                    var latestTs: String? = null
                    var latestPreview: String? = null
                    var insertedAny = false
                    for (i in 0 until msgs.length()) {
                        val m         = msgs.optJSONObject(i) ?: continue
                        val messageId = m.optString("messageId").takeIf { it.isNotBlank() } ?: continue
                        val from      = m.optString("from")
                        val payload   = m.optString("payload")
                        val timestamp = m.optString("timestamp")
                        if (from.isBlank() || payload.isBlank()) continue
                        if ((com.silvershireinn.app.utils.isoToMillis(timestamp) ?: Long.MAX_VALUE) <= groupClearedBefore) continue
                        if (messageRepo.getMessageById(messageId) != null) continue
                        val isSent = from == me
                        val preview = messageRepo.insertGroupHistorical(
                            from, groupId, group.name, groupPlain(payload), timestamp, messageId, isSent,
                            updateConversation = false
                        )
                        insertedAny = true
                        if (latestTs == null || timestamp >= latestTs!!) {
                            latestTs = timestamp
                            latestPreview = preview
                        }
                    }
                    if (insertedAny && latestTs != null && latestPreview != null) {
                        messageRepo.applyHistoryConversationPreview(
                            groupId, latestPreview!!, latestTs!!, group.name
                        )
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Wipes all local account data: Room DB, cached profile/banner files, and account prefs.
     * Called on wipe_complete and account_wiped so no stale data survives to a new account.
     */
    private suspend fun clearLocalAccountData() {
        // MUST disconnect first — cancels any pending scheduleReconnect() loop.
        // If the server already closed the connection (e.g. after sending account_wiped),
        // scheduleReconnect() fires almost immediately. Without this disconnect() call first,
        // the stale reconnect with the old username can win the race, end up in the Connected
        // state, and then WebSocketService.onStartCommand() from CreateIdentityActivity returns
        // START_STICKY early — meaning the new register message is never sent and
        // CreateIdentityActivity times out with "Server did not respond."
        WebSocketManager.disconnect()
        // Stop the watchdog — there's no account to keep a connection alive for anymore.
        // A fresh login restarts the service, which re-arms it.
        WatchdogReceiver.cancel(this@WebSocketService)

        // Own pictures, then everything the shared wipe covers (DB incl. groups, contact
        // pictures AND banners, cached chat media, per-contact settings). The old copy here
        // left banners and every cached photo / video of the wiped account on the phone.
        listOf(PreferenceManager.profilePicturePath, PreferenceManager.bannerPicPath).forEach { p ->
            if (p.isNotBlank()) try { java.io.File(p).delete() } catch (_: Exception) {}
        }
        com.silvershireinn.app.utils.AccountData.wipeLocal(this@WebSocketService)

        withContext(Dispatchers.Main) {
            PreferenceManager.profilePicturePath = ""
            PreferenceManager.bannerPicPath = ""
            // clearAccountData() preserves server_url and server_password so the
            // user can create a new account on the same server without re-entering them.
            PreferenceManager.clearAccountData()
            PreferenceManager.isSetupComplete = true
        }
    }

    // ── Unsent-message recovery ───────────────────────────────────────────────
    // A message stays "unacked" when its send raced a reconnect, was sent in the gap
    // before login finished (the server drops those), or its screen closed mid-send.
    // Previously these were only retried at the NEXT login (could be hours) and group
    // messages / files were never retried at all.
    private val pendingAttempts  = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pendingFirstSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val flushMutex = kotlinx.coroutines.sync.Mutex()

    /** Group payload as plain text (decrypts group E2E; old plain messages pass through). */
    /** A border changed: chat heads redraw, open screens refresh their pictures. */
    private suspend fun framesChanged(user: String) {
        withContext(Dispatchers.Main) {
            try { if (user == PreferenceManager.username) ChatHeadService.refreshAvatar(this@WebSocketService, PreferenceManager.profilePicturePath)
                  else ChatHeadService.refreshContactAvatar(this@WebSocketService, user) } catch (_: Exception) {}
        }
        sendBroadcast(android.content.Intent("com.silvershireinn.PROFILE_UPDATED").apply { putExtra("username", user); putExtra("frame", true); `package` = packageName })
        sendBroadcast(android.content.Intent("com.silvershireinn.FRAMES_CHANGED").apply { `package` = packageName })
    }

    private fun groupPlain(payload: String): String {
        if (!CryptoManager.isGroupE2E(payload)) return payload
        return CryptoManager.decryptIfE2E(payload)
            ?: "🔒 Encrypted message you can't read here (sent before you joined, or to an older key)"
    }

    private fun startPendingFlushLoop() {
        scope.launch {
            while (isActive) {
                delay(10_000L)
                try { flushPendingOutgoing(minAgeMs = 8_000L) } catch (_: Exception) {}
            }
        }
    }

    /** Resend messages the server has not acked. [minAgeMs]=0 (after login) resends all. */
    private suspend fun flushPendingOutgoing(minAgeMs: Long) {
        if (!WebSocketManager.isAuthenticated) return
        if (!flushMutex.tryLock()) return
        try {
            val me = PreferenceManager.username
            if (me.isBlank()) return
            if (minAgeMs == 0L) pendingAttempts.clear()
            val now = System.currentTimeMillis()
            val pending = SilvershireApp.database.messageDao().getPendingOutgoing()
            val live = pending.map { it.messageId }.toSet()
            pendingFirstSeen.keys.retainAll(live)
            pendingAttempts.keys.retainAll(live)
            for (msg in pending) {
                val first = pendingFirstSeen.getOrPut(msg.messageId) { now }
                if (now - first < minAgeMs) continue
                val tries = pendingAttempts[msg.messageId] ?: 0
                if (tries >= 6) continue
                // Call-log rows are local only; never send them.
                if (msg.attachmentMime == "silvershire/call" || msg.messageId.startsWith("call_")) continue
                // Only recent messages: an ancient unacked row may have aged out of the
                // server's history (no dedupe) and would arrive as if brand new.
                val sentAt = com.silvershireinn.app.utils.isoToMillis(msg.timestamp)
                if (sentAt == null || now - sentAt > 3L * 24 * 60 * 60 * 1000) continue
                // Some group rows (uploads) were stored without groupId; detect by conversation.
                val gid = msg.groupId ?: groupRepo.getGroup(msg.conversationId)?.groupId
                if (gid != null) {
                    // Group rows are stored as plain text on the phone: encrypt again to resend.
                    val plain = if (msg.attachmentName != null) {
                        val fid = msg.attachmentId ?: continue      // still uploading
                        org.json.JSONObject().put("_att", com.silvershireinn.app.utils.MediaKeys.decorateNew()
                            .put("fileId", fid).put("name", msg.attachmentName)
                            .put("mime", msg.attachmentMime ?: "application/octet-stream")
                            .put("size", msg.attachmentSize)).toString()
                    } else msg.encryptedPayload
                    pendingAttempts[msg.messageId] = tries + 1
                    val gPayload = com.silvershireinn.app.ui.chat.ChatSender.encryptForGroup(gid, plain)
                    WebSocketManager.sendGroupMessage(gid, gPayload, msg.messageId, msg.timestamp)
                    continue
                }
                val text = if (msg.attachmentName != null) {
                    val fid = msg.attachmentId ?: continue
                    org.json.JSONObject().put("_att", com.silvershireinn.app.utils.MediaKeys.decorateNew()
                        .put("fileId", fid)
                        .put("name", msg.attachmentName)
                        .put("mime", msg.attachmentMime ?: "application/octet-stream")
                        .put("size", msg.attachmentSize)).toString()
                } else msg.encryptedPayload
                val payload = try {
                    val pubKey = SilvershireApp.database.contactDao().getContact(msg.toUser)
                        ?.publicKey?.takeIf { it.isNotBlank() }
                    if (pubKey != null && CryptoManager.hasRsaKeyPair()) CryptoManager.encryptE2E(text, pubKey)
                    else text
                } catch (_: Exception) { text }
                pendingAttempts[msg.messageId] = tries + 1
                WebSocketManager.sendPrivateMessage(me, msg.toUser, payload, msg.messageId, msg.timestamp)
            }
        } finally {
            flushMutex.unlock()
        }
    }

    /**
     * Pre-download a received video to the SAME cache file the viewers read from
     * (cacheDir/viewer_<fileId>.<ext>), so the first time the user opens it, it plays
     * instantly instead of streaming. Runs in the background; skipped if already cached.
     */
    private fun precacheVideo(fileId: String, mime: String, size: Long = 0L) {
        if (fileId.isBlank() || !mime.startsWith("video/")) return
        // Only on Wi-Fi / unmetered and for reasonably sized clips: downloading every received
        // video in full on mobile data cost friends their data plan and storage.
        if (size > 80L * 1024 * 1024) return
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            if (cm == null || cm.isActiveNetworkMetered) return
        } catch (_: Exception) { return }
        val ext = when {
            mime.contains("mp4")  -> ".mp4"
            mime.contains("webm") -> ".webm"
            else                  -> ".mp4"
        }
        val cacheFile = java.io.File(cacheDir, "viewer_$fileId$ext")
        if (cacheFile.exists() && cacheFile.length() > 0L) return
        scope.launch {
            try {
                val tmp = java.io.File(cacheDir, "viewer_$fileId$ext.precache.tmp")
                tmp.takeIf { it.exists() }?.delete()
                val ok = FileRepository.downloadFileToPath(fileId, tmp)
                if (ok && tmp.exists() && tmp.length() > 0L) tmp.renameTo(cacheFile)
                else tmp.takeIf { it.exists() }?.delete()
            } catch (_: Exception) {}
        }
    }

    /** "since" for history requests: newest stored message of [conversationId] minus 1 h, or null. */
    private suspend fun historySince(conversationId: String): String? {
        val newest = try { SilvershireApp.database.messageDao().recentTimestamps(conversationId).firstOrNull() } catch (_: Exception) { null }
        val ms = com.silvershireinn.app.utils.isoToMillis(newest) ?: return null
        return com.silvershireinn.app.utils.millisToIso(ms - 60 * 60 * 1000L)
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app away. stopWithTask="false" keeps us running, but as a
        // belt-and-suspenders schedule a near-immediate watchdog tick so we revive even if
        // the system tears the service down right after.
        try { WatchdogReceiver.scheduleSoon(this) } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        PreferenceManager.unregisterListener(prefsListener)
        try {
            networkCallback?.let { cb ->
                getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        networkCallback = null
        scope.cancel()
        super.onDestroy()
    }
}
