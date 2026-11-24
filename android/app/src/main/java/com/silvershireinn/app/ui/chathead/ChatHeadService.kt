package com.silvershireinn.app.ui.chathead

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import coil.load
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.R
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.network.MediaAuth
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.repository.SharedFileInfo
import com.silvershireinn.app.ui.chat.ChatActivity
import com.silvershireinn.app.utils.MutedLoopVideoHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.nowIso
import org.json.JSONObject
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Single shared chat-head bubble for all active conversations.
 *
 * Design:
 *  • One WindowManager overlay regardless of how many contacts are messaging.
 *  • Up to 3 stacked avatar circles (newest sender front, older behind).
 *  • Total unread badge in the top-right corner.
 *  • Drag-to-dismiss: drag onto the ✕ zone at screen bottom.
 *  • Tap: opens the newest-sender's chat if the app is in the background,
 *         or minimises the app if it is already in the foreground.
 *  • PreferenceManager.chatHeadDismissOnTap: if true the bubble is removed
 *    after the first tap; if false it persists and a second tap minimises.
 */
class ChatHeadService : Service() {

    // ── Companion / static helpers ────────────────────────────────────────────

    companion object {
        const val ACTION_SHOW                   = "com.silvershireinn.SHOW_HEAD"
        const val ACTION_DISMISS                = "com.silvershireinn.DISMISS_HEAD"
        const val ACTION_REFRESH_AVATAR         = "com.silvershireinn.REFRESH_AVATAR"
        const val ACTION_REFRESH_CONTACT_AVATAR = "com.silvershireinn.REFRESH_CONTACT_AVATAR"
        const val ACTION_PICKER_RESULT          = "com.silvershireinn.PICKER_RESULT"
        const val EXTRA_USER                    = "from_user"
        const val EXTRA_PREVIEW                 = "preview"
        const val EXTRA_PIC_PATH               = "pic_path"
        const val EXTRA_CONTACT_USER            = "contact_user"
        const val EXTRA_GROUP_ID                = "group_id"
        const val EXTRA_GROUP_NAME              = "group_name"
        const val EXTRA_PICKER_URI              = "picker_uri"
        const val EXTRA_PICKER_TEXT             = "picker_text"
        const val EXTRA_MANUAL                  = "manual"
        const val ACTION_RESTORE_AFTER_VIEWER   = "com.silvershireinn.RESTORE_AFTER_VIEWER"

        /** An app update finished downloading: close the floating chat so Android's installer shows. */
        fun closeForInstaller(context: Context) {
            val s = instance ?: return
            Handler(Looper.getMainLooper()).post { try { if (s.overlayView != null) s.hideOverlay() } catch (_: Throwable) {} }
        }

        /** The full media viewer (opened from the floating chat) closed: bring the chat back. */
        fun restoreAfterViewer(context: Context) {
            if (instance == null) return
            safeStart(context, Intent(context, ChatHeadService::class.java).apply { action = ACTION_RESTORE_AFTER_VIEWER })
        }

        /** Chat settings > Open chat head: a head for this chat, no unread count. */
        fun openManually(context: Context, id: String, groupName: String? = null) {
            safeStart(context, Intent(context, ChatHeadService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_USER, id)
                putExtra(EXTRA_MANUAL, true)
                if (groupName != null) { putExtra(EXTRA_GROUP_ID, id); putExtra(EXTRA_GROUP_NAME, groupName) }
            })
        }

        /** Android 8+ refuses startService while the app is in the background; never crash on it. */
        private fun safeStart(context: Context, intent: Intent) {
            if (com.silvershireinn.app.utils.PanicLock.locked) return   // nothing shows while panic-locked
            try { context.startService(intent) } catch (e: Throwable) {
                android.util.Log.w("ChatHead", "start refused: ${e.message}")
            }
        }

        /** Show (or bump the unread count of) the chat head for [fromUser]. */
        fun show(context: Context, fromUser: String, preview: String = "") {
            safeStart(context, Intent(context, ChatHeadService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_USER, fromUser)
                putExtra(EXTRA_PREVIEW, preview)
            })
        }

        /** Show (or bump) the chat head for a group message. Uses groupId as the key. */
        fun showGroup(context: Context, groupId: String, groupName: String, preview: String = "") {
            safeStart(context, Intent(context, ChatHeadService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_USER, groupId)
                putExtra(EXTRA_PREVIEW, preview)
                putExtra(EXTRA_GROUP_ID, groupId)
                putExtra(EXTRA_GROUP_NAME, groupName)
            })
        }

        /** Remove [fromUser] from the bubble; if no more senders, dismiss bubble. */
        fun dismiss(context: Context, fromUser: String) {
            // Nothing showing: don't start the overlay service just to remove nothing.
            if (instance == null) return
            safeStart(context, Intent(context, ChatHeadService::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_USER, fromUser)
            })
        }

        /** Refresh the own-user avatar on the visible bubble. */
        fun refreshAvatar(context: Context, picturePath: String) {
            if (instance == null) return
            safeStart(context, Intent(context, ChatHeadService::class.java).apply {
                action = ACTION_REFRESH_AVATAR
                putExtra(EXTRA_PIC_PATH, picturePath)
            })
        }

        /** Refresh a specific contact's avatar on the visible bubble. */
        fun refreshContactAvatar(context: Context, contactUsername: String) {
            if (instance == null) return
            safeStart(context, Intent(context, ChatHeadService::class.java).apply {
                action = ACTION_REFRESH_CONTACT_AVATAR
                putExtra(EXTRA_CONTACT_USER, contactUsername)
            })
        }

        /** Returns the locally cached profile-pic path for a contact, or blank if none. */
        fun contactPicPath(context: Context, contactUsername: String): String {
            val found = com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(context, contactUsername)
            if (found != null && found.exists()) return found.absolutePath
            val file = File(context.filesDir, "contact_pics/$contactUsername.jpg")
            return if (file.exists()) file.absolutePath else ""
        }

        /** Shared position key used for the single bubble. */
        private const val BUBBLE_POS_KEY = "__shared_bubble__"

        /**
         * The contactId/groupId whose overlay is currently open, or null.
         * WebSocketService reads this to suppress notifications and badges while
         * the user is actively viewing that conversation in the overlay.
         */
        @Volatile var overlayContactId: String? = null

        /**
         * True when the overlay's RecyclerView is scrolled to (or near) the bottom —
         * i.e. the user can see the latest messages. When false the user is scrolled
         * up reading older messages; new messages should still increment the badge so
         * they know something arrived.
         */
        @Volatile var overlayAtBottom: Boolean = true

        /** The running service, or null when no head is showing. */
        @Volatile private var instance: ChatHeadService? = null

        /**
         * Clear the unread badge for [id] but KEEP its head (the chat is being read in the
         * app or on another device). Never starts the service just for this.
         */
        fun clearUnread(context: Context, id: String) {
            val svc = instance ?: return
            Handler(Looper.getMainLooper()).post { svc.clearUnreadFor(id) }
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var wm: WindowManager

    /**
     * Active senders with their unread counts.
     * LinkedHashMap preserves insertion order — the LAST entry is always the
     * newest sender (displayed as the front avatar).
     */
    private val activeSenders = LinkedHashMap<String, Int>()   // username/groupId → unread count

    /** Group extras keyed by groupId, populated when ACTION_SHOW carries group info. */
    private val groupExtras = HashMap<String, Pair<String, String>>()   // groupId → (groupId, groupName)

    /** Floating overlay chat window. */
    private var overlayView: View? = null

    // Attachment draft for overlay compose (confirm with Send)
    private var overlayDraftUri: Uri? = null
    private var overlayDraftMime: String? = null
    private var overlayDraftName: String? = null
    private var overlayParams: WindowManager.LayoutParams? = null   // saved so we can re-add after picker
    /** The floating chat's window. Its content (one chat) is swapped inside it when switching
     *  chats, so the window, the heads above it and the top row never disappear (they blinked). */
    private var overlayHost: FrameLayout? = null
    /** True while one chat is swapped for another (keep the window). */
    private var switchingOverlay = false
    private var overlayContact: String? = null
    /** Text being typed in the floating chat, per chat, kept while the window is closed. */
    private val typedDrafts = HashMap<String, String>()

    /** The floating chat is really in front of someone: shown, screen on and unlocked. */
    private fun overlaySeen(): Boolean {
        if (overlayView == null || overlayHiddenForPicker) return false
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return (pm?.isInteractive != false) && (km?.isKeyguardLocked != true)
    }
    private var overlayGroup: Pair<String, String>? = null   // (groupId, groupName) for current overlay
    /** True when the overlay has been temporarily removed from WM to let a picker Activity show. */
    private var overlayHiddenForPicker: Boolean = false
    /** Receiver that hides the overlay when home / recents is pressed. */
    private var overlaySystemReceiver: android.content.BroadcastReceiver? = null
    /** Video background helper for the overlay chat area. */
    private var overlayVideoBgHelper: com.silvershireinn.app.utils.VideoBackgroundHelper? = null
    /** Full-screen image/video overlay shown above the chat overlay when media is tapped. */
    private var mediaOverlayView: View? = null
    private var overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)

    /** Recreate if cancelled; never cancel from hideOverlay (only onDestroy). */
    private fun ensureOverlayScope(): CoroutineScope {
        if (!overlayScope.isActive) {
            overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)
        }
        return overlayScope
    }

    private val messageRepo by lazy { MessageRepository(SilvershireApp.database) }

    // ── Search state ──────────────────────────────────────────────────────────
    private var overlaySearchQuery: String = ""
    private var overlayAdapter: com.silvershireinn.app.ui.chat.MessageAdapter? = null

    // ── Voice recording state (in-service) ────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var recordingStartRunnable: Runnable? = null
    private val RECORD_START_DELAY_MS = 300L
    private val RECORD_LOCK_MS = 3_000L
    private var recordingLocked = false
    private var recordingLockRunnable: Runnable? = null
    private var suppressUpAfterLock = false
    /** Recording stopped; clip awaiting send/trash (early release before lock). */
    private var voicePendingReview = false
    private var pendingVoicePlayer: MediaPlayer? = null
    private var bubbleHiddenForPicker = false
    private var pendingVoiceContactId: String? = null
    private var pendingVoiceGroup: Pair<String, String>? = null
    private var pendingVoiceDisplayName: String? = null
    private var pendingVoiceHint: TextView? = null
    private var pendingWaveformTicker: Runnable? = null

    /** The single shared bubble (null when nothing is active). */
    private var bubbleView: View?                      = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Dismiss-zone overlay shown while dragging. */
    private var dismissOverlay: View? = null
    /** Floating clone while dragging a stack-switcher head over the overlay. */
    private var switcherDragGhost: View? = null
    private var switcherDragGhostParams: WindowManager.LayoutParams? = null
    private var switcherDragGhostVideo: MutedLoopVideoHelper? = null
    private var enteredDismissZone = false

    /** True while the floating bubble is being dragged (skip avatar rebinds). */
    private var isDraggingBubble = false
    private var lastBubbleTapMs = 0L
    private var overlayOpening = false


    // Geometry helpers
    private val dp              get() = resources.displayMetrics.density
    private val dismissRadius   get() = 56 * dp
    private val screenHeight    get() = resources.displayMetrics.heightPixels
    private val screenWidth     get() = resources.displayMetrics.widthPixels
    private val dismissCX       get() = screenWidth  / 2f
    private val dismissCY       get() = screenHeight - 80 * dp

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        // When one of our real screens comes up, the floating overlay must not cover it.
        com.silvershireinn.app.SilvershireApp.onAppForegrounded = {
            if (overlayView != null && !overlayHiddenForPicker) hideOverlay()
        }
        // Unlocking / turning the screen on: put the head back against its edge (the screen size
        // can differ on the lock screen, and the window may have been nudged meanwhile).
        try {
            val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }
            edgeReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { Handler(Looper.getMainLooper()).postDelayed({ if (bubbleView != null) updateBubble(refreshAvatars = false) }, 300) }
            }
            registerReceiver(edgeReceiver, f)
        } catch (_: Throwable) {}
    }

    private var edgeReceiver: android.content.BroadcastReceiver? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).postDelayed({ if (bubbleView != null) updateBubble(refreshAvatars = false) }, 200)
    }

    /** The screen edge the head was dropped against ("left" / "right"), or "" when free. */
    private var headSide: String
        get() = getSharedPreferences("chat_head_frames", Context.MODE_PRIVATE).getString("side", "") ?: ""
        set(v) { getSharedPreferences("chat_head_frames", Context.MODE_PRIVATE).edit().putString("side", v).apply() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val fromUser  = intent.getStringExtra(EXTRA_USER) ?: return START_NOT_STICKY
                val preview   = intent.getStringExtra(EXTRA_PREVIEW) ?: ""
                val groupId   = intent.getStringExtra(EXTRA_GROUP_ID)
                val groupName = intent.getStringExtra(EXTRA_GROUP_NAME)
                if (groupId != null && groupName != null) {
                    groupExtras[groupId] = groupId to groupName
                }
                handleShow(fromUser, preview, manual = intent.getBooleanExtra(EXTRA_MANUAL, false))
            }
            ACTION_DISMISS -> {
                val fromUser = intent.getStringExtra(EXTRA_USER) ?: return START_NOT_STICKY
                removeSender(fromUser)
            }
            ACTION_REFRESH_AVATAR, ACTION_REFRESH_CONTACT_AVATAR -> {
                refreshAllAvatars()
                updateBubble(refreshAvatars = false)      // size may have changed too
                bubbleView?.alpha = PreferenceManager.chatHeadAlpha / 100f   // and the opacity (live while sliding)
            }
            ACTION_PICKER_RESULT -> {
                handlePickerResult(intent)
            }
            ACTION_RESTORE_AFTER_VIEWER -> {
                restoreOverlayIfNeeded()
                minimizeApp()
                // A photo edited in the viewer to send here: it becomes the floating chat's draft
                overlayContact?.let { c ->
                    com.silvershireinn.app.ui.media.EditedMedia.take(c)?.let { f ->
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
                            setOverlayDraft(uri, "image/jpeg", f.name)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        return START_NOT_STICKY
    }


    private fun hasOverlayDraft(): Boolean = overlayDraftUri != null

    private fun setOverlayDraft(uri: Uri, mime: String, name: String) {
        overlayDraftUri = uri
        overlayDraftMime = mime
        overlayDraftName = name
        val bar = overlayView?.findViewById<View>(R.id.overlayDraftBar)
        val thumb = overlayView?.findViewById<ImageView>(R.id.ivOverlayDraftThumb)
        val nameTv = overlayView?.findViewById<TextView>(R.id.tvOverlayDraftFileName)
        val sendBtn = overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)
        bar?.visibility = View.VISIBLE
        val isImage = mime.startsWith("image/")
        if (isImage) {
            nameTv?.visibility = View.GONE
            thumb?.setPadding(0, 0, 0, 0)
            thumb?.scaleType = ImageView.ScaleType.CENTER_CROP
            // Small decode only (full-size photos crashed lower-end phones here).
            thumb?.let { t -> coil.Coil.imageLoader(t.context).enqueue(coil.request.ImageRequest.Builder(t.context).data(uri).size(320).target(t).build()) }
        } else {
            thumb?.setImageURI(null)
            thumb?.setImageResource(R.drawable.ic_attach)
            val pad = (10 * resources.displayMetrics.density).toInt()
            thumb?.setPadding(pad, pad, pad, pad)
            thumb?.scaleType = ImageView.ScaleType.CENTER_INSIDE
            nameTv?.text = name
            nameTv?.visibility = View.VISIBLE
        }
        sendBtn?.setImageResource(R.drawable.ic_send)
    }

    private fun clearOverlayDraft() {
        overlayDraftUri = null
        overlayDraftMime = null
        overlayDraftName = null
        val bar = overlayView?.findViewById<View>(R.id.overlayDraftBar)
        val thumb = overlayView?.findViewById<ImageView>(R.id.ivOverlayDraftThumb)
        val nameTv = overlayView?.findViewById<TextView>(R.id.tvOverlayDraftFileName)
        bar?.visibility = View.GONE
        thumb?.setImageDrawable(null)
        thumb?.setPadding(0, 0, 0, 0)
        nameTv?.visibility = View.GONE
        nameTv?.text = ""
        val et = overlayView?.findViewById<EditText>(R.id.etOverlayInput)
        val sendBtn = overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)
        val hasText = !et?.text.isNullOrBlank()
        sendBtn?.setImageResource(if (hasText) R.drawable.ic_send else R.drawable.ic_mic)
    }

    /**
     * Upload the overlay's attachment draft through the shared sender: a bubble with progress
     * appears at once, retries on a bad connection, can be cancelled, and a failure says so
     * (it used to vanish silently, and group files skipped the server's message id).
     */
    private fun uploadOverlayDraft(contactId: String, groupId: String?, groupName: String?) {
        val uri = overlayDraftUri ?: return
        val mime = overlayDraftMime
        val fileName = overlayDraftName
        clearOverlayDraft()
        com.silvershireinn.app.ui.chat.ChatSender.sendFile(this, contactId, groupId, groupName ?: "", uri, mime, fileName) { msg ->
            try { android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun handlePickerResult(intent: Intent) {
        // Picker Activity has finished — restore the overlay so the user is back in chat,
        // then push the app task to the background so it doesn't show behind the overlay.
        restoreOverlayIfNeeded()
        minimizeApp()

        val uriStr      = intent.getStringExtra(EXTRA_PICKER_URI)
        val text        = intent.getStringExtra(EXTRA_PICKER_TEXT)
        val contactId   = intent.getStringExtra(ChatHeadPickerActivity.EXTRA_CONTACT_ID) ?: return
        val groupId     = intent.getStringExtra(ChatHeadPickerActivity.EXTRA_GROUP_ID)
        val groupName   = intent.getStringExtra(ChatHeadPickerActivity.EXTRA_GROUP_NAME)
        val displayName = intent.getStringExtra(ChatHeadPickerActivity.EXTRA_DISPLAY_NAME) ?: contactId

        // STT result — insert text into overlay input if the overlay is still open for this contact
        if (text != null) {
            val et = overlayView?.findViewById<EditText>(R.id.etOverlayInput)
            if (et != null && overlayContact == contactId) {
                val current = et.text?.toString() ?: ""
                val merged  = if (current.isBlank()) text else "$current $text"
                et.setText(merged)
                et.setSelection(merged.length)
            }
            return
        }

        // File URI result — all types become drafts; Send uploads
        if (uriStr != null) {
            val uri = Uri.parse(uriStr)
            val cr = applicationContext.contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            if (overlayContact == contactId) {
                val fileName = queryFileName(uri) ?: when {
                    mime.startsWith("image/") -> "image_${System.currentTimeMillis()}.jpg"
                    mime.startsWith("video/") -> "video_${System.currentTimeMillis()}.mp4"
                    else -> "file_${System.currentTimeMillis()}"
                }
                setOverlayDraft(uri, mime, fileName)
            }
        }
    }

    /** Best-effort filename extraction from a content URI. */
    private fun queryFileName(uri: Uri): String? = try {
        applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    } catch (_: Exception) { null }

    override fun onDestroy() {
        try { edgeReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        edgeReceiver = null
        com.silvershireinn.app.SilvershireApp.overlayOpen = false
        if (instance === this) instance = null
        try { avatarFrameLoader.shutdownNow() } catch (_: Exception) {}
        avatarFrameCache.clear()
        com.silvershireinn.app.SilvershireApp.onAppForegrounded = null
        releaseBubbleVideos()
        releaseSwitcherVideos()
        overlayAvatarVideoHelper?.release()
        overlayAvatarVideoHelper = null
        endSwitcherDragGhost()
        stopPendingVoicePlayback()
        cancelRecording()
        recordingHandler.removeCallbacksAndMessages(null)
        overlayScope.cancel()
        hideDismissOverlay()
        mediaOverlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        mediaOverlayView = null
        overlayHost?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlayHost = null
        overlayView = null
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView   = null
        bubbleParams = null
        super.onDestroy()
    }

    // ── Show / update ─────────────────────────────────────────────────────────

    private fun handleShow(fromUser: String, preview: String, manual: Boolean = false) {
        // Move sender to tail (newest) and increment their unread count - unless that chat is
        // open in the floating window and scrolled to the bottom (then it's already seen).
        // Heads arranged by hand keep their places; otherwise the sender moves to the front
        val prev = if (manualHeadOrder && activeSenders.containsKey(fromUser)) activeSenders[fromUser] ?: 0
                   else activeSenders.remove(fromUser) ?: 0
        // Open in the floating window right now: no number on the head for it (only other chats count)
        val seenNow = overlayView != null && !overlayHiddenForPicker && overlayContact == fromUser
        activeSenders[fromUser] = if (seenNow) 0 else if (manual) prev else prev + 1

        if (bubbleView == null) createBubble()
        updateBubble()
        refreshSwitcherIfOpen()
    }

    private fun createBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val (savedX, savedY) = PreferenceManager.loadChatHeadPos(BUBBLE_POS_KEY, 300)
        bubblePad = -1          // the saved spot includes the border room the bubble had then

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // GPU drawing: the pictures are hardware bitmaps, which a software-drawn window can't show.
            // No limits: the heads may be pushed partly off the screen edge (up to half a head).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x       = savedX
            y       = savedY
        }

        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_chat_head_stacked, null, false)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.alpha = PreferenceManager.chatHeadAlpha / 100f
        // Ensure perfect circular clips (oval outline, not rectangular WM backing)
        fun forceOval(v: View?) {
            if (v == null) return
            v.clipToOutline = true
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            v.elevation = 0f
        }
        listOf(R.id.cvAvatarFront, R.id.cvAvatarMid, R.id.cvAvatarBack, R.id.chatHeadBadgeCard).forEach { id ->
            forceOval(view.findViewById(id))
        }
        listOf(R.id.ivPicFront, R.id.ivPicMid, R.id.ivPicBack, R.id.vvPicFront, R.id.vvPicMid, R.id.vvPicBack).forEach { id ->
            forceOval(view.findViewById(id))
        }
        attachTouchListener(view)

        try {
            wm.addView(view, bubbleParams)
            bubbleView = view
        } catch (e: SecurityException) {
            android.util.Log.e("ChatHead", "Overlay permission denied", e)
            PreferenceManager.chatHeads = false
        } catch (e: IllegalStateException) {
            android.util.Log.e("ChatHead", "Bubble addView IllegalState", e)
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("ChatHead", "Bubble addView IllegalArgument", e)
        }
    }

    /**
     * Re-render the stacked avatars and total-unread badge to match [activeSenders].
     * The newest sender (tail of the LinkedHashMap) goes to the front slot;
     * older senders peek behind.
     */
    private fun updateBubble(refreshAvatars: Boolean = true) {
        val view = bubbleView ?: return
        val senders = activeSenders.keys.toList()   // index 0 = oldest, last = newest

        val frontUser = senders.getOrNull(senders.size - 1)
        val midUser   = senders.getOrNull(senders.size - 2)
        val backUser  = senders.getOrNull(senders.size - 3)

        val cvFront = view.findViewById<View>(R.id.cvAvatarFront)
        val cvMid   = view.findViewById<View>(R.id.cvAvatarMid)
        val cvBack  = view.findViewById<View>(R.id.cvAvatarBack)

        val count = listOfNotNull(frontUser, midUser, backUser).size

        // Size (Profile > Chat heads > Size): front head, the ones behind a little smaller
        run {
            val s = PreferenceManager.chatHeadSize * dp
            fun size(v: View, f: Float) { val lp = v.layoutParams; val px = (s * f).toInt(); if (lp.width != px) { lp.width = px; lp.height = px; v.layoutParams = lp } }
            size(cvFront, 1f); size(cvMid, 50f / 56f); size(cvBack, 44f / 56f)
        }
        val step = 14 * dp * PreferenceManager.chatHeadSize / 56f
        // Position avatars dynamically — overlap so earlier senders peek behind
        when (count) {
            1 -> {
                // Single sender: just the front avatar, no offset needed
                cvFront.translationX = 0f
                cvMid.visibility     = View.GONE
                cvBack.visibility    = View.GONE
            }
            2 -> {
                // Two senders: mid peeks 14 dp to the left of front
                cvFront.translationX = step
                cvMid.translationX   = 0f
                cvMid.visibility     = View.VISIBLE
                cvBack.visibility    = View.GONE
            }
            else -> {
                // Three senders: staggered 14 dp each
                cvFront.translationX = step * 2
                cvMid.translationX   = step
                cvBack.translationX  = 0f
                cvMid.visibility     = View.VISIBLE
                cvBack.visibility    = View.VISIBLE
            }
        }

        // Apply avatar art to each visible slot (skip mid-drag / overlay-open to avoid flash)
        if (refreshAvatars && !isDraggingBubble) {
            frontUser?.let { applyAvatar(view, it, AvatarSlot.FRONT) }
            midUser?.let   { applyAvatar(view, it, AvatarSlot.MID)   }
            backUser?.let  { applyAvatar(view, it, AvatarSlot.BACK)  }
        }
        (view as? FrameLayout)?.let { layoutBubbleFrames(it, listOf(cvBack to backUser, cvMid to midUser, cvFront to frontUser)) }

        // Total unread badge (top-end)
        val totalUnread = activeSenders.values.sum()
        val badgeCard   = view.findViewById<View>(R.id.chatHeadBadgeCard)
        val badgeTv     = view.findViewById<TextView>(R.id.tvChatHeadBadge)
        if (totalUnread > 0) {
            badgeCard.visibility = View.VISIBLE
            badgeTv.text         = if (totalUnread > 99) "99+" else totalUnread.toString()
        } else {
            badgeCard.visibility = View.GONE
        }

        // Yellow total-chat count when stack exceeds 3 (top-start, opposite unread)
        val stackCountCard = view.findViewById<View?>(R.id.chatHeadStackCountCard)
        val stackCountTv   = view.findViewById<TextView?>(R.id.tvChatHeadStackCount)
        if (stackCountCard != null && stackCountTv != null) {
            val totalChats = senders.size
            if (totalChats > 3) {
                stackCountCard.visibility = View.VISIBLE
                stackCountTv.text = totalChats.toString()
            } else {
                stackCountCard.visibility = View.GONE
            }
        }
    }

    // ── Avatar rendering ──────────────────────────────────────────────────────

    private enum class AvatarSlot { FRONT, MID, BACK }

    private fun applyAvatar(view: View, username: String, slot: AvatarSlot) {
        val ivId = when (slot) { AvatarSlot.FRONT -> R.id.ivPicFront; AvatarSlot.MID -> R.id.ivPicMid; else -> R.id.ivPicBack }
        val tvId = when (slot) { AvatarSlot.FRONT -> R.id.tvInitialsFront; AvatarSlot.MID -> R.id.tvInitialsMid; else -> R.id.tvInitialsBack }
        val cvId = when (slot) { AvatarSlot.FRONT -> R.id.cvAvatarFront; AvatarSlot.MID -> R.id.cvAvatarMid; else -> R.id.cvAvatarBack }
        val vvId = when (slot) { AvatarSlot.FRONT -> R.id.vvPicFront; AvatarSlot.MID -> R.id.vvPicMid; else -> R.id.vvPicBack }

        val iv = view.findViewById<ImageView>(ivId) ?: return
        val tv = view.findViewById<TextView>(tvId)  ?: return
        val cv = view.findViewById<View>(cvId)  ?: return
        val vv = view.findViewById<TextureView>(vvId)

        run {
            val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(username)
            val color = username.toAvatarColor()
            cv.background = com.silvershireinn.app.utils.AvatarShapeHelper.backgroundDrawable(color, shape)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(cv, shape)
            if (cv.foreground is com.silvershireinn.app.utils.ProfileFrames.FrameDrawable) cv.foreground = null   // full border instead (layoutBubbleFrames)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(iv, shape)
            if (vv != null) com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(vv, shape)
        }
        tv.text = username.toInitials()

        val picPath = contactPicPath(this, username)
        bindAvatarArt(slot, iv, tv, vv, picPath)
    }

    /**
     * Bind avatar art to an ImageView + initials TextView. Static images decode
     * synchronously; video profile pics get a single extracted frame loaded off
     * the main thread so bubbles show a real picture instead of initials.
     */
    private val avatarFrameLoader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /**
     * Still frames of video avatars, by file + modified time, at most ~6 MB. It was an
     * unbounded map keyed by path only: it grew forever and kept showing a friend's OLD
     * video after they changed it.
     */
    private class FrameCache {
        private val lru = object : android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024) {
            override fun sizeOf(k: String, v: android.graphics.Bitmap) = (v.byteCount / 1024).coerceAtLeast(1)
        }
        private fun key(p: String) = "$p@${File(p).lastModified()}"
        operator fun get(path: String): android.graphics.Bitmap? = lru.get(key(path))
        operator fun set(path: String, bmp: android.graphics.Bitmap) { lru.put(key(path), bmp) }
        fun clear() = lru.evictAll()
    }
    private val avatarFrameCache = FrameCache()
    private val bubbleVideoHelpers = HashMap<AvatarSlot, MutedLoopVideoHelper>()
    private var overlayAvatarVideoHelper: MutedLoopVideoHelper? = null
    private val switcherVideoHelpers = ArrayList<MutedLoopVideoHelper>()

    private fun bubbleVideoHelper(slot: AvatarSlot, vv: TextureView): MutedLoopVideoHelper {
        bubbleVideoHelpers[slot]?.let { return it }
        val h = MutedLoopVideoHelper(this, vv)
        bubbleVideoHelpers[slot] = h
        return h
    }

    private fun releaseBubbleVideos() {
        bubbleVideoHelpers.values.forEach { it.release() }
        bubbleVideoHelpers.clear()
    }

    private fun releaseSwitcherVideos() {
        switcherVideoHelpers.forEach { it.release() }
        switcherVideoHelpers.clear()
    }

    private fun bindAvatarArt(slot: AvatarSlot, iv: ImageView, tv: TextView, vv: TextureView?, path: String) {
        val helper = if (vv != null) bubbleVideoHelper(slot, vv) else null
        if (path.isBlank()) {
            helper?.clear(iv)
            vv?.visibility = View.GONE
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            return
        }
        val f = File(path)
        if (!f.exists()) {
            helper?.clear(iv)
            vv?.visibility = View.GONE
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            return
        }

        if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) {
            tv.visibility = View.GONE
            // Prefer shared frame cache; keep ImageView covered until TextureView plays
            // Only already-decoded frames here; a miss is decoded on the worker below (it was
            // decoded right here on the main thread, a stutter whenever a message arrived).
            val cached = avatarFrameCache[path]
                ?: com.silvershireinn.app.utils.ProfileMediaHelper.cachedVideoFrame(path)
            if (cached != null) {
                avatarFrameCache[path] = cached
                iv.setImageBitmap(cached)
                iv.visibility = View.VISIBLE
            } else {
                // Never flash initials for video — keep prior art or wait for frame
                if (iv.drawable == null) iv.visibility = View.INVISIBLE else iv.visibility = View.VISIBLE
                avatarFrameLoader.execute {
                    val bmp = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(path)
                    Handler(Looper.getMainLooper()).post {
                        if (bmp == null) return@post
                        avatarFrameCache[path] = bmp
                        iv.setImageBitmap(bmp)
                        iv.visibility = View.VISIBLE
                        tv.visibility = View.GONE
                    }
                }
            }
            if (helper != null && vv != null) {
                helper.play(path, iv)
            } else if (iv.drawable != null) {
                iv.visibility = View.VISIBLE
            }
        } else {
            helper?.clear(iv)
            vv?.visibility = View.GONE
            // Decoded on the worker thread (first decode of a big photo blocked the main thread).
            iv.tag = path
            avatarFrameLoader.execute {
                val bmp = try { com.silvershireinn.app.utils.VideoThumbs.decodeSmall(f.absolutePath) } catch (_: Throwable) { null }
                Handler(Looper.getMainLooper()).post {
                    if (iv.tag != path) return@post
                    if (bmp != null) { iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE; tv.visibility = View.GONE }
                    else { iv.visibility = View.GONE; tv.visibility = View.VISIBLE }
                }
            }
        }
    }

    /** Drawn area of each border picture used on the bubble (key: frame|shape|size). */
    private val bubbleDrawn = HashMap<String, FloatArray>()
    private var bubblePad = -1
    private var bubblePadT = 0
    /** Leftmost / rightmost drawn pixel of the heads (window x), and how far they may go off screen. */
    private var bubbleVisL = 0f
    private var bubbleVisR = 0f
    private var bubbleMaxOff = 0f
    /** Window x range: the heads may be pushed off either edge by up to half a head. */
    private fun bubbleMinX(): Int = kotlin.math.floor(-bubbleVisL - bubbleMaxOff).toInt()
    private fun bubbleMaxX(): Int = maxOf(bubbleMinX(), kotlin.math.ceil(screenWidth - bubbleVisR + bubbleMaxOff).toInt())
    /** How far (px) past its edge the stack was left (0 = touching it). */
    private var headEdgeOff: Float
        get() = getSharedPreferences("chat_head_frames", Context.MODE_PRIVATE).getFloat("edge_off", 0f)
        set(v) { getSharedPreferences("chat_head_frames", Context.MODE_PRIVATE).edit().putFloat("edge_off", v).apply() }

    /**
     * Each head in the bubble gets its whole border (dragons and all), drawn right above its own
     * picture so the head in front still covers the ones behind it. The bubble window grows by
     * just enough room for the borders (and keeps the pictures where they were); a border that
     * would still not fit shrinks together with its picture.
     */
    private fun layoutBubbleFrames(root: FrameLayout, slots: List<Pair<View, String?>>) {
        val PF = com.silvershireinn.app.utils.ProfileFrames
        val tol = 2 * dp
        val contentH = PreferenceManager.chatHeadSize * dp
        data class S(val cv: View, val iv: ImageView, val a: Float, val full: Float, val d: FloatArray?)
        val live = ArrayList<S>()
        for ((cv, user) in slots) {
            val tagName = "bubble_frame_${cv.id}"
            var iv = root.findViewWithTag<ImageView>(tagName)
            val id = PF.frameFor(user)
            val framed = user != null && cv.visibility == View.VISIBLE && !(id == "none" || id.startsWith("gen:none"))
            if (!framed) { iv?.visibility = View.GONE; cv.scaleX = 1f; cv.scaleY = 1f; continue }
            if (iv == null) {
                iv = ImageView(this).apply {
                    tag = tagName
                    scaleType = ImageView.ScaleType.FIT_XY
                    isClickable = false; isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                root.addView(iv, root.indexOfChild(cv) + 1, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.CENTER_VERTICAL))
            }
            iv.visibility = View.VISIBLE
            val a = (cv.layoutParams?.width ?: (56 * dp).toInt()).toFloat()
            val full = a / PF.PIC
            val shape = PF.shapeName(com.silvershireinn.app.utils.AvatarShapeHelper.forUser(user))
            val px = PF.bucket(full.toInt()).coerceAtLeast(128)
            val key = "$id|$shape|$px"
            if (iv.getTag(R.id.profile_frame_key) != key) { iv.setImageDrawable(null); iv.setTag(R.id.profile_frame_key, key) }
            if (iv.drawable == null) {
                val target = iv
                com.silvershireinn.app.utils.FrameRenderer.get(applicationContext, id, shape, px, "full") { b ->
                    if (b == null || target.getTag(R.id.profile_frame_key) != key) return@get
                    target.setImageBitmap(b)
                    if (bubbleDrawn[key] == null) { bubbleDrawn[key] = PF.drawnArea(b); root.post { updateBubble(refreshAvatars = false) } }
                }
            }
            live.add(S(cv, iv, a, full, bubbleDrawn[key]))
        }
        // Room the borders need past the pictures, worked out for each side on its own: a side
        // no border reaches gets none, so the pictures sit right against the screen edge there
        // (the same room on every side kept the heads ~20 px off the edge).
        val contentW = slots.filter { it.first.visibility == View.VISIBLE }
            .maxOfOrNull { it.first.translationX + (it.first.layoutParams?.width ?: 0) } ?: contentH
        var nL = 0f; var nR = 0f; var nT = 0f; var nB = 0f
        for (s in live) {
            val d = s.d ?: continue
            val cx0 = s.cv.translationX + s.a / 2f; val cy0 = contentH / 2f
            nL = maxOf(nL, (0.5f - d[0]) * s.full - cx0 + tol)
            nR = maxOf(nR, cx0 + (d[2] - 0.5f) * s.full - contentW + tol)
            nT = maxOf(nT, (0.5f - d[1]) * s.full - cy0 + tol)
            nB = maxOf(nB, cy0 + (d[3] - 0.5f) * s.full - contentH + tol)
        }
        val cap = contentH * 0.22f                                  // a little room: the border shrinks to fit
        val pL = nL.coerceIn(0f, cap).toInt(); val pR = nR.coerceIn(0f, cap).toInt()
        val pT = nT.coerceIn(0f, cap).toInt(); val pB = nB.coerceIn(0f, cap).toInt()
        val pad = pL                                                // remembered: keeps the pictures in place
        val w = (contentW + pL + pR).toInt(); val h = (contentH + pT + pB).toInt()
        for (s in live) {
            val d = s.d
            var k = 1f
            if (d != null) {
                val cx = pL + s.cv.translationX + s.a / 2f; val cy = pT + contentH / 2f
                val nl = (0.5f - d[0]) * s.full; val nr = (d[2] - 0.5f) * s.full
                val nt = (0.5f - d[1]) * s.full; val nb = (d[3] - 0.5f) * s.full
                if (nl > 0) k = minOf(k, (cx - tol) / nl)
                if (nr > 0) k = minOf(k, (w - cx - tol) / nr)
                if (nt > 0) k = minOf(k, (cy - tol) / nt)
                if (nb > 0) k = minOf(k, (h - cy - tol) / nb)
                k = k.coerceIn(0.3f, 1f)
            } else s.iv.visibility = View.INVISIBLE                 // until its reach is known
            val size = (s.full * k).toInt()
            val lp = s.iv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != size || lp.height != size) { lp.width = size; lp.height = size; s.iv.layoutParams = lp }
            s.iv.translationX = s.cv.translationX + (s.a - size) / 2f
            s.cv.scaleX = k; s.cv.scaleY = k
        }
        // Grow / shrink the bubble window around the same spot
        if (root.paddingLeft != pL || root.paddingTop != pT || root.paddingRight != pR || root.paddingBottom != pB) root.setPadding(pL, pT, pR, pB)
        // Where something is really drawn, left and right (window coordinates): each head's own
        // shape at its real size and scale, and each border's drawn pixels. The edges line up
        // with these, not with the window, so the heads touch the screen edge exactly.
        run {
            var vl = Float.MAX_VALUE; var vr = -Float.MAX_VALUE
            for ((cv, user) in slots) {
                if (cv.visibility != View.VISIBLE || user == null) continue
                val a = (cv.layoutParams?.width ?: 0).toFloat()
                val cx = pL + cv.translationX + a / 2f
                vl = minOf(vl, cx - a * cv.scaleX / 2f); vr = maxOf(vr, cx + a * cv.scaleX / 2f)
            }
            for (s in live) {
                val d = s.d ?: continue
                if (s.iv.visibility != View.VISIBLE) continue
                val size = (s.iv.layoutParams?.width ?: 0).toFloat()
                val left = pL + s.iv.translationX
                vl = minOf(vl, left + d[0] * size); vr = maxOf(vr, left + d[2] * size)
            }
            if (vl == Float.MAX_VALUE) { vl = 0f; vr = w.toFloat() }
            bubbleVisL = vl; bubbleVisR = vr; bubbleMaxOff = contentH / 2f
        }
        val p = bubbleParams ?: return
        val padPrefs = getSharedPreferences("chat_head_frames", Context.MODE_PRIVATE)
        val old = if (bubblePad < 0) padPrefs.getInt("pad", 0) else bubblePad
        val oldT = if (bubblePad < 0) padPrefs.getInt("padT", old) else bubblePadT
        if (p.width != w || p.height != h || old != pad || oldT != pT || bubblePad < 0) {
            p.width = w; p.height = h
            if (old != pad) p.x = (p.x - (pad - old)).coerceIn(bubbleMinX(), bubbleMaxX())
            if (oldT != pT) p.y = (p.y - (pT - oldT)).coerceIn(0, (screenHeight - h).coerceAtLeast(0))
            bubblePad = pad; bubblePadT = pT
            padPrefs.edit().putInt("pad", pad).putInt("padT", pT).apply()
            try { if (root.isAttachedToWindow) wm.updateViewLayout(root, p) } catch (_: Exception) {}
        }
        // Dropped against an edge: stay flush with it whatever the width does (more heads in the
        // stack, a border, a size change, the screen size after unlocking). It used to keep its
        // old x and end up 40-50 px away from the edge.
        if (!isDraggingBubble) {
            // Heads placed before this was remembered: work out the edge once from where it is
            val sp = getSharedPreferences("chat_head_frames", Context.MODE_PRIVATE)
            if (!sp.contains("side")) headSide = when {
                p.x + bubbleVisL <= 8 * dp -> "left"
                p.x + bubbleVisR >= screenWidth - 40 * dp -> "right"
                else -> ""
            }
            val off = headEdgeOff.coerceIn(0f, bubbleMaxOff)
            val wantX = when (headSide) {
                "right" -> kotlin.math.round(screenWidth - bubbleVisR + off).toInt()
                "left" -> kotlin.math.round(-bubbleVisL - off).toInt()
                else -> p.x.coerceIn(bubbleMinX(), bubbleMaxX())
            }
            if (p.x != wantX) {
                p.x = wantX
                try { if (root.isAttachedToWindow) wm.updateViewLayout(root, p) } catch (_: Exception) {}
            }
        }
    }

    private fun refreshAllAvatars() {
        if (isDraggingBubble) return
        val view    = bubbleView ?: return
        val senders = activeSenders.keys.toList()
        senders.getOrNull(senders.size - 1)?.let { applyAvatar(view, it, AvatarSlot.FRONT) }
        senders.getOrNull(senders.size - 2)?.let { applyAvatar(view, it, AvatarSlot.MID)   }
        senders.getOrNull(senders.size - 3)?.let { applyAvatar(view, it, AvatarSlot.BACK)  }
    }

    /** Restart muted loop videos without clearing still frames / initials flash. */
    private fun softRestartBubbleVideos() {
        if (isDraggingBubble) return
        val view = bubbleView ?: return
        ensureBubbleStillFramesVisible(view)
        val senders = activeSenders.keys.toList()
        val slots = listOf(
            AvatarSlot.FRONT to senders.getOrNull(senders.size - 1),
            AvatarSlot.MID to senders.getOrNull(senders.size - 2),
            AvatarSlot.BACK to senders.getOrNull(senders.size - 3)
        )
        for ((slot, user) in slots) {
            if (user == null) continue
            val path = contactPicPath(this, user)
            if (path.isBlank() || !com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) continue
            val vvId = when (slot) {
                AvatarSlot.FRONT -> R.id.vvPicFront
                AvatarSlot.MID -> R.id.vvPicMid
                AvatarSlot.BACK -> R.id.vvPicBack
            }
            val ivId = when (slot) {
                AvatarSlot.FRONT -> R.id.ivPicFront
                AvatarSlot.MID -> R.id.ivPicMid
                AvatarSlot.BACK -> R.id.ivPicBack
            }
            val vv = view.findViewById<TextureView?>(vvId) ?: continue
            val iv = view.findViewById<ImageView?>(ivId) ?: continue
            val cached = avatarFrameCache[path]
            if (cached != null) {
                iv.setImageBitmap(cached)
                iv.visibility = View.VISIBLE
            }
            bubbleVideoHelper(slot, vv).play(path, iv)
        }
    }

    // ── Touch / drag ──────────────────────────────────────────────────────────

    private fun attachTouchListener(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var downPX   = 0
        var downPY   = 0
        var moved    = false
        var headW = (72 * dp).toInt()
        var headH = (72 * dp).toInt()
        val dragSlop = 16 * dp

        view.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    downPX   = params.x
                    downPY   = params.y
                    moved    = false
                    enteredDismissZone = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                        moved = true
                        isDraggingBubble = true
                        headW = if (view.width  > 0) view.width  else (72 * dp).toInt()
                        headH = if (view.height > 0) view.height else (72 * dp).toInt()
                        // Still frames only. NEVER removeView/addView the bubble mid-drag —
                        // that cancels the touch stream (ACTION_CANCEL) and leaves the X stuck.
                        ensureBubbleStillFramesVisible(view)
                        // Add dismiss X as a new overlay window (on top). Do not touch bubble WM.
                        showDismissOverlay()
                    }
                    if (!moved) return@setOnTouchListener true
                    var nx = (downPX + dx).toInt()
                    var ny = (downPY + dy).toInt()
                    if (isOverDismiss(event.rawX, event.rawY)) {
                        if (!enteredDismissZone) {
                            enteredDismissZone = true
                            vibrateOnDismiss()
                        }
                        nx = (dismissCX - headW / 2f).toInt()
                        ny = (dismissCY - headH / 2f).toInt()
                    } else {
                        enteredDismissZone = false
                    }
                    nx = if (bubbleVisR > bubbleVisL) nx.coerceIn(bubbleMinX(), bubbleMaxX()) else nx.coerceIn(0, (screenWidth - headW).coerceAtLeast(0))
                    ny = ny.coerceIn(0, (screenHeight - headH).coerceAtLeast(0))
                    params.x = nx
                    params.y = ny
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    highlightDismiss(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Always clear dismiss UI first so a cancelled drag never leaves the X up.
                    hideDismissOverlay()
                    enteredDismissZone = false
                    isDraggingBubble = false
                    val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    val dismissed = !isCancel && moved && isOverDismiss(event.rawX, event.rawY)
                    if (dismissed) {
                        vibrateOnDismiss()
                        removeAllHeads()
                    } else {
                        if (moved) {
                            val exact = bubbleVisR > bubbleVisL
                            params.x = if (exact) params.x.coerceIn(bubbleMinX(), bubbleMaxX()) else params.x.coerceIn(0, (screenWidth - headW).coerceAtLeast(0))
                            params.y = params.y.coerceIn(0, (screenHeight - headH).coerceAtLeast(0))
                            if (PreferenceManager.chatHeadSnapEnabled) {
                                edgeSnap(params, headW, headH)
                            }
                            // Remember the edge it now sits against (kept there later), and how
                            // far past it the heads were pushed (up to half a head may hide).
                            val visLeft = params.x + (if (exact) bubbleVisL else 0f)
                            val visRight = params.x + (if (exact) bubbleVisR else headW.toFloat())
                            headSide = when {
                                visLeft <= 0.5f -> "left"
                                visRight >= screenWidth - 0.5f -> "right"
                                else -> ""
                            }
                            headEdgeOff = when (headSide) {
                                "left" -> (-visLeft).coerceIn(0f, bubbleMaxOff)
                                "right" -> (visRight - screenWidth).coerceIn(0f, bubbleMaxOff)
                                else -> 0f
                            }
                            PreferenceManager.saveChatHeadPos(BUBBLE_POS_KEY, params.x, params.y)
                            try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                        }
                        if (moved) refreshAllAvatars()
                        if (!moved && !isCancel) handleTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Tap: toggle the overlay chat window for the newest sender.
     * The overlay floats over whatever app is open — we never navigate away.
     */
    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBubbleTapMs < 300L) return
        lastBubbleTapMs = now

        val newestSender = activeSenders.keys.lastOrNull() ?: return
        // A camera / picker is open on top: never pull the chat back over it.
        if (overlayHiddenForPicker || bubbleHiddenForPicker) return

        // Overlay open: a tap closes it (or switches to the newest sender if that changed).
        if (overlayView != null) {
            hideOverlay()
            if (overlayClosedFor(newestSender)) return
            openOverlayFor(newestSender)
            return
        }

        // Our app is on screen: NEVER float the overlay over it. (Before, an unread badge on
        // the head made this branch open the overlay on top of the app.) Right after we
        // minimize, the app's activity takes a few hundred ms to stop, so treat it as hidden.
        val appVisible = com.silvershireinn.app.SilvershireApp.isAppVisible && now - minimizedAtMs > 1500L
        if (appVisible) {
            val openInApp = ChatActivity.activeChatContact
            val unread = activeSenders[newestSender] ?: 0
            if (unread > 0 && openInApp != newestSender) {
                // New messages from a chat you are not looking at: open it here in the app.
                openChatInApp(newestSender)
            } else {
                // Same chat (or nothing new): get out of the way. Tap again for the overlay.
                minimizeApp()
            }
            return
        }

        // App is in the background: open the floating chat.
        openOverlayFor(newestSender)
    }

    /** Contact the overlay was showing right before the last hideOverlay(). */
    private var lastOverlayContact: String? = null
    private fun overlayClosedFor(id: String) = lastOverlayContact == id

    private fun openOverlayFor(id: String) {
        showOverlay(id, groupExtras[id])
        activeSenders[id] = 0
        updateBubble(refreshAvatars = false)
        ensureBubbleAttachedSoon()
    }

    private fun openChatInApp(id: String) {
        val group = groupExtras[id]
        activeSenders[id] = 0
        updateBubble(refreshAvatars = false)
        try {
            startActivity(Intent(this, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("contact_username", id)
                putExtra("launched_from_chat_head", true)
                if (group != null) {
                    putExtra("group_id", group.first)
                    putExtra("group_name", group.second)
                }
            })
        } catch (_: Exception) {
            openOverlayFor(id)
        }
    }

    /** Badge-only clear; see companion clearUnread(). */
    fun clearUnreadFor(id: String) {
        val n = activeSenders[id] ?: return
        if (n == 0) return
        activeSenders[id] = 0
        updateBubble(refreshAvatars = false)
        refreshSwitcherIfOpen()
    }

    /**
     * Safety net: the bubble is removed and re-added to stay above the overlay. If that
     * re-add ever fails (seen with rapid taps), the head used to vanish for good.
     */
    private fun ensureBubbleAttachedSoon() {
        Handler(Looper.getMainLooper()).postDelayed({
            val bv = bubbleView ?: return@postDelayed
            val bp = bubbleParams ?: return@postDelayed
            if (bubbleHiddenForPicker || isDraggingBubble) return@postDelayed
            if (!bv.isAttachedToWindow) {
                try { wm.addView(bv, bp) } catch (_: Exception) {}
            }
            val target = PreferenceManager.chatHeadAlpha / 100f
            if (bv.alpha < 0.05f) bv.alpha = target
        }, 450L)
    }

    @Suppress("ClickableViewAccessibility")
    private fun showOverlay(contactId: String, group: Pair<String, String>?) {
        if (overlayOpening) return
        // Ensure any previous overlay is fully removed before re-add
        if (overlayView != null) {
            try { hideOverlay() } catch (_: Exception) {}
        }
        overlayOpening = true
        overlayContact = contactId
        overlayGroup   = group
        val displayName = group?.second ?: com.silvershireinn.app.utils.PreferenceManager.displayNameFor(contactId)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val themedCtx = android.view.ContextThemeWrapper(applicationContext, R.style.Theme_Silvershire)
        val view = LayoutInflater.from(themedCtx)
            .inflate(R.layout.layout_chat_head_overlay, null, false)

        view.findViewById<TextView>(R.id.tvOverlayName).text = displayName
        // Switching chats: the row of heads at the top moves over as it is (pictures, borders,
        // scroll spot); only the highlight changes. Rebuilding it made every head blink.
        val carried = carrySwitcher
        carrySwitcher = null
        if (carried == null || !adoptSwitcher(view, carried, contactId)) setupOverlayStackSwitcher(view, contactId)


        // ── Close ─────────────────────────────────────────────────────────────
        view.findViewById<ImageButton>(R.id.btnOverlayClose).setOnClickListener { hideOverlay() }

        // ── Expand to full ChatActivity ────────────────────────────────────────
        fun openFullChat() {
            hideOverlay()
            startActivity(Intent(this, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("contact_username", contactId)
                putExtra("launched_from_chat_head", true)
                if (group != null) {
                    putExtra("group_id", group.first)
                    putExtra("group_name", group.second)
                }
            })
        }
        view.findViewById<ImageButton>(R.id.btnOverlayExpand).setOnClickListener { openFullChat() }

        // ── Call buttons (1:1 only) ────────────────────────────────────────────
        val callBtn  = view.findViewById<ImageButton>(R.id.btnOverlayCall)
        val videoBtn = view.findViewById<ImageButton>(R.id.btnOverlayVideoCall)
        if (group != null) {
            callBtn.visibility  = View.GONE
            videoBtn.visibility = View.GONE
        } else {
            callBtn.setOnClickListener {
                confirmOutgoingCall(contactId, video = false) {
                    hideOverlay()
                    com.silvershireinn.app.network.CallManager.placeCall(contactId)
                    startActivity(Intent(this, com.silvershireinn.app.ui.call.CallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("remote_user", contactId)
                        putExtra("is_incoming", false)
                    })
                }
            }
            videoBtn.setOnClickListener {
                confirmOutgoingCall(contactId, video = true) {
                    hideOverlay()
                    com.silvershireinn.app.network.VideoCallManager.placeCall(contactId)
                startActivity(Intent(this, com.silvershireinn.app.ui.call.VideoCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("remote_user", contactId)
                    putExtra("is_incoming", false)
                })
                }
            }
        }

        // ── Settings ──────────────────────────────────────────────────────────
        view.findViewById<ImageButton>(R.id.btnOverlaySettings).setOnClickListener {
            hideOverlay()
            startActivity(Intent(this, com.silvershireinn.app.ui.chat.ChatSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.silvershireinn.app.ui.chat.ChatSettingsActivity.EXTRA_CONTACT, contactId)
                if (group != null) putExtra(com.silvershireinn.app.ui.chat.ChatSettingsActivity.EXTRA_GROUP_NAME, group.second)
            })
        }

        // ── Avatar ────────────────────────────────────────────────────────────
        val avatarIv = view.findViewById<ImageView>(R.id.ivOverlayAvatar)
        val avatarVv = view.findViewById<TextureView?>(R.id.vvOverlayAvatar)
        overlayAvatarVideoHelper?.release()
        overlayAvatarVideoHelper = null
        if (avatarVv != null) {
            overlayAvatarVideoHelper = MutedLoopVideoHelper(this, avatarVv)
            val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(contactId)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(avatarIv, shape)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(avatarVv, shape)
        }
        val picPath  = contactPicPath(this, contactId)
        if (picPath.isNotBlank() && File(picPath).exists()) {
            if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(picPath)) {
                val cached = avatarFrameCache[picPath]
                    ?: com.silvershireinn.app.utils.ProfileMediaHelper.cachedVideoFrame(picPath)
                if (cached != null) {
                    avatarFrameCache[picPath] = cached
                    avatarIv.setImageBitmap(cached)
                    avatarIv.visibility = View.VISIBLE
                } else avatarFrameLoader.execute {
                    val b = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(picPath) ?: return@execute
                    Handler(Looper.getMainLooper()).post {
                        avatarFrameCache[picPath] = b
                        if (avatarIv.drawable == null) { avatarIv.setImageBitmap(b); avatarIv.visibility = View.VISIBLE }
                    }
                }
                overlayAvatarVideoHelper?.play(picPath, avatarIv)
            } else {
                overlayAvatarVideoHelper?.clear(avatarIv)
                avatarVv?.visibility = View.GONE
                avatarIv.visibility = View.VISIBLE
                avatarIv.setImageBitmap(com.silvershireinn.app.utils.VideoThumbs.decodeSmall(picPath))
            }
        } else {
            overlayAvatarVideoHelper?.clear(avatarIv)
            avatarVv?.visibility = View.GONE
            avatarIv.visibility = View.GONE
        }
        // Shaped backing + initials (shown when there is no picture), and their border
        view.findViewById<FrameLayout?>(R.id.overlayAvatarBox)?.let { box ->
            val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(contactId)
            box.background = com.silvershireinn.app.utils.AvatarShapeHelper.backgroundDrawable(contactId.toAvatarColor(), shape)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(box, shape)
            view.findViewById<TextView?>(R.id.tvOverlayInitials)?.text = (group?.second ?: PreferenceManager.displayNameFor(contactId)).toInitials()
            com.silvershireinn.app.utils.ProfileFrames.applyFloating(box, contactId)
        }


        // ── Search ────────────────────────────────────────────────────────────
        val searchBar   = view.findViewById<View>(R.id.overlaySearchBar)
        val etSearch    = view.findViewById<EditText>(R.id.etOverlaySearch)
        val btnSearch   = view.findViewById<ImageButton>(R.id.btnOverlaySearch)
        val btnSearchCl = view.findViewById<ImageButton>(R.id.btnOverlaySearchClose)

        fun closeSearch() {
            searchBar.visibility = View.GONE
            etSearch.setText("")
            overlaySearchQuery = ""
            overlayAdapter?.setFilter("")
        }

        btnSearch.setOnClickListener {
            searchBar.visibility = View.VISIBLE
            etSearch.requestFocus()
        }
        btnSearchCl.setOnClickListener { closeSearch() }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                overlaySearchQuery = s?.toString() ?: ""
                overlayAdapter?.setFilter(overlaySearchQuery)
            }
        })

        // ── Message list ──────────────────────────────────────────────────────
        val recycler = view.findViewById<RecyclerView>(R.id.overlayRecycler)
        recycler.layoutManager = LinearLayoutManager(themedCtx).apply { stackFromEnd = true }

        val adapter = com.silvershireinn.app.ui.chat.MessageAdapter(
            currentUser     = PreferenceManager.username,
            contactUsername = contactId,
            isGroupChat     = group != null,
            onDownload      = { msg ->
                ensureOverlayScope().launch {
                    msg.attachmentId?.let { fid ->
                        val name = msg.attachmentName ?: "file"
                        val mime = msg.attachmentMime ?: "application/octet-stream"
                        val f = FileRepository.ensureCached(applicationContext, fid, mime, name)
                        val ok = f != null && FileRepository.saveFileToGallery(applicationContext, f, name, mime)
                        // It saved silently before; say so either way.
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(applicationContext,
                                if (ok) "Saved to your phone: $name" else "Couldn't save $name",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onTapMedia  = { msg ->
                // Open media above the overlay in a new TYPE_APPLICATION_OVERLAY window
                // so the chat stays open and the image/video appears on top.
                val fileId = msg.attachmentId ?: return@MessageAdapter
                val url    = try { MediaAuth.fileUrl(fileId) } catch (_: Exception) { "" }
                val mime   = msg.attachmentMime ?: "*/*"
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    // The full viewer (zoom, swipe through, edit, share, save...), then back here
                    hideOverlayTemporarily(alsoBubble = true)
                    try {
                        com.silvershireinn.app.ui.media.MediaViewerActivity.start(this, fileId, msg.attachmentName ?: "media", mime, url,
                            msg.conversationId, fromOverlay = true)
                    } catch (_: Throwable) {
                        restoreOverlayIfNeeded()
                        showInOverlayMedia(fileId, msg.attachmentName ?: "file", mime, url)
                    }
                } else showInOverlayMedia(fileId, msg.attachmentName ?: "file", mime, url)
            },
            onLongPress = { msg -> showOverlayContextMenu(msg, contactId, group, displayName, themedCtx, view) },
            onReplyJump = { msg -> overlayJumpToReply(msg, recycler) },
            onApkDownload = { _ -> com.silvershireinn.app.utils.AppUpdateHelper.openDownload(applicationContext) },
            // Link taps: launch browser WHILE overlay is still visible (Android 10+
            // blocks startActivity from background — we must have a visible window),
            // then hide the overlay so the browser appears on top.
            onOpenUrl   = { url ->
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (_: Exception) {}
                hideOverlay()
            }
        )
        recycler.adapter = adapter
        overlayAdapter   = adapter

        // ── Swipe-right to reply (mirrors ChatActivity.setupSwipeToReply) ────────
        val replyBar      = view.findViewById<View>(R.id.overlayReplyBar)
        val tvReplyLabel  = view.findViewById<android.widget.TextView>(R.id.tvOverlayReplyLabel)
        val tvReplyPreview= view.findViewById<android.widget.TextView>(R.id.tvOverlayReplyPreview)
        val btnCancelReply= view.findViewById<android.widget.ImageButton>(R.id.btnOverlayCancelReply)
        val inputField    = view.findViewById<android.widget.EditText>(R.id.etOverlayInput)
        typedDrafts[contactId]?.let { t -> inputField.setText(t); inputField.setSelection(t.length) }   // what was being typed

        var replyToMessage: com.silvershireinn.app.model.Message? = null

        fun clearReply() {
            replyToMessage = null
            replyBar.visibility = View.GONE
        }

        fun startOverlayReply(msg: com.silvershireinn.app.model.Message) {
            if (msg.isDeleted || msg.isUploading || msg.attachmentMime == "silvershire/call") return
            replyToMessage = msg
            // Names and the message's own text (it showed usernames / the group id and nested quotes).
            val q = com.silvershireinn.app.ui.chat.ChatSender.quoteFor(msg).removePrefix("> ")
            tvReplyLabel.text   = "Replying to ${q.substringBefore(": ")}"
            tvReplyPreview.text = q.substringAfter(": ")
            replyBar.visibility = View.VISIBLE
            inputField.requestFocus()
        }

        btnCancelReply.setOnClickListener { clearReply() }

        val swipeCallback = com.silvershireinn.app.ui.chat.SwipeToReply(
            messageAt = { pos -> adapter.currentList.getOrNull(pos) },
            canReply = { m -> !(m.isDeleted || m.isUploading || m.attachmentMime == "silvershire/call") },
            onReply = { startOverlayReply(it) }
        )
        androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(recycler)

        // ── Notification / badge suppression ─────────────────────────────────────
        // Tell WebSocketService which contact's overlay is open so it can suppress
        // notifications and new chat-head badges for that conversation.
        overlayContactId = contactId
        overlayAtBottom  = true   // assume bottom until proven otherwise
        com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact = contactId
        overlaySetActiveChat = contactId
        // Cancel any existing notifications for this contact immediately on open
        com.silvershireinn.app.notifications.NotificationHelper
            .cancelMessageNotifications(applicationContext, contactId)

        // Scroll listener: track whether the user is near the bottom.
        // - At bottom → new messages are visible, suppress badge + notifications entirely.
        // - Scrolled up → new messages aren't visible; show badge so they notice,
        //   and cancel notification again when they scroll back down.
        val fabScrollBottom = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
            R.id.fabOverlayScrollBottom)
        val tvFabBadge = view.findViewById<android.widget.TextView?>(R.id.tvOverlayFabBadge)

        // Red "new messages below" counter on the overlay scroll FAB (same as main chat)
        var overlayFabUnread = 0
        fun updateOverlayFabBadge(count: Int) {
            overlayFabUnread = count
            tvFabBadge?.text = if (count > 99) "99+" else count.toString()
            tvFabBadge?.visibility = if (count > 0) View.VISIBLE else View.GONE
        }

        fabScrollBottom?.setOnClickListener {
            val last = adapter.itemCount - 1
            if (last >= 0) {
                recycler.stopScroll(); recycler.scrollToPosition(last)
                recycler.post { recycler.scrollBy(0, 1_000_000) }   // the very end, even of a tall last message
            }
            updateOverlayFabBadge(0)
        }

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val atBottom = com.silvershireinn.app.ui.chat.SwipeToReply.atVeryBottom(rv)
                overlayAtBottom = atBottom
                // Show/hide scroll-to-bottom FAB
                fabScrollBottom?.visibility = if (atBottom) View.GONE else View.VISIBLE
                if (atBottom) {
                    com.silvershireinn.app.notifications.NotificationHelper
                        .cancelMessageNotifications(applicationContext, contactId)
                    updateOverlayFabBadge(0)
                }
            }
        })

        // ── Keyboard / IME inset for TYPE_APPLICATION_OVERLAY ─────────────────
        // On Android R+, ask the WM to fit IME + system bars into this window.
        // Also listen for WindowInsets and GlobalLayout (gesture-nav fallback).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val pad = ime.coerceAtLeast(0)
            if (v.paddingBottom != pad) v.setPadding(0, 0, 0, pad)
            insets
        }
        view.viewTreeObserver.addOnGlobalLayoutListener {
            if (overlayView == null) return@addOnGlobalLayoutListener
            var pad = 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val rootInsets = view.rootWindowInsets
                pad = rootInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
            }
            if (pad == 0) {
                val visible = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(visible)
                val keyboardHeight = resources.displayMetrics.heightPixels - visible.bottom
                val threshold = (resources.displayMetrics.heightPixels * 0.12).toInt()
                // Also compare against real display metrics via location — helps gesture nav
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val bottomGap = resources.displayMetrics.heightPixels - (loc[1] + view.height - view.paddingBottom)
                val candidate = maxOf(keyboardHeight, if (bottomGap > threshold) bottomGap else 0)
                pad = if (candidate > threshold) candidate else 0
            }
            if (view.paddingBottom != pad) view.setPadding(0, 0, 0, pad)
        }

        // ── Apply all per-chat & global settings (mirrors ChatActivity.onResume) ──
        // 1. Chat background color
        val bgHex = com.silvershireinn.app.ui.chat.ChatSettingsActivity.getChatBgColor(applicationContext, contactId)
        if (bgHex != null) {
            try { recycler.setBackgroundColor(android.graphics.Color.parseColor(bgHex)) }
            catch (_: Exception) {}
        } else {
            recycler.setBackgroundResource(0)
        }
        // 2. Video / image background
        val ivChatBg  = view.findViewById<android.widget.ImageView>(R.id.ivOverlayChatImageBg)
        val svChatBg  = view.findViewById<android.view.SurfaceView>(R.id.svOverlayChatVideoBg)
        overlayVideoBgHelper = com.silvershireinn.app.utils.VideoBackgroundHelper(this, svChatBg)
        val videoPath = com.silvershireinn.app.utils.PreferenceManager.getChatVideoBg(contactId)
        val imagePath = com.silvershireinn.app.utils.PreferenceManager.getChatImageBg(contactId)
        when {
            videoPath != null -> {
                ivChatBg.visibility = View.GONE
                overlayVideoBgHelper?.applyPath(videoPath)
            }
            imagePath != null && java.io.File(imagePath).exists() -> {
                overlayVideoBgHelper?.applyPath(null)
                ivChatBg.visibility = View.VISIBLE
                ivChatBg.load(java.io.File(imagePath))
            }
            else -> {
                overlayVideoBgHelper?.applyPath(null)
                ivChatBg.visibility = View.GONE
            }
        }
        // 3. Accent color, font, UI text color
        com.silvershireinn.app.utils.AccentColorHelper.apply(view, com.silvershireinn.app.utils.PreferenceManager.accentColorInt())
        com.silvershireinn.app.utils.FontHelper.apply(view, com.silvershireinn.app.utils.PreferenceManager.fontFamily)
        com.silvershireinn.app.utils.AccentColorHelper.applyTextColor(view, com.silvershireinn.app.utils.PreferenceManager.uiTextColorInt())
        // 4. Per-chat message text color
        adapter.messageTextColor = com.silvershireinn.app.utils.PreferenceManager.chatTextColorInt(contactId)
        // 5. UI opacity on header and input bar (same as ChatActivity)
        val opacity = com.silvershireinn.app.utils.PreferenceManager.uiOpacity / 100f
        view.findViewById<View>(R.id.overlayHeader)?.alpha   = opacity
        view.findViewById<View>(R.id.overlayInputBar)?.alpha = opacity

        // Send read receipts when the overlay opens with messages on screen.
        // markAllRead clears local unread badge; receipts go out because the thread is visible.
        ensureOverlayScope().launch {
            val unread = messageRepo.markAllReadAndGetUnread(contactId)
            unread.forEach { sendOverlayReadReceipt(contactId, it) }
            val me = PreferenceManager.username
            if (me.isNotBlank()) WebSocketManager.sendConversationRead(me, contactId)
        }

        // Auto-scroll policy (user-specified, same as ChatActivity):
        //  1. First list emission after the overlay opens → always scroll once.
        //  2. After that, scroll only if the user is at/near the bottom, or the
        //     newest message is one WE just sent.
        //  3. New incoming message while scrolled up → red counter on the scroll
        //     FAB instead of yanking the view down.
        var overlayHasAutoScrolled = false
        var overlayLastNewestId: String? = null
        overlayJobs += ensureOverlayScope().launch(Dispatchers.Main) {
            // Live "Uploading 42%" on bubbles being sent from here.
            FileRepository.uploadProgress.collect { adapter.setUploadProgress(it) }
        }
        overlayJobs += ensureOverlayScope().launch {
            kotlinx.coroutines.flow.combine(messageRepo.getMessages(contactId), com.silvershireinn.app.utils.PeerLocks.locked) { l, lk ->
                com.silvershireinn.app.utils.PeerLocks.mask(contactId, l, lk, isGroup = group != null)
            }.collect { list ->
                withContext(Dispatchers.Main) {
                    if (overlayView != null && overlayContact == contactId) {
                        val newest = list.lastOrNull()
                        val newestIsMine = newest != null && newest.isSent &&
                            newest.messageId != overlayLastNewestId
                        val newIncomingCount =
                            if (overlayLastNewestId == null) 0
                            else list.takeLastWhile { it.messageId != overlayLastNewestId }.count { !it.isSent }
                        // PIXEL-perfect bottom check (same fix as ChatActivity): big media
                        // bubbles made the item-based overlayAtBottom flag report "at bottom"
                        // while the user was visibly scrolled up, causing yank-downs.
                        val pinnedToBottom = com.silvershireinn.app.ui.chat.SwipeToReply.atVeryBottom(recycler)
                        // Read receipts only when the newest messages are actually on screen
                        if (pinnedToBottom && overlaySeen() && list.any { !it.isSent && !it.isRead }) {
                            ensureOverlayScope().launch(Dispatchers.IO) {
                                val unread = messageRepo.markAllReadAndGetUnread(contactId)
                                if (unread.isEmpty()) return@launch
                                unread.forEach { sendOverlayReadReceipt(contactId, it) }
                                val me = PreferenceManager.username
                                if (me.isNotBlank()) WebSocketManager.sendConversationRead(me, contactId)
                            }
                        }
                        // Don't yank to the bottom on the user's own send while scrolled up,
                        // unless they've enabled "jump to bottom on send".
                        val shouldScroll = list.isNotEmpty() &&
                            (!overlayHasAutoScrolled || pinnedToBottom ||
                                (newestIsMine && com.silvershireinn.app.utils.PreferenceManager.jumpToBottomOnSend))
                        overlayLastNewestId = newest?.messageId

                        // Scroll after diff commits so the new item is rendered before we scroll
                        adapter.submitSearchableList(list, if (shouldScroll) Runnable {
                            overlayHasAutoScrolled = true
                            recycler.scrollToPosition(list.size - 1)
                            updateOverlayFabBadge(0)
                        } else Runnable {
                            if (newIncomingCount > 0) {
                                updateOverlayFabBadge(overlayFabUnread + newIncomingCount)
                                fabScrollBottom?.visibility = View.VISIBLE
                            }
                        })
                        if (overlaySearchQuery.isNotBlank()) adapter.setFilter(overlaySearchQuery)
                    }
                }
            }
        }

        // ── File drawer (toggled by the attach button in the input bar) ────────────
        val fileDrawer    = view.findViewById<View>(R.id.overlayFileDrawer)
        val filesRecycler = view.findViewById<RecyclerView?>(R.id.overlayFilesRecycler)
        val tvNoFiles     = view.findViewById<android.widget.TextView?>(R.id.tvOverlayNoFiles)
        val fileTabs      = view.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.overlayFileTabs)

        filesRecycler?.layoutManager = LinearLayoutManager(themedCtx)
        // Photos / videos open in the floating viewer (the app's viewer opened BEHIND this window).
        val filesAdapter = com.silvershireinn.app.ui.chat.SharedFilesAdapter(contactId, onOpenMedia = { f ->
            val url = try { MediaAuth.fileUrl(f.fileId) } catch (_: Exception) { "" }
            showInOverlayMedia(f.fileId, f.fileName, f.mimeType, url)
        }) { fileInfo ->
            ensureOverlayScope().launch {
                val f  = FileRepository.ensureCached(applicationContext, fileInfo.fileId, fileInfo.mimeType, fileInfo.fileName)
                val ok = f != null && FileRepository.saveFileToGallery(applicationContext, f, fileInfo.fileName, fileInfo.mimeType)
                withContext(Dispatchers.Main) {
                    val msg = if (ok) "Saved: ${fileInfo.fileName}" else "Download failed"
                    showOverlaySnackbar(msg)
                }
            }
        }
        filesRecycler?.adapter = filesAdapter

        // All files loaded from server; tab filter applied in-memory
        var allDrawerFiles: List<com.silvershireinn.app.repository.SharedFileInfo> = emptyList()
        var drawerTab = 0

        fun applyDrawerTab() {
            val filtered = when (drawerTab) {
                1    -> allDrawerFiles.filter { it.mimeType.startsWith("image/") }
                2    -> allDrawerFiles.filter { it.mimeType.startsWith("video/") }
                3    -> allDrawerFiles.filter { !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") }
                else -> allDrawerFiles
            }
            filesAdapter.submitList(filtered)
            val empty = filtered.isEmpty()
            tvNoFiles?.visibility     = if (empty) View.VISIBLE else View.GONE
            filesRecycler?.visibility = if (empty) View.GONE    else View.VISIBLE
        }

        // Add tabs once
        fileTabs?.let { tabs ->
            listOf("All", "Images", "Videos", "Files").forEach { name ->
                tabs.addTab(tabs.newTab().setText(name))
            }
            tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    drawerTab = tab?.position ?: 0
                    applyDrawerTab()
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            })
        }

        // "Send File" button — launches system file picker
        view.findViewById<android.view.View?>(R.id.btnOverlayPickFile)?.setOnClickListener {
            fileDrawer.visibility = View.GONE
            launchPickerActivity(
                ChatHeadPickerActivity.ACTION_PICK_FILE,
                contactId, group?.first, group?.second, displayName)
        }

        // ── Emoji grid ────────────────────────────────────────────────────────
        val emojiGrid = view.findViewById<LinearLayout>(R.id.overlayEmojiGrid)
        val btnEmoji  = view.findViewById<ImageButton>(R.id.btnOverlayEmoji)
        // inputField already declared in swipe-to-reply section above
        setupEmojiGrid(emojiGrid, inputField)
        btnEmoji.setOnClickListener {
            emojiGrid.visibility = if (emojiGrid.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // ── Input / send / mic ────────────────────────────────────────────────
        val recordingHint = view.findViewById<TextView>(R.id.tvOverlayRecordingHint)
        val sendBtn       = view.findViewById<ImageButton>(R.id.btnOverlaySend)
        val recordingBar  = view.findViewById<View>(R.id.overlayRecordingBar)
        val btnCancelRec  = view.findViewById<ImageButton>(R.id.btnOverlayCancelRecording)
        btnCancelRec?.setOnClickListener {
            cancelRecording()
            recordingBar?.visibility = View.GONE
            recordingHint.visibility = View.GONE
            btnCancelRec.visibility = View.GONE
            sendBtn.setImageResource(R.drawable.ic_mic)
        }

        fun doSend() {
            val rawText = inputField.text.toString().trim()
            val hadDraft = hasOverlayDraft()
            if (rawText.isBlank() && !hadDraft) return
            if (hadDraft) {
                if (group != null) uploadOverlayDraft(contactId, contactId, displayName)
                else uploadOverlayDraft(contactId, null, null)
            }
            if (rawText.isBlank()) return
            inputField.setText("")
            typedDrafts.remove(contactId)
            if (com.silvershireinn.app.utils.PanicLock.matchesLockWord(rawText)) { com.silvershireinn.app.utils.PanicLock.lockNow(this@ChatHeadService); return }

            // Prepend reply quote if replying, exactly like ChatActivity.sendMessage()
            // Reply quote with names (it showed usernames / the group id) - same as the app.
            val reply = replyToMessage
            val finalText = if (reply != null && !reply.isDeleted) {
                "${com.silvershireinn.app.ui.chat.ChatSender.quoteFor(reply)}\n\n$rawText"
            } else rawText
            clearReply()
            // Shared sender: encrypted, retried, resent later if still offline.
            com.silvershireinn.app.ui.chat.ChatSender.sendText(contactId, if (group != null) contactId else null, displayName, finalText)
        }

        // Switch icon: send when text present, mic when empty
        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            private var lastTypingSent = 0L
            override fun afterTextChanged(s: Editable?) {
                if (com.silvershireinn.app.utils.PanicLock.matchesLockWord(s)) {
                    s?.clear()
                    com.silvershireinn.app.utils.PanicLock.lockNow(this@ChatHeadService)
                    return
                }
                val hasText = !s.isNullOrBlank()
                sendBtn.setImageResource(if (hasText || hasOverlayDraft()) R.drawable.ic_send else R.drawable.ic_mic)
                // "typing..." for the friend, like the app (the floating chat never sent it).
                val now = System.currentTimeMillis()
                if (hasText && group == null && PreferenceManager.typingIndicators && now - lastTypingSent > 2_500L) {
                    lastTypingSent = now
                    WebSocketManager.sendTyping(PreferenceManager.username, contactId)
                }
            }
        })

        sendBtn.setOnClickListener {
            val text = inputField.text?.toString()?.trim() ?: ""
            if (text.isNotBlank() || hasOverlayDraft()) doSend()
            else if (voicePendingReview) sendPendingVoice()
            // Short taps when empty are handled by the touch listener (→ STT)
        }

        view.findViewById<ImageButton>(R.id.btnOverlayClearDraft)?.setOnClickListener {
            clearOverlayDraft()
        }

        // Paste into overlay compose -> draft (any attachment URI)
        androidx.core.view.ViewCompat.setOnReceiveContentListener(
            inputField,
            arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*")
        ) { _, payload ->
            val split = payload.partition { item -> item.uri != null }
            val received = split.first
            if (received != null) {
                val clipData = received.clip
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri ?: continue
                    val mime = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream"
                    val name = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"
                    setOverlayDraft(uri, mime, name)
                    break
                }
            }
            split.second
        }

        sendBtn.setOnTouchListener { _, event ->
            val hasText = !inputField.text.isNullOrBlank()
            if (hasText || hasOverlayDraft()) return@setOnTouchListener false   // let click handle send
            if (voicePendingReview) {
                if (event.action == MotionEvent.ACTION_UP) {
                    sendPendingVoice()
                    recordingBar?.visibility = View.GONE
                    btnCancelRec?.visibility = View.GONE
                }
                return@setOnTouchListener true
            }
            if (suppressUpAfterLock && event.action == MotionEvent.ACTION_UP) {
                suppressUpAfterLock = false
                return@setOnTouchListener true
            }
            if (recordingLocked && event.action == MotionEvent.ACTION_UP) {
                stopRecordingAndSend(contactId, group, displayName, recordingHint)
                recordingBar?.visibility = View.GONE
                btnCancelRec?.visibility = View.GONE
                return@setOnTouchListener true
            }
            if (recordingLocked) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val r = Runnable {
                        pendingVoiceContactId = contactId
                        pendingVoiceGroup = group
                        pendingVoiceDisplayName = displayName
                        pendingVoiceHint = recordingHint
                        startRecording(recordingHint)
                    }
                    recordingStartRunnable = r
                    recordingHandler.postDelayed(r, RECORD_START_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val r = recordingStartRunnable
                    recordingStartRunnable = null
                    recordingLockRunnable?.let { recordingHandler.removeCallbacks(it) }
                    recordingLockRunnable = null
                    if (r != null) {
                        recordingHandler.removeCallbacks(r)
                        if (!isRecording) {
                            hideOverlayTemporarily(alsoBubble = true)
                            launchPickerActivity(
                                ChatHeadPickerActivity.ACTION_STT,
                                contactId, group?.first, group?.second, displayName)
                        } else if (!recordingLocked) {
                            // Early release: pending review — never auto-send
                            stopRecordingToPendingReview(contactId, group, displayName, recordingHint)
                        }
                    } else if (isRecording && !recordingLocked) {
                        stopRecordingToPendingReview(contactId, group, displayName, recordingHint)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (recordingLocked || voicePendingReview) return@setOnTouchListener true
                    recordingStartRunnable?.let { recordingHandler.removeCallbacks(it) }
                    recordingStartRunnable = null
                    recordingLockRunnable?.let { recordingHandler.removeCallbacks(it) }
                    recordingLockRunnable = null
                    cancelRecording()
                    recordingBar?.visibility = View.GONE
                    recordingHint.visibility = View.GONE
                    btnCancelRec?.visibility = View.GONE
                    true
                }
                else -> false
            }
        }

        inputField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                androidx.core.view.ViewCompat.requestApplyInsets(view)
                // Nudge IME inset application on gesture-nav devices
                view.post { androidx.core.view.ViewCompat.requestApplyInsets(view) }
            }
        }

        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                doSend(); true
            } else false
        }

        // ── Attach / camera buttons ───────────────────────────────────────────
        // Attach: toggle the shared-files drawer (matches normal chat's btnAttach behaviour)
        view.findViewById<ImageButton>(R.id.btnOverlayAttach).setOnClickListener {
            if (fileDrawer.visibility == View.VISIBLE) {
                fileDrawer.visibility = View.GONE
            } else {
                fileDrawer.visibility = View.VISIBLE
                ensureOverlayScope().launch {
                    val files = FileRepository.getSharedFiles(contactId).filter {
                        if (group == null) !com.silvershireinn.app.utils.PeerLocks.isLocked(contactId)
                        else !com.silvershireinn.app.utils.PeerLocks.isLocked(it.uploadedBy)
                    }
                    withContext(Dispatchers.Main) {
                        allDrawerFiles = files
                        applyDrawerTab()
                    }
                }
            }
        }
        // Camera: show the 3-option chooser as a TYPE_APPLICATION_OVERLAY dialog so it appears
        // above the overlay window (regular Activity dialogs sit behind overlay windows).
        view.findViewById<ImageButton>(R.id.btnOverlayCamera).setOnClickListener {
            val dialog = android.app.AlertDialog.Builder(themedCtx)
                .setTitle("Add Media")
                .setItems(arrayOf("Take Photo", "Record Video", "Choose from Gallery")) { _, which ->
                    // Hide overlay AND the head so nothing floats over the camera/gallery
                    // (tapping a head left over the camera brought the chat back on top of it).
                    hideOverlayTemporarily(alsoBubble = true)
                    when (which) {
                        0 -> launchPickerActivity(ChatHeadPickerActivity.ACTION_TAKE_PHOTO,
                                contactId, group?.first, group?.second, displayName)
                        1 -> launchPickerActivity(ChatHeadPickerActivity.ACTION_TAKE_VIDEO,
                                contactId, group?.first, group?.second, displayName)
                        2 -> launchPickerActivity(ChatHeadPickerActivity.ACTION_PICK_GALLERY,
                                contactId, group?.first, group?.second, displayName)
                    }
                }
                .create()
            try {
                dialog.window?.setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                )
                dialog.show()
            } catch (e: Exception) {
                android.util.Log.e("ChatHead", "Camera chooser dialog failed: ${e.message}")
            }
        }

        // ── Back button via OverlayRootLayout.dispatchKeyEvent ───────────────────
        // setOnKeyListener on the root LinearLayout never fires when a child (EditText,
        // button) has focus. OverlayRootLayout overrides dispatchKeyEvent which IS
        // always called regardless of which child holds focus.
        val root = view as com.silvershireinn.app.ui.chathead.OverlayRootLayout
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.onBackPressed = {
            // Back closes whatever is open on top first (emoji, search), then the window.
            val fd = view.findViewById<View>(R.id.overlayFileDrawer)
            val eg = view.findViewById<View>(R.id.overlayEmojiGrid)
            val sb = view.findViewById<View>(R.id.overlaySearchBar)
            if (fd?.visibility == View.VISIBLE) {
                fd.visibility = View.GONE
                true
            } else if (eg?.visibility == View.VISIBLE) {
                eg.visibility = View.GONE
                true
            } else if (sb?.visibility == View.VISIBLE) {
                view.findViewById<View>(R.id.btnOverlaySearchClose)?.performClick()
                true
            } else {
                hideOverlay()
                true
            }
        }

        // ── Home / recents → hide overlay ─────────────────────────────────────
        // Primary: ACTION_CLOSE_SYSTEM_DIALOGS (works on all versions; system sends
        // it with reason "homekey" / "recentapps" even on Android 12+).
        val sysReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val reason = intent?.getStringExtra("reason") ?: ""
                if (reason == "homekey" || reason == "recentapps") {
                    hideOverlay()
                }
            }
        }
        val sysFilter = android.content.IntentFilter(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sysReceiver, sysFilter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sysReceiver, sysFilter)
        }
        overlaySystemReceiver = sysReceiver

        // ── Add overlay to WindowManager ──────────────────────────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,   // pictures are hardware bitmaps (software drawing crashed)
            PixelFormat.OPAQUE
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(
                    android.view.WindowInsets.Type.ime() or
                        android.view.WindowInsets.Type.systemBars()
                )
            }
        }

        try {
            // One window for the floating chat: switching chats only swaps what is inside it
            val host = overlayHost
            val reusing = host != null && host.isAttachedToWindow
            if (reusing) {
                host!!.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                overlayHost?.let { old -> try { wm.removeView(old) } catch (_: Exception) {} }
                // Keys (back) go straight to the chat inside, focused or not, as when it was the window
                val h = object : FrameLayout(themedCtx) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                        getChildAt(childCount - 1)?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
                }.apply { setBackgroundColor(Color.TRANSPARENT) }
                h.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                wm.addView(h, params)
                overlayHost = h
                overlayParams = params
            }
            overlayView   = view
            view.post { try { com.silvershireinn.app.utils.OrnateTheme.attachRoot(view) } catch (_: Throwable) {} }   // same theme as the app
            com.silvershireinn.app.SilvershireApp.overlayOpen = true   // chatting in the floating window = online
            // Re-add bubble on top so it stays visible and tappable over the overlay.
            // Keep still-frame ImageViews visible across the remove+re-add so video
            // TextureViews do not blank while their surfaces are recreated.
            val targetAlpha = PreferenceManager.chatHeadAlpha / 100f
            bubbleView?.let { bv ->
                ensureBubbleStillFramesVisible(bv)
                bv.alpha = targetAlpha
            }
            if (!reusing) android.view.Choreographer.getInstance().postFrameCallback {     // (same window: the heads are already above it)
                if (bubbleView == null) return@postFrameCallback
                bubbleView?.let { bv ->
                    ensureBubbleStillFramesVisible(bv)
                    try { wm.removeView(bv) } catch (_: IllegalArgumentException) {}
                    catch (_: IllegalStateException) {}
                    catch (_: Exception) {}
                    try {
                        if (bubbleParams != null) wm.addView(bv, bubbleParams)
                    } catch (_: IllegalStateException) {}
                    catch (_: IllegalArgumentException) {}
                    catch (_: Exception) {}
                    bv.alpha = targetAlpha
                    // Soft-restart videos only; still frames already on ImageViews (no initials flash)
                    softRestartBubbleVideos()
                }
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("ChatHead", "Overlay addView IllegalState: ${e.message}")
            overlayView    = null
            overlayContact = null
            overlayGroup   = null
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("ChatHead", "Overlay addView IllegalArgument: ${e.message}")
            overlayView    = null
            overlayContact = null
            overlayGroup   = null
        } catch (e: Exception) {
            android.util.Log.e("ChatHead", "Overlay failed: ${e.message}")
            overlayView    = null
            overlayContact = null
            overlayGroup   = null
        } finally {
            overlayOpening = false
        }
    }

    // ── In-overlay media viewer ───────────────────────────────────────────────────
    /**
     * Shows an image or video above the chat overlay in its own TYPE_APPLICATION_OVERLAY
     * window. This keeps the chat overlay intact (no close/reopen needed).
     * For images: ZoomableImageView loaded via Coil.
     * For videos: VideoView with tap-to-pause, no MediaController needed.
     */
    @Suppress("DEPRECATION")
    private fun showInOverlayMedia(
        fileId: String, fileName: String, mimeType: String, url: String
    ) {
        // Clean up any previous media overlay
        mediaOverlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        mediaOverlayView = null

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val ctx = android.view.ContextThemeWrapper(applicationContext, R.style.Theme_Silvershire)
        // Anonymous subclass so we can intercept KEYCODE_BACK without a separate layout/class.
        val container = object : android.widget.FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.action == android.view.KeyEvent.ACTION_UP) {
                    mediaOverlayView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
                    mediaOverlayView = null
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.isFocusableInTouchMode = true
        container.setBackgroundColor(android.graphics.Color.BLACK)

        val isVideo = mimeType.startsWith("video/")

        if (isVideo) {
            // Use the pre-cached file if present (instant); otherwise stream from the URL.
            // Same cache name the app uses (it often missed a copy that was already downloaded).
            val cacheFile = File(cacheDir, "viewer_$fileId${FileRepository.extFor(mimeType, fileName)}")

            val videoView = android.widget.VideoView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER
                )
            }
            container.addView(videoView)

            // Loading spinner — hidden once the video is prepared.
            val loading = android.widget.ProgressBar(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            }
            container.addView(loading)

            // Playback controls: elapsed | seek bar | duration, pinned above the filename label.
            val dens  = resources.displayMetrics.density
            val tvPos = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 12f; text = "0:00" }
            val tvDur = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 12f; text = "0:00" }
            val seek  = android.widget.SeekBar(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = (10 * dens).toInt(); rightMargin = (10 * dens).toInt()
                }
            }
            val controls = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((14 * dens).toInt(), (6 * dens).toInt(), (14 * dens).toInt(), (6 * dens).toInt())
                setBackgroundColor(0xAA000000.toInt())
                visibility = View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM
                ).apply { bottomMargin = (40 * dens).toInt() }
                addView(tvPos); addView(seek); addView(tvDur)
            }
            container.addView(controls)

            fun fmt(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
            val ctrlHandler = Handler(Looper.getMainLooper())
            var userSeeking = false
            val updater = object : Runnable {
                override fun run() {
                    if (mediaOverlayView !== container) return   // viewer closed/replaced → stop
                    try {
                        if (!userSeeking && videoView.isPlaying) {
                            seek.progress = videoView.currentPosition
                            tvPos.text = fmt(videoView.currentPosition)
                        }
                    } catch (_: Exception) {}
                    ctrlHandler.postDelayed(this, 500)
                }
            }
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) tvPos.text = fmt(p)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    userSeeking = false; try { videoView.seekTo(sb.progress) } catch (_: Exception) {}
                }
            })

            if (cacheFile.exists() && cacheFile.length() > 0L) videoView.setVideoPath(cacheFile.absolutePath)
            else if (com.silvershireinn.app.utils.MediaKeys.isEncrypted(fileId)) {
                // End-to-end encrypted: download + decrypt first (VideoView can't decrypt).
                ensureOverlayScope().launch {
                    val f = FileRepository.ensureCached(applicationContext, fileId, mimeType, fileName)
                    withContext(Dispatchers.Main) {
                        if (f != null) videoView.setVideoPath(f.absolutePath)
                        else { loading.visibility = View.GONE; showOverlaySnackbar("Could not open the video") }
                    }
                }
            }
            else videoView.setVideoURI(android.net.Uri.parse(url), MediaAuth.headers())
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                loading.visibility = View.GONE
                controls.visibility = View.VISIBLE
                seek.max = videoView.duration.coerceAtLeast(0)
                tvDur.text = fmt(videoView.duration)
                videoView.start()
                ctrlHandler.post(updater)
            }
            videoView.setOnCompletionListener {
                seek.progress = videoView.duration
                tvPos.text = fmt(videoView.duration)
            }
            videoView.setOnErrorListener { _, _, _ -> loading.visibility = View.GONE; true }
            videoView.setOnClickListener { if (videoView.isPlaying) videoView.pause() else videoView.start() }
        } else {
            val imageView = com.silvershireinn.app.ui.media.ZoomableImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER
                )
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            container.addView(imageView)
            imageView.load(url) { MediaAuth.headers().forEach { (k, v) -> addHeader(k, v) } }
        }

        // File name label at the bottom
        val tvName = android.widget.TextView(ctx).apply {
            text = fileName
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xAA000000.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        }
        container.addView(tvName)

        // Button row — top-right: download + close
        val btnPx  = (48 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        val statusBar = try {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        } catch (_: Exception) { 0 }

        // Close button — top-right corner
        val closeBtn = android.widget.ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(android.graphics.Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(btnPx, btnPx).apply {
                gravity     = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin   = statusBar + margin
                rightMargin = margin
            }
        }
        container.addView(closeBtn)
        closeBtn.setOnClickListener {
            mediaOverlayView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
            mediaOverlayView = null
        }

        // Download button — just to the left of the close button
        val downloadBtn = android.widget.ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_download)
            setColorFilter(android.graphics.Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            layoutParams = android.widget.FrameLayout.LayoutParams(btnPx, btnPx).apply {
                gravity     = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin   = statusBar + margin
                rightMargin = margin + btnPx + margin   // left of close button
            }
        }
        // Snackbar that lives inside the media overlay (above video/image content)
        val mediaSnackbar = android.widget.TextView(ctx).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xCC1A1A1A.toInt())
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            ).apply {
                // Sit above the filename label (which is ~40dp tall)
                bottomMargin = (40 * resources.displayMetrics.density).toInt()
            }
        }
        container.addView(mediaSnackbar)

        fun showMediaSnackbar(message: String) {
            mediaSnackbar.text = message
            mediaSnackbar.visibility = View.VISIBLE
            mediaSnackbar.alpha = 1f
            Handler(Looper.getMainLooper()).postDelayed({
                mediaSnackbar.animate().alpha(0f).setDuration(300).withEndAction {
                    mediaSnackbar.visibility = View.GONE
                    mediaSnackbar.alpha = 1f
                }.start()
            }, 2500)
        }

        container.addView(downloadBtn)
        downloadBtn.setOnClickListener {
            ensureOverlayScope().launch {
                val f  = FileRepository.ensureCached(applicationContext, fileId, mimeType, fileName)
                val ok = f != null && FileRepository.saveFileToGallery(applicationContext, f, fileName, mimeType)
                withContext(Dispatchers.Main) {
                    showMediaSnackbar(if (ok) "Saved: $fileName" else "Download failed")
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // No FLAG_NOT_FOCUSABLE so the window receives key events (back button)
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,   // pictures are hardware bitmaps (software drawing crashed)
            PixelFormat.OPAQUE
        )

        try {
            wm.addView(container, params)
            mediaOverlayView = container
            // Request focus so KEYCODE_BACK reaches dispatchKeyEvent
            container.requestFocus()
        } catch (e: Exception) {
            android.util.Log.e("ChatHead", "Media overlay failed: ${e.message}")
        }
    }

    // ── Overlay message context menu (mirrors ChatActivity.showMessageContextMenu) ──

    private fun showOverlayContextMenu(
        msg: com.silvershireinn.app.model.Message,
        contactId: String,
        group: Pair<String, String>?,
        displayName: String,
        ctx: android.content.Context,
        overlayRootView: View
    ) {
        val options = mutableListOf<String>()

        val isCall = msg.attachmentMime == "silvershire/call"
        if (msg.isUploading) {
            options += "✕  Cancel upload"
        } else {
            if (!msg.isDeleted && !isCall) options += "↩  Reply"
            if (!msg.isAttachment && !msg.isDeleted) options += "📋  Copy text"
            if (msg.isAttachment && !msg.isDeleted && !isCall) {
                val mime = msg.attachmentMime ?: ""
                if (mime.startsWith("image/") || mime.startsWith("video/"))
                    options += "🖼  Open"
                options += "⬇  Save to phone"
                options += "📋  " + com.silvershireinn.app.utils.ClipCopy.label(mime)
            }
            if (!msg.isDeleted) {
                options += "🗑  Delete for me"
                if (msg.isSent && !isCall) options += "🗑  Delete for everyone"
            }
            if (msg.isSent && msg.isRead) options += "✓✓  Seen"
        }

        // Use AlertDialog.Builder (not Material) — more reliable from a Service context.
        // Always set TYPE_APPLICATION_OVERLAY on the window before show() so the dialog
        // appears above the overlay window rather than failing with a null token.
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle(null)
            .setItems(options.map { com.silvershireinn.app.utils.ThemeIcons.iconize(ctx, it) }.toTypedArray()) { _, which ->
                when (options[which]) {
                    "✕  Cancel upload" -> {
                        // Cancels right here now (it used to open the full chat to do it).
                        com.silvershireinn.app.ui.chat.ChatSender.cancel(msg.messageId)
                    }
                    "↩  Reply" -> {
                        // Insert a reply prefix into the input field (names, no nested quotes)
                        val prefix  = "${com.silvershireinn.app.ui.chat.ChatSender.quoteFor(msg)}\n\n"
                        val et = overlayRootView.findViewById<EditText>(R.id.etOverlayInput)
                        et?.setText(prefix)
                        et?.setSelection(prefix.length)
                    }
                    "📋  Copy text" -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.encryptedPayload))
                        showOverlaySnackbar("Copied")
                    }
                    "🖼  Open" -> {
                        // In the floating viewer (the app's viewer opened behind this window).
                        val fileId = msg.attachmentId ?: return@setItems
                        val url    = try { MediaAuth.fileUrl(fileId) } catch (_: Exception) { "" }
                        showInOverlayMedia(fileId, msg.attachmentName ?: "file", msg.attachmentMime ?: "*/*", url)
                    }
                    "📋  Copy picture", "📋  Copy video", "📋  Copy file", "📋  Copy" -> {
                        ensureOverlayScope().launch {
                            val dName = msg.attachmentName ?: "file"
                            val dMime = msg.attachmentMime ?: "application/octet-stream"
                            val f     = FileRepository.ensureCached(applicationContext, msg.attachmentId ?: return@launch, dMime, dName)
                            withContext(Dispatchers.Main) {
                                val ok = f != null && com.silvershireinn.app.utils.ClipCopy.file(applicationContext, f, dName, dMime)
                                showOverlaySnackbar(if (!ok) "Copy failed" else if (dMime.startsWith("image/")) "Picture copied" else "Copied")
                            }
                        }
                    }
                    "⬇  Save to phone" -> {
                        ensureOverlayScope().launch {
                            val dName = msg.attachmentName ?: "file"
                            val dMime = msg.attachmentMime ?: "application/octet-stream"
                            val f     = FileRepository.ensureCached(applicationContext, msg.attachmentId ?: return@launch, dMime, dName)
                            val ok    = f != null && FileRepository.saveFileToGallery(applicationContext, f, dName, dMime)
                            withContext(Dispatchers.Main) {
                                showOverlaySnackbar(if (ok) "Saved: ${msg.attachmentName}" else "Download failed")
                            }
                        }
                    }
                    "🗑  Delete for me" -> {
                        ensureOverlayScope().launch { messageRepo.markDeleted(msg.messageId) }
                    }
                    "🗑  Delete for everyone" -> {
                        try {
                            val confirm = android.app.AlertDialog.Builder(ctx)
                                .setTitle("Delete for everyone?")
                                .setMessage("This message will be deleted for all participants.")
                                .setPositiveButton("Delete") { _, _ ->
                                    ensureOverlayScope().launch {
                                        messageRepo.markDeleted(msg.messageId)
                                        val json = org.json.JSONObject().apply {
                                            put("type", "delete_message")
                                            put("to", contactId)
                                            put("messageId", msg.messageId)
                                            put("deleteForEveryone", true)
                                        }
                                        WebSocketManager.sendRaw(json.toString())
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .create()
                            confirm.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            confirm.show()
                        } catch (e: Exception) {
                            android.util.Log.e("ChatHead", "Confirm dialog failed: ${e.message}")
                            showOverlaySnackbar("Open full chat to delete for everyone")
                        }
                    }
                    "✓✓  Seen" -> showOverlaySnackbar("Read by $displayName")
                }
            }
            .create()
        try {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("ChatHead", "Context menu dialog failed: ${e.message}")
            showOverlaySnackbar("Long-press menu unavailable")
        }
    }

    // ── Emoji grid setup ──────────────────────────────────────────────────────

    private fun setupEmojiGrid(grid: LinearLayout, target: EditText) {
        val emojis = listOf(
            "😀","😂","😍","😎","🥰","😭","😤","🤔","😴","🤩",
            "👍","👎","❤️","🔥","✨","🎉","👏","🙌","💪","🤝",
            "😊","😢","😡","😱","🤗","😏","🙄","😬","🤭","😇",
            "🐶","🐱","🐻","🦊","🐼","🦋","🌸","🌍","⭐","🌙",
            "🍕","🍔","🍣","☕","🍺","🎮","📱","💻","🎵","🎶",
            "🚀","💯","✅","❌","⚡","💥","🎯","👀","💀","🙏"
        )
        grid.removeAllViews()
        val cellSizePx = (40 * resources.displayMetrics.density).toInt()
        val cols = 10
        // Build rows of `cols` emojis each
        emojis.chunked(cols).forEach { rowEmojis ->
            val row = LinearLayout(grid.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowEmojis.forEach { emoji ->
                val tv = TextView(grid.context).apply {
                    text     = emoji
                    textSize = 20f
                    gravity  = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(cellSizePx, cellSizePx)
                    setOnClickListener {
                        val start = target.selectionStart.coerceAtLeast(0)
                        val end   = target.selectionEnd.coerceAtLeast(start)
                        target.text?.replace(start, end, emoji, 0, emoji.length)
                        grid.visibility = View.GONE
                    }
                }
                row.addView(tv)
            }
            grid.addView(row)
        }
    }

    // ── Launch ChatHeadPickerActivity ─────────────────────────────────────────

    private fun launchPickerActivity(
        action: String,
        contactId: String,
        groupId: String?,
        groupName: String?,
        displayName: String
    ) {
        startActivity(Intent(this, ChatHeadPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ChatHeadPickerActivity.EXTRA_ACTION,       action)
            putExtra(ChatHeadPickerActivity.EXTRA_CONTACT_ID,   contactId)
            putExtra(ChatHeadPickerActivity.EXTRA_GROUP_ID,     groupId)
            putExtra(ChatHeadPickerActivity.EXTRA_GROUP_NAME,   groupName)
            putExtra(ChatHeadPickerActivity.EXTRA_DISPLAY_NAME, displayName)
        })
    }

    // ── Voice recording (MediaRecorder in Service context) ────────────────────

    @Suppress("MissingPermission", "DEPRECATION")
    private fun startRecording(hintView: TextView) {
        if (isRecording) return
        // No microphone permission yet: ask through the picker screen (a service can't show
        // the prompt). It used to fail silently with nothing on screen.
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val cid = pendingVoiceContactId ?: overlayContact ?: return
            hideOverlayTemporarily(alsoBubble = true)
            launchPickerActivity(ChatHeadPickerActivity.ACTION_MIC_PERMISSION, cid,
                pendingVoiceGroup?.first, pendingVoiceGroup?.second, pendingVoiceDisplayName ?: cid)
            return
        }
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        recordingFile = file
        try {
            mediaRecorder = com.silvershireinn.app.utils.VoiceRecorder.start(this, file)
            ?: throw IllegalStateException("Microphone unavailable")
            isRecording       = true
            recordingLocked   = false
            voicePendingReview = false
            suppressUpAfterLock = false
            recordingStartTime = System.currentTimeMillis()
            hintView.visibility = View.VISIBLE
            hintView.text = "Recording... keep holding to lock"
            overlayView?.findViewById<View>(R.id.overlayRecordingBar)?.visibility = View.VISIBLE
            overlayView?.findViewById<View>(R.id.btnOverlayCancelRecording)?.visibility = View.GONE
            recordingLockRunnable?.let { recordingHandler.removeCallbacks(it) }
            val lockR = Runnable {
                if (isRecording && !recordingLocked) {
                    recordingLocked = true
                    suppressUpAfterLock = true
                    hintView.text = "Locked - stop to listen, send, or trash"
                    overlayView?.findViewById<View>(R.id.btnOverlayCancelRecording)?.visibility = View.VISIBLE
                    // Stop button: end the recording and review it before sending.
                    overlayView?.findViewById<ImageButton>(R.id.btnOverlayPlayPendingRecording)?.let { btn ->
                        btn.visibility = View.VISIBLE
                        btn.setImageResource(R.drawable.ic_stop)
                        btn.setOnClickListener {
                            val cid = pendingVoiceContactId ?: return@setOnClickListener
                            stopRecordingToPendingReview(cid, pendingVoiceGroup, pendingVoiceDisplayName ?: cid, hintView)
                        }
                    }
                    overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)
                        ?.setImageResource(R.drawable.ic_send)
                }
            }
            recordingLockRunnable = lockR
            recordingHandler.postDelayed(lockR, RECORD_LOCK_MS)
        } catch (e: Exception) {
            android.util.Log.e("ChatHead", "MediaRecorder start failed: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            recordingFile = null
            showOverlaySnackbar("Microphone is busy or unavailable")
        }
    }

    private fun stopRecordingAndSend(
        contactId: String,
        group: Pair<String, String>?,
        displayName: String,
        hintView: TextView
    ) {
        if (!isRecording) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val recordedOk = com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
        mediaRecorder = null
        isRecording   = false
        recordingLocked = false
        voicePendingReview = false
        suppressUpAfterLock = false
        recordingLockRunnable?.let { recordingHandler.removeCallbacks(it) }
        recordingLockRunnable = null
        clearPendingVoiceContext()
        hintView.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.overlayRecordingBar)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.btnOverlayCancelRecording)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.waveformOverlayPending)?.visibility = View.GONE
        overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)?.setImageResource(R.drawable.ic_mic)
        val file = recordingFile ?: return
        recordingFile = null
        if (elapsed < 600L || !recordedOk) {
            file.delete()
            if (elapsed >= 600L) showOverlaySnackbar("Recording failed. Try again")
            return
        }
        sendVoiceFile(file, contactId, group, displayName)
    }

    /**
     * Upload a recorded voice note through the shared sender (bubble with "Sending 42%",
     * retries, cancel). Both overlay voice paths had their own upload code with none of that,
     * and a failed upload simply disappeared.
     */
    private fun sendVoiceFile(file: File, contactId: String, group: Pair<String, String>?, displayName: String) {
        ensureOverlayScope().launch {
            try {
                val bytes = file.readBytes()
                val name  = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
                withContext(Dispatchers.Main) {
                    com.silvershireinn.app.ui.chat.ChatSender.sendBytes(contactId, group?.first, group?.second ?: displayName,
                        name, "audio/mp4", bytes) { msg -> showOverlaySnackbar(msg) }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatHead", "Voice read failed: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    /** Stop capture but keep clip for trash/send (early release before lock). */
    private fun stopRecordingToPendingReview(
        contactId: String,
        group: Pair<String, String>?,
        displayName: String,
        hintView: TextView
    ) {
        if (!isRecording) return
        recordingLockRunnable?.let { recordingHandler.removeCallbacks(it) }
        recordingLockRunnable = null
        val recordedOk = com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
        mediaRecorder = null
        isRecording = false
        if (!recordedOk || System.currentTimeMillis() - recordingStartTime < 600L) {
            // A quick tap (or a failed recording) has nothing to review.
            cancelRecording()
            hintView.visibility = View.GONE
            overlayView?.findViewById<View>(R.id.overlayRecordingBar)?.visibility = View.GONE
            overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)?.setImageResource(R.drawable.ic_mic)
            showOverlaySnackbar("Hold the mic to record a voice message")
            return
        }
        recordingLocked = false
        suppressUpAfterLock = false
        voicePendingReview = true
        pendingVoiceContactId = contactId
        pendingVoiceGroup = group
        pendingVoiceDisplayName = displayName
        pendingVoiceHint = hintView
        hintView.visibility = View.VISIBLE
        hintView.text = "Voice ready - play, send or trash"
        overlayView?.findViewById<View>(R.id.overlayRecordingBar)?.visibility = View.VISIBLE
        overlayView?.findViewById<View>(R.id.btnOverlayCancelRecording)?.visibility = View.VISIBLE
        overlayView?.findViewById<com.silvershireinn.app.ui.chat.WaveformView>(R.id.waveformOverlayPending)?.let { wave ->
            wave.visibility = View.VISIBLE
            wave.setSeed("pending_" + (recordingFile?.name ?: "voice"))
            wave.setProgressFraction(0f)
            wave.onSeekStop = { frac ->
                try {
                    pendingVoicePlayer?.let { pl ->
                        val dur = pl.duration
                        if (dur > 0) pl.seekTo((dur * frac).toInt())
                    }
                } catch (_: Exception) {}
            }
            hintView.visibility = View.GONE
        }
        overlayView?.findViewById<ImageButton>(R.id.btnOverlayPlayPendingRecording)?.let { btn ->
            btn.visibility = View.VISIBLE
            btn.setImageResource(R.drawable.ic_play)
            btn.setOnClickListener { togglePendingVoicePlayback(btn) }
        }
        overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)?.setImageResource(R.drawable.ic_send)
    }

    /** Upload a pending-review voice clip (recording already stopped). */
    private fun sendPendingVoice() {
        if (!voicePendingReview) return
        stopPendingVoicePlayback()
        val contactId = pendingVoiceContactId ?: return
        val group = pendingVoiceGroup
        val displayName = pendingVoiceDisplayName ?: contactId
        val hintView = pendingVoiceHint
        val file = recordingFile
        voicePendingReview = false
        clearPendingVoiceContext()
        recordingFile = null
        hintView?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.overlayRecordingBar)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.btnOverlayCancelRecording)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.waveformOverlayPending)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.btnOverlayPlayPendingRecording)?.visibility = View.GONE
        overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)?.setImageResource(R.drawable.ic_mic)
        if (file == null || !file.exists()) {
            file?.delete()
            return
        }
        sendVoiceFile(file, contactId, group, displayName)
    }

    private fun startPendingWaveformTicker() {
        stopPendingWaveformTicker()
        val r = object : Runnable {
            override fun run() {
                try {
                    val pl = pendingVoicePlayer ?: return
                    if (!pl.isPlaying) return
                    val dur = pl.duration.coerceAtLeast(1)
                    overlayView?.findViewById<com.silvershireinn.app.ui.chat.WaveformView>(R.id.waveformOverlayPending)
                        ?.setProgressFraction(pl.currentPosition.toFloat() / dur)
                    recordingHandler.postDelayed(this, 50L)
                } catch (_: Exception) {}
            }
        }
        pendingWaveformTicker = r
        recordingHandler.post(r)
    }

    private fun stopPendingWaveformTicker() {
        pendingWaveformTicker?.let { recordingHandler.removeCallbacks(it) }
        pendingWaveformTicker = null
    }

    private fun stopPendingVoicePlayback() {
        stopPendingWaveformTicker()
        try { pendingVoicePlayer?.stop() } catch (_: Exception) {}
        try { pendingVoicePlayer?.release() } catch (_: Exception) {}
        pendingVoicePlayer = null
        // Keep play visible while still in pending review; cancel/send hide it.
        overlayView?.findViewById<ImageButton>(R.id.btnOverlayPlayPendingRecording)?.setImageResource(R.drawable.ic_play)
        overlayView?.findViewById<com.silvershireinn.app.ui.chat.WaveformView>(R.id.waveformOverlayPending)?.setProgressFraction(0f)
    }

    private fun togglePendingVoicePlayback(btn: ImageButton) {
        val file = recordingFile ?: return
        val existing = pendingVoicePlayer
        if (existing != null) {
            try {
                if (existing.isPlaying) {
                    existing.pause()
                    btn.setImageResource(R.drawable.ic_play)
                    return
                } else {
                    existing.start()
                    btn.setImageResource(R.drawable.ic_pause)
                    startPendingWaveformTicker()
                    return
                }
            } catch (_: Exception) {
                stopPendingVoicePlayback()
            }
        }
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    btn.setImageResource(R.drawable.ic_play)
                    stopPendingWaveformTicker()
                    overlayView?.findViewById<com.silvershireinn.app.ui.chat.WaveformView>(R.id.waveformOverlayPending)?.setProgressFraction(1f)
                }
                prepare()
                start()
            }
            pendingVoicePlayer = mp
            btn.setImageResource(R.drawable.ic_pause)
            startPendingWaveformTicker()
        } catch (e: Exception) {
            android.util.Log.e("ChatHead", "Pending voice play failed: ${e.message}")
            stopPendingVoicePlayback()
        }
    }

    private fun clearPendingVoiceContext() {
        pendingVoiceContactId = null
        pendingVoiceGroup = null
        pendingVoiceDisplayName = null
        pendingVoiceHint = null
    }

    private fun cancelRecording() {
        stopPendingVoicePlayback()
        recordingStartRunnable?.let { recordingHandler.removeCallbacks(it) }
        recordingStartRunnable = null
        recordingLockRunnable?.let { recordingHandler.removeCallbacks(it) }
        recordingLockRunnable = null
        recordingLocked = false
        suppressUpAfterLock = false
        voicePendingReview = false
        clearPendingVoiceContext()
        if (isRecording) {
            com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
            mediaRecorder = null
            isRecording   = false
        }
        recordingFile?.delete()
        recordingFile = null
        overlayView?.findViewById<View>(R.id.overlayRecordingBar)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.btnOverlayCancelRecording)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.waveformOverlayPending)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.btnOverlayPlayPendingRecording)?.visibility = View.GONE
        overlayView?.findViewById<ImageButton>(R.id.btnOverlaySend)?.setImageResource(R.drawable.ic_mic)

    }

    /** Tapped a reply quote in the overlay → scroll to the original message and flash it. */
    private fun overlayJumpToReply(reply: com.silvershireinn.app.model.Message, recycler: RecyclerView) {
        val raw = reply.encryptedPayload
        val sep = raw.indexOf("\n\n")
        if (!raw.startsWith("> ") || sep <= 0) return
        val quote = raw.substring(0, sep).removePrefix("> ")
        val ci = quote.indexOf(": ")
        val preview = (if (ci >= 0) quote.substring(ci + 2) else quote).trim()
        if (preview.isEmpty()) return
        val list = overlayAdapter?.currentList ?: return
        val replyIdx = list.indexOfFirst { it.messageId == reply.messageId }
        val end = if (replyIdx >= 0) replyIdx else list.size
        for (i in end - 1 downTo 0) {
            val m = list[i]
            if (m.isDeleted) continue
            val content = if (m.isAttachment) "[file] ${m.attachmentName ?: ""}" else m.encryptedPayload
            if (content.startsWith(preview) || preview.startsWith(content) || content == preview) {
                recycler.smoothScrollToPosition(i)
                recycler.postDelayed({
                    val v = recycler.findViewHolderForAdapterPosition(i)?.itemView ?: return@postDelayed
                    v.animate().alpha(0.35f).setDuration(180).withEndAction {
                        v.animate().alpha(1f).setDuration(450).start()
                    }.start()
                }, 350)
                return
            }
        }
        showOverlaySnackbar("Original message not found")
    }

    /** Send a read receipt from the overlay (mirrors ChatViewModel.sendReadReceipt). */
    private fun sendOverlayReadReceipt(contactId: String, messageId: String) {
        if (!com.silvershireinn.app.utils.PreferenceManager.readReceipts) return
        WebSocketManager.sendReadReceipt(
            from      = PreferenceManager.username,
            to        = contactId,
            messageId = messageId
        )
    }

    /** Show a brief message inside the overlay instead of a system Toast (which renders behind it). */
    private fun showOverlaySnackbar(message: String) {
        val tv = overlayView?.findViewById<android.widget.TextView>(R.id.tvOverlaySnackbar) ?: return
        tv.text = message
        tv.visibility = View.VISIBLE
        tv.animate().alpha(1f).setDuration(150).start()
        Handler(Looper.getMainLooper()).postDelayed({
            tv.animate().alpha(0f).setDuration(300).withEndAction {
                tv.visibility = View.GONE
                tv.alpha = 1f
            }.start()
        }, 2500)
    }

    /**
     * Temporarily remove the overlay from the WindowManager so a picker Activity
     * (camera, gallery, video) can render on top of it. All state is preserved;
     * call [restoreOverlayIfNeeded] to put it back.
     */

    private fun setupOverlayStackSwitcher(view: View, currentId: String) {
        val scroll = view.findViewById<android.widget.HorizontalScrollView?>(R.id.overlayStackSwitcherScroll)
        val row = view.findViewById<LinearLayout?>(R.id.overlayStackSwitcher) ?: return
        row.removeAllViews()
        releaseSwitcherVideos()
        val senders = activeSenders.keys.toList()
        // Always show the top switcher row while overlay is open (including a single head).
        if (senders.isEmpty()) {
            scroll?.visibility = View.GONE
            return
        }
        scroll?.visibility = View.VISIBLE
        val op = PreferenceManager.stackSwitcherOpacity.coerceIn(0, 100)
        val accent = PreferenceManager.accentColorInt()
        val a = (op * 255 / 100)
        scroll?.setBackgroundColor(android.graphics.Color.argb(a, Color.red(accent)/4, Color.green(accent)/4, Color.blue(accent)/4))
        val size = (48 * dp).toInt()  // >=48dp hit target; circle fills chip
        val margin = (6 * dp).toInt()
        for (user in senders.asReversed()) { // newest first
            val isCurrent = user == currentId
            val avatarShape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(user)
            val avatar = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = margin
                }
                background = com.silvershireinn.app.utils.AvatarShapeHelper.backgroundDrawable(
                    user.toAvatarColor(),
                    avatarShape
                )
                // Not clipped as a whole (the picture layers clip themselves) so the unread
                // badge can sit in the corner.
                alpha = if (isCurrent) 1f else 0.62f
                foreground = if (isCurrent) selectionRing(avatarShape) else null
                contentDescription = switcherName(user) + if (isCurrent) " (open)" else ""
                setOnClickListener {
                    if (user == overlayContact) hideOverlay()
                    else switchOverlayTo(user)
                }
            }
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(this, avatarShape)
            }
            val tv = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                setTextColor(resources.getColor(R.color.color_background, null))
                textSize = 12f
                text = switcherName(user).toInitials()   // groups: their name, not the id
            }
            val vv = TextureView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }
            var chipVideo: MutedLoopVideoHelper? = null
            val picPath = contactPicPath(this, user)
            if (picPath.isNotBlank()) {
                val pf = File(picPath)
                if (pf.exists()) {
                    if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(picPath)) {
                        val cached = avatarFrameCache[picPath]
                        if (cached != null) { iv.setImageBitmap(cached); iv.visibility = View.VISIBLE; tv.visibility = View.GONE }
                        else avatarFrameLoader.execute {
                            val fbmp = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(picPath)
                            Handler(Looper.getMainLooper()).post {
                                if (fbmp == null) return@post
                                avatarFrameCache[picPath] = fbmp
                                if (chipVideo?.showingVideo != true) {
                                    iv.setImageBitmap(fbmp)
                                    iv.visibility = View.VISIBLE
                                    iv.bringToFront()
                                    tv.visibility = View.GONE
                                }
                            }
                        }
                        com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(vv, avatarShape)
                        val vh = MutedLoopVideoHelper(this, vv)
                        chipVideo = vh
                        switcherVideoHelpers.add(vh)
                        vh.play(picPath, iv)
                    } else {
                        val bmp = com.silvershireinn.app.utils.VideoThumbs.decodeSmall(pf.absolutePath)
                        if (bmp != null) { iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE; tv.visibility = View.GONE }
                        else { iv.visibility = View.GONE; tv.visibility = View.VISIBLE }
                    }
                }
            }
            avatar.addView(iv)
            avatar.addView(vv)
            avatar.addView(tv)
            // Unread count for the other conversations.
            val unread = activeSenders[user] ?: 0
            avatar.addView(TextView(this).apply {
                tag = "badge"
                text = if (unread > 99) "99+" else unread.toString()
                textSize = 9f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                minWidth = (16 * dp).toInt()
                background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(0xFFE53935.toInt()); setStroke((1.5f * dp).toInt(), Color.WHITE) }
                visibility = if (unread > 0 && !isCurrent) View.VISIBLE else View.GONE
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, (16 * dp).toInt(), Gravity.TOP or Gravity.END)
            })
            attachSwitcherHeadDrag(avatar, user)
            avatar.setTag(R.id.switcher_user, user)
            row.addView(avatar)
            com.silvershireinn.app.utils.ProfileFrames.applyFloating(avatar, user)   // their border, in room of its own
        }
    }

    private fun switchOverlayTo(contactId: String) {
        if (overlayContact == contactId) return
        stopPendingVoicePlayback()
        cancelRecording()
        clearOverlayDraft()
        // Clear reply if present
        overlayView?.findViewById<View>(R.id.overlayReplyBar)?.visibility = View.GONE
        activeSenders[contactId] = 0
        updateBubble(refreshAvatars = false)
        // Prefer soft highlight of the existing switcher row, then reopen content.
        // Soft-updating the full message pipeline in place is brittle; minimize flicker
        // by keeping bubble still-frames and not parking during the reopen.
        val existing = overlayView
        if (existing != null) {
            softHighlightSwitcher(existing, contactId)
            // keep the row of heads for the next chat (see showOverlay)
            existing.findViewById<View?>(R.id.overlayStackSwitcherScroll)?.let { sc ->
                (sc.parent as? ViewGroup)?.let { p ->
                    carrySwitcherLp = sc.layoutParams
                    p.removeView(sc)
                    carrySwitcher = sc
                }
            }
        }
        switchingOverlay = true
        try {
            hideOverlay()
            showOverlay(contactId, groupExtras[contactId])
        } finally { switchingOverlay = false }
    }

    /** Row of heads carried from the chat being left to the one being opened. */
    private var carrySwitcher: View? = null
    private var carrySwitcherLp: ViewGroup.LayoutParams? = null

    /** Put the carried row of heads into the new window in place of its fresh, empty one.
     *  False when the heads changed meanwhile (then the row is built anew). */
    private fun adoptSwitcher(view: View, carried: View, currentId: String): Boolean {
        val row = carried.findViewById<LinearLayout?>(R.id.overlayStackSwitcher) ?: return false
        val senders = activeSenders.keys.toList().asReversed()
        if (row.childCount != senders.size) return false
        for (i in 0 until row.childCount) if (row.getChildAt(i).getTag(R.id.switcher_user) != senders[i]) return false
        val fresh = view.findViewById<View?>(R.id.overlayStackSwitcherScroll) ?: return false
        val parent = fresh.parent as? ViewGroup ?: return false
        val idx = parent.indexOfChild(fresh)
        val lp = carrySwitcherLp ?: fresh.layoutParams
        parent.removeViewAt(idx)
        parent.addView(carried, idx, lp)
        softHighlightSwitcher(view, currentId)
        return true
    }

    /** Update switcher selection styling without releasing/rebuilding video chips. */
    private fun softHighlightSwitcher(view: View, currentId: String) {
        val row = view.findViewById<LinearLayout?>(R.id.overlayStackSwitcher) ?: return
        val senders = activeSenders.keys.toList().asReversed()
        if (row.childCount != senders.size) return
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i)
            val user = senders.getOrNull(i) ?: continue
            val isCurrent = user == currentId
            chip.alpha = if (isCurrent) 1f else 0.62f
            val avatarShape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(user)
            chip.foreground = if (isCurrent) selectionRing(avatarShape) else null
            chip.contentDescription = switcherName(user) + if (isCurrent) " (open)" else ""
            (chip.findViewWithTag<View>("badge") as? TextView)?.let { b ->
                val unread = activeSenders[user] ?: 0
                b.text = if (unread > 99) "99+" else unread.toString()
                b.visibility = if (unread > 0 && !isCurrent) View.VISIBLE else View.GONE
            }
        }
        row.invalidate()      // borders follow the new fade
    }

    /** Ring around the open conversation's head, matching its shape (circle included). */
    private fun selectionRing(shape: com.silvershireinn.app.utils.AvatarShape): GradientDrawable = GradientDrawable().apply {
        when (shape) {
            com.silvershireinn.app.utils.AvatarShape.CIRCLE, com.silvershireinn.app.utils.AvatarShape.DIAMOND -> this.shape = GradientDrawable.OVAL
            com.silvershireinn.app.utils.AvatarShape.ROUNDED_SQUARE -> { this.shape = GradientDrawable.RECTANGLE; cornerRadius = 12 * dp }
            com.silvershireinn.app.utils.AvatarShape.SQUIRCLE -> { this.shape = GradientDrawable.RECTANGLE; cornerRadius = 17 * dp }
        }
        setStroke((2.5f * dp).toInt(), PreferenceManager.accentColorInt())
        setColor(Color.TRANSPARENT)
    }

    private fun switcherName(id: String): String =
        groupExtras[id]?.second ?: PreferenceManager.displayNameFor(id)

    /** Rebuild the top row of heads if the floating chat is open (new sender, count changed, removed). */
    private fun refreshSwitcherIfOpen() {
        val ov = overlayView ?: return
        val cur = overlayContact ?: return
        if (isDraggingBubble) return
        try { setupOverlayStackSwitcher(ov, cur) } catch (_: Throwable) {}
    }

    private fun hideOverlayTemporarily(alsoBubble: Boolean = false) {
        val v = overlayHost
        if (v != null && overlayView != null) {
            overlayHiddenForPicker = true
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        if (alsoBubble) {
            bubbleView?.let {
                try { wm.removeView(it) } catch (_: Exception) {}
                bubbleHiddenForPicker = true
            }
        }
    }

    /**
     * Re-add the overlay after a picker Activity finishes. No-op if the overlay
     * was fully closed in the meantime or was never hidden.
     */
    private fun restoreOverlayIfNeeded() {
        val needOverlay = overlayHiddenForPicker
        val needBubble = bubbleHiddenForPicker
        if (!needOverlay && !needBubble) return
        overlayHiddenForPicker = false
        bubbleHiddenForPicker = false
        if (needOverlay) {
            val v = overlayHost
            val p = overlayParams
            if (v != null && p != null && !v.isAttachedToWindow) {
                try { wm.addView(v, p) } catch (_: Exception) {}
            }
        }
        // Keep / restore bubble on top
        android.view.Choreographer.getInstance().postFrameCallback {
            bubbleView?.let { bv ->
                try { wm.removeView(bv) } catch (_: Exception) {}
                try { wm.addView(bv, bubbleParams) } catch (_: Exception) {}
            }
        }
        ensureBubbleAttachedSoon()
    }

    /** Coroutines that belong to the open floating chat; cancelled when it closes. */
    private val overlayJobs = mutableListOf<kotlinx.coroutines.Job>()

    fun hideOverlay() {
        // Stop the closed chat's message listener. It used to keep running after the window
        // closed (one more per open), and when another chat was opened it kept marking the
        // OLD chat as read and sending read receipts for messages nobody had seen.
        overlayJobs.forEach { it.cancel() }
        overlayJobs.clear()
        com.silvershireinn.app.SilvershireApp.overlayOpen = false
        overlayHiddenForPicker = false   // if overlay is fully closed, cancel any pending restore
        bubbleHiddenForPicker = false
        overlayOpening = false
        cancelRecording()
        overlaySystemReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        overlaySystemReceiver = null
        overlayVideoBgHelper?.release()
        overlayVideoBgHelper = null
        mediaOverlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        mediaOverlayView = null
        // Keep what was typed (a tap on the head closes the window; the text must not be lost)
        overlayContact?.let { c ->
            val t = overlayView?.findViewById<EditText>(R.id.etOverlayInput)?.text?.toString().orEmpty()
            if (t.isNotBlank()) typedDrafts[c] = t else typedDrafts.remove(c)
        }
        overlayView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        if (!switchingOverlay) {
            overlayHost?.let { h -> try { wm.removeView(h) } catch (_: Exception) {} }
            overlayHost = null
            carrySwitcher = null
        }
        overlayView    = null
        lastOverlayContact = overlayContact
        overlayContact = null
        overlayGroup   = null
        overlayAdapter = null
        // Clear suppression state so WebSocketService resumes normal notification behaviour
        overlayContactId = null
        overlayAtBottom  = true
        // Only undo what the overlay itself set: wiping it unconditionally cleared the flag
        // of a chat that was open in the app, so its messages started notifying again.
        val mine = overlaySetActiveChat
        if (mine != null && com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact == mine) {
            com.silvershireinn.app.ui.chat.ChatActivity.activeChatContact = null
        }
        overlaySetActiveChat = null
    }

    /** Contact the overlay put into ChatActivity.activeChatContact (null if none). */
    private var overlaySetActiveChat: String? = null


    // ── Remove ────────────────────────────────────────────────────────────────

    private fun removeSender(fromUser: String) {
        activeSenders.remove(fromUser)
        groupExtras.remove(fromUser)
        if (overlayContact == fromUser) hideOverlay()
        if (activeSenders.isEmpty()) removeAllHeads() else { updateBubble(); refreshSwitcherIfOpen() }
    }

    private fun removeAllHeads() {
        releaseBubbleVideos()
        releaseSwitcherVideos()
        overlayAvatarVideoHelper?.release()
        overlayAvatarVideoHelper = null
        endSwitcherDragGhost()
        manualHeadOrder = false
        activeSenders.clear()
        groupExtras.clear()
        hideOverlay()
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView   = null
        bubbleParams = null
        stopSelf()
    }

    // ── Dismiss overlay ───────────────────────────────────────────────────────

    private fun showDismissOverlay() {
        if (dismissOverlay != null) return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val size = (72 * dp).toInt()
        val pad = (18 * dp).toInt()
        val circle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            // Dark gray by default; highlightDismiss turns it red when near
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC424242.toInt())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = 0f
        }
        val xIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentDescription = "Dismiss"
            setBackgroundColor(Color.TRANSPARENT)
        }
        circle.addView(xIcon)
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(circle)
        }
        val dParams = WindowManager.LayoutParams(size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * dp).toInt()
            format = PixelFormat.TRANSLUCENT
        }
        try {
            wm.addView(frame, dParams)
            dismissOverlay = frame
        } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: Exception) {}
    }

    /**
     * Z-order while dragging (overlay is NEVER removed/parked):
     *   overlay (unchanged) → bubble (optional) → switcher ghost → dismiss X on top.
     */
    private fun ensureDragZOrder(bringBubble: Boolean) {
        if (bringBubble && !isDraggingBubble) bringBubbleToFrontQuiet()
        // Do NOT remount the switcher ghost — remove/add kills TextureView and stops video.
        // Just put the dismiss X above whatever is already showing.
        hideDismissOverlay()
        showDismissOverlay()
    }

    private fun bringBubbleToFrontQuiet() {
        // Mid-drag remove/add cancels the active pointer and sticks the dismiss X.
        if (isDraggingBubble) return
        if (bubbleHiddenForPicker) return   // camera / picker open: stay hidden until it closes
        val bv = bubbleView ?: return
        val bp = bubbleParams ?: return
        ensureBubbleStillFramesVisible(bv)
        try { wm.removeView(bv) } catch (_: Exception) {}
        try { wm.addView(bv, bp) } catch (_: Exception) {}
        ensureBubbleAttachedSoon()
        // TextureView surfaces die on remove; rebind with still frame first
        if (!isDraggingBubble) refreshAllAvatars()
    }

    /** Keep ImageView still frames visible so video avatars do not blank. */
    private fun ensureBubbleStillFramesVisible(view: View) {
        val slots = listOf(
            Triple(R.id.ivPicFront, R.id.vvPicFront, AvatarSlot.FRONT),
            Triple(R.id.ivPicMid, R.id.vvPicMid, AvatarSlot.MID),
            Triple(R.id.ivPicBack, R.id.vvPicBack, AvatarSlot.BACK)
        )
        val senders = activeSenders.keys.toList()
        val users = listOf(
            senders.getOrNull(senders.size - 1),
            senders.getOrNull(senders.size - 2),
            senders.getOrNull(senders.size - 3)
        )
        slots.zip(users).forEach { (ids, user) ->
            if (user == null) return@forEach
            val iv = view.findViewById<ImageView?>(ids.first) ?: return@forEach
            val vv = view.findViewById<TextureView?>(ids.second)
            val path = contactPicPath(this, user)
            if (path.isBlank() || !com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) return@forEach
            var bmp = avatarFrameCache[path]
            if (bmp == null && vv != null && vv.isAvailable) {
                try {
                    bmp = vv.bitmap
                    if (bmp != null) avatarFrameCache[path] = bmp
                } catch (_: Exception) {}
            }
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Ensure switcher chip has a still ImageView frame (cache or TextureView.bitmap)
     * so drag ghosts are not blank when video is playing.
     */
    private fun ensureSwitcherStillFrame(chip: View, user: String): android.graphics.Bitmap? {
        val g = chip as? ViewGroup ?: return null
        var iv: ImageView? = null
        var vv: TextureView? = null
        for (i in 0 until g.childCount) {
            when (val c = g.getChildAt(i)) {
                is ImageView -> iv = c
                is TextureView -> vv = c
            }
        }
        val path = contactPicPath(this, user)
        var bmp: android.graphics.Bitmap? = if (path.isNotBlank()) avatarFrameCache[path] else null
        if (bmp == null && vv != null && vv.isAvailable) {
            try {
                bmp = vv.bitmap
                if (bmp != null && path.isNotBlank()) avatarFrameCache[path] = bmp
            } catch (_: Exception) {}
        }
        if (bmp == null && iv != null && iv.drawable != null) {
            try {
                val d = iv.drawable
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(out)
                d.setBounds(0, 0, w, h)
                d.draw(canvas)
                bmp = out
            } catch (_: Exception) {}
        }
        if (bmp != null && iv != null) {
            iv.setImageBitmap(bmp)
            iv.visibility = View.VISIBLE
        }
        return bmp
    }




    /**
     * Gesture split for the top switcher row:
     * - Dominant horizontal move past slop → yield to HorizontalScrollView (side scroll).
     * - Dominant vertical (esp. downward) move past slop → lift dismiss ghost over chat UI.
     * Overlay stays visible; ghost + dismiss X are separate WM windows above it.
     */
    @Suppress("ClickableViewAccessibility")
    /** The person arranged the heads by hand: new messages no longer shuffle them. */
    private var manualHeadOrder = false

    private fun attachSwitcherHeadDrag(view: View, user: String) {
        var downRawX = 0f
        var downRawY = 0f
        var dragged = false
        var yieldedToScroll = false
        var reordering = false
        var holdReady = false
        val slop = 14 * dp  // lower vertical slop (~12-16dp) for easier dismiss drag
        val ghostSize = (48 * dp).toInt()
        val main = Handler(Looper.getMainLooper())
        // Held still for a moment: ready to be moved along the row even when the row scrolls
        fun rowScrolls(v: View): Boolean {
            val scroll = (v.parent as? View)?.parent as? View ?: return false
            return scroll.canScrollHorizontally(1) || scroll.canScrollHorizontally(-1)
        }
        val hold = Runnable {
            holdReady = true
            view.parent?.requestDisallowInterceptTouchEvent(true)     // the row stops scrolling: move this head
            // lifted a little (its border follows; scaling would fight the border's own fit)
            view.animate().translationY(-4 * dp).setDuration(120).setUpdateListener { (view.parent as? View)?.invalidate() }.start()
            try { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
        }
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragged = false
                    yieldedToScroll = false
                    reordering = false
                    holdReady = false
                    main.postDelayed(hold, 280)
                    // A row that fits never scrolls: the touch is all this head's (the row used to
                    // grab sideways moves, so the heads could not be dragged and taps got lost).
                    // A long row may scroll until the head is held for a moment.
                    v.parent?.requestDisallowInterceptTouchEvent(!rowScrolls(v))
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (yieldedToScroll) return@setOnTouchListener false
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (reordering) { moveReorder(v, dx); return@setOnTouchListener true }
                    val sideways = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                    if (!dragged && ((sideways && kotlin.math.abs(dx) > 8 * dp) || kotlin.math.abs(dy) > slop)) {
                        main.removeCallbacks(hold)
                        if (sideways) {
                            if (rowScrolls(v) && !holdReady) {
                                // A long row: a quick swipe scrolls it (hold a head first to move it)
                                yieldedToScroll = true
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                return@setOnTouchListener false
                            }
                            // Sideways: move this head along the row
                            reordering = true
                            isDraggingBubble = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            startReorder(v)
                            moveReorder(v, dx)
                            return@setOnTouchListener true
                        }
                        // Vertical / downward: start dismiss-ghost drag
                        dragged = true
                        isDraggingBubble = true
                        v.animate().cancel()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        ensureSwitcherStillFrame(v, user)  // still on ImageView before ghost
                        startSwitcherDragGhost(v, user, ghostSize, ev.rawX, ev.rawY)
                        v.visibility = View.INVISIBLE
                        v.translationX = 0f
                        v.translationY = 0f
                        // Overlay stays; raise ghost then dismiss X above chat panel
                        ensureDragZOrder(bringBubble = false)
                    }
                    if (!dragged) return@setOnTouchListener true
                    moveSwitcherDragGhost(ev.rawX, ev.rawY, ghostSize)
                    if (isOverDismiss(ev.rawX, ev.rawY)) {
                        if (!enteredDismissZone) {
                            enteredDismissZone = true
                            vibrateOnDismiss()
                        }
                    } else {
                        enteredDismissZone = false
                    }
                    highlightDismiss(ev.rawX, ev.rawY)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    if (holdReady && !reordering) {
                        v.animate().translationY(0f).setDuration(120).setUpdateListener { (v.parent as? View)?.invalidate() }.start()
                    }
                    val isCancel = ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    if (reordering) {
                        reordering = false
                        isDraggingBubble = false
                        endReorder(v)
                        return@setOnTouchListener true
                    }
                    if (yieldedToScroll) {
                        yieldedToScroll = false
                        return@setOnTouchListener false
                    }
                    val overX = !isCancel && dragged && isOverDismiss(ev.rawX, ev.rawY)
                    hideDismissOverlay()
                    enteredDismissZone = false
                    isDraggingBubble = false
                    endSwitcherDragGhost()
                    if (overX) {
                        vibrateOnDismiss()
                        dismissOneHead(user)
                    } else if (!dragged && !isCancel) {
                        v.visibility = View.VISIBLE
                        v.performClick()
                    } else {
                        v.visibility = View.VISIBLE
                        v.translationX = 0f
                        v.translationY = 0f
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ── Moving a head along the top row ───────────────────────────────────────
    private var reorderFrom = -1
    private var reorderTo = -1
    /** Each head's place in the row (left edge of its slot, margins included) and slot width. */
    private var reorderSlots: List<Pair<Int, Int>> = emptyList()

    private fun slotOf(c: View): Pair<Int, Int> {
        val lp = c.layoutParams as? ViewGroup.MarginLayoutParams
        val ml = lp?.leftMargin ?: 0; val mr = lp?.rightMargin ?: 0
        return Pair(c.left - ml, c.width + ml + mr)
    }

    private fun startReorder(chip: View) {
        val row = chip.parent as? ViewGroup ?: return
        reorderSlots = (0 until row.childCount).map { slotOf(row.getChildAt(it)) }
        reorderFrom = row.indexOfChild(chip)
        reorderTo = reorderFrom
        chip.animate().cancel()
        chip.translationY = -4 * dp
        chip.translationZ = 8 * dp          // drawn above the others while it moves
    }

    /** Where each head would sit (left edge of its slot) with [order] (indexes into the row). */
    private fun slotLefts(order: List<Int>): IntArray {
        val out = IntArray(order.size)
        var x = reorderSlots.firstOrNull()?.first ?: 0
        for (i in order.indices) {
            val idx = order[i]
            out[idx] = x
            x += reorderSlots[idx].second
        }
        return out
    }

    private fun moveReorder(chip: View, dx: Float) {
        val row = chip.parent as? ViewGroup ?: return
        val n = reorderSlots.size
        if (n < 2 || reorderFrom < 0) return
        val first = reorderSlots.first().first.toFloat()
        val lastEnd = (reorderSlots.last().first + reorderSlots.last().second).toFloat()
        val mine = reorderSlots[reorderFrom]
        val t = dx.coerceIn(first - mine.first, lastEnd - (mine.first + mine.second))
        chip.translationX = t
        // the gap opens where the head's centre is now
        val centre = mine.first + t + mine.second / 2f
        var to = 0
        for (i in 0 until n) if (i != reorderFrom) {
            val s = reorderSlots[i]
            if (s.first + s.second / 2f < centre) to++
        }
        if (to != reorderTo) {
            reorderTo = to
            val order = (0 until n).filter { it != reorderFrom }.toMutableList().apply { add(to, reorderFrom) }
            val lefts = slotLefts(order)
            for (i in 0 until n) if (i != reorderFrom) {
                val c = row.getChildAt(i) ?: continue
                c.animate().translationX((lefts[i] - reorderSlots[i].first).toFloat()).setDuration(140)
                    .setUpdateListener { row.invalidate() }.start()
            }
            try { chip.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
        }
        row.invalidate()        // the borders follow the heads
    }

    private fun endReorder(chip: View) {
        val row = chip.parent as? ViewGroup ?: return
        val n = reorderSlots.size
        val from = reorderFrom; val to = reorderTo
        reorderFrom = -1; reorderTo = -1
        if (n < 2 || from < 0) { chip.translationX = 0f; chip.translationY = 0f; chip.translationZ = 0f; row.invalidate(); return }
        val order = (0 until n).filter { it != from }.toMutableList().apply { add(to, from) }
        val lefts = slotLefts(order)
        val fingerLeft = reorderSlots[from].first + chip.translationX
        val views = (0 until n).map { row.getChildAt(it) }
        views.forEach { if (it !== chip) { it.animate().cancel(); it.translationX = 0f } }
        chip.animate().cancel()
        if (to != from) {
            row.removeAllViews()
            order.forEach { row.addView(views[it]) }
            // The heads' new order is the chats' order (first in the row = front of the stack)
            val users = order.mapNotNull { views[it].getTag(R.id.switcher_user) as? String }
            val copy = LinkedHashMap(activeSenders)
            activeSenders.clear()
            users.asReversed().forEach { u -> copy[u]?.let { activeSenders[u] = it } }
            copy.forEach { (u, c) -> if (!activeSenders.containsKey(u)) activeSenders[u] = c }
            manualHeadOrder = true
            updateBubble()
        }
        // settle into its new place from where the finger left it
        chip.translationX = fingerLeft - lefts[from]
        chip.animate().translationX(0f).translationY(0f).setDuration(160)
            .setUpdateListener { row.invalidate() }
            .withEndAction { chip.translationZ = 0f; row.invalidate() }.start()
        row.invalidate()
    }

    private fun startSwitcherDragGhost(src: View, user: String, size: Int, rawX: Float, rawY: Float) {
        endSwitcherDragGhost()
        val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(user)
        val ghost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = com.silvershireinn.app.utils.AvatarShapeHelper.backgroundDrawable(user.toAvatarColor(), shape)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = 24f
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(this, shape)
        }
        val srcIv = (src as? ViewGroup)?.let { g ->
            (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is ImageView } as? ImageView
        }
        val srcTv = (src as? ViewGroup)?.let { g ->
            (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is TextView } as? TextView
        }
        val picPath = contactPicPath(this, user)
        val isVideo = picPath.isNotBlank() &&
            File(picPath).exists() &&
            com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(picPath)

        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width.coerceAtLeast(1), v.height.coerceAtLeast(1))
                }
            }
            val bmp = if (isVideo) avatarFrameCache[picPath] else null
            when {
                bmp != null -> {
                    setImageBitmap(bmp)
                    visibility = View.VISIBLE
                }
                srcIv != null && srcIv.drawable != null -> {
                    setImageDrawable(srcIv.drawable.constantState?.newDrawable()?.mutate() ?: srcIv.drawable)
                    visibility = View.VISIBLE
                }
                else -> visibility = View.GONE
            }
        }
        ghost.addView(iv)

        if (isVideo) {
            val vv = TextureView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.VISIBLE
            }
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(vv, shape)
            ghost.addView(vv)
            iv.bringToFront()
            val vh = MutedLoopVideoHelper(this, vv)
            switcherDragGhostVideo = vh
            vh.play(picPath, iv)
        } else if (iv.visibility != View.VISIBLE) {
            ghost.addView(TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                setTextColor(resources.getColor(R.color.color_background, null))
                textSize = 12f
                text = srcTv?.text ?: user.toInitials()
            })
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // The head keeps its border while dragged: a window with room around the picture
        val fid = com.silvershireinn.app.utils.ProfileFrames.frameFor(user)
        val framed = !(fid == "none" || fid.startsWith("gen:none"))
        val room = if (framed) (size * 0.3f).toInt() else 0
        val total = size + 2 * room
        val holder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        holder.addView(ghost, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        if (framed) com.silvershireinn.app.utils.ProfileFrames.applyFloating(ghost, user, reserve = false)
        val lp = WindowManager.LayoutParams(
            total, total, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (rawX - total / 2f).toInt().coerceIn(-room, (screenWidth - total + room).coerceAtLeast(0))
            y = (rawY - total / 2f).toInt().coerceIn(-room, (screenHeight - total + room).coerceAtLeast(0))
        }
        try {
            wm.addView(holder, lp)
            switcherDragGhost = holder
            switcherDragGhostParams = lp
        } catch (e: Exception) {
            android.util.Log.e("ChatHead", "switcher ghost add failed", e)
            switcherDragGhostVideo?.release()
            switcherDragGhostVideo = null
        }
    }

    private fun moveSwitcherDragGhost(rawX: Float, rawY: Float, size: Int) {
        val ghost = switcherDragGhost ?: return
        val lp = switcherDragGhostParams ?: return
        val total = lp.width.coerceAtLeast(size)
        val room = (total - size) / 2
        lp.x = (rawX - total / 2f).toInt().coerceIn(-room, (screenWidth - total + room).coerceAtLeast(0))
        lp.y = (rawY - total / 2f).toInt().coerceIn(-room, (screenHeight - total + room).coerceAtLeast(0))
        try { wm.updateViewLayout(ghost, lp) } catch (_: Exception) {}
    }

    private fun endSwitcherDragGhost() {
        switcherDragGhostVideo?.release()
        switcherDragGhostVideo = null
        switcherDragGhost?.let { g ->
            try { wm.removeView(g) } catch (_: Exception) {}
        }
        switcherDragGhost = null
        switcherDragGhostParams = null
    }

    private fun confirmOutgoingCall(who: String, video: Boolean, onYes: () -> Unit) {
        val name = PreferenceManager.displayNameFor(who)
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (video) "Video call $name?" else "Call $name?")
            .setMessage(if (video) "Place a video call?" else "Place a voice call?")
            .setPositiveButton(if (video) "Video" else "Call") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.window?.setType(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE
        )
        dlg.show()
    }

    private fun edgeSnap(params: WindowManager.LayoutParams, bubbleW: Int, bubbleH: Int) {
        val edge = (28 * dp).toInt()
        if (bubbleVisR > bubbleVisL) {
            // By what is really drawn: near an edge it snaps flush; pushed past it, it stays there
            val l = params.x + bubbleVisL; val r = params.x + bubbleVisR
            if (l < edge) { if (l > 0f) params.x = kotlin.math.round(-bubbleVisL).toInt() }
            else if (r > screenWidth - edge) { if (r < screenWidth) params.x = kotlin.math.round(screenWidth - bubbleVisR).toInt() }
        } else if (params.x < edge) params.x = 0
        else if (params.x > screenWidth - bubbleW - edge) params.x = screenWidth - bubbleW
        if (params.y < edge) params.y = 0
        else if (params.y > screenHeight - bubbleH - edge) params.y = screenHeight - bubbleH
    }

    /** Dismiss one contact's head; others remain. Reappears on a new message (no blocklist). */
    private fun dismissOneHead(user: String) {
        // newest-first row order (same as setupOverlayStackSwitcher)
        val newestFirst = activeSenders.keys.toList().asReversed()
        val idx = newestFirst.indexOf(user)
        val neighborRight = if (idx >= 0 && idx + 1 < newestFirst.size) newestFirst[idx + 1] else null
        val remaining = newestFirst.filter { it != user }
        val nextUser = neighborRight ?: remaining.firstOrNull()
        val wasCurrent = overlayContact == user
        val overlayOpen = overlayView != null
        val dismissIdx = idx

        fun finishDismiss() {
            activeSenders.remove(user)
            groupExtras.remove(user)
            if (activeSenders.isEmpty()) {
                removeAllHeads()
                return
            }
            updateBubble()
            if (overlayOpen) {
                if (wasCurrent && nextUser != null) {
                    // Keep overlay open: switch to neighbor (right), else new first
                    switchOverlayTo(nextUser)
                } else {
                    val ov = overlayView
                    val cur = overlayContact
                    if (ov != null && cur != null) setupOverlayStackSwitcher(ov, cur)
                }
            }
        }

        val ov = overlayView
        if (ov != null && dismissIdx >= 0) {
            animateSwitcherAfterDismiss(ov, dismissIdx) { finishDismiss() }
        } else {
            finishDismiss()
        }
    }

    /** Fade out dismissed chip and slide remaining heads left (~200ms), then [onEnd]. */
    private fun animateSwitcherAfterDismiss(ov: View, dismissedIndex: Int, onEnd: () -> Unit) {
        val row = ov.findViewById<LinearLayout?>(R.id.overlayStackSwitcher)
        if (row == null || dismissedIndex < 0 || dismissedIndex >= row.childCount) {
            onEnd()
            return
        }
        val chip = row.getChildAt(dismissedIndex)
        val slidePx = (chip.width.takeIf { it > 0 } ?: (48 * dp).toInt()) +
            ((chip.layoutParams as? LinearLayout.LayoutParams)?.marginEnd ?: (6 * dp).toInt())
        chip.animate().alpha(0f).setDuration(200L).start()
        for (i in dismissedIndex + 1 until row.childCount) {
            row.getChildAt(i).animate()
                .translationX(-slidePx.toFloat())
                .setDuration(200L)
                .start()
        }
        row.postDelayed({
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                c.animate().cancel()
                c.translationX = 0f
                c.alpha = 1f
            }
            onEnd()
        }, 200L)
    }

    private fun hideDismissOverlay() {
        dismissOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dismissOverlay = null
    }

    private fun highlightDismiss(rawX: Float, rawY: Float) {
        val near = isOverDismiss(rawX, rawY)
        val circle = (dismissOverlay as? FrameLayout)?.getChildAt(0)
        val bg = circle?.background as? GradientDrawable
        bg?.setColor(if (near) 0xCCB71C1C.toInt() else 0xCC424242.toInt())
        dismissOverlay?.animate()
            ?.scaleX(if (near) 1.3f else 1f)?.scaleY(if (near) 1.3f else 1f)
            ?.setDuration(100)?.start()
    }

    private fun isOverDismiss(rawX: Float, rawY: Float) =
        hypot(rawX - dismissCX, rawY - dismissCY) < dismissRadius * 1.5f

    @Suppress("DEPRECATION")
    private fun vibrateOnDismiss() {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                        ?.defaultVibrator?.vibrate(
                            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    (getSystemService(VIBRATOR_SERVICE) as? Vibrator)
                        ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                else ->
                    (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    private var minimizedAtMs = 0L

    private fun minimizeApp() {
        minimizedAtMs = SystemClock.uptimeMillis()
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
