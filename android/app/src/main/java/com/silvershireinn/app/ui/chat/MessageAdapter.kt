package com.silvershireinn.app.ui.chat

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.text.style.ImageSpan
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.request.CachePolicy
import com.silvershireinn.app.R
import com.silvershireinn.app.databinding.ItemMessageAttachmentReceivedBinding
import com.silvershireinn.app.databinding.ItemMessageAttachmentSentBinding
import com.silvershireinn.app.databinding.ItemMessageAudioReceivedBinding
import com.silvershireinn.app.databinding.ItemMessageAudioSentBinding
import com.silvershireinn.app.databinding.ItemMessageCallReceivedBinding
import com.silvershireinn.app.databinding.ItemMessageCallSentBinding
import com.silvershireinn.app.databinding.ItemMessageReceivedBinding
import com.silvershireinn.app.databinding.ItemMessageSentBinding
import com.silvershireinn.app.model.Message
import com.silvershireinn.app.network.MediaAuth
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.utils.LinkPreviewHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.formatTimestamp
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class MessageAdapter(
    private val currentUser: String,
    private val contactUsername: String = "",        // for per-contact received bubble color
    val isGroupChat: Boolean = false,                // shows sender name above received messages
    private val onDownload: (Message) -> Unit = {},
    private val onTapMedia: (Message) -> Unit = {},
    private val onLongPress: (Message) -> Unit = {},
    private val onCallBack: (String) -> Unit = {},   // remoteUser to call back
    /** Tapped a reply quote → jump to the original message (host scrolls + highlights). */
    private val onReplyJump: (Message) -> Unit = {},
    /** Silvershire Updates APK notice: open /download/silvershire-inn.apk. */
    private val onApkDownload: (Message) -> Unit = {},
    /**
     * Called when a link-preview card is tapped. When null (default — normal chat),
     * the adapter opens the URL directly. When set (e.g. from the overlay service),
     * the caller handles the URL (e.g. close the overlay first, then open browser).
     */
    val onOpenUrl: ((String) -> Unit)? = null,
    /** Tapped the sender's portrait beside a received message. */
    private val onAvatarTap: (String) -> Unit = {}
) : ListAdapter<Message, RecyclerView.ViewHolder>(DIFF) {

    // ── Sender portrait beside received messages ─────────────────────────────
    /**
     * Shows the sender's picture (or colored initials) next to a received bubble so it is
     * always obvious who wrote it. Mode 0 = every message, 1 = only the last of a run
     * (the others keep the space so bubbles stay aligned), 2 = off.
     */
    private fun bindSenderAvatar(frame: View, iv: ImageView, tv: android.widget.TextView,
                                 msg: Message, position: Int, mine: Boolean = false) {
        val mode = PreferenceManager.chatAvatarMode
        if (mine && !PreferenceManager.showMyAvatar) { frame.visibility = View.GONE; return }
        if (!mine && mode == 2) { frame.visibility = View.GONE; return }
        val user = if (mine) PreferenceManager.username else msg.fromUser.ifBlank { contactUsername }
        if (mode == 1) {
            val next = if (position + 1 < itemCount) getItem(position + 1) else null
            val sameRun = next != null && !isSystemMessage(next) &&
                (if (mine) next.isSent else (!next.isSent && next.fromUser == msg.fromUser))
            if (sameRun) { frame.visibility = View.INVISIBLE; return }
        }
        frame.visibility = View.VISIBLE
        iv.tag = null
        val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(user)
        com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(frame, shape)
        com.silvershireinn.app.utils.ProfileFrames.applyFloating(frame, user)
        tv.background = com.silvershireinn.app.utils.AvatarShapeHelper.backgroundDrawable(
            user.toAvatarColor(), shape)
        tv.text = user.toInitials()
        val pic = if (mine) com.silvershireinn.app.utils.ProfileMediaHelper.findOwnProfilePic(frame.context)
                  else com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(frame.context, user)
        if (pic != null && pic.exists()) {
            iv.visibility = View.VISIBLE
            if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(pic.absolutePath)) {
                iv.dispose()
                // Video portraits move here too: a short shared loop of small frames (one timer
                // for every bubble), with the still frame until the loop is ready.
                iv.setImageDrawable(null)
                val path = pic.absolutePath
                com.silvershireinn.app.utils.AvatarLoop.bind(iv, path) { target ->
                    thumbScope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            try { com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(path) } catch (_: Throwable) { null }
                        }
                        if (target.tag != path) return@launch
                        if (bmp != null) target.setImageBitmap(bmp) else target.visibility = View.GONE
                    }
                }
            } else {
                com.silvershireinn.app.utils.AvatarLoop.unbind(iv)
                iv.load(pic) {
                    memoryCacheKey("chat_avatar_${pic.absolutePath}_${pic.lastModified()}")
                    size(96)
                    listener(onError = { _, _ -> iv.visibility = View.GONE })
                }
            }
        } else {
            com.silvershireinn.app.utils.AvatarLoop.unbind(iv)
            iv.dispose()
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
        }
        if (mine) frame.setOnClickListener(null) else frame.setOnClickListener { onAvatarTap(user) }
        frame.contentDescription = user
    }

    /**
     * Returns the user's custom sent-bubble color tint for [msg], or null to use default.
     * Only applies if [msg] was sent after the color was set (so old messages keep their color).
     */
    private fun sentBubbleTint(msg: Message): ColorStateList? {
        val hex   = PreferenceManager.myBubbleColor ?: return null
        val since = PreferenceManager.myBubbleColorSince
        // If there's a "since" timestamp and the message predates it, use default
        if (since != null && msg.timestamp < since) return null
        return try { ColorStateList.valueOf(Color.parseColor(hex)) } catch (_: Exception) { null }
    }

    /**
     * Returns the received-bubble color tint for [contactUsername] for [msg], or null for default.
     * Only applies if [msg] arrived after the color was set.
     */
    private fun receivedBubbleTint(msg: Message): ColorStateList? {
        // Per-contact color wins; else global theirBubbleColor; else theme default.
        val contactHex = if (contactUsername.isNotBlank()) PreferenceManager.getContactBubbleColor(contactUsername) else null
        val hex: String?
        val since: String?
        if (contactHex != null) {
            hex = contactHex
            since = PreferenceManager.getContactBubbleColorSince(contactUsername)
        } else {
            hex = PreferenceManager.theirBubbleColor
            since = PreferenceManager.theirBubbleColorSince
        }
        if (hex == null) return null
        if (since != null && msg.timestamp < since) return null
        return try { ColorStateList.valueOf(Color.parseColor(hex)) } catch (_: Exception) { null }
    }

    // ── Sent vs received must always be easy to tell apart ────────────────────
    // Defaults mirror res/values/colors.xml (color_msg_sent / color_msg_received).
    private val defaultSentColor = 0xFF4E4027.toInt()
    private val defaultRecvColor = 0xFF2A2B31.toInt()

    /** Color received bubbles in this chat currently show (custom or default). */
    private fun currentReceivedColor(): Int {
        val hex = (if (contactUsername.isNotBlank()) PreferenceManager.getContactBubbleColor(contactUsername) else null)
            ?: PreferenceManager.theirBubbleColor
        return hex?.let { try { Color.parseColor(it) } catch (_: Exception) { null } } ?: defaultRecvColor
    }

    private fun colorDistance(a: Int, b: Int): Double {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /**
     * Sent-bubble tint guaranteed to look different from the received bubbles. If the user's
     * colors (or a custom received color vs our default) are too close, nudge the sent bubble
     * toward the accent color, or lighter/darker, until the two sides are clearly distinct.
     */
    private fun sentTintDistinct(msg: Message): ColorStateList? {
        val custom = sentBubbleTint(msg)
        val sent = custom?.defaultColor ?: defaultSentColor
        val recv = currentReceivedColor()
        if (colorDistance(sent, recv) >= 40.0) return custom
        var out = blend(sent, PreferenceManager.accentColorInt(), 0.40f)
        if (colorDistance(out, recv) < 40.0) {
            val recvLight = (Color.red(recv) * 299 + Color.green(recv) * 587 + Color.blue(recv) * 114) / 1000 > 128
            out = if (recvLight) blend(sent, Color.BLACK, 0.45f) else blend(sent, Color.WHITE, 0.30f)
        }
        return ColorStateList.valueOf(out)
    }

    // ── Per-chat message text color ───────────────────────────────────────────
    // When set, this color overrides the theme default for message text in both sent and received bubbles.
    // null = use the XML-defined text color (color_text_primary / white).
    var messageTextColor: Int? = null
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }   // was a full rebind on every resume

    /** Text color for a bubble: the chat's custom color, else the layout's own default. */
    private fun applyTextColor(tv: android.widget.TextView, default: ColorStateList) {
        val c = messageTextColor
        if (c != null) tv.setTextColor(c) else tv.setTextColor(default)
    }

    // ── Upload progress ("Uploading 42%") ────────────────────────────────────
    private var uploadPct: Map<String, Int> = emptyMap()
    private val PAYLOAD_PROGRESS = "upload_progress"

    /** Feed from FileRepository.uploadProgress; only the uploading rows are refreshed. */
    fun setUploadProgress(map: Map<String, Int>) {
        val changed = (map.keys + uploadPct.keys).filter { map[it] != uploadPct[it] }.toSet()
        uploadPct = map
        if (changed.isEmpty()) return
        currentList.forEachIndexed { i, m -> if (m.messageId in changed && m.isUploading) notifyItemChanged(i, PAYLOAD_PROGRESS) }
    }

    private fun uploadingLabel(id: String): String = uploadPct[id]?.let { "Uploading $it%" } ?: "Uploading..."

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
            val m = getItem(position)
            when (holder) {
                is AttachmentSentVH -> if (m.isUploading) { holder.binding.tvAttachmentSize.text = uploadingLabel(m.messageId); return }
                is AudioSentVH -> if (m.isUploading) { holder.binding.tvAudioDuration.text = uploadPct[m.messageId]?.let { "Sending $it%" } ?: "Sending..."; return }
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    // ── Search filter ─────────────────────────────────────────────────────────
    // The full, unfiltered list is kept here so we can restore it when search is cleared.
    private var fullList: List<Message> = emptyList()
    private var activeQuery: String = ""

    /**
     * Call instead of [submitList] to keep a searchable master copy.
     * If a query is active the new list is immediately filtered before display.
     */
    fun submitSearchableList(list: List<Message>, commitCallback: Runnable? = null) {
        fullList = list
        val displayed = if (activeQuery.isBlank()) list
        else list.filter { matches(it, activeQuery) }
        submitList(displayed) {
            // "Last of a run" avatars: the previous bottom message may need to hide its portrait.
            if (PreferenceManager.chatAvatarMode == 1 && itemCount > 1) {
                notifyItemRangeChanged((itemCount - 3).coerceAtLeast(0), 3.coerceAtMost(itemCount))
            }
            commitCallback?.run()
        }
    }

    /**
     * Filter visible messages to those whose text content contains [query].
     * Pass an empty string to clear the filter and show all messages.
     */
    fun setFilter(query: String) {
        activeQuery = query
        val displayed = if (query.isBlank()) fullList
        else fullList.filter { matches(it, query) }
        submitList(displayed)
    }

    /** Search the whole message (the preview is cut at 60 characters) and file names. */
    private fun matches(m: Message, q: String): Boolean = !m.isDeleted && (
        m.encryptedPayload.contains(q, ignoreCase = true) ||
        (m.attachmentName?.contains(q, ignoreCase = true) == true && m.attachmentMime != MIME_CALL))

    // Scope for thumbnail loading — cancelled when adapter is detached/destroyed
    private val thumbScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)


    /**
     * Load the first video frame in the background and display it in [imageView].
     *
     * Strategy: download the first 2 MB of the video via OkHttp (reliable, handles auth +
     * headers), write to a temp file, then use MediaMetadataRetriever on the local file.
     * This is more reliable than feeding an HTTP URL directly to MMR, which struggles with
     * range requests, redirects, and non-standard server configurations.
     *
     * Results are cached by URL so rapid RecyclerView scrolling doesn't re-download.
     * [imageView.dispose()] must be called by the caller BEFORE this to cancel any
     * pending Coil request that could race and overwrite our grey placeholder.
     */
    private fun loadVideoThumbnail(msg: Message, imageView: ImageView): Job {
        val fileId = msg.attachmentId ?: ""
        // Memory hit: paint immediately, no placeholder flash.
        com.silvershireinn.app.utils.VideoThumbs.cached(fileId)?.let {
            imageView.setImageBitmap(it); return thumbScope.launch {}
        }
        imageView.setImageResource(android.R.color.darker_gray)
        imageView.tag = fileId
        return thumbScope.launch {
            val bmp = com.silvershireinn.app.utils.VideoThumbs.get(imageView.context, fileId, mediaUrl(msg))
            if (bmp != null && imageView.tag == fileId) imageView.setImageBitmap(bmp)
        }
    }

    /**
     * Apply Linkify to [textView] and then asynchronously fetch+show a link preview
     * below the message. Returns the launched [Job] so callers can cancel it on rebind.
     */
    private fun applyLinksAndPreview(
        text: String,
        textView: android.widget.TextView,
        previewLayout: android.view.ViewGroup,
        imageView: android.widget.ImageView,
        tvDomain: android.widget.TextView,
        tvTitle: android.widget.TextView,
        tvDesc: android.widget.TextView
    ): Job? {
        // Step 1: make URLs in the message TEXT tappable via Linkify.
        com.silvershireinn.app.utils.LinkPreviewHelper.linkifyTextView(textView)
        textView.setLinkTextColor(0xFF4EA1FF.toInt())
        // Use SafeLinkMovementMethod so long-press on a link doesn't ALSO open the URL.
        textView.movementMethod = SafeLinkMovementMethod()

        // ── CRITICAL FIX ────────────────────────────────────────────────────────
        // URLSpan.onClick() calls context.startActivity() WITHOUT FLAG_ACTIVITY_NEW_TASK.
        // From a non-Activity context (like the chat-head overlay service) this throws
        // AndroidRuntimeException and crashes. Replace every URLSpan with a custom one
        // that: (a) always adds FLAG_ACTIVITY_NEW_TASK, and (b) in the overlay calls
        // onOpenUrl so the overlay closes BEFORE the browser launches (Android 10+
        // background-launch restriction: we must have a visible window when launching).
        val spannable = textView.text as? android.text.Spannable
        spannable?.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
            ?.forEach { old ->
                val start = spannable.getSpanStart(old)
                val end   = spannable.getSpanEnd(old)
                val flags = spannable.getSpanFlags(old)
                val u     = old.url
                spannable.removeSpan(old)
                spannable.setSpan(object : android.text.style.URLSpan(u) {
                    override fun onClick(widget: android.view.View) {
                        if (onOpenUrl != null) {
                            onOpenUrl.invoke(u)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { widget.context.startActivity(intent) } catch (_: Exception) {}
                        }
                    }
                }, start, end, flags)
            }
        // ────────────────────────────────────────────────────────────────────────

        // Reset preview card state
        previewLayout.visibility = View.GONE
        imageView.visibility     = View.GONE

        val url = LinkPreviewHelper.firstUrl(text) ?: return null

        return thumbScope.launch {
            val preview = LinkPreviewHelper.fetch(url) ?: return@launch
            if (preview.title.isBlank()) return@launch

            tvDomain.text = preview.domain
            tvTitle.text  = preview.title

            if (preview.description.isNotBlank()) {
                tvDesc.text       = preview.description
                tvDesc.visibility = View.VISIBLE
            } else {
                tvDesc.visibility = View.GONE
            }

            if (preview.imageUrl != null) {
                imageView.load(preview.imageUrl) {
                    listener(
                        onSuccess = { _, _ -> imageView.visibility = View.VISIBLE },
                        onError   = { _, _ -> imageView.visibility = View.GONE }
                    )
                }
            }

            // Touch listener for the preview CARD (distinct from the text URL spans above).
            // Returning true on ACTION_DOWN fully owns the gesture — prevents Android's
            // built-in long-press timer from firing onLongPress(msg) on the root VH,
            // which was causing a second dialog (and crash) on long-press.
            var longPressed = false
            val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val longPressRunnable = Runnable {
                longPressed = true
                val cm = previewLayout.context
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                android.widget.Toast.makeText(previewLayout.context, "Link copied",
                    android.widget.Toast.LENGTH_SHORT).show()
            }

            previewLayout.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressed = false
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        true  // fully own — stops Android's built-in long-press from also firing
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (!longPressed) {
                            // Short tap — open URL
                            if (onOpenUrl != null) {
                                onOpenUrl.invoke(url)
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try { previewLayout.context.startActivity(intent) } catch (_: Exception) {}
                            }
                        }
                        longPressed = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> {
                        handler.removeCallbacks(longPressRunnable)
                        longPressed = false
                        false
                    }
                    else -> false
                }
            }

            previewLayout.visibility = View.VISIBLE
        }
    }

    /**
     * LinkMovementMethod subclass that suppresses URL clicks when a long-press occurred.
     *
     * The standard [LinkMovementMethod] calls [android.text.style.ClickableSpan.onClick] on
     * ACTION_UP *before* Android's own check of mHasPerformedLongPress. So on a long-press
     * both the long-press callback AND the URL click fire. We fix this by comparing the
     * elapsed time between ACTION_DOWN and ACTION_UP: if it exceeds the system long-press
     * threshold the user did a long-press and we suppress the onClick.
     */
    private inner class SafeLinkMovementMethod : LinkMovementMethod() {
        private var downTime = 0L

        override fun onTouchEvent(
            widget: android.widget.TextView,
            buffer: android.text.Spannable,
            event: MotionEvent
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN  -> downTime = event.downTime
                MotionEvent.ACTION_CANCEL -> downTime = 0L
                MotionEvent.ACTION_UP    -> {
                    val elapsed = event.eventTime - downTime
                    if (downTime != 0L &&
                        elapsed >= android.view.ViewConfiguration.getLongPressTimeout()) {
                        // Long-press — clear selection and consume without calling onClick
                        android.text.Selection.removeSelection(buffer)
                        downTime = 0L
                        return true
                    }
                    downTime = 0L
                }
            }
            return super.onTouchEvent(widget, buffer, event)
        }
    }


    private fun isApkUpdateNotice(msg: Message): Boolean {
        if (msg.isAttachment || msg.isDeleted) return false
        val t = msg.encryptedPayload
        val hasPath = t.contains("/download/silvershire-inn.apk") ||
            t.contains("[APK_DOWNLOAD]")
        return hasPath && (msg.fromUser == "Silvershire Updates" || hasPath)
    }

    private fun stripApkDownloadMarkup(text: String): String {
        var t = text
            .replace(Regex("""https?://\S*/download/silvershire-inn\.apk"""), "")
            .replace("/download/silvershire-inn.apk", "")
            .replace("[APK_DOWNLOAD]", "")
            .replace(Regex("""(?i)Download:\s*"""), "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .trim()
        if (t.isBlank()) t = "Android app update available."
        return t
    }
    /** 4-step ticks matching Silvershire web — drawn as bitmaps so OEM emoji fonts cannot force white. */
    private fun bindOutgoingTicks(tv: android.widget.TextView, msg: Message) {
        val pending = 0xFF9A9AA3.toInt()   // web --text-dim
        val blue    = 0xFF5B9BD5.toInt()
        val grey    = 0xFF888880.toInt()   // second tick when delivered
        val gold    = 0xFFD4AF37.toInt()   // web --gold
        tv.letterSpacing = 0f
        tv.setTextColor(Color.TRANSPARENT) // hide any leftover glyph under the ImageSpan
        when {
            msg.isRead -> tv.text = tickImageSpan(tv, gold, gold)
            msg.isDelivered -> tv.text = tickImageSpan(tv, blue, grey)
            msg.isServerAck -> tv.text = tickImageSpan(tv, blue, null)
            else -> tv.text = tickImageSpan(tv, pending, null)
        }
    }

    /** One or two checkmarks burned into a bitmap (colors cannot be overridden by the system font). */
    private val tickCache = HashMap<String, Bitmap>()

    private fun tickImageSpan(tv: android.widget.TextView, c0: Int, c1: Int?): CharSequence {
        val density = tv.resources.displayMetrics.density
        // 4 possible bitmaps; they used to be drawn again for every row on every bind.
        val bmp = tickCache.getOrPut("$c0/$c1/$density") { drawTicksBitmap(density, c0, c1) }
        val s = SpannableString(" ")
        val span = ImageSpan(tv.context, bmp, ImageSpan.ALIGN_BASELINE)
        s.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun drawTicksBitmap(density: Float, c0: Int, c1: Int?): Bitmap {
        val h = (12f * density).toInt().coerceAtLeast(12)
        val tickW = (10f * density).toInt().coerceAtLeast(10)
        val gap = if (c1 != null) (2f * density).toInt() else 0
        val w = if (c1 != null) tickW * 2 + gap else tickW
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        fun stroke(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun drawCheck(offsetX: Float, color: Int) {
            val p = stroke(color)
            val path = Path()
            val s = h.toFloat()
            path.moveTo(offsetX + s * 0.12f, s * 0.52f)
            path.lineTo(offsetX + s * 0.38f, s * 0.78f)
            path.lineTo(offsetX + s * 0.88f, s * 0.22f)
            canvas.drawPath(path, p)
        }
        drawCheck(0f, c0)
        if (c1 != null) drawCheck((tickW + gap).toFloat(), c1)
        return bmp
    }


    companion object {
        private const val VIEW_SENT                = 1
        private const val VIEW_RECEIVED            = 2
        private const val VIEW_ATTACHMENT_SENT     = 3
        private const val VIEW_ATTACHMENT_RECEIVED = 4
        private const val VIEW_AUDIO_SENT          = 5
        private const val VIEW_AUDIO_RECEIVED      = 6
        private const val VIEW_CALL_SENT           = 7
        private const val VIEW_CALL_RECEIVED       = 8
        private const val VIEW_SYSTEM              = 9

        private const val MIME_CALL = "silvershire/call"

        private val fontCache = HashMap<String, android.graphics.Typeface>()
        private val fontRequested = HashSet<String>()

        private const val TICK = "\u2713"

        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.messageId == b.messageId
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }

        private fun emojiForMime(mime: String?): String = when {
            mime == null                 -> "📎"
            mime.startsWith("image/")   -> "🖼"
            mime.startsWith("video/")   -> "🎬"
            mime.startsWith("audio/")   -> "🎵"
            mime == "application/pdf"   -> "📄"
            mime.startsWith("text/")    -> "📝"
            mime.contains("zip") || mime.contains("rar") -> "📦"
            mime.contains("apk")        -> "📱"
            else                        -> "📎"
        }
    }

    private fun fontSizeSp(): Float = when (PreferenceManager.fontSize) {
        0    -> 12f   // Small
        2    -> 20f   // Large
        else -> 15f   // Medium (default)
    }

    /** Applies the chat font size using SP units explicitly so it isn't affected by
     *  the UI font-scale override in MainActivity.attachBaseContext. */
    private fun applyFontSize(tv: android.widget.TextView) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSizeSp())
    }

    private fun applyFont(tv: android.widget.TextView) {
        // Per-chat font overrides the global app font.
        // null → fall back to the global PreferenceManager.fontFamily.
        val perChatFamily = if (contactUsername.isNotBlank())
            ChatSettingsActivity.getFontFamily(tv.context, contactUsername)
        else null
        val family = perChatFamily ?: PreferenceManager.fontFamily

        if (family == "sans-serif") { tv.typeface = android.graphics.Typeface.DEFAULT; return }
        // Resolved fonts are cached: requesting a downloadable font on every bind made
        // scrolling stutter on slower phones.
        fontCache[family]?.let { tv.typeface = it; return }
        if (family in fontRequested) return   // request already in flight; next bind picks it up

        // Known system font families that Typeface.create() handles reliably.
        // We apply them unconditionally — even if the result equals DEFAULT on a
        // particular device, that is still correct (it means the device renders
        // sans-serif and serif identically, and falling through to a Google Fonts
        // request would only make things worse).
        val systemFamilies = setOf("serif", "monospace", "cursive",
            "sans-serif-condensed", "sans-serif-light", "sans-serif-thin",
            "sans-serif-medium", "sans-serif-black")
        if (family in systemFamilies) {
            try {
                tv.typeface = android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL)
            } catch (_: Exception) { tv.typeface = android.graphics.Typeface.DEFAULT }
            return
        }

        // For everything else (Google Fonts), try Typeface.create() first in case
        // the font is available as a system font, otherwise fall through to the
        // downloadable-font path below.
        try {
            val tf = android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL)
            if (tf != null && tf != android.graphics.Typeface.DEFAULT) {
                tv.typeface = tf
                return
            }
        } catch (_: Exception) {}
        // Fallback: try as a downloadable font via font request (Android O+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val request = androidx.core.provider.FontRequest(
                    "com.google.android.gms.fonts",
                    "com.google.android.gms",
                    family,
                    com.silvershireinn.app.R.array.com_google_android_gms_fonts_certs
                )
                fontRequested.add(family)
                androidx.core.provider.FontsContractCompat.requestFont(
                    tv.context,
                    request,
                    object : androidx.core.provider.FontsContractCompat.FontRequestCallback() {
                        override fun onTypefaceRetrieved(typeface: android.graphics.Typeface) {
                            fontCache[family] = typeface
                            tv.typeface = typeface
                            notifyDataSetChanged()
                        }
                        override fun onTypefaceRequestFailed(reason: Int) { /* use default */ }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
            } catch (_: Exception) { /* unsupported — use default */ }
        }
    }

    private fun compactVertPadding(): Int =
        if (PreferenceManager.compactBubbles) 3 else 14

    private fun mediaUrl(msg: Message): String =
        MediaAuth.fileUrl(msg.attachmentId ?: "")

    /**
     * If [msg] is a reply (text begins with "> …" followed by a blank line), show the quote
     * chip (tap → jump to the original) and return the body text. Otherwise hide the chip and
     * return the full text. Shared by the sent + received text view-holders.
     */
    
    private fun scrubMojibake(s: String): String {
        if (s.isEmpty()) return s
        var t = s
        // Broken paperclip (UTF-8 emoji read as Windows-1252, saved as UTF-8)
        t = t.replace("\u00F0\u0178\u201C\u017D", "[file]")
        t = t.replace("\u00F0\u0178\u201C\u017E", "[file]")
        t = t.replace(Regex("\u00F0\u0178[\u201C\u201D\u0022]."), "[file]")
        t = t.replace("\uFFFD", "")
        return t
    }
private fun bindReplyQuote(quoteView: android.widget.TextView, msg: Message): String {
        val raw = scrubMojibake(msg.encryptedPayload)
        val sep = if (raw.startsWith("> ")) raw.indexOf("\n\n") else -1
        if (sep <= 0) {
            quoteView.visibility = View.GONE
            quoteView.setOnClickListener(null)
            return raw
        }
        quoteView.text = raw.substring(0, sep).removePrefix("> ").replace("\n> ", "\n")
        quoteView.visibility = View.VISIBLE
        quoteView.setOnClickListener { onReplyJump(msg) }
        return raw.substring(sep + 2)
    }

    override fun getItemViewType(position: Int): Int {
        val msg  = getItem(position)
        val mime = msg.attachmentMime ?: ""
        return when {
            isSystemMessage(msg)                                           -> VIEW_SYSTEM
            msg.isAttachment && mime == MIME_CALL && msg.isSent           -> VIEW_CALL_SENT
            msg.isAttachment && mime == MIME_CALL && !msg.isSent          -> VIEW_CALL_RECEIVED
            msg.isAttachment && mime.startsWith("audio/") && msg.isSent   -> VIEW_AUDIO_SENT
            msg.isAttachment && mime.startsWith("audio/") && !msg.isSent  -> VIEW_AUDIO_RECEIVED
            msg.isAttachment && msg.isSent                                 -> VIEW_ATTACHMENT_SENT
            msg.isAttachment && !msg.isSent                                -> VIEW_ATTACHMENT_RECEIVED
            msg.isSent                                                     -> VIEW_SENT
            else                                                           -> VIEW_RECEIVED
        }
    }

    private fun isSystemMessage(msg: com.silvershireinn.app.model.Message): Boolean {
        // Called many times per scroll: only parse what can possibly be {"_sys": ...}.
        val t = msg.encryptedPayload
        if (!t.startsWith("{") || !t.contains("\"_sys\"")) return false
        return try {
            val j = org.json.JSONObject(msg.encryptedPayload)
            j.has("_sys")
        } catch (_: Exception) { false }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_SYSTEM              -> SystemVH(android.widget.TextView(parent.context))
            VIEW_SENT                -> SentVH(ItemMessageSentBinding.inflate(inf, parent, false))
            VIEW_RECEIVED            -> ReceivedVH(ItemMessageReceivedBinding.inflate(inf, parent, false))
            VIEW_ATTACHMENT_SENT     -> AttachmentSentVH(ItemMessageAttachmentSentBinding.inflate(inf, parent, false))
            VIEW_ATTACHMENT_RECEIVED -> AttachmentReceivedVH(ItemMessageAttachmentReceivedBinding.inflate(inf, parent, false))
            VIEW_AUDIO_SENT          -> AudioSentVH(ItemMessageAudioSentBinding.inflate(inf, parent, false))
            VIEW_AUDIO_RECEIVED      -> AudioReceivedVH(ItemMessageAudioReceivedBinding.inflate(inf, parent, false))
            VIEW_CALL_SENT           -> CallSentVH(ItemMessageCallSentBinding.inflate(inf, parent, false))
            VIEW_CALL_RECEIVED       -> CallReceivedVH(ItemMessageCallReceivedBinding.inflate(inf, parent, false))
            else                     -> SentVH(ItemMessageSentBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is SystemVH             -> holder.bind(msg)
            is SentVH               -> { holder.bind(msg); bindSenderAvatar(holder.binding.avatarFrameMine, holder.binding.ivMyAvatar, holder.binding.tvMyInitials, msg, position, mine = true) }
            is ReceivedVH           -> { holder.bind(msg); bindSenderAvatar(holder.binding.avatarFrame, holder.binding.ivSenderAvatar, holder.binding.tvSenderInitials, msg, position) }
            is AttachmentSentVH     -> { holder.bind(msg); bindSenderAvatar(holder.binding.avatarFrameMine, holder.binding.ivMyAvatar, holder.binding.tvMyInitials, msg, position, mine = true) }
            is AttachmentReceivedVH -> { holder.bind(msg); bindSenderAvatar(holder.binding.avatarFrame, holder.binding.ivSenderAvatar, holder.binding.tvSenderInitials, msg, position) }
            is AudioSentVH          -> { holder.bind(msg); bindSenderAvatar(holder.binding.avatarFrameMine, holder.binding.ivMyAvatar, holder.binding.tvMyInitials, msg, position, mine = true) }
            is AudioReceivedVH      -> { holder.bind(msg); bindSenderAvatar(holder.binding.avatarFrame, holder.binding.ivSenderAvatar, holder.binding.tvSenderInitials, msg, position) }
            is CallSentVH           -> holder.bind(msg)
            is CallReceivedVH       -> holder.bind(msg)
        }
    }

    // ─── System message (e.g. "username left the group") ──────────────────────
    inner class SystemVH(private val tv: android.widget.TextView) : RecyclerView.ViewHolder(tv) {
        init {
            tv.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.gravity = android.view.Gravity.CENTER
            tv.setPadding(0, 12, 0, 12)
            tv.textSize = 12f
            tv.setTextColor(0xFF888888.toInt())
        }
        fun bind(msg: com.silvershireinn.app.model.Message) {
            val text = try {
                org.json.JSONObject(msg.encryptedPayload).optString("_sys", "")
            } catch (_: Exception) { "" }
            tv.text = text
        }
    }

    // ─── Text sent ─────────────────────────────────────────────────────────────
    inner class SentVH(val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        private var linkJob: Job? = null
        private val defaultColors = binding.tvMessageText.textColors

        fun bind(msg: Message) {
            linkJob?.cancel(); linkJob = null
            binding.root.setOnLongClickListener { onLongPress(msg); true }
            binding.tvMessageText.setOnLongClickListener { onLongPress(msg); true }
            binding.root.setPadding(binding.root.paddingLeft, compactVertPadding(), binding.root.paddingRight, compactVertPadding())
            applyFont(binding.tvMessageText)
            applyFontSize(binding.tvMessageText)
            // Per-chat text color; clearing it now restores the default (recycled rows kept the old color).
            applyTextColor(binding.tvMessageText, defaultColors)
            if (msg.isDeleted) {
                binding.tvReplyQuote.visibility = View.GONE
                binding.tvMessageText.text = "Message deleted"
                binding.tvMessageText.alpha = 0.45f
                binding.tvMessageText.movementMethod = null
                binding.layoutLinkPreview.visibility = View.GONE
            } else {
                binding.tvMessageText.alpha = 1f
                val bodyText = bindReplyQuote(binding.tvReplyQuote, msg)
                binding.tvMessageText.text  = bodyText
                linkJob = applyLinksAndPreview(
                    bodyText,
                    binding.tvMessageText,
                    binding.layoutLinkPreview,
                    binding.ivLinkPreviewImage,
                    binding.tvLinkDomain,
                    binding.tvLinkTitle,
                    binding.tvLinkDescription
                )
            }
            binding.tvTimestamp.text = msg.timestamp.formatTimestamp()
            bindOutgoingTicks(binding.tvDeliveryStatus, msg)
            // Apply custom sent-bubble color (tints the shape drawable while preserving corners)
            binding.bubbleSent.backgroundTintList = sentTintDistinct(msg)
            binding.bubbleSent.foreground = ornateRim(binding.bubbleSent, true)
        }
    }

    // ─── Text received ──────────────────────────────────────────────────────────
    inner class ReceivedVH(val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        private var linkJob: Job? = null
        private val defaultColors = binding.tvMessageText.textColors

        fun bind(msg: Message) {
            linkJob?.cancel(); linkJob = null
            binding.root.setOnLongClickListener { onLongPress(msg); true }
            binding.tvMessageText.setOnLongClickListener { onLongPress(msg); true }
            binding.root.setPadding(binding.root.paddingLeft, compactVertPadding(), binding.root.paddingRight, compactVertPadding())
            applyFont(binding.tvMessageText)
            applyFontSize(binding.tvMessageText)
            // Show sender name for group chats
            if (isGroupChat) {
                // Name (not username), in that person's own color so runs are easy to follow.
                binding.tvSenderName.visibility = View.VISIBLE
                binding.tvSenderName.text = PreferenceManager.displayNameFor(msg.fromUser)
                binding.tvSenderName.setTextColor(blend(msg.fromUser.toAvatarColor(), Color.WHITE, 0.35f))
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
            applyTextColor(binding.tvMessageText, defaultColors)
            if (msg.isDeleted) {
                binding.tvReplyQuote.visibility = View.GONE
                binding.tvMessageText.text = "Message deleted"
                binding.tvMessageText.alpha = 0.45f
                binding.tvMessageText.movementMethod = null
                binding.layoutLinkPreview.visibility = View.GONE
                binding.btnApkDownload.visibility = View.GONE
                binding.btnApkDownload.setOnClickListener(null)
            } else {
                binding.tvMessageText.alpha = 1f
                var bodyText = bindReplyQuote(binding.tvReplyQuote, msg)
                val apkNotice = isApkUpdateNotice(msg)
                if (apkNotice) {
                    bodyText = stripApkDownloadMarkup(bodyText)
                    binding.btnApkDownload.visibility = View.VISIBLE
                    binding.btnApkDownload.setOnClickListener { onApkDownload(msg) }
                } else {
                    binding.btnApkDownload.visibility = View.GONE
                    binding.btnApkDownload.setOnClickListener(null)
                }
                binding.tvMessageText.text  = bodyText.let { t ->       // app notes ("locked", "can't decrypt"): icon, not emoji
                    val str = t.toString()
                    if (str.startsWith("🔒 Locked by ") || str.startsWith("🔒 Encrypted message") || str.startsWith("🔒 Message couldn't"))
                        com.silvershireinn.app.utils.ThemeIcons.iconize(binding.root.context, t, (binding.tvMessageText.textSize * 1.25f).toInt()) else t
                }
                linkJob = if (apkNotice) {
                    binding.layoutLinkPreview.visibility = View.GONE
                    binding.tvMessageText.movementMethod = null
                    null
                } else {
                    applyLinksAndPreview(
                        bodyText,
                        binding.tvMessageText,
                        binding.layoutLinkPreview,
                        binding.ivLinkPreviewImage,
                        binding.tvLinkDomain,
                        binding.tvLinkTitle,
                        binding.tvLinkDescription
                    )
                }
            }
            binding.tvTimestamp.text = msg.timestamp.formatTimestamp()
            // Apply custom received-bubble color
            binding.bubbleReceived.backgroundTintList = receivedBubbleTint(msg)
            binding.bubbleReceived.foreground = ornateRim(binding.bubbleReceived, false)
        }
    }

    /** Silvershire theme: bronze rim on my bubbles, steel on theirs (none in Classic). */
    private fun ornateRim(v: View, sent: Boolean): android.graphics.drawable.Drawable? =
        if (!com.silvershireinn.app.utils.OrnateTheme.isOn(v.context)) null
        else androidx.core.content.ContextCompat.getDrawable(v.context, if (sent) R.drawable.ornate_rim_sent else R.drawable.ornate_rim_recv)

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is AudioSentVH -> holder.unbind()
            is AudioReceivedVH -> holder.unbind()
        }
        super.onViewRecycled(holder)
    }

    // ─── Voice messages (sent + received share one binder) ─────────────────────
    init {
        // Auto-play: when a voice note ends, play the next one if it comes right after it.
        AudioPlayerManager.nextProvider = { id ->
            val list = currentList
            val i = list.indexOfFirst { it.messageId == id }
            val n = if (i >= 0 && i + 1 < list.size) list[i + 1] else null
            n?.takeIf { it.isAttachment && !it.isDeleted && (it.attachmentMime ?: "").startsWith("audio/") }
        }
    }

    private inner class VoiceBinder(
        private val btn: android.widget.ImageButton,
        private val wave: WaveformView,
        private val tvDur: android.widget.TextView,
        private val tvSpeed: android.widget.TextView
    ) : AudioPlayerManager.Listener {
        var msg: Message? = null

        init {
            btn.setOnClickListener { msg?.let { AudioPlayerManager.toggle(it); sync() } }
            wave.onSeekStop = { frac -> msg?.let { AudioPlayerManager.seek(it, frac) } }
            tvSpeed.setOnClickListener {
                AudioPlayerManager.cycleSpeed()
                tvSpeed.text = AudioPlayerManager.speedLabel()
            }
        }

        fun bind(m: Message) {
            AudioPlayerManager.removeListener(this)
            msg = m
            tvSpeed.text = AudioPlayerManager.speedLabel()
            val lv = AudioPlayerManager.knownLevels(m.messageId)
            if (lv != null) wave.setLevels(lv) else wave.setSeed(m.messageId)
            if (m.isDeleted || m.isUploading || m.attachmentId == null) {
                if (AudioPlayerManager.isActive(m.messageId)) AudioPlayerManager.stopCurrent()
                btn.isEnabled = false; wave.isEnabled = false; tvSpeed.visibility = View.GONE
                tvDur.text = if (m.isDeleted) "Deleted" else uploadPct[m.messageId]?.let { "Sending $it%" } ?: "Sending..."
                wave.setProgressFraction(0f)
                return
            }
            btn.isEnabled = true; wave.isEnabled = true; tvSpeed.visibility = View.VISIBLE
            AudioPlayerManager.addListener(this)
            if (lv == null || AudioPlayerManager.knownDuration(m.messageId) == 0) AudioPlayerManager.prefetchInfo(m)
            sync()
        }

        fun unbind() { AudioPlayerManager.removeListener(this); msg = null }

        fun sync() {
            val m = msg ?: return
            val id = m.messageId
            val playing = AudioPlayerManager.isPlaying(id)
            btn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            btn.alpha = if (AudioPlayerManager.isLoading(id)) 0.5f else 1f
            val dur = AudioPlayerManager.durationMs(id)
            if (playing || AudioPlayerManager.isPaused(id)) {
                val pos = AudioPlayerManager.positionMs(id)
                wave.setProgressFraction(AudioPlayerManager.progressFraction(id))
                tvDur.text = if (dur > 0) "${AudioPlayerManager.formatMs(pos)} / ${AudioPlayerManager.formatMs(dur)}" else AudioPlayerManager.formatMs(pos)
            } else {
                wave.setProgressFraction(0f)
                tvDur.text = when {
                    AudioPlayerManager.isLoading(id) -> "Loading..."
                    dur > 0 -> AudioPlayerManager.formatMs(dur)
                    else -> "Voice"
                }
            }
        }

        override fun onProgress(messageId: String, positionMs: Int, durationMs: Int, fraction: Float) {
            if (messageId != msg?.messageId) return
            wave.setProgressFraction(fraction)
            tvDur.text = "${AudioPlayerManager.formatMs(positionMs)} / ${AudioPlayerManager.formatMs(durationMs)}"
        }
        override fun onStateChanged(messageId: String, playing: Boolean, paused: Boolean) {
            if (messageId == msg?.messageId || !AudioPlayerManager.isActive(msg?.messageId ?: "")) sync()
        }
        override fun onCompleted(messageId: String) { if (messageId == msg?.messageId) sync() }
        override fun onLoading(messageId: String, loading: Boolean) { if (messageId == msg?.messageId) sync() }
        override fun onInfoReady(messageId: String) {
            val m = msg ?: return
            if (messageId != m.messageId) return
            AudioPlayerManager.knownLevels(messageId)?.let { wave.setLevels(it) }
            sync()
        }
    }

    // --- Audio sent ---
    inner class AudioSentVH(val binding: ItemMessageAudioSentBinding) : RecyclerView.ViewHolder(binding.root) {
        private val voice = VoiceBinder(binding.btnPlayPause, binding.waveformAudio, binding.tvAudioDuration, binding.tvAudioSpeed)
        fun bind(m: Message) {
            binding.root.setOnLongClickListener { onLongPress(m); true }
            binding.tvTimestamp.text = m.timestamp.formatTimestamp()
            if (m.isDeleted) binding.tvDeliveryStatus.text = "" else bindOutgoingTicks(binding.tvDeliveryStatus, m)
            voice.bind(m)
        }
        fun unbind() = voice.unbind()
    }

    // --- Audio received ---
    inner class AudioReceivedVH(val binding: ItemMessageAudioReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        private val voice = VoiceBinder(binding.btnPlayPause, binding.waveformAudio, binding.tvAudioDuration, binding.tvAudioSpeed)
        fun bind(m: Message) {
            binding.root.setOnLongClickListener { onLongPress(m); true }
            binding.tvTimestamp.text = m.timestamp.formatTimestamp()
            voice.bind(m)
        }
        fun unbind() = voice.unbind()
    }

    // ─── Attachment sent ──────────────────────────────────────────────────────────
    inner class AttachmentSentVH(val binding: ItemMessageAttachmentSentBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbJob: Job? = null

        fun bind(msg: Message) {
            thumbJob?.cancel()
            thumbJob = null
            binding.root.setOnLongClickListener { onLongPress(msg); true }
            binding.root.setPadding(binding.root.paddingLeft, compactVertPadding(), binding.root.paddingRight, compactVertPadding())

            // ── Deleted attachment: show "Message deleted" and nothing else ──────
            if (msg.isDeleted) {
                binding.frameMediaPreview.visibility = View.GONE
                binding.rowFileInfo.visibility       = View.VISIBLE
                binding.tvFileEmoji.visibility       = View.VISIBLE
                binding.progressUpload.visibility    = View.GONE
                com.silvershireinn.app.utils.ThemeIcons.setIcon(binding.tvFileEmoji, R.drawable.ic_delete)
                binding.tvAttachmentName.text        = "Message deleted"
                binding.tvAttachmentName.alpha       = 0.45f
                binding.tvAttachmentSize.text        = ""
                binding.btnDownloadAttachment.visibility = View.GONE
                binding.tvTimestamp.text             = msg.timestamp.formatTimestamp()
                binding.tvDeliveryStatus.text        = ""
                return
            }
            binding.tvAttachmentName.alpha       = 1f
            binding.btnDownloadAttachment.visibility = View.VISIBLE

            // ── Uploading placeholder: show spinner instead of emoji ─────────────
            if (msg.isUploading) {
                binding.frameMediaPreview.visibility = View.GONE
                binding.rowFileInfo.visibility       = View.VISIBLE
                binding.tvFileEmoji.visibility       = View.GONE
                binding.progressUpload.visibility    = View.VISIBLE
                binding.tvAttachmentName.text        = msg.attachmentName ?: "Uploading…"
                binding.tvAttachmentSize.text        = uploadingLabel(msg.messageId)
                binding.btnDownloadAttachment.text   = "Hold to cancel"
                binding.btnDownloadAttachment.setOnClickListener(null)   // single tap does nothing
                binding.tvTimestamp.text             = msg.timestamp.formatTimestamp()
                bindOutgoingTicks(binding.tvDeliveryStatus, msg)
                return
            }

            // ── Normal attachment ────────────────────────────────────────────────
            binding.tvFileEmoji.visibility    = View.VISIBLE
            binding.progressUpload.visibility = View.GONE
            val mime    = msg.attachmentMime ?: ""
            val isImage = mime.startsWith("image/")
            val isVideo = mime.startsWith("video/")
            val isMedia = isImage || isVideo
            if (isMedia) {
                binding.frameMediaPreview.visibility = View.VISIBLE; binding.rowFileInfo.visibility = View.GONE
                binding.tvPlayOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE
                when {
                    isImage -> binding.ivMediaPreview.apply { tag = null }.load(mediaUrl(msg)) { crossfade(true); diskCachePolicy(CachePolicy.ENABLED); MediaAuth.headers().forEach { (k, v) -> addHeader(k, v) } }
                    isVideo -> {
                        binding.ivMediaPreview.dispose()
                        thumbJob = loadVideoThumbnail(msg, binding.ivMediaPreview)
                    }
                }
                binding.frameMediaPreview.setOnClickListener { onTapMedia(msg) }
                binding.btnDownloadAttachment.text = if (isImage) "Open" else "Play"
            } else {
                binding.frameMediaPreview.visibility = View.GONE; binding.rowFileInfo.visibility = View.VISIBLE
                com.silvershireinn.app.utils.ThemeIcons.setIcon(binding.tvFileEmoji, com.silvershireinn.app.utils.ThemeIcons.forMime(mime)); binding.btnDownloadAttachment.text = "Download"
            }
            binding.tvAttachmentName.text = msg.attachmentName ?: "File"
            binding.tvAttachmentSize.text = if (msg.attachmentSize > 0) FileRepository.formatSize(msg.attachmentSize) else ""
            binding.tvTimestamp.text      = msg.timestamp.formatTimestamp()
            bindOutgoingTicks(binding.tvDeliveryStatus, msg)
            binding.btnDownloadAttachment.setOnClickListener { onDownload(msg) }
        }
    }

    // ─── Attachment received ──────────────────────────────────────────────────────
    inner class AttachmentReceivedVH(val binding: ItemMessageAttachmentReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbJob: Job? = null

        fun bind(msg: Message) {
            thumbJob?.cancel()
            thumbJob = null
            binding.root.setOnLongClickListener { onLongPress(msg); true }
            binding.root.setPadding(binding.root.paddingLeft, compactVertPadding(), binding.root.paddingRight, compactVertPadding())

            // ── Deleted attachment: show "Message deleted" and nothing else ──────
            if (msg.isDeleted) {
                binding.frameMediaPreview.visibility = View.GONE
                binding.rowFileInfo.visibility       = View.VISIBLE
                com.silvershireinn.app.utils.ThemeIcons.setIcon(binding.tvFileEmoji, R.drawable.ic_delete)
                binding.tvAttachmentName.text        = "Message deleted"
                binding.tvAttachmentName.alpha       = 0.45f
                binding.tvAttachmentSize.text        = ""
                binding.btnDownloadAttachment.visibility = View.GONE
                binding.tvTimestamp.text             = msg.timestamp.formatTimestamp()
                return
            }
            binding.tvAttachmentName.alpha       = 1f
            binding.btnDownloadAttachment.visibility = View.VISIBLE

            val mime    = msg.attachmentMime ?: ""
            val isImage = mime.startsWith("image/")
            val isVideo = mime.startsWith("video/")
            val isMedia = isImage || isVideo
            if (isMedia) {
                binding.frameMediaPreview.visibility = View.VISIBLE; binding.rowFileInfo.visibility = View.GONE
                binding.tvPlayOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE
                when {
                    isImage -> binding.ivMediaPreview.apply { tag = null }.load(mediaUrl(msg)) { crossfade(true); diskCachePolicy(CachePolicy.ENABLED); MediaAuth.headers().forEach { (k, v) -> addHeader(k, v) } }
                    isVideo -> {
                        binding.ivMediaPreview.dispose()
                        thumbJob = loadVideoThumbnail(msg, binding.ivMediaPreview)
                    }
                }
                binding.frameMediaPreview.setOnClickListener { onTapMedia(msg) }
                binding.btnDownloadAttachment.text = if (isImage) "Open" else "Play"
            } else {
                binding.frameMediaPreview.visibility = View.GONE; binding.rowFileInfo.visibility = View.VISIBLE
                com.silvershireinn.app.utils.ThemeIcons.setIcon(binding.tvFileEmoji, com.silvershireinn.app.utils.ThemeIcons.forMime(mime)); binding.btnDownloadAttachment.text = "Download"
            }
            binding.tvAttachmentName.text = msg.attachmentName ?: "File"
            binding.tvAttachmentSize.text = if (msg.attachmentSize > 0) FileRepository.formatSize(msg.attachmentSize) else ""
            binding.tvTimestamp.text      = msg.timestamp.formatTimestamp()
            binding.btnDownloadAttachment.setOnClickListener { onDownload(msg) }
        }
    }

    // ─── Call sent ────────────────────────────────────────────────────────────────
    inner class CallSentVH(val binding: ItemMessageCallSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: Message) {
            val direction = msg.attachmentName ?: "outgoing"
            val duration  = msg.attachmentSize.toInt()   // seconds

            // Icon: missed/rejected → red tint; else gold
            val isMissed = direction.substringBefore(':') == "missed"
            val tint     = if (isMissed) 0xFFCC3333.toInt() else 0xFFC9A84C.toInt()
            binding.ivCallIcon.setColorFilter(tint)
            binding.tvCallLabel.text = com.silvershireinn.app.repository.callLabel(direction, duration, msg.isSent)
            binding.tvCallLabel.setTextColor(if (isMissed) 0xFFCC3333.toInt() else 0xFFFFC107.toInt())
            binding.tvCallDuration.text = if (duration > 0) formatDuration(duration) else ""
            binding.tvTimestamp.text    = msg.timestamp.formatTimestamp()
            bindOutgoingTicks(binding.tvDeliveryStatus, msg)
            binding.btnCallBack.setOnClickListener { onCallBack(msg.toUser) }
        }
    }

    // ─── Call received ────────────────────────────────────────────────────────────
    inner class CallReceivedVH(val binding: ItemMessageCallReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: Message) {
            val direction = msg.attachmentName ?: "incoming"
            val duration  = msg.attachmentSize.toInt()

            val isMissed = direction.substringBefore(':') == "missed"
            val tint     = if (isMissed) 0xFFCC3333.toInt() else 0xFFC9A84C.toInt()
            binding.ivCallIcon.setColorFilter(tint)
            binding.tvCallLabel.text = com.silvershireinn.app.repository.callLabel(direction, duration, msg.isSent)
            binding.tvCallLabel.setTextColor(if (isMissed) 0xFFCC3333.toInt() else 0xFFFFC107.toInt())
            binding.tvCallDuration.text = if (duration > 0) formatDuration(duration) else ""
            binding.tvTimestamp.text    = msg.timestamp.formatTimestamp()
            binding.btnCallBack.setOnClickListener { onCallBack(msg.fromUser) }
        }
    }

    private fun formatDuration(secs: Int): String {
        val m = secs / 60; val s = secs % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
