package com.silvershireinn.app.ui.contacts

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import coil.load
import com.silvershireinn.app.utils.AvatarShapeHelper
import com.silvershireinn.app.utils.MutedLoopVideoHelper
import com.silvershireinn.app.utils.ProfileMediaHelper
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.LinearLayout
import com.silvershireinn.app.databinding.BottomSheetContactProfileBinding
import com.silvershireinn.app.network.CallManager
import com.silvershireinn.app.network.VideoCallManager
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.ui.call.CallActivity
import com.silvershireinn.app.ui.call.VideoCallActivity
import com.silvershireinn.app.ui.chat.ChatActivity
import com.silvershireinn.app.ui.chat.ChatSettingsActivity
import com.silvershireinn.app.util.HapticUtil
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import com.silvershireinn.app.utils.toast
import java.io.File

/**
 * Full-screen bottom sheet showing a contact's profile:
 *  - Banner + profile pic
 *  - Username + online status
 *  - Message / audio call / video call action buttons
 *  - Nickname editor
 *  - Chat settings shortcut
 *  - Block / Delete contact
 */
class ContactProfileBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetContactProfileBinding? = null
    private val binding get() = _binding!!
    private var picVideoHelper: MutedLoopVideoHelper? = null
    private var bannerVideoHelper: MutedLoopVideoHelper? = null

    companion object {
        private const val ARG_USERNAME = "username"

        fun newInstance(username: String): ContactProfileBottomSheet =
            ContactProfileBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_USERNAME, username) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetContactProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.viewTreeObserver.addOnGlobalLayoutListener(bannerSizer)

        val username = arguments?.getString(ARG_USERNAME) ?: return

        // ── Name + initials + avatar color ───────────────────────────────────
        val displayName = PreferenceManager.displayNameFor(username)
        binding.tvContactUsername.text = displayName
        binding.tvCanonicalUsername.text = "Username: $username"
        binding.tvCanonicalUsername.visibility = android.view.View.VISIBLE
        binding.tvContactInitials.text = username.toInitials()
        binding.cvContactPic.setCardBackgroundColor(username.toAvatarColor())

        // ── Online status / presence ─────────────────────────────────────────
        val isOnline = com.silvershireinn.app.network.OnlineStatusManager.isOnline(username)
        val label = com.silvershireinn.app.network.OnlineStatusManager.statusLabel(username)
        binding.onlineIndicator.setCardBackgroundColor(
            com.silvershireinn.app.network.OnlineStatusManager.statusColor(username)
        )
        binding.tvOnlineStatus.text = when {
            label.isNotBlank() -> label
            isOnline -> "Online"
            else -> "Offline"
        }
        binding.tvOnlineStatus.setTextColor(
            com.silvershireinn.app.network.OnlineStatusManager.statusColor(username)
        )

        // ── Avatar shape + profile media ─────────────────────────────────────
        val shape = AvatarShapeHelper.forUser(username)
        AvatarShapeHelper.applyClip(binding.cvContactPic, shape)
        AvatarShapeHelper.applyClip(binding.ivContactPic, shape)
        binding.cvContactPic.post {
            if (_binding == null) return@post
            val framed = com.silvershireinn.app.utils.ProfileFrames.applyFull(binding.cvContactPic, username)
            // leave room under the picture for the frame's lower half
            (binding.cvContactPic.parent as? View)?.let { p -> (p.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = if (framed) (30 * resources.displayMetrics.density).toInt() else 0; p.layoutParams = lp } }
        }
        try {
            picVideoHelper = MutedLoopVideoHelper(requireContext(), binding.vvContactPic)
            bannerVideoHelper = MutedLoopVideoHelper(requireContext(), binding.vvContactBanner)
            AvatarShapeHelper.applyClip(binding.vvContactPic, shape)
        } catch (_: Exception) {}

        val picFile = ProfileMediaHelper.contactPic(requireContext(), username)
        if (picFile != null && picFile.exists()) {
            binding.tvContactInitials.visibility = View.GONE
            if (ProfileMediaHelper.isVideoPath(picFile.absolutePath)) {
                // The looping video covers this; a still frame (decoded in the background, it
                // used to freeze the sheet while opening) shows until it starts.
                binding.ivContactPic.visibility = View.GONE
                stillFrame(picFile.absolutePath, binding.ivContactPic)
                picVideoHelper?.play(picFile.absolutePath, binding.ivContactPic)
            } else {
                picVideoHelper?.clear(binding.ivContactPic)
                binding.ivContactPic.visibility = View.VISIBLE
                binding.ivContactPic.load(picFile) { crossfade(true) }
            }
        } else {
            picVideoHelper?.clear(binding.ivContactPic)
            binding.ivContactPic.visibility = View.GONE
            binding.tvContactInitials.visibility = View.VISIBLE
        }

        // ── Banner ────────────────────────────────────────────────────────────
        val bannerFile = ProfileMediaHelper.contactBanner(requireContext(), username)
        if (bannerFile != null && bannerFile.exists()) {
            if (ProfileMediaHelper.isVideoPath(bannerFile.absolutePath)) {
                binding.ivContactBanner.visibility = View.GONE
                stillFrame(bannerFile.absolutePath, binding.ivContactBanner)
                bannerVideoHelper?.play(bannerFile.absolutePath, binding.ivContactBanner)
            } else {
                bannerVideoHelper?.clear(binding.ivContactBanner)
                binding.ivContactBanner.visibility = View.VISIBLE
                binding.ivContactBanner.load(bannerFile) { crossfade(true) }
            }
        } else {
            bannerVideoHelper?.clear(binding.ivContactBanner)
            binding.ivContactBanner.visibility = View.GONE
        }

        // ── Nickname ──────────────────────────────────────────────────────────
        val savedNick = PreferenceManager.getNickname(username)
        binding.tvNickname.text = savedNick.ifBlank { "None" }
        binding.rowNickname.setOnClickListener { HapticUtil.vibrate(requireContext()); showNicknameDialog(username) }

        // ── Chat settings ─────────────────────────────────────────────────────
        binding.rowChatSettings.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            startActivity(
                Intent(requireContext(), ChatSettingsActivity::class.java)
                    .putExtra("contact_username", username)
            )
        }

        // ── Action buttons ────────────────────────────────────────────────────
        binding.btnMessage.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            dismiss()
            startActivity(
                Intent(requireContext(), ChatActivity::class.java)
                    .putExtra("contact_username", username)
            )
        }

        binding.btnAudioCall.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            if (CallManager.state != CallManager.State.IDLE || VideoCallManager.state != VideoCallManager.State.IDLE) {
                context?.toast("Already in a call")
                return@setOnClickListener
            }
            com.silvershireinn.app.utils.CallConfirm.voice(requireContext(), username) {
                dismiss()
                CallManager.placeCall(username)
                startActivity(CallActivity.outgoingIntent(requireContext(), username))
            }
        }

        binding.btnVideoCall.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            if (VideoCallManager.state != VideoCallManager.State.IDLE || CallManager.state != CallManager.State.IDLE) {
                context?.toast("Already in a call")
                return@setOnClickListener
            }
            com.silvershireinn.app.utils.CallConfirm.video(requireContext(), username) {
                dismiss()
                VideoCallManager.placeCall(username)
                startActivity(VideoCallActivity.outgoingIntent(requireContext(), username))
            }
        }

        // ── Block ─────────────────────────────────────────────────────────────
        binding.rowBlock.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Block $displayName?")
                .setMessage("They'll be removed from your contacts and can't message you or send you friend requests. You can unblock them in Settings.")
                .setPositiveButton("Block") { _, _ ->
                    val app = requireContext().applicationContext
                    com.silvershireinn.app.SilvershireApp.appScope.launch { ContactActions.block(app, username) }
                    dismiss()
                    android.widget.Toast.makeText(app, "$displayName blocked", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ── Delete ────────────────────────────────────────────────────────────
        binding.rowDelete.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove $displayName?")
                .setMessage("Remove this contact? You can add each other again later.")
                .setPositiveButton("Remove") { _, _ ->
                    val app = requireContext().applicationContext
                    com.silvershireinn.app.SilvershireApp.appScope.launch { ContactActions.remove(app, username) }
                    dismiss()
                    android.widget.Toast.makeText(app, "$displayName removed", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ── Close button ──────────────────────────────────────────────────────
        binding.btnClose.setOnClickListener { HapticUtil.vibrate(requireContext()); dismiss() }
    }

    // ── Nickname dialog ───────────────────────────────────────────────────────

    private fun showNicknameDialog(username: String) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 16, 64, 8)
        }
        val til = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Nickname (blank to clear)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val et = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(PreferenceManager.getNickname(username))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        til.addView(et)
        container.addView(til)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Nickname for ${PreferenceManager.displayNameFor(username)}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val nick = et.text?.toString()?.trim() ?: ""
                if (nick.isBlank()) PreferenceManager.clearNickname(username)
                else               PreferenceManager.setNickname(username, nick)
                binding.tvNickname.text = nick.ifBlank { "None" }
                binding.tvContactUsername.text = PreferenceManager.displayNameFor(username)
                binding.tvCanonicalUsername.text = "Username: $username"
            }
            .setNeutralButton("Remove nickname") { _, _ ->
                PreferenceManager.clearNickname(username)
                binding.tvNickname.text = "None"
                binding.tvContactUsername.text = PreferenceManager.displayNameFor(username)
                binding.tvCanonicalUsername.text = "Username: $username"
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun stillFrame(path: String, iv: android.widget.ImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            val frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProfileMediaHelper.videoFrameBitmap(path) }
            if (_binding != null && frame != null && iv.drawable == null) { iv.setImageBitmap(frame); iv.visibility = View.VISIBLE }
        }
    }

    /**
     * The friend's banner fills the top of the sheet: down behind their picture, name and the
     * Message / Call / Video buttons, ending just above "Chat settings".
     */
    private val bannerSizer = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        val b = _binding ?: return@OnGlobalLayoutListener
        if (b.layoutBannerContainer.visibility != View.VISIBLE) return@OnGlobalLayoutListener
        val av = b.layoutContactAvatar
        val delta = b.layoutContactActions.bottom - av.top            // picture top .. buttons bottom
        if (delta <= 0) return@OnGlobalLayoutListener
        val top = (156 * resources.displayMetrics.density).toInt()  // banner showing above the picture
        val blp = b.layoutBannerContainer.layoutParams
        if (blp.height != top + delta) { blp.height = top + delta; b.layoutBannerContainer.layoutParams = blp }
        val alp = av.layoutParams as ViewGroup.MarginLayoutParams
        if (alp.topMargin != -delta) { alp.topMargin = -delta; av.layoutParams = alp }
    }

    override fun onDestroyView() {
        try { _binding?.root?.viewTreeObserver?.removeOnGlobalLayoutListener(bannerSizer) } catch (_: Exception) {}
        super.onDestroyView()
        picVideoHelper?.release(); picVideoHelper = null
        bannerVideoHelper?.release(); bannerVideoHelper = null
        _binding = null
    }
}
