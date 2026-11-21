package com.silvershireinn.app.ui.chats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.silvershireinn.app.databinding.ItemConversationBinding
import com.silvershireinn.app.model.Conversation
import com.silvershireinn.app.network.OnlineStatusManager
import com.silvershireinn.app.ui.chat.ChatSettingsActivity
import com.silvershireinn.app.utils.FontHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.formatListTimestamp
import com.silvershireinn.app.utils.hide
import com.silvershireinn.app.utils.show
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import java.io.File
import kotlinx.coroutines.launch

class ConversationAdapter(
    private val onClick: (Conversation) -> Unit,
    private val onLongPress: (Conversation) -> Unit = {}
) : ListAdapter<Conversation, ConversationAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(a: Conversation, b: Conversation) = a.contactUsername == b.contactUsername
            override fun areContentsTheSame(a: Conversation, b: Conversation) = a == b
        }
        /** Chats list picture size (Profile > Picture sizes): the picture and the box holding it. */
        fun applyListSize(avatar: View, initials: android.widget.TextView?) {
            val d = avatar.resources.displayMetrics.density
            val px = (PreferenceManager.listAvatarSize * d).toInt()
            val lp = avatar.layoutParams ?: return
            if (lp.width == px && lp.height == px) return
            lp.width = px; lp.height = px; avatar.layoutParams = lp
            (avatar.parent as? View)?.let { box -> box.layoutParams?.let { bl -> bl.width = px; bl.height = px; box.layoutParams = bl } }
            (avatar as? androidx.cardview.widget.CardView)?.radius = px / 2f
            initials?.textSize = PreferenceManager.listAvatarSize * 0.36f
        }
    }

    inner class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Video profile pictures loop (muted) right in the list. */
        val video = com.silvershireinn.app.utils.MutedLoopVideoHelper(binding.root.context, binding.vvContactPic)
        init {
            binding.root.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
            binding.root.setOnLongClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongPress(getItem(pos))
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onViewRecycled(holder: VH) { holder.video.release(); super.onViewRecycled(holder) }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val conv = getItem(position)
        with(holder.binding) {
            val isGroup = conv.isGroupChat
            // Show group name for group chats; nickname/username for 1:1
            val isNotes = com.silvershireinn.app.utils.NotesChat.isNotes(conv.contactUsername)
            val displayName = when {
                isGroup -> conv.groupName
                isNotes -> com.silvershireinn.app.utils.NotesChat.TITLE
                else -> PreferenceManager.displayNameFor(conv.contactUsername)
            }
            tvContactName.text = displayName
            applyListSize(avatarCircle, tvInitials)

            // ── Avatar: profile pic if cached, else initials / group icon ─────
            if (!isGroup) {
                val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(conv.contactUsername)
                com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(avatarCircle, shape)
                com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(ivContactPic, shape)
            }
            com.silvershireinn.app.utils.ProfileFrames.applyFloating(avatarCircle, if (isGroup) null else conv.contactUsername)
            val picFile = when {
                isGroup -> null
                isNotes -> com.silvershireinn.app.utils.ProfileMediaHelper.findOwnProfilePic(holder.itemView.context)
                else -> com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(holder.itemView.context, conv.contactUsername)
            }
            if (!isGroup && picFile?.exists() == true) {
                ivContactPic.visibility = View.VISIBLE
                tvInitials.visibility   = View.GONE
                val path = picFile.absolutePath
                ivContactPic.tag = path
                if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) {
                    // Video portrait: first frame decoded off the main thread (it was decoded
                    // during scrolling, a visible stutter on cheap phones).
                    // Already decoded once: show it at once (switching tabs used to blink the pictures)
                    val cachedFrame = com.silvershireinn.app.utils.ProfileMediaHelper.cachedVideoFrame(path)
                    if (cachedFrame != null) ivContactPic.setImageBitmap(cachedFrame) else ivContactPic.setImageDrawable(null)
                    if (cachedFrame == null) com.silvershireinn.app.SilvershireApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val frame = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(path)
                        ivContactPic.post {
                            if (ivContactPic.tag != path) return@post
                            if (frame != null) ivContactPic.setImageBitmap(frame)
                            else {
                                ivContactPic.visibility = View.GONE
                                tvInitials.visibility = View.VISIBLE
                                tvInitials.text = if (isGroup) tvInitials.text else displayName.toInitials()
                                avatarCircle.setCardBackgroundColor(conv.contactUsername.toAvatarColor())
                            }
                        }
                    }
                    holder.video.play(path, ivContactPic)
                } else {
                    holder.video.clear()
                    // Keyed by modified time: a friend's NEW picture (same file name) used to
                    // keep showing the old one from the image cache until the app restarted.
                    ivContactPic.load(picFile) {
                        memoryCacheKey("conv_${path}_${picFile.lastModified()}")
                        size(144)
                    }
                }
            } else {
                holder.video.clear()
                ivContactPic.visibility = View.GONE
                tvInitials.visibility   = View.VISIBLE
                if (isGroup) com.silvershireinn.app.utils.ThemeIcons.setIcon(tvInitials, com.silvershireinn.app.R.drawable.ic_group, 24)
                else { com.silvershireinn.app.utils.ThemeIcons.clearIcon(tvInitials); tvInitials.text = displayName.toInitials() }
                avatarCircle.setCardBackgroundColor(
                    if (isGroup) 0xFF4A3728.toInt() else conv.contactUsername.toAvatarColor()
                )
            }
            tvLastMessage.text = com.silvershireinn.app.utils.ThemeIcons.iconize(tvLastMessage.context,
                if (!isGroup && com.silvershireinn.app.utils.PeerLocks.isLocked(conv.contactUsername)) "🔒 Locked"
                else if (isNotes && com.silvershireinn.app.utils.NotesLock.enabled) "🔒 Locked"
                else conv.lastMessagePreview,
                (tvLastMessage.textSize * 1.2f).toInt())
            // Unread chats stand out: brighter, bold preview.
            val unread = conv.unreadCount > 0
            tvLastMessage.setTypeface(null, if (unread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tvLastMessage.alpha = if (unread) 1f else 0.85f
            tvTimestamp.text   = conv.lastMessageTimestamp.formatListTimestamp()

            // ── Online status — hidden for group chats ────────────────────────
            val isOnline = if (!isGroup && !isNotes) OnlineStatusManager.isOnline(conv.contactUsername) else false
            // The status dot sits beside the name (like the web app), never on the picture
            onlineDot.visibility = View.GONE
            if (isOnline) {
                val color = OnlineStatusManager.statusColor(conv.contactUsername)
                val px = (9 * tvContactName.resources.displayMetrics.density).toInt()
                val dot = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setSize(px, px); setBounds(0, 0, px, px)
                }
                tvContactName.setCompoundDrawablesRelative(dot, null, null, null)
                tvContactName.compoundDrawablePadding = (6 * tvContactName.resources.displayMetrics.density).toInt()
            } else tvContactName.setCompoundDrawablesRelative(null, null, null, null)

            val label = if (!isGroup) OnlineStatusManager.statusLabel(conv.contactUsername) else ""
            if (label.isNotEmpty()) {
                tvOnlineStatus.text       = label
                tvOnlineStatus.visibility = View.VISIBLE
                tvOnlineStatus.setTextColor(
                    if (isOnline) OnlineStatusManager.statusColor(conv.contactUsername)
                    else 0xFF888888.toInt()
                )
            } else {
                tvOnlineStatus.visibility = View.GONE
            }

            // ── Unread badge ──────────────────────────────────────────────────
            if (conv.unreadCount > 0) {
                tvUnreadCount.show()
                tvUnreadText.text = if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString()
            } else {
                tvUnreadCount.hide()
            }

            // ── Font ──────────────────────────────────────────────────────────
            FontHelper.apply(holder.itemView, PreferenceManager.fontFamily)
        }
    }

    fun getItemAt(position: Int): Conversation = getItem(position)
}
