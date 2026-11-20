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
            val displayName = if (isGroup) {
                conv.groupName
            } else {
                PreferenceManager.displayNameFor(conv.contactUsername)
            }
            tvContactName.text = displayName

            // ── Avatar: profile pic if cached, else initials / group icon ─────
            if (!isGroup) {
                val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(conv.contactUsername)
                com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(avatarCircle, shape)
                com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(ivContactPic, shape)
            }
            com.silvershireinn.app.utils.ProfileFrames.applyFloating(avatarCircle, if (isGroup) null else conv.contactUsername)
            val picFile = if (!isGroup)
                com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(
                    holder.itemView.context, conv.contactUsername) else null
            if (!isGroup && picFile?.exists() == true) {
                ivContactPic.visibility = View.VISIBLE
                tvInitials.visibility   = View.GONE
                val path = picFile.absolutePath
                ivContactPic.tag = path
                if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) {
                    // Video portrait: first frame decoded off the main thread (it was decoded
                    // during scrolling, a visible stutter on cheap phones).
                    ivContactPic.setImageDrawable(null)
                    com.silvershireinn.app.SilvershireApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val frame = com.silvershireinn.app.utils.ProfileMediaHelper.videoFrameBitmap(path)
                        ivContactPic.post {
                            if (ivContactPic.tag != path) return@post
                            if (frame != null) ivContactPic.setImageBitmap(frame)
                            else {
                                ivContactPic.visibility = View.GONE
                                tvInitials.visibility = View.VISIBLE
                                tvInitials.text = displayName.toInitials()
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
                tvInitials.text         = if (isGroup) "👥" else displayName.toInitials()
                avatarCircle.setCardBackgroundColor(
                    if (isGroup) 0xFF4A3728.toInt() else conv.contactUsername.toAvatarColor()
                )
            }
            tvLastMessage.text = if (!isGroup && com.silvershireinn.app.utils.PeerLocks.isLocked(conv.contactUsername)) "🔒 Locked"
                                 else conv.lastMessagePreview
            // Unread chats stand out: brighter, bold preview.
            val unread = conv.unreadCount > 0
            tvLastMessage.setTypeface(null, if (unread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tvLastMessage.alpha = if (unread) 1f else 0.85f
            tvTimestamp.text   = conv.lastMessageTimestamp.formatListTimestamp()

            // ── Online status — hidden for group chats ────────────────────────
            val isOnline = if (!isGroup) OnlineStatusManager.isOnline(conv.contactUsername) else false
            onlineDot.visibility = if (isOnline) View.VISIBLE else View.GONE
            if (isOnline) {
                val color = OnlineStatusManager.statusColor(conv.contactUsername)
                onlineDot.setCardBackgroundColor(color)
                if (onlineDot.childCount > 0) {
                    onlineDot.getChildAt(0).setBackgroundColor(color)
                }
            }

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
