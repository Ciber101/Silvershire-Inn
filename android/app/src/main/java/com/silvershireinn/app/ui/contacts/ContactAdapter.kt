package com.silvershireinn.app.ui.contacts

import kotlinx.coroutines.launch
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.silvershireinn.app.databinding.ItemContactBinding
import com.silvershireinn.app.model.Contact
import com.silvershireinn.app.network.OnlineStatusManager
import com.silvershireinn.app.utils.FontHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import java.io.File

class ContactAdapter(
    private val onClick: (Contact) -> Unit,
    private val onLongPress: (Contact) -> Unit = {}
) : ListAdapter<Contact, ContactAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(a: Contact, b: Contact) = a.username == b.username
            override fun areContentsTheSame(a: Contact, b: Contact) = a == b
        }
    }

    inner class VH(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root) {
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
        VH(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onViewRecycled(holder: VH) { holder.video.release(); super.onViewRecycled(holder) }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = getItem(position)
        with(holder.binding) {
            val displayName = PreferenceManager.displayNameFor(contact.username)
            tvContactName.text = displayName
            val isOnline = OnlineStatusManager.isOnline(contact.username)
            val label = OnlineStatusManager.statusLabel(contact.username)
            tvOnlineStatus.text = if (label.isNotBlank()) label else if (isOnline) "Online" else "Offline"
            tvOnlineStatus.alpha = if (isOnline) 1f else 0.5f
            tvOnlineStatus.setTextColor(OnlineStatusManager.statusColor(contact.username))
            onlineIndicator.setCardBackgroundColor(OnlineStatusManager.statusColor(contact.username))

            // ── Avatar: profile pic if cached, else initials + synced shape ─
            val shape = com.silvershireinn.app.utils.AvatarShapeHelper.forUser(contact.username)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(avatarCircle, shape)
            com.silvershireinn.app.utils.AvatarShapeHelper.applyClip(ivContactPic, shape)
            com.silvershireinn.app.utils.ProfileFrames.applyFloating(avatarCircle, contact.username)
            val picFile = com.silvershireinn.app.utils.ProfileMediaHelper.contactPic(
                holder.itemView.context, contact.username)
            if (picFile != null && picFile.exists()) {
                ivContactPic.visibility = View.VISIBLE
                tvInitials.visibility   = View.GONE
                val path = picFile.absolutePath
                ivContactPic.tag = path
                if (com.silvershireinn.app.utils.ProfileMediaHelper.isVideoPath(path)) {
                    // First frame decoded off the main thread (it stuttered while scrolling).
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
                                avatarCircle.setCardBackgroundColor(contact.username.toAvatarColor())
                            }
                        }
                    }
                    holder.video.play(path, ivContactPic)
                } else {
                    holder.video.clear()
                    // Cache key includes the file time so a new picture shows right away.
                    ivContactPic.load(picFile) { memoryCacheKey("contact_${path}_${picFile.lastModified()}"); size(144) }
                }
            } else {
                holder.video.clear()
                ivContactPic.visibility = View.GONE
                tvInitials.visibility   = View.VISIBLE
                tvInitials.text         = displayName.toInitials()
                avatarCircle.setCardBackgroundColor(contact.username.toAvatarColor())
            }

            // ── Font ──────────────────────────────────────────────────────────
            FontHelper.apply(holder.itemView, PreferenceManager.fontFamily)
        }
    }
}
