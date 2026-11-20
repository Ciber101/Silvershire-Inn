package com.silvershireinn.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.silvershireinn.app.databinding.ItemSharedFileBinding
import com.silvershireinn.app.network.MediaAuth
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.repository.SharedFileInfo
import com.silvershireinn.app.ui.media.MediaViewerActivity
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.formatListTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

class SharedFilesAdapter(
    /** Chat these files belong to, so the viewer can swipe through the rest of them. */
    private val conversationId: String? = null,
    /** Open a photo / video. Default: the full-screen viewer (the floating chat shows its own). */
    private val onOpenMedia: ((SharedFileInfo) -> Unit)? = null,
    private val onDownload: (SharedFileInfo) -> Unit
) : ListAdapter<SharedFileInfo, SharedFilesAdapter.FileVH>(DIFF) {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + com.silvershireinn.app.utils.CrashGuard.coroutineHandler)
    /** In-memory LRU cache: url → bitmap (survives RecyclerView recycling within a session). */

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SharedFileInfo>() {
            override fun areItemsTheSame(a: SharedFileInfo, b: SharedFileInfo) = a.fileId == b.fileId
            override fun areContentsTheSame(a: SharedFileInfo, b: SharedFileInfo) = a == b
        }

        fun emojiForMime(mime: String): String = when {
            mime.startsWith("image/")       -> "🖼"
            mime.startsWith("video/")       -> "🎬"
            mime.startsWith("audio/")       -> "🎵"
            mime == "application/pdf"       -> "📄"
            mime.startsWith("text/")        -> "📝"
            mime.contains("zip")
                || mime.contains("rar")
                || mime.contains("7z")      -> "📦"
            mime.contains("apk")            -> "📱"
            else                            -> "📎"
        }

        private fun buildUrl(fileId: String): String = try {
            MediaAuth.fileUrl(fileId)
        } catch (_: Exception) { "" }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = FileVH(
        ItemSharedFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: FileVH, position: Int) = holder.bind(getItem(position))

    inner class FileVH(val binding: ItemSharedFileBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbJob: Job? = null

        fun bind(info: SharedFileInfo) {
            thumbJob?.cancel(); thumbJob = null

            binding.tvFileName.text     = info.fileName
            binding.tvFileSize.text     = if (info.size > 0) FileRepository.formatSize(info.size) else ""
            // "You · Nov 3" / "Sam · Yesterday" instead of a bare username.
            val who = if (info.uploadedBy == PreferenceManager.username) "You" else PreferenceManager.displayNameFor(info.uploadedBy)
            val whenTxt = info.uploadedAt.takeIf { it.isNotBlank() }?.formatListTimestamp()
            binding.tvFileUploader.text = if (whenTxt.isNullOrBlank()) who else "$who \u00B7 $whenTxt"

            val isImage = info.mimeType.startsWith("image/")
            val isVideo = info.mimeType.startsWith("video/")
            val isMedia = isImage || isVideo

            if (isMedia) {
                binding.tvFileIcon.visibility      = View.GONE
                binding.ivFileThumbnail.visibility = View.VISIBLE
                val url = buildUrl(info.fileId)

                if (isImage) {
                    binding.ivFileThumbnail.tag = null
                    binding.ivFileThumbnail.load(url) { crossfade(true); MediaAuth.headers().forEach { (k, v) -> addHeader(k, v) } }
                } else {
                    // Shared, memory-safe thumbnail loader (disk cached, scaled, max 2 decodes at once).
                    val fid = info.fileId
                    val hit = com.silvershireinn.app.utils.VideoThumbs.cached(fid)
                    if (hit != null) binding.ivFileThumbnail.setImageBitmap(hit)
                    else {
                        binding.ivFileThumbnail.setImageResource(android.R.color.darker_gray)
                        binding.ivFileThumbnail.tag = fid
                        thumbJob = adapterScope.launch {
                            val bmp = com.silvershireinn.app.utils.VideoThumbs.get(binding.root.context, fid, url)
                            if (binding.ivFileThumbnail.tag != fid) return@launch
                            if (bmp != null) binding.ivFileThumbnail.setImageBitmap(bmp)
                            else binding.ivFileThumbnail.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                }

                binding.ivFileThumbnail.setOnClickListener {
                    onOpenMedia?.let { open -> open(info); return@setOnClickListener }
                    MediaViewerActivity.start(
                        binding.root.context,
                        info.fileId,
                        info.fileName,
                        info.mimeType,
                        buildUrl(info.fileId),
                        conversationId
                    )
                }
            } else {
                com.silvershireinn.app.utils.ThemeIcons.setIcon(binding.tvFileIcon, com.silvershireinn.app.utils.ThemeIcons.forMime(info.mimeType), 28)
                binding.tvFileIcon.visibility      = View.VISIBLE
                binding.ivFileThumbnail.visibility = View.GONE
            }

            binding.btnDownloadFile.setOnClickListener { onDownload(info) }
        }
    }
}
