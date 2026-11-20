package com.silvershireinn.app.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.silvershireinn.app.databinding.ItemInviteBinding
import com.silvershireinn.app.model.Contact
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials

class InviteAdapter(
    private val onAccept: (Contact) -> Unit,
    private val onDecline: (Contact) -> Unit
) : ListAdapter<Contact, InviteAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(a: Contact, b: Contact) = a.username == b.username
            override fun areContentsTheSame(a: Contact, b: Contact) = a == b
        }
    }

    inner class VH(val binding: ItemInviteBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnAccept.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onAccept(getItem(pos))
            }
            binding.btnDecline.setOnClickListener {
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onDecline(getItem(pos))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemInviteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = getItem(position)
        with(holder.binding) {
            tvInitials.text = contact.username.toInitials()
            avatarCircle.setCardBackgroundColor(contact.username.toAvatarColor())
            tvContactName.text = contact.username
        }
    }
}
