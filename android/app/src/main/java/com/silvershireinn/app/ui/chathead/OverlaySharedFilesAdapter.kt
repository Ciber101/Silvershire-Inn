package com.silvershireinn.app.ui.chathead

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.SharedFileInfo

/**
 * Simple RecyclerView adapter for the chat-head overlay file drawer.
 * Shows a flat list of shared files; tapping one triggers [onDownload].
 */
class OverlaySharedFilesAdapter(
    private val onDownload: (SharedFileInfo) -> Unit
) : ListAdapter<SharedFileInfo, OverlaySharedFilesAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SharedFileInfo>() {
            override fun areItemsTheSame(a: SharedFileInfo, b: SharedFileInfo) = a.fileId == b.fileId
            override fun areContentsTheSame(a: SharedFileInfo, b: SharedFileInfo) = a == b
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(android.R.id.text1)
        private val tvMeta: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(info: SharedFileInfo) {
            tvName.text = info.fileName
            tvMeta.text = FileRepository.formatSize(info.size)
            itemView.setOnClickListener { onDownload(info) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // Use the built-in two-line list item — no extra layout file needed
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
