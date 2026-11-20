package com.silvershireinn.app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.databinding.BottomSheetSharedFilesBinding
import com.silvershireinn.app.repository.SharedFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SharedFilesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSharedFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SharedFilesAdapter
    private var allFiles: List<SharedFileInfo> = emptyList()
    private var currentTab = 0   // 0=All, 1=Images, 2=Videos, 3=Files

    interface Listener {
        fun onSendFileClicked()
        fun onDownloadFileFromDrawer(info: SharedFileInfo)
    }

    var listener: Listener? = null

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = BottomSheetSharedFilesBinding.inflate(inf, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // After the screen is recreated the listener is gone: fall back to the hosting chat.
        if (listener == null) listener = activity as? Listener
        val conv = arguments?.getString(ARG_CONTACT)
        adapter = SharedFilesAdapter(conv) { info ->
            listener?.onDownloadFileFromDrawer(info)
            dismiss()
        }

        binding.rvSharedFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSharedFiles.adapter = adapter

        binding.btnSendFile.setOnClickListener {
            listener?.onSendFileClicked()
            dismiss()
        }

        // Tabs: All, Images, Videos, Files
        listOf("All", "Photos", "Videos", "Files", "Voice").forEach { name ->
            binding.tabLayoutFiles.addTab(binding.tabLayoutFiles.newTab().setText(name))
        }

        binding.tabLayoutFiles.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                updateList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        loadFiles()
    }

    private fun loadFiles() {
        val contactUsername = arguments?.getString(ARG_CONTACT)
            ?.takeIf { it.isNotBlank() } ?: return

        lifecycleScope.launch {
            // Query the local Room DB for all attachment messages in this conversation.
            // Room is the source of truth — every sent and received file is stored here,
            // so the drawer is populated instantly without a network round-trip and works
            // even when the server is offline.
            val messages = withContext(Dispatchers.IO) {
                SilvershireApp.database.messageDao()
                    .getAttachmentsForConversation(contactUsername)
                    .first()
            }

            // Real files only: call-log rows (stored like attachments) showed up here as files
            // named "outgoing", plus deleted and still-uploading ones. Newest first.
            allFiles = messages
                .filter { it.attachmentId != null && !it.isDeleted && !it.isUploading && it.attachmentMime != "silvershire/call" }
                .sortedByDescending { it.timestamp }
                .map { msg ->
                    SharedFileInfo(
                        fileId     = msg.attachmentId!!,
                        fileName   = msg.attachmentName ?: "file",
                        mimeType   = msg.attachmentMime ?: "application/octet-stream",
                        size       = msg.attachmentSize,
                        uploadedBy = msg.fromUser,
                        uploadedAt = msg.timestamp
                    )
                }
            updateList()
        }
    }

    private fun updateList() {
        val filtered = when (currentTab) {
            1    -> allFiles.filter { it.mimeType.startsWith("image/") }
            2    -> allFiles.filter { it.mimeType.startsWith("video/") }
            3    -> allFiles.filter { !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") }
            4    -> allFiles.filter { it.mimeType.startsWith("audio/") }
            else -> allFiles
        }
        adapter.submitList(filtered)
        val b = _binding ?: return   // the list can finish loading after the sheet closed
        b.tvNoFiles.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        b.rvSharedFiles.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SharedFilesBottomSheet"
        private const val ARG_CONTACT = "contact"

        fun newInstance(contactUsername: String) = SharedFilesBottomSheet().apply {
            arguments = Bundle().also { it.putString(ARG_CONTACT, contactUsername) }
        }
    }
}
