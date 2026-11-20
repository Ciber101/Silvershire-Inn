package com.silvershireinn.app.ui.chat

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.silvershireinn.app.R
import com.silvershireinn.app.databinding.ActivityChatBinding
import com.silvershireinn.app.model.Message
import com.silvershireinn.app.network.CallManager
import com.silvershireinn.app.network.MediaAuth
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.ui.call.CallActivity
import com.silvershireinn.app.ui.call.VideoCallActivity
import com.silvershireinn.app.ui.media.CameraCaptureActivity
import com.silvershireinn.app.network.VideoCallManager
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.SharedFileInfo
import com.silvershireinn.app.utils.VideoBackgroundHelper
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.util.HapticUtil
import com.silvershireinn.app.utils.hide
import com.silvershireinn.app.utils.show
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity(), SharedFilesBottomSheet.Listener {

    companion object {
        /** Username/groupId of the chat currently visible to the user. Null when no chat is open. */
        @Volatile var activeChatContact: String? = null
    }

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(intent.getStringExtra("contact_username") ?: "")
    }
    private lateinit var adapter: MessageAdapter
    private val typingHandler = Handler(Looper.getMainLooper())

    // Cached so the profile-update receiver can use it
    private var contactName: String = ""

    // Refresh group member subtitle when someone leaves the group
    private val groupUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val updatedGroupId = intent?.getStringExtra("groupId") ?: return
            val gid = this@ChatActivity.intent.getStringExtra("group_id") ?: return
            if (updatedGroupId == gid) loadGroupMemberSubtitle(gid)
        }
    }

    // Refresh toolbar avatar when a contact uploads a new pic
    private val profileUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val updatedUser = intent?.getStringExtra("username") ?: return
            if (updatedUser == contactName) loadToolbarAvatar()
        }
    }

    // Refresh all chat bubbles immediately when the font size slider changes in ProfileFragment
    private val chatFontChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::adapter.isInitialized) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
    }

    // Close this chat immediately when the open contact wipes their account
    private val contactRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val removed = intent?.getStringExtra("username") ?: return
                if (removed == contactName && !isFinishing && !isDestroyed) {
                    // Contact has been wiped - their chat should vanish immediately.
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "contactRemovedReceiver error", e)
            }
        }
    }

    // ── Voice recording ─────────────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0L
    private var recordingStartRunnable: Runnable? = null
    private val RECORD_START_DELAY_MS = 300L   // hold 300 ms to start recording; shorter tap -> STT
    private val RECORD_LOCK_MS = 3_000L       // hold 3s to lock recording (release finger, keep recording)
    private var recordingLocked = false
    private var recordingLockRunnable: Runnable? = null
    private var suppressUpAfterLock = false
    /** Recording stopped; clip awaiting send/trash (early release before lock). */
    private var voicePendingReview = false
    private var pendingWaveformTicker: Runnable? = null
    private var pendingVoicePlayer: MediaPlayer? = null

    // ── Video background ────────────────────────────────────────────────────────────────
    private var chatVideoBgHelper: VideoBackgroundHelper? = null

    // ── Reply state ─────────────────────────────────────────────────────────────────────
    private var replyToMessage: Message? = null

    // ── Auto-scroll control (see messages observer in observeViewModel) ──
    private var hasAutoScrolledOnce = false       // first emission after entering the chat scrolls once
    private var lastSeenNewestId: String? = null  // newest messageId from the previous list emission
    private var fabUnreadCount = 0                // red counter on the scroll-to-bottom FAB

    /** Show/update/hide the red "new messages below" counter on the scroll FAB. */
    private fun updateFabUnreadBadge(count: Int) {
        fabUnreadCount = count
        binding.tvFabUnreadBadge.text = if (count > 99) "99+" else count.toString()
        binding.tvFabUnreadBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    // ── Camera ──────────────────────────────────────────────────────────────────────────
    private var pendingPhotoUri: Uri? = null

    // ── Attachment draft (confirm with Send; cancel clears) ─────────────────────────────
    private var pendingDraftUri: Uri? = null
    private var pendingDraftBytes: ByteArray? = null
    private var pendingDraftName: String? = null
    private var pendingDraftMime: String? = null

    // ── Activity result launchers ───────────────────────────────────────────────────────
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        setAttachmentDraft(uri, mime)
    }

    /** Several photos / videos picked at once: one picks a draft, more are confirmed and sent. */
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (uris.size == 1) {
            val u = uris[0]
            setAttachmentDraft(u, contentResolver.getType(u) ?: "image/jpeg")
        } else sendManyUris(uris)
    }

    /** Confirm, then send each item as its own message (each gets its own bubble + progress). */
    private fun sendManyUris(uris: List<Uri>) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Send ${uris.size} items?")
            .setMessage("They are sent in full quality, one message each.")
            .setPositiveButton("Send") { _, _ -> uris.forEach { viewModel.uploadAndSendFile(this, it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingPhotoUri?.let { uri -> setAttachmentDraft(uri, "image/jpeg") }
        }
    }

    private val takeVideoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) {
            // Draft video URI - upload only when user taps Send
            pendingPhotoUri?.let { uri ->
                val mime = contentResolver.getType(uri) ?: "video/mp4"
                setAttachmentDraft(uri, mime)
            }
        }
    }

    private val inAppCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val mime = contentResolver.getType(uri) ?: if ((uri.lastPathSegment ?: "").endsWith(".mp4")) "video/mp4" else "image/jpeg"
        // The original capture file is sent byte-for-byte (streamed, never re-encoded).
        setAttachmentDraft(uri, mime)
    }

    /** Edited draft photo comes back as a file; it replaces the draft (original is untouched). */
    private val draftEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val out = r.data?.data ?: return@registerForActivityResult
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val f = out.path?.let { File(it) } ?: return@registerForActivityResult
        if (!f.exists()) return@registerForActivityResult
        setAttachmentDraft(FileProvider.getUriForFile(this, "${packageName}.provider", f), "image/jpeg")
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("Microphone ready - hold to record")
        else toast("Microphone permission denied")
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { text ->
                    val current = binding.etMessage.text?.toString() ?: ""
                    binding.etMessage.setText(if (current.isBlank()) text else "$current $text")
                    binding.etMessage.setSelection(binding.etMessage.length())
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Override the system back button: if we came from a chat head, go to MainActivity
        // instead of letting Android fall back to the home screen (no back stack).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If search bar is open, close it instead of leaving the chat
                if (binding.searchBar.visibility == View.VISIBLE) {
                    closeSearch()
                    return
                }
                navigateBack()
            }
        })

        // ── Auth gate - notification taps can reach this activity when the user is ──
        // logged out. Redirect to the login/identity screen instead of showing messages.
        if (com.silvershireinn.app.utils.PreferenceManager.username.isBlank() ||
            !com.silvershireinn.app.utils.PreferenceManager.isSetupComplete) {
            val contactExtra = intent?.getStringExtra("contact_username") ?: ""
            val redirectIntent = Intent(
                this,
                com.silvershireinn.app.ui.identity.CreateIdentityActivity::class.java
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // Pass through so after login MainActivity can open the right chat
                if (contactExtra.isNotBlank()) putExtra("pending_chat", contactExtra)
            }
            startActivity(redirectIntent)
            finish()
            return
        }

        // "Require password" is on and this chat was opened from a notification / chat head
        // without unlocking: lock screen first, then straight back to this chat.
        if (com.silvershireinn.app.utils.AppLock.gate(this)) return

        // If launched via the system share sheet (ACTION_SEND), we won't have a
        // contact_username yet. Show a contact picker, then forward the content.
        if (intent?.action == android.content.Intent.ACTION_SEND ||
            intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
            handleIncomingShare(intent)
            return
        }

        contactName = intent.getStringExtra("contact_username") ?: ""

        // Group chat setup
        val groupId   = intent.getStringExtra("group_id")
        val groupName = intent.getStringExtra("group_name") ?: ""
        if (groupId != null) {
            viewModel.groupId   = groupId
            viewModel.groupName = groupName
        }

        // Dismiss any pending notifications for this conversation
        com.silvershireinn.app.notifications.NotificationHelper.cancelMessageNotifications(this, contactName)

        // Set up per-chat video background
        chatVideoBgHelper = VideoBackgroundHelper(this, binding.svChatVideoBg)
        chatVideoBgHelper?.applyPath(com.silvershireinn.app.utils.PreferenceManager.getChatVideoBg(contactName))
        setSupportActionBar(binding.toolbar)
        val displayName = if (groupId != null) {
            groupName.ifBlank { "Group" }
        } else {
            com.silvershireinn.app.utils.PreferenceManager.displayNameFor(contactName)
        }
        supportActionBar?.title = displayName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Style subtitle smaller than title
        binding.toolbar.findViewById<android.widget.TextView>(
            androidx.appcompat.R.id.action_bar_subtitle
        )?.textSize = 11f
        if (groupId == null) {
            loadToolbarAvatar()
        } else {
            loadGroupMemberSubtitle(groupId)
        }

        // Hide call/video buttons in group chats
        if (groupId != null) {
            binding.btnCall?.visibility      = android.view.View.GONE
            binding.btnVideoCall?.visibility = android.view.View.GONE
        }

        adapter = MessageAdapter(
            currentUser     = viewModel.currentUser,
            contactUsername = viewModel.contactUsername,
            isGroupChat     = groupId != null,
            onDownload      = { msg -> handleAttachmentDownload(msg) },
            onTapMedia      = { msg -> openMedia(msg) },
            onLongPress     = { msg -> showMessageContextMenu(msg) },
            onCallBack      = { remoteUser -> placeCallTo(remoteUser) },
            onReplyJump     = { msg -> jumpToReplyOriginal(msg) },
            onApkDownload  = { _ -> com.silvershireinn.app.utils.AppUpdateHelper.openDownload(this) },
            onAvatarTap    = { user ->
                if (user.isNotBlank() && user != viewModel.currentUser) {
                    com.silvershireinn.app.ui.contacts.ContactProfileBottomSheet
                        .newInstance(user).show(supportFragmentManager, "contact_profile")
                }
            }
        )
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter
        // Smoother on slower phones: no cross-fade every time a tick/receipt updates a row,
        // and more rows kept ready while scrolling.
        (binding.rvMessages.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvMessages.setItemViewCacheSize(24)

        // Show scroll-to-bottom FAB when user has scrolled up more than ~2 items
        binding.rvMessages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
                val total = adapter.itemCount
                val scrolledUp = total > 0 && lastVisible < total - 2
                binding.fabScrollBottom.visibility = if (scrolledUp) View.VISIBLE else View.GONE
                // Back at the bottom -> everything below is seen, clear the red counter
                if (!scrolledUp && fabUnreadCount > 0) updateFabUnreadBadge(0)
            }
        })
        binding.fabScrollBottom.setOnClickListener {
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            updateFabUnreadBadge(0)
        }

        setupSwipeToReply()
        setupReplyBar()
        setupInputBar(contactName)
        setupCallButtons(contactName)
        observeViewModel()
        observeCallState()

        // Handle content forwarded from the share picker above
        intent.getStringExtra("shared_text")?.let { text ->
            binding.etMessage.setText(text)
            binding.etMessage.setSelection(text.length)
        }
        intent.getStringExtra("shared_uri")?.let { uriStr ->
            val uri = android.net.Uri.parse(uriStr)
            val mime = intent.getStringExtra("shared_mime")
                ?: try { contentResolver.getType(uri) } catch (_: Exception) { null } ?: "application/octet-stream"
            setAttachmentDraft(uri, mime)
        }
        intent.getStringArrayListExtra("shared_uris")?.takeIf { it.isNotEmpty() }?.let { list ->
            binding.root.post { sendManyUris(list.map { android.net.Uri.parse(it) }) }
        }
    }

    // ── Handle incoming share from other apps ───────────────────────────────────────────

    /** A chat you can send to: a confirmed friend or a group. */
    private data class ChatTarget(val id: String, val label: String, val groupName: String?)

    /** Friends (display names) then groups, alphabetical. Pending requests are left out. */
    private suspend fun loadChatTargets(): List<ChatTarget> = withContext(Dispatchers.IO) {
        val db = com.silvershireinn.app.SilvershireApp.database
        val friends = db.contactDao().getAllConfirmedContacts()
            .map { ChatTarget(it.username, com.silvershireinn.app.utils.PreferenceManager.displayNameFor(it.username), null) }
            .sortedBy { it.label.lowercase() }
        val groups = try { db.groupDao().getGroups().first() } catch (_: Exception) { emptyList() }
            .map { ChatTarget(it.groupId, "${it.name.ifBlank { "Group" }} (group)", it.name.ifBlank { "Group" }) }
            .sortedBy { it.label.lowercase() }
        friends + groups
    }

    /** Intent that opens the chat for [t]. */
    private fun chatIntentFor(t: ChatTarget): Intent = Intent(this, ChatActivity::class.java).apply {
        putExtra("contact_username", t.id)
        if (t.groupName != null) {
            putExtra("group_id", t.id)
            putExtra("group_name", t.groupName)
        }
    }

    private fun pickChatTarget(title: String, onCancel: (() -> Unit)? = null, onPick: (ChatTarget) -> Unit) {
        lifecycleScope.launch {
            val targets = loadChatTargets()
            if (isFinishing || isDestroyed) return@launch
            if (targets.isEmpty()) {
                toast("No chats to send to yet")
                onCancel?.invoke()
                return@launch
            }
            MaterialAlertDialogBuilder(this@ChatActivity)
                .setTitle(title)
                .setItems(targets.map { it.label }.toTypedArray()) { _, which -> onPick(targets[which]) }
                .setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
                .setOnCancelListener { onCancel?.invoke() }
                .show()
        }
    }

    /**
     * Shared into Silvershire from another app (one item, several items, or text): pick the
     * chat, then it opens there as a draft (or a "Send N items?" confirm). The read permission
     * for the shared files is passed on; it used to be lost when this screen closed, so shared
     * photos often failed to upload. Groups are offered too, and names instead of usernames.
     */
    private fun handleIncomingShare(intent: android.content.Intent) {
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        val uris: List<Uri> = try {
            if (intent.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM))
                    ?.filterNotNull().orEmpty()
            } else {
                listOfNotNull(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM))
            }
        } catch (_: Exception) { emptyList() }
        if (text.isNullOrBlank() && uris.isEmpty()) { finish(); return }
        pickChatTarget("Send to...", onCancel = { finish() }) { target ->
            val forward = chatIntentFor(target).apply {
                if (!text.isNullOrBlank()) putExtra("shared_text", text)
                if (uris.size == 1) {
                    putExtra("shared_uri", uris[0].toString())
                    intent.type?.takeIf { !it.contains('*') }?.let { putExtra("shared_mime", it) }
                } else if (uris.size > 1) {
                    putStringArrayListExtra("shared_uris", ArrayList(uris.map { it.toString() }))
                }
                if (uris.isNotEmpty()) {
                    clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(forward)
            finish()
        }
    }

    /**
     * Forward a message to another chat. It opens there as a draft (text in the box, or the
     * file attached) so you can add a note or change your mind before sending.
     */
    private fun forwardMessage(msg: Message) {
        pickChatTarget("Forward to...") { target ->
            if (!msg.isAttachment) {
                startActivity(chatIntentFor(target).putExtra("shared_text", quotePreview(msg).let {
                    if (msg.encryptedPayload.startsWith("> ")) it else msg.encryptedPayload }))
                return@pickChatTarget
            }
            val fileId = msg.attachmentId ?: return@pickChatTarget
            toast("Preparing...")
            lifecycleScope.launch {
                val f = FileRepository.ensureCached(this@ChatActivity, fileId, msg.attachmentMime, msg.attachmentName)
                if (f == null) { toast("Couldn't download it to forward"); return@launch }
                // Readable name for the new upload, in our shared cache folder.
                val named = withContext(Dispatchers.IO) {
                    try {
                        val dir = File(cacheDir, "forward").apply { mkdirs() }
                        val out = File(dir, (msg.attachmentName ?: f.name).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                        f.copyTo(out, overwrite = true)
                    } catch (_: Exception) { f }
                }
                val uri = FileProvider.getUriForFile(this@ChatActivity, "${packageName}.provider", named)
                startActivity(chatIntentFor(target).apply {
                    putExtra("shared_uri", uri.toString())
                    msg.attachmentMime?.let { putExtra("shared_mime", it) }
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
    }

    // ── Input bar ───────────────────────────────────────────────────────────────────────

    private fun setupInputBar(contactName: String) {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Panic word typed (or dictated): clear it and lock at once, nothing is sent.
                if (com.silvershireinn.app.utils.PanicLock.matchesLockWord(s)) {
                    binding.etMessage.post {
                        binding.etMessage.setText("")
                        com.silvershireinn.app.utils.PanicLock.lockNow(this@ChatActivity)
                    }
                    return
                }
                val hasText = !s.isNullOrBlank()
                updateVoiceOrSendIcon(hasText)
                // (This used to clear EVERY pending callback on this handler, which also killed
                // the voice-recording lock timer and ticker if you typed while recording.)
                if (hasText) viewModel.sendTyping()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnVoiceOrSend.setOnClickListener {
            HapticUtil.vibrate(this)
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isNotBlank() || hasAttachmentDraft()) sendMessage()
            else if (voicePendingReview) sendPendingVoice()
            // Short taps when empty are handled by the touch listener (-> STT)
        }

        binding.btnCancelRecording.setOnClickListener {
            cancelRecording()
        }

        binding.btnPlayPendingRecording.setOnClickListener {
            // While a locked recording is running this is the Stop button: stop and review.
            if (isRecording && recordingLocked) stopRecordingToPendingReview()
            else togglePendingVoicePlayback()
        }


        binding.btnVoiceOrSend.setOnTouchListener { _, event ->
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isNotBlank() || hasAttachmentDraft()) return@setOnTouchListener false   // let click handle send
            // Pending review: let click send; ignore hold gestures
            if (voicePendingReview) {
                if (event.action == MotionEvent.ACTION_UP) {
                    sendPendingVoice()
                    return@setOnTouchListener true
                }
                return@setOnTouchListener true
            }
            // After lock engages mid-hold, ignore that finger's UP so recording continues
            if (suppressUpAfterLock && event.action == MotionEvent.ACTION_UP) {
                suppressUpAfterLock = false
                return@setOnTouchListener true
            }
            // When locked, a new tap on mic/send finishes & sends
            if (recordingLocked && event.action == MotionEvent.ACTION_UP) {
                stopRecordingAndSend()
                return@setOnTouchListener true
            }
            if (recordingLocked) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Schedule recording start after 300 ms; if released before -> STT
                    val r = Runnable { requestAudioPermissionOrRecord() }
                    recordingStartRunnable = r
                    typingHandler.postDelayed(r, RECORD_START_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val r = recordingStartRunnable
                    recordingStartRunnable = null
                    recordingLockRunnable?.let { typingHandler.removeCallbacks(it) }
                    recordingLockRunnable = null
                    if (r != null) {
                        typingHandler.removeCallbacks(r)
                        if (!isRecording) {
                            // Tap released before recording kicked in -> STT
                            startSpeechToText()
                        } else if (!recordingLocked) {
                            // Early release: pending review - never auto-send
                            stopRecordingToPendingReview()
                        }
                    } else if (isRecording && !recordingLocked) {
                        stopRecordingToPendingReview()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (recordingLocked || voicePendingReview) return@setOnTouchListener true
                    recordingStartRunnable?.let { typingHandler.removeCallbacks(it) }
                    recordingStartRunnable = null
                    recordingLockRunnable?.let { typingHandler.removeCallbacks(it) }
                    recordingLockRunnable = null
                    cancelRecording()
                    true
                }
                else -> false
            }
        }

        binding.btnAttach.setOnClickListener {
            HapticUtil.vibrate(this)
            val sheet = SharedFilesBottomSheet.newInstance(contactName)
            sheet.listener = this
            sheet.show(supportFragmentManager, SharedFilesBottomSheet.TAG)
            viewModel.loadSharedFiles()
        }

        binding.btnCamera.setOnClickListener { HapticUtil.vibrate(this); showCameraChooser() }

        binding.btnEmoji.setOnClickListener {
            HapticUtil.vibrate(this)
            EmojiPickerBottomSheet { emoji ->
                val et    = binding.etMessage
                val start = et.selectionStart.coerceAtLeast(0)
                val end   = et.selectionEnd.coerceAtLeast(start)
                et.text?.replace(start, end, emoji, 0, emoji.length)
            }.show(supportFragmentManager, "emoji_picker")
        }

        binding.btnClearDraft.setOnClickListener {
            HapticUtil.vibrate(this)
            clearAttachmentDraft()
        }

        // Keyboard / clipboard attach paste -> draft (not auto-send)
        ViewCompat.setOnReceiveContentListener(
            binding.etMessage,
            arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*"),
            OnReceiveContentListener { _, payload ->
                // partition predicate receives ClipData.Item - any URI item becomes a draft
                val split = payload.partition { item -> item.uri != null }
                val received = split.first
                if (received != null) {
                    val clipData = received.clip
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri ?: continue
                        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                        setAttachmentDraft(uri, mime)
                        break
                    }
                }
                split.second
            }
        )

        binding.fabScrollBottom.setOnClickListener {
            HapticUtil.vibrate(this)
            scrollAllTheWayDown()
            updateFabUnreadBadge(0)
        }
    }

    /**
     * The down arrow: all the way to the end in one press. smoothScrollToPosition stops as soon as
     * the last row peeks in (a tall picture or one still loading left it part way), so finish
     * with a direct scroll to the very end once the glide settles, and again after pictures load.
     */
    private fun scrollAllTheWayDown() {
        val rv = binding.rvMessages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val last = adapter.itemCount - 1
        if (last < 0) return
        if (last - lm.findLastVisibleItemPosition() > 20) rv.scrollToPosition(last - 10)   // far away: jump close first
        rv.smoothScrollToPosition(last)
        val finish = Runnable { if (rv.canScrollVertically(1)) { rv.stopScroll(); rv.scrollToPosition(last); rv.post { rv.scrollBy(0, 1_000_000) } } }
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(r: androidx.recyclerview.widget.RecyclerView, state: Int) {
                if (state != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return
                r.removeOnScrollListener(this)
                finish.run(); r.postDelayed(finish, 350)
            }
        })
        rv.postDelayed({ if (rv.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) finish.run() }, 1200)
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        val hadDraft = hasAttachmentDraft()
        if (text.isBlank() && !hadDraft) return

        // Upload attachment draft first (if any), then optional text
        if (hadDraft) {
            val uri = pendingDraftUri
            val bytes = pendingDraftBytes
            val name = pendingDraftName ?: "file_${System.currentTimeMillis()}"
            val mime = pendingDraftMime ?: "application/octet-stream"
            clearAttachmentDraft()
            if (bytes != null) {
                viewModel.uploadAndSendBytes(name, mime, bytes)
            } else if (uri != null) {
                viewModel.uploadAndSendFile(this, uri)
            }
        }

        if (text.isBlank()) return
        binding.etMessage.setText("")

        // If replying, prepend a quoted block
        val reply = replyToMessage
        val finalText = if (reply != null && !reply.isDeleted) {
            "> ${replySender(reply)}: ${quotePreview(reply).take(120)}\n\n$text"
        } else {
            text
        }
        clearReply()

        viewModel.sendMessage(finalText)
        viewModel.clearContactTyping()
    }

    private fun hasAttachmentDraft(): Boolean =
        pendingDraftUri != null || pendingDraftBytes != null

    private fun updateVoiceOrSendIcon(hasText: Boolean = !binding.etMessage.text.isNullOrBlank()) {
        binding.btnVoiceOrSend.setImageResource(
            if (hasText || hasAttachmentDraft()) R.drawable.ic_send else R.drawable.ic_mic
        )
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun showDraftPreview(mime: String, uri: Uri?, bytes: ByteArray?) {
        binding.draftBar.visibility = View.VISIBLE
        val isImage = mime.startsWith("image/")
        binding.ivDraftThumb.setOnClickListener {
            val u = pendingDraftUri
            if (isImage && u != null && !mime.contains("gif")) {
                try { draftEditorLauncher.launch(com.silvershireinn.app.ui.media.PhotoEditorActivity.intent(this, u)) } catch (_: Throwable) {}
            }
        }
        if (isImage) {
            binding.tvDraftFileName.visibility = View.GONE
            binding.ivDraftThumb.setPadding(0, 0, 0, 0)
            binding.ivDraftThumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            // Never decode the full photo for this small thumbnail: a 12-50 MP image is
            // 50-200 MB of memory and crashed lower-end phones. Coil decodes a small copy.
            if (uri != null) {
                binding.ivDraftThumb.load(uri) { size(320); crossfade(false) }
            } else if (bytes != null) {
                binding.ivDraftThumb.load(bytes) { size(320); crossfade(false) }
            }
        } else if (mime.startsWith("video/") && uri != null) {
            binding.tvDraftFileName.text = pendingDraftName ?: "Video"
            binding.tvDraftFileName.visibility = View.VISIBLE
            binding.ivDraftThumb.setPadding(0, 0, 0, 0)
            binding.ivDraftThumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.ivDraftThumb.setImageResource(android.R.color.darker_gray)
            lifecycleScope.launch {
                val frame = withContext(Dispatchers.IO) {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(this@ChatActivity, uri)
                        r.getFrameAtTime(0)?.let { com.silvershireinn.app.utils.VideoThumbs.scale(it, 320) }
                    } catch (_: Throwable) { null } finally { try { r.release() } catch (_: Throwable) {} }
                }
                if (frame != null && pendingDraftUri == uri) binding.ivDraftThumb.setImageBitmap(frame)
            }
        } else {
            binding.ivDraftThumb.setImageURI(null)
            binding.ivDraftThumb.setImageResource(R.drawable.ic_attach)
            val pad = (12 * resources.displayMetrics.density).toInt()
            binding.ivDraftThumb.setPadding(pad, pad, pad, pad)
            binding.ivDraftThumb.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            binding.tvDraftFileName.text = pendingDraftName ?: "Attachment"
            binding.tvDraftFileName.visibility = View.VISIBLE
        }
        updateVoiceOrSendIcon()
    }

    private fun setAttachmentDraft(uri: Uri, mime: String) {
        pendingDraftUri = uri
        pendingDraftBytes = null
        val guessed = queryDisplayName(uri)
        pendingDraftName = guessed ?: when {
            mime.startsWith("image/") -> "image_${System.currentTimeMillis()}.jpg"
            mime.startsWith("video/") -> "video_${System.currentTimeMillis()}.mp4"
            else -> "file_${System.currentTimeMillis()}"
        }
        pendingDraftMime = mime
        showDraftPreview(mime, uri, null)
    }


    private fun clearAttachmentDraft() {
        pendingDraftUri = null
        pendingDraftBytes = null
        pendingDraftName = null
        pendingDraftMime = null
        binding.draftBar.visibility = View.GONE
        binding.ivDraftThumb.setImageDrawable(null)
        binding.ivDraftThumb.setPadding(0, 0, 0, 0)
        binding.tvDraftFileName.visibility = View.GONE
        binding.tvDraftFileName.text = ""
        updateVoiceOrSendIcon()
    }



    // ── Call buttons + chat settings ────────────────────────────────────────────────────

    private fun setupCallButtons(contactName: String) {
        binding.btnChatSettings.setOnClickListener {
            HapticUtil.vibrate(this)
            ChatSettingsActivity.start(this, contactName, viewModel.groupName)
        }

        // ── Search ──────────────────────────────────────────────────────────────────────
        binding.btnSearch.setOnClickListener {
            binding.searchBar.visibility = View.VISIBLE
            binding.etSearchQuery.requestFocus()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(binding.etSearchQuery, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        binding.btnSearchClose.setOnClickListener { closeSearch() }
        binding.etSearchQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.setFilter(s?.toString() ?: "")
            }
        })
        binding.etSearchQuery.setOnEditorActionListener { _, _, _ ->
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
            true
        }

        binding.btnCall.setOnClickListener {
            HapticUtil.vibrate(this)
            if (CallManager.state != CallManager.State.IDLE) {
                showOngoingCallDialog(); return@setOnClickListener
            }
            if (VideoCallManager.state != VideoCallManager.State.IDLE) {
                toast("Already in a video call"); return@setOnClickListener
            }
            com.silvershireinn.app.utils.CallConfirm.voice(this, contactName) {
                placeCallTo(contactName)
            }
        }

        binding.btnVideoCall.setOnClickListener {
            HapticUtil.vibrate(this)
            if (VideoCallManager.state != VideoCallManager.State.IDLE ||
                CallManager.state != CallManager.State.IDLE) {
                toast("Already in a call"); return@setOnClickListener
            }
            com.silvershireinn.app.utils.CallConfirm.video(this, contactName) {
                VideoCallManager.placeCall(contactName)
                startActivity(VideoCallActivity.outgoingIntent(this, contactName))
            }
        }
    }

    private fun placeCallTo(remoteUser: String) {
        CallManager.placeCall(remoteUser)
        startActivity(CallActivity.outgoingIntent(this, remoteUser))
    }

    private fun showOngoingCallDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Call in progress")
            .setMessage("You're in a call with ${com.silvershireinn.app.utils.PreferenceManager.displayNameFor(CallManager.remoteUser)}. End it?")
            .setPositiveButton("End call") { _, _ -> CallManager.endCall() }
            .setNegativeButton("Keep", null)
            .show()
    }


    // ── Open media (image / video tap) ──────────────────────────────────────────────────

    private fun openMedia(msg: Message) {
        val mime   = msg.attachmentMime ?: return
        val fileId = msg.attachmentId   ?: return
        val name   = msg.attachmentName ?: "media"

        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            // Build the authenticated URL so Coil can stream images without a full download
            val url  = try { MediaAuth.fileUrl(fileId) } catch (_: Exception) { "" }
            com.silvershireinn.app.ui.media.MediaViewerActivity.start(this, fileId, name, mime, url, msg.conversationId)
        } else {
            viewModel.downloadAttachment(this, msg)
        }
    }

    /** Download + save then open; for images/video shows the viewer instead */
    private fun handleAttachmentDownload(msg: Message) {
        val mime = msg.attachmentMime ?: ""
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            openMedia(msg)
        } else {
            viewModel.downloadAttachment(this, msg)
        }
    }

    // ── Camera chooser ──────────────────────────────────────────────────────────────────

    private fun showCameraChooser() {
        val options = arrayOf("Take photo", "Record video", "Photos and videos", "File")
        MaterialAlertDialogBuilder(this)
            .setTitle("Add media")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> launchVideoCapture()
                    // The phone's photo picker: no storage permission needed, pick several at once.
                    2 -> try {
                        galleryPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    } catch (_: Exception) { filePicker.launch("*/*") }
                    3 -> filePicker.launch("*/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        try {
            inAppCameraLauncher.launch(CameraCaptureActivity.intent(this, CameraCaptureActivity.MODE_PHOTO))
        } catch (_: Exception) {
            val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", photoFile)
            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    private fun launchVideoCapture() {
        try {
            inAppCameraLauncher.launch(CameraCaptureActivity.intent(this, CameraCaptureActivity.MODE_VIDEO))
        } catch (_: Exception) {
            val videoFile = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", videoFile)
            pendingPhotoUri = uri
            takeVideoLauncher.launch(uri)
        }
    }

    // ── Voice recording ─────────────────────────────────────────────────────────────────

    private fun requestAudioPermissionOrRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startRecording()
        else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (isRecording) return
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        recordingFile = file
        mediaRecorder = com.silvershireinn.app.utils.VoiceRecorder.start(this, file)
            ?: run { recordingFile = null; toast("Microphone is busy or unavailable"); return }
        isRecording = true
        recordingLocked = false
        voicePendingReview = false
        recordingStartTime = System.currentTimeMillis()
        binding.recordingBar.visibility = android.view.View.VISIBLE
        binding.tvRecordingHint.text = "Recording 0:00  -  keep holding to lock"
        binding.tvRecordingHint.show()
        binding.btnCancelRecording.visibility = android.view.View.GONE
        binding.btnPlayPendingRecording.visibility = android.view.View.GONE
        startRecordingTicker()
        recordingLockRunnable?.let { typingHandler.removeCallbacks(it) }
        val lockR = Runnable {
            if (isRecording && !recordingLocked) {
                recordingLocked = true
                suppressUpAfterLock = true
                binding.btnCancelRecording.visibility = android.view.View.VISIBLE
                // Locked: Stop (listen back first), Send, or Delete.
                binding.btnPlayPendingRecording.visibility = android.view.View.VISIBLE
                binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_stop)
                binding.btnVoiceOrSend.setImageResource(R.drawable.ic_send)
                try { HapticUtil.vibrate(this@ChatActivity) } catch (_: Exception) {}
            }
        }
        recordingLockRunnable = lockR
        typingHandler.postDelayed(lockR, RECORD_LOCK_MS)
    }

    private fun stopRecordingAndSend() {
        if (!isRecording) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val recordedOk = com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
        mediaRecorder = null
        isRecording = false
        recordingLocked = false
        voicePendingReview = false
        suppressUpAfterLock = false
        recordingLockRunnable?.let { typingHandler.removeCallbacks(it) }
        recordingLockRunnable = null
        stopRecordingTicker()
        binding.btnPlayPendingRecording.visibility = android.view.View.GONE
        binding.btnCancelRecording.visibility = android.view.View.GONE
        binding.recordingBar.visibility = android.view.View.GONE
        binding.tvRecordingHint.hide()
        binding.btnVoiceOrSend.setImageResource(R.drawable.ic_mic)
        val file = recordingFile ?: return
        recordingFile = null
        if (elapsed < 600L || !recordedOk) {
            // Too short, or the phone produced no usable audio: never send a broken clip.
            file.delete()
            if (elapsed >= 600L) toast("Recording failed. Try again")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = file.readBytes()
            val name  = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
            viewModel.uploadAndSendBytes(name, "audio/mp4", bytes)
            file.delete()
        }
    }

    private var recTicker: Runnable? = null

    /** Live "Recording 0:07" while capturing (with the lock state). */
    private fun startRecordingTicker() {
        stopRecordingTicker()
        val r = object : Runnable {
            override fun run() {
                if (!isRecording) return
                val sec = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
                val t = "%d:%02d".format(sec / 60, sec % 60)
                binding.tvRecordingHint.text = if (recordingLocked) "Recording $t  -  stop to listen, or send"
                                               else "Recording $t  -  keep holding to lock"
                typingHandler.postDelayed(this, 500)
            }
        }
        recTicker = r
        typingHandler.post(r)
    }

    private fun stopRecordingTicker() {
        recTicker?.let { typingHandler.removeCallbacks(it) }
        recTicker = null
    }

    /** Stop capture but keep clip for trash/send (early release before lock). */
    private fun stopRecordingToPendingReview() {
        if (!isRecording) return
        recordingLockRunnable?.let { typingHandler.removeCallbacks(it) }
        recordingLockRunnable = null
        val recordedOk = com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
        mediaRecorder = null
        isRecording = false
        if (!recordedOk || System.currentTimeMillis() - recordingStartTime < 600L) {
            // A quick tap-and-release (or a failed recording) has nothing to review.
            cancelRecording()
            toast("Hold the mic to record a voice message")
            return
        }
        recordingLocked = false
        suppressUpAfterLock = false
        voicePendingReview = true
        stopRecordingTicker()
        binding.recordingBar.visibility = android.view.View.VISIBLE
        val lenSec = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
        binding.tvRecordingHint.text = "%d:%02d  -  play to listen, then send or delete".format(lenSec / 60, lenSec % 60)
        binding.tvRecordingHint.show()
        binding.btnCancelRecording.visibility = android.view.View.VISIBLE
        binding.btnPlayPendingRecording.visibility = android.view.View.VISIBLE
        binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_play)
        try {
            binding.waveformPendingRecording.visibility = android.view.View.VISIBLE
            binding.waveformPendingRecording.setSeed("pending_" + (recordingFile?.name ?: "voice"))
            binding.waveformPendingRecording.setProgressFraction(0f)
            // Real waveform of what was just recorded.
            recordingFile?.let { rf ->
                lifecycleScope.launch {
                    val lv = withContext(Dispatchers.IO) { AudioPlayerManager.levelsForFile(rf) }
                    if (voicePendingReview && recordingFile == rf) binding.waveformPendingRecording.setLevels(lv)
                }
            }
            binding.waveformPendingRecording.onSeekStop = { frac ->
                try {
                    pendingVoicePlayer?.let { pl ->
                        val dur = pl.duration
                        if (dur > 0) pl.seekTo((dur * frac).toInt())
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        binding.btnVoiceOrSend.setImageResource(R.drawable.ic_send)
    }

    /** Upload a pending-review voice clip (recording already stopped). */
    private fun sendPendingVoice() {
        if (!voicePendingReview) return
        stopPendingVoicePlayback()
        val file = recordingFile
        voicePendingReview = false
        recordingFile = null
        binding.btnCancelRecording.visibility = android.view.View.GONE
        binding.recordingBar.visibility = android.view.View.GONE
        binding.tvRecordingHint.hide()
        try { binding.waveformPendingRecording.visibility = android.view.View.GONE } catch (_: Exception) {}
        binding.btnVoiceOrSend.setImageResource(R.drawable.ic_mic)
        if (file == null || !file.exists()) {
            file?.delete()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val name  = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
                viewModel.uploadAndSendBytes(name, "audio/mp4", bytes)
            } finally {
                file.delete()
            }
        }
    }



    private fun startPendingWaveformTicker() {
        stopPendingWaveformTicker()
        val r = object : Runnable {
            override fun run() {
                try {
                    val pl = pendingVoicePlayer ?: return
                    if (!pl.isPlaying) return
                    val dur = pl.duration.coerceAtLeast(1)
                    binding.waveformPendingRecording.setProgressFraction(pl.currentPosition.toFloat() / dur)
                    typingHandler.postDelayed(this, 50L)
                } catch (_: Exception) {}
            }
        }
        pendingWaveformTicker = r
        typingHandler.post(r)
    }
    private fun stopPendingWaveformTicker() {
        pendingWaveformTicker?.let { typingHandler.removeCallbacks(it) }
        pendingWaveformTicker = null
    }

    private fun stopPendingVoicePlayback() {
        stopPendingWaveformTicker()
        try { pendingVoicePlayer?.stop() } catch (_: Exception) {}
        try { pendingVoicePlayer?.release() } catch (_: Exception) {}
        pendingVoicePlayer = null
        try {
            binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_play)
            binding.btnPlayPendingRecording.visibility = android.view.View.GONE
            binding.waveformPendingRecording.setProgressFraction(0f)
        } catch (_: Exception) {}
    }

    private fun togglePendingVoicePlayback() {
        val file = recordingFile ?: return
        val existing = pendingVoicePlayer
        if (existing != null) {
            try {
                if (existing.isPlaying) {
                    existing.pause()
                    binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_play)
                    return
                } else {
                    existing.start()
                    binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_pause)
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
                    binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_play)
                }
                prepare()
                start()
            }
            pendingVoicePlayer = mp
            binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_pause)
            startPendingWaveformTicker()
        } catch (e: Exception) {
            toast("Could not play recording")
            stopPendingVoicePlayback()
        }
    }

    /** Abort an in-progress recording or pending review without sending. */
    private fun cancelRecording() {
        stopPendingVoicePlayback()
        recordingStartRunnable?.let { typingHandler.removeCallbacks(it) }
        recordingStartRunnable = null
        recordingLockRunnable?.let { typingHandler.removeCallbacks(it) }
        recordingLockRunnable = null
        recordingLocked = false
        suppressUpAfterLock = false
        voicePendingReview = false
        if (isRecording) {
            com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
            mediaRecorder = null
            isRecording = false
        }
        binding.btnCancelRecording.visibility = android.view.View.GONE
        binding.recordingBar.visibility = android.view.View.GONE
        binding.tvRecordingHint.hide()
        binding.btnVoiceOrSend.setImageResource(R.drawable.ic_mic)
        recordingFile?.delete()
        recordingFile = null
    }

    // ── Speech to text ──────────────────────────────────────────────────────────────────

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message...")
        }
        try { speechLauncher.launch(intent) }
        catch (_: Exception) { toast("Speech recognition not available") }
    }

    // ── Message context menu ────────────────────────────────────────────────────────────

    // ── Swipe-right to reply ────────────────────────────────────────────────────────────

    private fun setupSwipeToReply() {
        // Springs back instead of sliding the message away (it used to vanish until a refresh).
        val swipeCallback = SwipeToReply(
            messageAt = { pos -> adapter.currentList.getOrNull(pos) },
            canReply = { isReplyable(it) },
            onReply = { startReply(it) }
        )
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)
    }

    private fun setupReplyBar() {
        binding.btnCancelReply.setOnClickListener { clearReply() }
    }

    /** Who wrote [msg], as shown in reply quotes ("You", or their name; groups used the group id). */
    private fun replySender(msg: Message): String =
        if (msg.isSent) "You" else com.silvershireinn.app.utils.PreferenceManager.displayNameFor(msg.fromUser)

    /** One-line text of [msg] for a reply quote: its own text without any quote it carried. */
    private fun quotePreview(msg: Message): String = when {
        msg.isDeleted    -> "[deleted message]"
        msg.isAttachment -> "[file] ${msg.attachmentName ?: "Attachment"}"
        else -> msg.encryptedPayload.let { t ->
            val sep = t.indexOf("\n\n")
            (if (t.startsWith("> ") && sep > 0) t.substring(sep + 2) else t).replace('\n', ' ').trim()
        }
    }

    /** Rows you can't reply to / act on like a normal message. */
    private fun isReplyable(msg: Message): Boolean =
        !msg.isDeleted && !msg.isUploading && msg.attachmentMime != "silvershire/call"

    private fun startReply(msg: Message) {
        if (!isReplyable(msg)) return
        replyToMessage = msg
        binding.tvReplyLabel.text = "Replying to ${replySender(msg)}"
        binding.tvReplyPreview.text = quotePreview(msg)
        binding.replyBar.show()
        binding.etMessage.requestFocus()
    }

    private fun clearReply() {
        replyToMessage = null
        binding.replyBar.hide()
    }

    // ── Message context menu ────────────────────────────────────────────────────────────

    private fun showMessageContextMenu(msg: Message) {
        val options = mutableListOf<String>()

        // Uploading placeholder - only option is to cancel
        if (msg.isUploading) {
            options += "Cancel Upload"
            MaterialAlertDialogBuilder(this)
                .setTitle(null)
                .setItems(options.toTypedArray()) { _, _ ->
                    viewModel.cancelUpload(msg.messageId)
                }
                .show()
            return
        }

        if (isReplyable(msg)) options += "Reply"
        if (isReplyable(msg)) options += "Forward"
        if (!msg.isAttachment && !msg.isDeleted) options += "Copy text"
        if (msg.isAttachment && !msg.isDeleted) {
            val mime = msg.attachmentMime ?: ""
            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                options += "Open"
                options += "Save to phone"
            } else if (mime != "silvershire/call") {
                options += "Save to phone"
            }
            if (mime != "silvershire/call") options += "Copy"
        }

        // Delete options for non-deleted messages
        if (!msg.isDeleted) {
            options += "Delete for me"
            if (msg.isSent && msg.attachmentMime != "silvershire/call") options += "Delete for everyone"
        }

        // Read receipt info (only for sent messages that have been read)
        if (msg.isSent && msg.isRead) options += "Seen"

        options += "Export chat"

        MaterialAlertDialogBuilder(this)
            .setTitle(null)
            .setItems(options.toTypedArray()) { _, which ->
                when (val opt = options[which]) {
                    "Reply"      -> startReply(msg)
                    "Forward"    -> forwardMessage(msg)
                    "Copy text" -> {
                        val clip = ClipData.newPlainText("message", quotePreview(msg).let { if (msg.encryptedPayload.startsWith("> ")) it else msg.encryptedPayload })
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        toast("Copied")
                    }
                    "Open"          -> openMedia(msg)
                    "Save to phone" -> viewModel.downloadAttachment(this, msg)
                    "Copy"          -> copyAttachmentToClipboard(msg)
                    "Delete for me" -> viewModel.deleteForMe(msg)
                    "Delete for everyone" -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Delete for everyone?")
                            .setMessage("This message will be deleted for all participants.")
                            .setPositiveButton("Delete") { _, _ -> viewModel.deleteForEveryone(msg) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    "Seen" -> toast("Read by ${com.silvershireinn.app.utils.PreferenceManager.displayNameFor(viewModel.contactUsername)}")
                    "Export chat" -> exportChat()
                }
            }
            .show()
    }

    /**
     * Downloads the attachment file (using cache if available) and copies it to the clipboard
     * as a content URI so the user can paste it into other apps (e.g. Photos, WhatsApp, Notes).
     */
    private fun copyAttachmentToClipboard(msg: Message) {
        val fileId   = msg.attachmentId   ?: return
        val fileName = msg.attachmentName ?: "file"
        val mimeType = msg.attachmentMime ?: "*/*"

        toast("Copying...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Same cached copy the viewer uses (downloaded once, streamed to disk).
                val targetFile = FileRepository.ensureCached(this@ChatActivity, fileId, mimeType, fileName)
                if (targetFile == null) {
                    withContext(Dispatchers.Main) { toast("Copy failed - download error") }
                    return@launch
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@ChatActivity,
                    "${packageName}.provider",
                    targetFile
                )
                val clip = android.content.ClipData.newUri(contentResolver, fileName, uri)
                withContext(Dispatchers.Main) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    toast("Copied to clipboard")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Copy failed - download error") }
            }
        }
    }

    private fun exportChat() {
        lifecycleScope.launch {
            val bytes    = viewModel.exportChatAsText()
            val who = (if (viewModel.groupId != null) viewModel.groupName else
                com.silvershireinn.app.utils.PreferenceManager.displayNameFor(viewModel.contactUsername))
                .replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifBlank { "chat" }
            val fileName = "Silvershire chat - $who - ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())}.txt"
            val ok       = withContext(Dispatchers.IO) {
                FileRepository.saveToDownloads(this@ChatActivity, fileName, "text/plain", bytes)
            }
            if (ok) toast("Chat exported to Downloads: $fileName")
            else    toast("Export failed")
        }
    }

    // ── Observers ───────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        // repeatOnLifecycle(STARTED) is the canonical safe pattern for UI observers:
        // coroutines are automatically CANCELLED when the activity goes to STOPPED
        // (e.g. when ChatSettingsActivity opens) and RE-STARTED when it returns to STARTED.
        // This prevents observers from touching binding/views while the activity is hidden,
        // which is the root cause of the intermittent crash-back-to-chats bug.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    try {
                        viewModel.messages.collect { list ->
                            // Scroll AFTER the diff is committed so the new item is actually
                            // in the RecyclerView before we ask it to scroll there.
                            //
                            // Auto-scroll policy (user-specified):
                            //  1. First list emission after entering the chat -> always scroll once.
                            //  2. After that, scroll only if the user is already at/near the bottom,
                            //     or the newest message is one WE just sent.
                            //  3. If a new incoming message arrives while scrolled up, do NOT yank
                            //     the view - show a red counter on the scroll-to-bottom FAB instead.
                            val searchOpen = binding.searchBar.visibility == View.VISIBLE
                            // PIXEL-perfect bottom check. The old item-based check
                            // (within 2 items of the end) failed on real chats: big
                            // image/video bubbles mean "2 items" can be a whole screen,
                            // so scrolled-up users were still yanked down on every update.
                            val atBottom = adapter.itemCount == 0 ||
                                !binding.rvMessages.canScrollVertically(1)
                            val newest = list.lastOrNull()
                            val newestIsMine = newest != null && newest.isSent &&
                                newest.messageId != lastSeenNewestId
                            val newIncomingCount =
                                if (lastSeenNewestId == null) 0
                                else list.takeLastWhile { it.messageId != lastSeenNewestId }.count { !it.isSent }

                            // Don't yank the view to the bottom when the user sends a message
                            // while scrolled up - unless they've opted into "jump to bottom on send".
                            // (Sending while already at the bottom still follows via `atBottom`.)
                            val shouldScroll = !searchOpen && list.isNotEmpty() &&
                                (!hasAutoScrolledOnce || atBottom ||
                                    (newestIsMine && com.silvershireinn.app.utils.PreferenceManager.jumpToBottomOnSend))

                            lastSeenNewestId = newest?.messageId

                            // New incoming message while this chat is on screen: mark it read now
                            // (reliable, unlike guessing when the database write has finished).
                            if (newIncomingCount > 0 || list.any { !it.isSent && !it.isRead }) viewModel.onMessagesShown()

                            adapter.submitSearchableList(list, if (shouldScroll) Runnable {
                                hasAutoScrolledOnce = true
                                binding.rvMessages.scrollToPosition(list.size - 1)
                                updateFabUnreadBadge(0)
                            } else Runnable {
                                if (newIncomingCount > 0) {
                                    updateFabUnreadBadge(fabUnreadCount + newIncomingCount)
                                    binding.fabScrollBottom.visibility = View.VISIBLE
                                }
                            })
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatActivity", "messages observer crashed", e)
                    }
                }
                launch {
                    try {
                        // collectLatest + delay: a reconnect that finishes within 2 s no longer
                        // flashes the "disconnected" banner.
                        WebSocketManager.state.collectLatest { state ->
                            when (state) {
                                is WebSocketManager.ConnectionState.Connected -> binding.bannerDisconnected.hide()
                                else -> { kotlinx.coroutines.delay(2_000); binding.bannerDisconnected.show() }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatActivity", "connection state observer crashed", e)
                    }
                }
                launch {
                    // Live "Uploading 42%" on the bubbles of files being sent.
                    try { FileRepository.uploadProgress.collect { adapter.setUploadProgress(it) } } catch (_: Exception) {}
                }
                launch {
                    try {
                        viewModel.isContactTyping.collect { typing ->
                            if (typing) binding.tvTypingIndicator.show() else binding.tvTypingIndicator.hide()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatActivity", "typing observer crashed", e)
                    }
                }
                launch {
                    try {
                        viewModel.uploadState.collect { state ->
                            when (state) {
                                // The bubble itself shows uploading / sent now; only failures toast.
                                is UploadState.Error     -> toast(state.message)
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatActivity", "upload state observer crashed", e)
                    }
                }
                launch {
                    try {
                        viewModel.downloadEvent.collect { (fileName, success) ->
                            if (success) toast("Saved to your phone: $fileName")
                            else toast("Download failed")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatActivity", "download event observer crashed", e)
                    }
                }
                // Real-time online/last-seen subtitle - only for 1:1 chats
                if (viewModel.groupId == null) {
                    launch {
                        try {
                            com.silvershireinn.app.network.OnlineStatusManager.statusFlow.collect {
                                val label = com.silvershireinn.app.network.OnlineStatusManager
                                    .statusLabel(contactName)
                                supportActionBar?.subtitle = label.ifBlank { null }
                                binding.toolbar.findViewById<android.widget.TextView>(
                                    androidx.appcompat.R.id.action_bar_subtitle
                                )?.setTextColor(
                                    com.silvershireinn.app.network.OnlineStatusManager.statusColor(contactName)
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatActivity", "online status observer crashed", e)
                        }
                    }
                }
            }
        }
    }

    private fun observeCallState() {
        // The call screens own the call listeners. Setting one here used to replace the call
        // screen's listener (and clear it on exit), leaving the call screen stuck after hang-up.
        // WebSocketService already opens the call screen for incoming calls.
    }

    // ── SharedFilesBottomSheet.Listener ─────────────────────────────────────────────────

    override fun onSendFileClicked() { filePicker.launch("*/*") }

    override fun onDownloadFileFromDrawer(info: SharedFileInfo) {
        lifecycleScope.launch(Dispatchers.IO) {
            val f  = FileRepository.ensureCached(this@ChatActivity, info.fileId, info.mimeType, info.fileName)
            val ok = f != null && FileRepository.saveFileToGallery(this@ChatActivity, f, info.fileName, info.mimeType)
            withContext(Dispatchers.Main) {
                if (ok) toast("Saved: ${info.fileName}") else toast("Download failed")
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateBack()
        return true
    }

    /** Back-navigation that is aware of the chat-head launch path.
     *  If ChatActivity was opened directly from a chat head bubble (no MainActivity in the
     *  back stack), pressing back would normally send the user to the home screen.
     *  Instead we explicitly start MainActivity so they land on the Chats tab.
     *
     *  IMPORTANT: Do NOT call onBackPressedDispatcher.onBackPressed() here - this activity
     *  registers an OnBackPressedCallback that calls navigateBack(), so calling
     *  onBackPressedDispatcher.onBackPressed() from inside navigateBack() creates infinite
     *  recursion -> StackOverflowError -> crash. Just call finish() to pop the back stack. */
    private fun closeSearch() {
        binding.searchBar.visibility = View.GONE
        binding.etSearchQuery.setText("")
        adapter.setFilter("")
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
    }

    private fun navigateBack() {
        // From a chat head: just finish, back to whatever app was in front before.
        // From a notification (nothing behind this screen): go to the chat list instead of
        // dropping out to the home screen.
        if (isTaskRoot && !intent.getBooleanExtra("launched_from_chat_head", false)) {
            try {
                startActivity(Intent(this, com.silvershireinn.app.ui.main.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (_: Exception) {}
        }
        finish()
    }

    // ── Reply jump ──────────────────────────────────────────────────────────────────────

    /** Tapped a reply quote -> scroll to the original message (heuristic match) and flash it. */
    private fun jumpToReplyOriginal(reply: Message) {
        val raw = reply.encryptedPayload
        val sep = raw.indexOf("\n\n")
        if (!raw.startsWith("> ") || sep <= 0) return
        val quote = raw.substring(0, sep).removePrefix("> ")
        val ci = quote.indexOf(": ")
        val preview = (if (ci >= 0) quote.substring(ci + 2) else quote).trim()
        if (preview.isEmpty()) return
        val list = adapter.currentList
        val replyIdx = list.indexOfFirst { it.messageId == reply.messageId }
        val end = if (replyIdx >= 0) replyIdx else list.size
        for (i in end - 1 downTo 0) {
            val m = list[i]
            if (m.isDeleted) continue
            val content = if (m.isAttachment) "[file] ${m.attachmentName ?: ""}" else m.encryptedPayload
            if (content.startsWith(preview) || preview.startsWith(content) || content == preview) {
                highlightMessageAt(i)
                return
            }
        }
        toast("Original message not found")
    }

    private fun highlightMessageAt(position: Int) {
        binding.rvMessages.smoothScrollToPosition(position)
        binding.rvMessages.postDelayed({
            val v = binding.rvMessages.findViewHolderForAdapterPosition(position)?.itemView
                ?: return@postDelayed
            v.animate().alpha(0.35f).setDuration(180).withEndAction {
                v.animate().alpha(1f).setDuration(450).start()
            }.start()
        }, 350)
    }

    // ── Toolbar contact avatar ──────────────────────────────────────────────────────────

    private fun loadGroupMemberSubtitle(groupId: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val group = com.silvershireinn.app.SilvershireApp.database.groupDao().getGroup(groupId)
            if (group != null) {
                val members = try {
                    val arr = org.json.JSONArray(group.membersJson)
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } catch (_: Exception) { emptyList() }
                // Names, not usernames; "You" last.
                val me = com.silvershireinn.app.utils.PreferenceManager.username
                val others = members.filter { it != me }
                    .map { com.silvershireinn.app.utils.PreferenceManager.displayNameFor(it) }.sortedBy { it.lowercase() }
                val subtitle = (others + if (me in members) listOf("You") else emptyList()).joinToString(", ")
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    supportActionBar?.subtitle = subtitle.ifBlank { null }
                }
            }
        }
    }

    private val toolbarVideo by lazy { com.silvershireinn.app.utils.MutedLoopVideoHelper(this, binding.vvToolbarAvatar) }

    private fun loadToolbarAvatar() {
        val picFile = com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(this, contactName)
        if (picFile != null && picFile.exists()) {
            binding.toolbarAvatarCard.visibility = View.VISIBLE
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(binding.toolbarAvatarCard, com.silvershireinn.app.utils.AvatarShapeHelper.forUser(contactName))
            com.silvershireinn.app.utils.ProfileFrames.applyFloating(binding.toolbarAvatarCard, contactName)
            if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(picFile.absolutePath)) {
                val frame = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(picFile.absolutePath)
                if (frame != null) binding.ivToolbarAvatar.setImageBitmap(frame)
                else binding.ivToolbarAvatar.load(picFile)
                toolbarVideo.play(picFile.absolutePath, binding.ivToolbarAvatar)   // loops in the header too
            } else {
                toolbarVideo.clear()
                binding.ivToolbarAvatar.load(picFile)
            }
            // Push the ActionBar title right: nav(56dp) + avatar(36dp) + gap(8dp) = 100dp total,
            // subtract the default contentInsetStart (~72dp) -> add ~28dp extra title margin.
            binding.toolbar.setTitleMarginStart(36.dpToPx())
        } else {
            binding.toolbarAvatarCard.visibility = View.GONE
            binding.toolbar.setTitleMarginStart(0)
        }
        // Always wire the tap - even without a pic the card might become visible later
        binding.toolbarAvatarCard.setOnClickListener {
            showContactProfile()
        }
    }

    private fun showContactProfile() {
        com.silvershireinn.app.ui.contacts.ContactProfileBottomSheet
            .newInstance(contactName)
            .show(supportFragmentManager, "contact_profile")
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStart() {
        super.onStart()
        val filter         = IntentFilter("com.silvershireinn.PROFILE_UPDATED")
        val fontFilter     = IntentFilter("com.silvershireinn.CHAT_FONT_CHANGED")
        val removedFilter  = IntentFilter("com.silvershireinn.CONTACT_REMOVED")
        val groupFilter    = IntentFilter("com.silvershireinn.GROUP_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileUpdatedReceiver, filter,        Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(chatFontChangedReceiver, fontFilter,   Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(contactRemovedReceiver, removedFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(groupUpdatedReceiver,   groupFilter,   Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(profileUpdatedReceiver, filter)
            registerReceiver(chatFontChangedReceiver, fontFilter)
            registerReceiver(contactRemovedReceiver, removedFilter)
            registerReceiver(groupUpdatedReceiver, groupFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::adapter.isInitialized) return   // share picker / redirect path: nothing set up
        // Leaving mid-recording (home, call, screen off): Android cuts the mic in the
        // background, so stop now and keep the clip for review instead of recording silence.
        if (isRecording) stopRecordingToPendingReview()
        try { pendingVoicePlayer?.takeIf { it.isPlaying }?.pause(); binding.btnPlayPendingRecording.setImageResource(R.drawable.ic_play) } catch (_: Exception) {}
        val thisContact = intent.getStringExtra("group_id")
            ?: intent.getStringExtra("contact_username")
        if (activeChatContact == thisContact) activeChatContact = null
        // Stop auto-sending read receipts while not visible (screen off / Home / another activity)
        viewModel.setChatVisible(false)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(profileUpdatedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(chatFontChangedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(contactRemovedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(groupUpdatedReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // If launched from a notification tap while already running, dismiss that notification
        com.silvershireinn.app.notifications.NotificationHelper.cancelMessageNotifications(this, contactName)
    }

    private var lastTsKey: String? = null

    override fun onResume() {
        super.onResume()
        // The share picker path returns before the chat is built: touching the adapter here
        // crashed the app whenever something was shared into Silvershire.
        if (!::adapter.isInitialized) return
        if (com.silvershireinn.app.utils.AppLock.gate(this)) return   // idle too long
        // Time format settings changed while away: redraw the times.
        val tsKey = "${com.silvershireinn.app.utils.PreferenceManager.showFullTimestamps}/${com.silvershireinn.app.utils.PreferenceManager.use24HourClock}"
        if (lastTsKey != null && lastTsKey != tsKey) adapter.notifyDataSetChanged()
        lastTsKey = tsKey
        // Track which chat is open so WebSocketService can suppress chat heads for it
        activeChatContact = intent.getStringExtra("group_id")
            ?: intent.getStringExtra("contact_username")
        // This chat is being read here: clear its badge on the chat head (keep the head).
        activeChatContact?.let {
            com.silvershireinn.app.ui.chathead.ChatHeadService.clearUnread(this, it)
        }
        // Conversation is on screen - flush pending read receipts (WhatsApp-like)
        viewModel.setChatVisible(true)
        // Re-apply chat background color (may have changed in ChatSettingsActivity)
        val contactName = intent.getStringExtra("contact_username") ?: ""
        val bgHex = ChatSettingsActivity.getChatBgColor(this, contactName)
        if (bgHex != null) {
            try {
                binding.rvMessages.setBackgroundColor(android.graphics.Color.parseColor(bgHex))
            } catch (_: Exception) {}
        } else {
            binding.rvMessages.setBackgroundResource(0)   // clear custom background -> shows parent
        }
        // Update toolbar title with current nickname (skip for group chats - use group name)
        val groupIdResume = intent.getStringExtra("group_id")
        val groupNameResume = intent.getStringExtra("group_name") ?: ""
        val displayName = if (groupIdResume != null) {
            groupNameResume.ifBlank { "Group" }
        } else {
            com.silvershireinn.app.utils.PreferenceManager.displayNameFor(contactName)
        }
        supportActionBar?.title = displayName

        // Force-rebind all visible items so font/bubble-color changes apply immediately
        if (::adapter.isInitialized) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }

        // Refresh background (video or image - may have changed in ChatSettingsActivity)
        val cn = intent.getStringExtra("contact_username") ?: ""
        val videoPath = com.silvershireinn.app.utils.PreferenceManager.getChatVideoBg(cn)
        val imagePath = com.silvershireinn.app.utils.PreferenceManager.getChatImageBg(cn)
        if (videoPath != null) {
            binding.ivChatImageBg.visibility = android.view.View.GONE
            chatVideoBgHelper?.applyPath(videoPath)
        } else if (imagePath != null && java.io.File(imagePath).exists()) {
            chatVideoBgHelper?.applyPath(null)
            binding.ivChatImageBg.visibility = android.view.View.VISIBLE
            binding.ivChatImageBg.load(java.io.File(imagePath))
        } else {
            chatVideoBgHelper?.applyPath(null)
            binding.ivChatImageBg.visibility = android.view.View.GONE
        }
        // Apply opacity to the main content container
        val opacity = com.silvershireinn.app.utils.PreferenceManager.uiOpacity / 100f
        binding.root.alpha = 1f   // keep root opaque so background shows through
        // Apply opacity to UI panels individually (toolbar, input bar, messages)
        binding.toolbar.alpha = opacity
        binding.inputBar.alpha = opacity

        // Dismiss any lingering notifications for this conversation
        com.silvershireinn.app.notifications.NotificationHelper.cancelMessageNotifications(this, contactName)

        // Apply accent color and app-wide font to all views in this screen
        AccentColorHelper.apply(binding.root, com.silvershireinn.app.utils.PreferenceManager.accentColorInt())
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, com.silvershireinn.app.utils.PreferenceManager.fontFamily)
        // Apply global UI text color to the chat screen (toolbar, labels, etc.)
        AccentColorHelper.applyTextColor(binding.root, com.silvershireinn.app.utils.PreferenceManager.uiTextColorInt())
        // Apply per-chat message text color to bubble text in the adapter
        adapter.messageTextColor = com.silvershireinn.app.utils.PreferenceManager.chatTextColorInt(contactName)

        // Reload toolbar avatar in case the contact uploaded a new pic while we were away
        loadToolbarAvatar()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        com.silvershireinn.app.utils.AppLock.noteActivity()   // chatting counts as activity for auto-lock
    }

    override fun onDestroy() {
        try { toolbarVideo.release() } catch (_: Exception) {}
        // Closing the chat stops a voice message from this chat (it used to keep playing on
        // after you left, with no way to pause it).
        if (isFinishing && ::adapter.isInitialized) {
            val playing = AudioPlayerManager.currentMessageId()
            if (playing != null && adapter.currentList.any { it.messageId == playing }) AudioPlayerManager.stopCurrent()
        }
        recordingStartRunnable?.let { typingHandler.removeCallbacks(it) }
        recordingStartRunnable = null
        if (isRecording) {
            com.silvershireinn.app.utils.VoiceRecorder.stop(mediaRecorder)
            mediaRecorder = null
        }
        chatVideoBgHelper?.release()
        super.onDestroy()
    }
}
