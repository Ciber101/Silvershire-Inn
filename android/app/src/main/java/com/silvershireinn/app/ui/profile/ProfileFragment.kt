package com.silvershireinn.app.ui.profile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.utils.AppUpdateHelper
import com.silvershireinn.app.utils.MutedLoopVideoHelper
import com.silvershireinn.app.utils.ProfileMediaHelper
import com.silvershireinn.app.utils.AvatarShapeHelper
import com.silvershireinn.app.utils.AvatarShape
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.silvershireinn.app.R
import com.silvershireinn.app.databinding.FragmentProfileBinding
import com.silvershireinn.app.network.OnlineStatusManager
import com.silvershireinn.app.util.HapticUtil
import com.silvershireinn.app.repository.FileRepository
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toast
import com.silvershireinn.app.ui.media.ImageCropActivity
import com.silvershireinn.app.utils.toAvatarColor
import com.silvershireinn.app.utils.toInitials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by viewModels()
    private var profileVideoHelper: MutedLoopVideoHelper? = null
    private var bannerVideoHelper: MutedLoopVideoHelper? = null

    // ── Profile picture picker ────────────────────────────────────────────────
    private var pendingCropKind = "avatar"
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        if (pendingCropKind == "banner") saveBannerPicture(uri, "image/jpeg") else saveProfilePicture(uri, "image/jpeg")
    }

    private val picturePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val mime = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
        if (ProfileMediaHelper.isVideoMime(mime)) {
            saveProfilePicture(uri, mime)
        } else {
            pendingCropKind = "avatar"
            cropLauncher.launch(
                ImageCropActivity.intent(requireContext(), uri, ImageCropActivity.ASPECT_AVATAR_W, ImageCropActivity.ASPECT_AVATAR_H, 1024)
            )
        }
    }

    // ── Banner picture picker ─────────────────────────────────────────────────
    private val bannerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val mime = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
        if (ProfileMediaHelper.isVideoMime(mime)) {
            saveBannerPicture(uri, mime)
        } else {
            pendingCropKind = "banner"
            cropLauncher.launch(
                ImageCropActivity.intent(requireContext(), uri, ImageCropActivity.ASPECT_BANNER_W, ImageCropActivity.ASPECT_BANNER_H, 1920)
            )
        }
    }

    private var pendingBrandKind = "logo"
    private val brandImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        saveBrandImage(uri, pendingBrandKind)
    }

    // ── Banner updated broadcast receiver ────────────────────────────────────
    private val bannerUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Our own banner changed from another device - reload it
            loadBanner()
        }
    }

    // ── Profile pic restored broadcast receiver ───────────────────────────────
    private val profilePicRestoredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Profile pic was just downloaded from the server (post-login async download)
            loadProfilePicture()
        }
    }

    // ── Tab video background picker ───────────────────────────────────────────
    private var pendingTabKey: String? = null
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val key = pendingTabKey ?: return@registerForActivityResult
        pendingTabKey = null
        uri ?: return@registerForActivityResult
        saveTabVideoBg(key, uri)
    }

    // ── Tab image background picker ───────────────────────────────────────────
    private var pendingTabImageKey: String? = null
    private val tabImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val key = pendingTabImageKey ?: return@registerForActivityResult
        pendingTabImageKey = null
        uri ?: return@registerForActivityResult
        saveTabImageBg(key, uri)
    }

    // ── Overlay-permission launcher (for chat heads) ─────────────────────────
    // We use a launcher instead of plain startActivity so that when the user returns
    // from the system settings page we can immediately re-evaluate and enable the switch.
    // On some OEM ROMs (Samsung, Xiaomi, etc.) canDrawOverlays() still reports false even
    // after the user grants the permission - so we enable chat heads regardless and let
    // ChatHeadService catch any SecurityException gracefully.
    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            // Back from the overlay settings screen. Some phones report the new permission a
            // moment late, so check again shortly. It used to switch chat heads on even when
            // the permission was refused (heads then silently never appeared).
            val b = _binding ?: return@registerForActivityResult
            b.root.postDelayed({
                val ctx = context ?: return@postDelayed
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
                PreferenceManager.chatHeads = granted
                _binding?.switchChatHeads?.isChecked = granted
                if (granted) showBubblesGuidanceIfNeeded()
                else ctx.toast("Chat heads need \"Display over other apps\" to be allowed")
            }, 600)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val accentOnCreate = PreferenceManager.accentColorInt()
        AccentColorHelper.apply(binding.root, accentOnCreate)
        AccentColorHelper.applyTextColor(binding.root, PreferenceManager.uiTextColorInt())
        applyDangerButtonColors(accentOnCreate)
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)

        val username = PreferenceManager.username
        binding.tvUsername.text = username
        binding.tvInitials.text = username.toInitials()
        binding.avatarCircle.setCardBackgroundColor(username.toAvatarColor())

        // Video surfaces for avatar / banner (muted loop)
        try {
            profileVideoHelper = MutedLoopVideoHelper(requireContext(), binding.vvProfilePic)
            bannerVideoHelper = MutedLoopVideoHelper(requireContext(), binding.vvBanner)
        } catch (_: Exception) {}
        applyOwnAvatarShape()

        // Load saved profile picture
        loadProfilePicture()

        // Edit photo button
        binding.btnEditPhoto.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            picturePicker.launch(arrayOf("image/*", "video/*"))
        }

        // ── Banner ────────────────────────────────────────────────────────
        loadBanner()
        binding.btnEditBanner.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            bannerPicker.launch(arrayOf("image/*", "video/*"))
        }

        // Change username button
        binding.btnChangeUsername.setOnClickListener { HapticUtil.vibrate(requireContext()); showChangeUsernameDialog() }

        // Version / build stamp (About card + bottom of settings)
        try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val stamp = "Build ${code} - v${info.versionName}"
            binding.tvVersionName.text = stamp
            binding.tvBuildStamp.text = stamp
        } catch (_: Exception) {
            binding.tvVersionName.text = "Version unknown"
            binding.tvBuildStamp.text = "Version unknown"
        }
        binding.tvVersionName.setOnClickListener {
            lifecycleScope.launch {
                AppUpdateHelper.checkAndPrompt(requireActivity(), forceShow = true)
            }
        }
        binding.cardAbout.setOnLongClickListener {
            lifecycleScope.launch {
                AppUpdateHelper.checkAndPrompt(requireActivity(), forceShow = true)
            }
            true
        }

        // ── Privacy ───────────────────────────────────────────────────────
        binding.switchDiscoverable.isChecked    = PreferenceManager.discoverable
        binding.switchReadReceipts.isChecked    = PreferenceManager.readReceipts
        binding.switchTypingIndicators.isChecked = PreferenceManager.typingIndicators

        binding.switchDiscoverable.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.discoverable = checked
            viewModel.setDiscoverable(checked)
        }
        binding.switchReadReceipts.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.readReceipts = checked
        }
        binding.switchTypingIndicators.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.typingIndicators = checked
        }

        // Presence mode (Online / AFK / DND / Busy / BRB / Invisible)
        refreshPresenceRow()
        binding.rowPresence.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            showPresencePicker()
        }

        // ── Banner toggles ────────────────────────────────────────────────
        binding.switchBannerEnabled.isChecked = PreferenceManager.bannerEnabled
        binding.switchBannerVisibleToContacts.isChecked = PreferenceManager.bannerVisibleToContacts

        binding.switchBannerEnabled.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.bannerEnabled = checked
            updateBannerVisibility()
        }
        binding.switchBannerVisibleToContacts.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.bannerVisibleToContacts = checked
        }

        // ── Notifications ─────────────────────────────────────────────────
        binding.switchNotificationSounds.isChecked  = PreferenceManager.notificationSounds
        binding.switchNotificationPreview.isChecked = PreferenceManager.notificationPreview

        binding.switchNotificationSounds.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.notificationSounds = checked
        }
        binding.switchNotificationPreview.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.notificationPreview = checked
        }

        // Master notifications switch - when off, the connection still runs but no
        // message/friend-request alerts are posted (incoming calls are unaffected).
        binding.switchNotificationsEnabled.isChecked = PreferenceManager.notificationsEnabled
        binding.switchNotificationsEnabled.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.notificationsEnabled = checked
            context?.toast(if (checked) "Notifications on" else "Notifications off")
        }

        // Background reliability - opens the system battery-optimization exemption dialog.
        binding.rowBatteryOptimization.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            com.silvershireinn.app.utils.BatteryOptimizationHelper.requestExemption(requireContext())
        }
        refreshBatteryOptStatus()

        // ── Security ──────────────────────────────────────────────────────
        binding.switchRequirePassword.isChecked = PreferenceManager.requirePasswordOnOpen
        updateSecuritySubsVisibility(PreferenceManager.requirePasswordOnOpen)

        binding.switchRequirePassword.setOnCheckedChangeListener { _, checked ->
            // Turning the lock on with nothing to unlock with did nothing; ask for a PIN.
            if (checked && PreferenceManager.passwordHash.isBlank() && PreferenceManager.pinHash.isBlank() &&
                !PreferenceManager.biometricEnabled) {
                PreferenceManager.requirePasswordOnOpen = true
                updateSecuritySubsVisibility(true)
                showSetupPinDialog(enableAfter = true)
                return@setOnCheckedChangeListener
            }
            PreferenceManager.requirePasswordOnOpen = checked
            updateSecuritySubsVisibility(checked)
            if (!checked) {
                // Disable all sub-toggles when master lock is off
                PreferenceManager.pinEnabled       = false
                PreferenceManager.biometricEnabled = false
                binding.switchUsePIN.isChecked        = false
                binding.switchUseBiometric.isChecked  = false
                binding.switchUsePassword.isChecked   = false
            }
            context?.toast(if (checked) "Lock screen enabled" else "Lock screen disabled")
        }

        // ── PIN sub-toggle ─────────────────────────────────────────────────
        binding.switchUsePIN.isChecked = PreferenceManager.pinEnabled
        updatePinButtonVisibility(PreferenceManager.pinEnabled)

        binding.switchUsePIN.setOnCheckedChangeListener { _, checked ->
            if (checked && PreferenceManager.pinHash.isBlank()) {
                // Must set PIN before enabling
                binding.switchUsePIN.isChecked = false
                showSetupPinDialog(enableAfter = true)
            } else {
                PreferenceManager.pinEnabled = checked
                updatePinButtonVisibility(checked)
                if (!checked) {
                    binding.tvPinStatus.text = "PIN not set"
                    PreferenceManager.pinHash = ""
                }
            }
        }

        binding.btnSetupPIN.setOnClickListener { showSetupPinDialog(enableAfter = false) }

        // ── Biometric sub-toggle ───────────────────────────────────────────
        binding.switchUseBiometric.isChecked = PreferenceManager.biometricEnabled
        val bioStatus = BiometricManager.from(requireContext())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (bioStatus != BiometricManager.BIOMETRIC_SUCCESS &&
            bioStatus != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            binding.switchUseBiometric.isEnabled = false
            binding.switchUseBiometric.alpha = 0.4f
        }

        binding.switchUseBiometric.setOnCheckedChangeListener { _, checked ->
            val canAuth = BiometricManager.from(requireContext())
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (checked && canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                binding.switchUseBiometric.isChecked = false
                context?.toast("No biometrics enrolled - enroll in device Settings first")
            } else if (checked && canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                binding.switchUseBiometric.isChecked = false
                context?.toast("Biometric authentication not available on this device")
            } else {
                PreferenceManager.biometricEnabled = checked
            }
        }

        // ── Use Password sub-toggle ────────────────────────────────────────
        // "Also ask for password": with a PIN / fingerprint on, require the password too.
        // Off: fingerprint / PIN alone unlocks and the password is the fallback.
        val hasPassword = PreferenceManager.passwordHash.isNotBlank()
        binding.switchUsePassword.isChecked = hasPassword && PreferenceManager.lockUsePassword
        binding.switchUsePassword.setOnCheckedChangeListener { _, checked ->
            if (checked && PreferenceManager.passwordHash.isBlank()) {
                binding.switchUsePassword.isChecked = false
                context?.toast("This account has no password. Use a PIN or fingerprint instead")
                return@setOnCheckedChangeListener
            }
            PreferenceManager.lockUsePassword = checked
        }

        // ── Auto-logout ────────────────────────────────────────────────────
        binding.switchAutoLogout.isChecked = PreferenceManager.autoLogoutEnabled
        updateAutoLogoutButtonVisibility(PreferenceManager.autoLogoutEnabled)
        updateAutoLogoutSubtitle()

        binding.switchAutoLogout.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.autoLogoutEnabled = checked
            updateAutoLogoutButtonVisibility(checked)
        }
        binding.btnAutoLogoutTime.setOnClickListener { showAutoLogoutTimePicker() }

        binding.btnServerAddress.setOnClickListener { showEditServerDialog() }
        binding.btnShareApp.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            shareAppDownloadLink()
        }
        binding.btnClearHistory.setOnClickListener  { showClearHistoryDialog() }
        binding.btnPanicWipe.setOnClickListener     { showPanicWipeDialog() }
        binding.btnExportIdentity.setOnClickListener { showExportIdentityDialog() }

        // ── Appearance ────────────────────────────────────────────────────
        val sizeLabel = { p: Int -> when (p) { 0 -> "Small"; 2 -> "Large"; else -> "Medium" } }

        // Chat message font size
        binding.seekbarFontSize.progress = PreferenceManager.fontSize
        binding.tvFontSizeLabel.text     = sizeLabel(PreferenceManager.fontSize)
        binding.seekbarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                PreferenceManager.fontSize = progress
                binding.tvFontSizeLabel.text = sizeLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Broadcast so any open ChatActivity refreshes its adapter immediately
                val ctx = context ?: return
                ctx.sendBroadcast(android.content.Intent("com.silvershireinn.CHAT_FONT_CHANGED").apply {
                    `package` = ctx.packageName
                })
            }
        })

        // UI text size (buttons, labels, settings rows)
        binding.seekbarUiFontSize.progress = PreferenceManager.uiFontSize
        binding.tvUiFontSizeLabel.text     = sizeLabel(PreferenceManager.uiFontSize)
        binding.seekbarUiFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                PreferenceManager.uiFontSize = progress
                binding.tvUiFontSizeLabel.text = sizeLabel(progress)
            }
            private var startValue = -1
            override fun onStartTrackingTouch(sb: SeekBar?) { startValue = sb?.progress ?: -1 }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (sb?.progress == startValue) return   // a tap that didn't change it: no rebuild
                // Recreate MainActivity so the new fontScale in attachBaseContext takes effect
                // across all fragments. This is the only way Configuration.fontScale changes
                // apply to existing Activities - a brief re-draw is expected.
                requireActivity().recreate()
            }
        })

        val avatarModes = arrayOf("Every message", "Last in a row", "Off")
        binding.tvChatAvatarMode.text = avatarModes[PreferenceManager.chatAvatarMode.coerceIn(0, 2)]
        binding.rowChatAvatarMode.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Friend's picture in chats")
                .setSingleChoiceItems(avatarModes, PreferenceManager.chatAvatarMode.coerceIn(0, 2)) { d, which ->
                    PreferenceManager.chatAvatarMode = which
                    binding.tvChatAvatarMode.text = avatarModes[which]
                    d.dismiss()
                }.show()
        }

        binding.switchMyAvatarInChats.isChecked = PreferenceManager.showMyAvatar
        binding.switchMyAvatarInChats.setOnCheckedChangeListener { _, checked ->
            HapticUtil.vibrate(requireContext())
            PreferenceManager.showMyAvatar = checked
        }

        binding.switchNotesChat.isChecked = PreferenceManager.notesEnabled
        binding.switchNotesChat.setOnCheckedChangeListener { _, checked ->
            HapticUtil.vibrate(requireContext())
            PreferenceManager.notesEnabled = checked
        }
        fun notesLockLabel() = when (PreferenceManager.notesLockType) {
            "pin" -> "PIN"; "password" -> "Password"; "biometric" -> "Fingerprint / face"; else -> "None"
        }
        binding.tvNotesLock.text = notesLockLabel()
        binding.rowNotesLock.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            com.silvershireinn.app.utils.NotesLock.configure(requireActivity()) { binding.tvNotesLock.text = notesLockLabel() }
        }
        binding.rowPictureSizes.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            com.silvershireinn.app.ui.profile.PictureSizeDialog.show(requireActivity())
        }

        binding.switchCompactBubbles.isChecked = PreferenceManager.compactBubbles
        binding.switchCompactBubbles.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.compactBubbles = checked
        }

        binding.switchJumpToBottom.isChecked = PreferenceManager.jumpToBottomOnSend
        binding.switchJumpToBottom.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.jumpToBottomOnSend = checked
        }

        // ── Chat heads + opacity slider ────────────────────────────────────
        binding.seekbarChatHeadAlpha.progress  = PreferenceManager.chatHeadAlpha
        binding.tvChatHeadAlphaLabel.text      = "${PreferenceManager.chatHeadAlpha}%"
        binding.seekbarChatHeadAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(10)
                PreferenceManager.chatHeadAlpha = clamped
                binding.tvChatHeadAlphaLabel.text = "$clamped%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?)  {}
        })

        // ── Chat head size (40-96 dp) ─────────────────────────────────────────
        binding.seekbarChatHeadSize.progress = PreferenceManager.chatHeadSize - 40
        binding.tvChatHeadSizeLabel.text = "${PreferenceManager.chatHeadSize}dp"
        binding.seekbarChatHeadSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                PreferenceManager.chatHeadSize = progress + 40
                binding.tvChatHeadSizeLabel.text = "${progress + 40}dp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                com.silvershireinn.app.ui.chathead.ChatHeadService.refreshAvatar(requireContext(), PreferenceManager.profilePicturePath)
            }
        })

        // ── Chat head dismiss-on-tap toggle ───────────────────────────────────
        binding.switchChatHeadDismissOnTap.isChecked = PreferenceManager.chatHeadDismissOnTap
        binding.switchChatHeadDismissOnTap.setOnCheckedChangeListener { _, checked ->
            HapticUtil.vibrate(requireContext())
            PreferenceManager.chatHeadDismissOnTap = checked
        }

        binding.switchChatHeadSnap.isChecked = PreferenceManager.chatHeadSnapEnabled
        binding.switchChatHeadSnap.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.chatHeadSnapEnabled = checked
        }
        fun shapeLabel() = com.silvershireinn.app.utils.AvatarShape.fromPref(PreferenceManager.chatHeadShape).label()
        binding.tvChatHeadShape.text = shapeLabel()
        binding.rowChatHeadShape.setOnClickListener {
            val items = com.silvershireinn.app.utils.AvatarShape.values()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("My picture shape")
                .setItems(items.map { it.label() }.toTypedArray()) { _, which ->
                    val chosen = items[which]
                    PreferenceManager.chatHeadShape = chosen.prefKey()
                    binding.tvChatHeadShape.text = chosen.label()
                    WebSocketManager.sendAvatarShape(chosen.toServer())
                    applyOwnAvatarShape()
                    com.silvershireinn.app.ui.chathead.ChatHeadService.refreshAvatar(
                        requireContext(), PreferenceManager.profilePicturePath
                    )
                }.show()
        }
        binding.tvUiTheme.text = if (com.silvershireinn.app.utils.OrnateTheme.isOn(requireContext())) "Silvershire (forged iron)" else "Classic"
        binding.rowUiTheme.setOnClickListener {
            val opts = arrayOf("Silvershire (forged iron)", "Classic")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Theme")
                .setItems(opts) { _, which ->
                    val ornate = which == 0
                    if (ornate == com.silvershireinn.app.utils.OrnateTheme.isOn(requireContext())) return@setItems
                    com.silvershireinn.app.utils.OrnateTheme.set(requireContext(), ornate)
                    requireActivity().recreate()      // rebuild every screen in the chosen look
                }.show()
        }
        binding.rowProfileFrame.setOnClickListener {
            com.silvershireinn.app.ui.profile.FrameGeneratorDialog.show(requireContext()) { applyOwnFrame() }
        }
        run {
            val f = android.content.IntentFilter("com.silvershireinn.FRAMES_CHANGED")
            if (android.os.Build.VERSION.SDK_INT >= 33) requireContext().registerReceiver(framesReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else requireContext().registerReceiver(framesReceiver, f)
        }
        binding.seekbarStackOpacity.progress = PreferenceManager.stackSwitcherOpacity
        binding.tvStackOpacityLabel.text = "${PreferenceManager.stackSwitcherOpacity}%"
        binding.seekbarStackOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                PreferenceManager.stackSwitcherOpacity = progress
                binding.tvStackOpacityLabel.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        bindBrandingUi()

        binding.switchChatHeads.isChecked = PreferenceManager.chatHeads
        binding.switchChatHeads.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
                    // Don't flip the switch yet; wait for the user to grant the permission
                    binding.switchChatHeads.isChecked = false
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Allow Chat Heads")
                        .setMessage(
                            "Chat heads need the \"Display over other apps\" permission.\n\n" +
                            "1. Tap Open Settings\n" +
                            "2. Find Silvershire Inn and enable \"Allow display over other apps\"\n" +
                            "3. Press Back - the switch will turn on automatically."
                        )
                        .setPositiveButton("Open Settings") { _, _ ->
                            // Use the launcher so we get the callback when the user returns
                            overlayPermLauncher.launch(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${requireContext().packageName}")
                            ))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    PreferenceManager.chatHeads = true
                    showBubblesGuidanceIfNeeded()
                }
            } else {
                PreferenceManager.chatHeads = false
            }
        }

        // ── Button Haptics ─────────────────────────────────────────────────
        binding.switchButtonHaptics.isChecked = PreferenceManager.buttonHapticsEnabled
        binding.switchButtonHaptics.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.buttonHapticsEnabled = checked
        }

        // ── My Bubble Color ────────────────────────────────────────────────
        val savedBubbleColor = PreferenceManager.myBubbleColor
        if (savedBubbleColor != null) {
            try { binding.viewMyBubbleColor.setBackgroundColor(Color.parseColor(savedBubbleColor)) }
            catch (_: Exception) {}
        }
        binding.btnMyBubbleColor.setOnClickListener { showBubbleColorPickerDialog() }

        // Their Bubble Color (global default for received)
        val savedTheirBubble = PreferenceManager.theirBubbleColor
        if (savedTheirBubble != null) {
            try {
                binding.viewTheirBubbleColor.setBackgroundColor(Color.parseColor(savedTheirBubble))
                binding.viewTheirBubbleColor.visibility = android.view.View.VISIBLE
            } catch (_: Exception) {}
        }
        binding.btnTheirBubbleColor.setOnClickListener { showTheirBubbleColorPickerDialog() }


        // ── Accent Color ───────────────────────────────────────────────────
        val savedAccent = PreferenceManager.accentColor
        if (savedAccent != null) {
            try { binding.viewAccentColor.setBackgroundColor(Color.parseColor(savedAccent)) }
            catch (_: Exception) {}
        } else {
            binding.viewAccentColor.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.color_gold))
        }
        binding.btnAccentColor.setOnClickListener { showAccentColorPickerDialog() }

        // ── UI Text Color ─────────────────────────────────────────────────
        val savedTextColor = PreferenceManager.uiTextColor
        if (savedTextColor != null) {
            try {
                binding.viewUiTextColor.setBackgroundColor(Color.parseColor(savedTextColor))
                binding.viewUiTextColor.visibility = android.view.View.VISIBLE
            } catch (_: Exception) {}
        }
        binding.btnPickUiTextColor.setOnClickListener {
            val current = PreferenceManager.uiTextColorInt()
                ?: ContextCompat.getColor(requireContext(), R.color.color_text_primary)
            com.silvershireinn.app.ui.common.HsvColorPickerDialog(requireContext(), current) { color ->
                val hex = String.format("#%06X", color and 0xFFFFFF)
                PreferenceManager.uiTextColor = hex
                binding.viewUiTextColor.setBackgroundColor(color)
                binding.viewUiTextColor.visibility = android.view.View.VISIBLE
                // Apply immediately
                AccentColorHelper.applyTextColor(binding.root, color)
                context?.toast("Text color updated")
            }.show()
        }
        binding.btnResetUiTextColor.setOnClickListener {
            PreferenceManager.uiTextColor = null
            binding.viewUiTextColor.visibility = android.view.View.INVISIBLE
            AccentColorHelper.applyTextColor(binding.root, null)
            context?.toast("Text color reset to default")
        }
        binding.rowUiTextColor.setOnLongClickListener {
            PreferenceManager.uiTextColor = null
            binding.viewUiTextColor.visibility = android.view.View.INVISIBLE
            AccentColorHelper.applyTextColor(binding.root, null)
            context?.toast("Text color reset to default")
            true
        }

        // ── Font Family ────────────────────────────────────────────────────
        binding.tvFontFamilyLabel.text = fontFamilyDisplayName(PreferenceManager.fontFamily)
        binding.btnFontFamily.setOnClickListener { showFontFamilyPickerDialog() }

        // ── Panic word ─────────────────────────────────────────────────────
        refreshPanicStatus()
        binding.rowPanicWords.setOnClickListener { showPanicWordsDialog() }

        // ── Full date and time on messages ─────────────────────────────────
        binding.switchFullTimestamps.isChecked = PreferenceManager.showFullTimestamps
        binding.switchFullTimestamps.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.showFullTimestamps = checked
        }

        // ── Clock Format ───────────────────────────────────────────────────
        binding.switchUse24HourClock.isChecked = PreferenceManager.use24HourClock
        binding.switchUse24HourClock.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.use24HourClock = checked
        }

        // ── Tab video backgrounds ──────────────────────────────────────────
        fun pickTabBg(tabKey: String) {
            pendingTabKey = tabKey
            videoPicker.launch("video/*")
        }
        fun pickTabImageBg(tabKey: String) {
            pendingTabImageKey = tabKey
            tabImagePicker.launch("image/*")
        }
        binding.btnChatsTabBg.setOnClickListener    { pickTabBg("chats") }
        binding.btnContactsTabBg.setOnClickListener { pickTabBg("contacts") }
        binding.btnProfileTabBg.setOnClickListener  { pickTabBg("profile") }

        binding.btnChatsTabImageBg.setOnClickListener    { pickTabImageBg("chats") }
        binding.btnContactsTabImageBg.setOnClickListener { pickTabImageBg("contacts") }
        binding.btnProfileTabImageBg.setOnClickListener  { pickTabImageBg("profile") }

        binding.btnChatsTabBgClear.setOnClickListener {
            PreferenceManager.setTabVideoBg("chats", null)
            PreferenceManager.setTabImageBg("chats", null)
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.refreshCurrentTabVideoBg()
            context?.toast("Chats background cleared")
        }
        binding.btnContactsTabBgClear.setOnClickListener {
            PreferenceManager.setTabVideoBg("contacts", null)
            PreferenceManager.setTabImageBg("contacts", null)
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.refreshCurrentTabVideoBg()
            context?.toast("Contacts background cleared")
        }
        binding.btnProfileTabBgClear.setOnClickListener {
            PreferenceManager.setTabVideoBg("profile", null)
            PreferenceManager.setTabImageBg("profile", null)
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.refreshCurrentTabVideoBg()
            context?.toast("Profile background cleared")
        }

        // ── UI Opacity ────────────────────────────────────────────────────
        val savedOpacity = PreferenceManager.uiOpacity.coerceIn(10, 100)
        binding.seekbarUiOpacity.progress = savedOpacity
        binding.tvUiOpacityLabel.text     = "$savedOpacity%"
        binding.seekbarUiOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(10)
                PreferenceManager.uiOpacity = clamped
                binding.tvUiOpacityLabel.text = "$clamped%"
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyUiOpacity()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?)  {}
        })

        // ── Navigation ────────────────────────────────────────────────────
        binding.switchNavBarTop.isChecked = PreferenceManager.navBarAtTop
        binding.switchNavBarTop.setOnCheckedChangeListener { _, checked ->
            PreferenceManager.navBarAtTop = checked
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyNavPosition()
        }
        binding.tvTabOrderLabel.text = tabOrderDisplayName(PreferenceManager.tabOrder)
        binding.btnTabOrder.setOnClickListener { HapticUtil.vibrate(requireContext()); showTabOrderDialog() }

        // ── Logout ────────────────────────────────────────────────────────
        binding.btnLogout.setOnClickListener { HapticUtil.vibrate(requireContext()); showLogoutConfirmDialog() }

        // ── Reset all settings ────────────────────────────────────────────
        binding.btnResetAllSettings.setOnClickListener { HapticUtil.vibrate(requireContext()); showResetAllDialog() }

        // ── Ko-fi / Support ───────────────────────────────────────────────
        // If the user permanently hid the support block, remove it entirely
        if (PreferenceManager.supportBlockHidden) {
            binding.headerSupport.visibility = View.GONE
            binding.cardSupport.visibility   = View.GONE
        }

        binding.btnKofi.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Open Ko-fi")
                .setMessage("You're about to be taken to an external website (ko-fi.com) in your browser. Thank you for your support!")
                .setPositiveButton("Open Browser") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/ciberai")))
                    } catch (_: Exception) {
                        context?.toast("Could not open browser")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnHideSupportBlock.setOnClickListener {
            HapticUtil.vibrate(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hide Support Section?")
                .setMessage("This will permanently hide the Support section from your profile. You own this app - you don't have to see it if you don't want to.")
                .setPositiveButton("Hide it") { _, _ ->
                    PreferenceManager.supportBlockHidden = true
                    binding.headerSupport.visibility = View.GONE
                    binding.cardSupport.visibility   = View.GONE
                }
                .setNegativeButton("Keep it", null)
                .show()
        }

        // ── Per-setting reset buttons ─────────────────────────────────────
        setupResetButtons()

        // ── Blocked users list ────────────────────────────────────────────
        refreshBlockedList()

        // ── Collapsible sections ──────────────────────────────────────────
        setupCollapsible("branding",      binding.headerBranding,      binding.cardBranding,      binding.chevronBranding)
        setupCollapsible("privacy",       binding.headerPrivacy,       binding.cardPrivacy,       binding.chevronPrivacy)
        setupCollapsible("notifications", binding.headerNotifications, binding.cardNotifications, binding.chevronNotifications)
        setupCollapsible("security",      binding.headerSecurity,      binding.cardSecurity,      binding.chevronSecurity)
        setupCollapsible("appearance",    binding.headerAppearance,    binding.cardAppearance,    binding.chevronAppearance)
        setupCollapsible("navigation",    binding.headerNavigation,    binding.cardNavigation,    binding.chevronNavigation)
        setupCollapsible("blocked",       binding.headerBlocked,       binding.cardBlocked,       binding.chevronBlocked)
        setupCollapsible("about",         binding.headerAbout,         binding.cardAbout,         binding.chevronAbout)
        // Only register support collapsible if it's still visible
        if (!PreferenceManager.supportBlockHidden) {
            setupCollapsible("support", binding.headerSupport, binding.cardSupport, binding.chevronSupport)
        }
        setupSettingsSearch()
    }

    // ── Panic phrase (account-wide, through the server) ───────────────────────

    private val micPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            com.silvershireinn.app.utils.PanicLock.listenOutLoud = false
            context?.toast("Microphone not allowed: listening out loud stays off")
        }
        context?.let { com.silvershireinn.app.utils.PanicVoice.refresh(it) }
        com.silvershireinn.app.network.WebSocketService.refreshForegroundTypes()
        if (_binding != null) refreshPanicStatus()
    }

    private fun refreshPanicStatus() {
        val pl = com.silvershireinn.app.utils.PanicLock
        binding.tvPanicStatus.text = if (pl.isSet())
            "On for your account (every device)" + (if (pl.listenOutLoud) ". Listening out loud" else "")
        else "Off. A word or phrase that locks your account everywhere"
    }

    private fun showPanicWordsDialog() {
        val ctx = requireContext()
        val pl = com.silvershireinn.app.utils.PanicLock
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun field(h: String) = android.widget.EditText(ctx).apply {
            hint = h
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val curF = field("Current unlock phrase")
        val lockF = field("Lock word or phrase")
        val unlockF = field("Unlock word or phrase")
        val unlock2F = field("Unlock word or phrase again")
        val info = TextView(ctx).apply {
            text = "Set once for your account: every phone and browser you sign in on uses the same phrases. " +
                   "Type the lock phrase in any text box (on its own or as the last words), or say it out loud, " +
                   "and your account locks on every device at once. A new install, a new browser or a reinstalled server stays locked too. " +
                   "Only the unlock phrase opens it, checked by the server (PIN, password and fingerprint won't). " +
                   "Capitals and punctuation don't matter. Pick phrases you'd never type by accident."
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.color_text_secondary))
            textSize = 13f
        }
        val onDevice = com.silvershireinn.app.utils.PanicVoice.onDevice(ctx)
        val listen = android.widget.CheckBox(ctx).apply {
            text = "Listen out loud for the phrases" + if (onDevice) "" else " (this phone may send the sound to Google to recognise it)"
            isChecked = pl.listenOutLoud
        }
        val listenBg = android.widget.CheckBox(ctx).apply {
            text = "Keep listening when the app is in the background (shows the microphone in use)"
            isChecked = pl.listenInBackground
            isEnabled = listen.isChecked
        }
        listen.setOnCheckedChangeListener { _, on ->
            pl.listenOutLoud = on; listenBg.isEnabled = on
            if (on && !com.silvershireinn.app.utils.PanicVoice.hasMicPermission(ctx)) micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            com.silvershireinn.app.utils.PanicVoice.refresh(ctx)
            com.silvershireinn.app.network.WebSocketService.refreshForegroundTypes()
            refreshPanicStatus()
        }
        listenBg.setOnCheckedChangeListener { _, on ->
            pl.listenInBackground = on
            com.silvershireinn.app.utils.PanicVoice.refresh(ctx)
            com.silvershireinn.app.network.WebSocketService.refreshForegroundTypes()
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(info)
            if (pl.isSet()) addView(curF)
            addView(lockF); addView(unlockF); addView(unlock2F)
            addView(listen); addView(listenBg)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }
        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle("Panic phrase")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Close", null)
        if (pl.isSet()) b.setNeutralButton("Turn off", null)
        val dlg = b.create()

        fun send(msg: org.json.JSONObject, done: String) {
            pl.setListener = { ok, err ->
                pl.setListener = null
                activity?.runOnUiThread {
                    if (ok) { dlg.dismiss(); context?.toast(done); if (_binding != null) refreshPanicStatus() }
                    else context?.toast(err ?: "Not saved")
                }
            }
            if (!com.silvershireinn.app.network.WebSocketManager.sendRaw(msg.toString())) {
                pl.setListener = null
                context?.toast("Not connected to the server. Try again in a moment.")
            }
        }

        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val l = pl.normalize(lockF.text); val u = pl.normalize(unlockF.text); val u2 = pl.normalize(unlock2F.text)
                val cur = curF.text?.toString().orEmpty()
                when {
                    pl.isSet() && pl.normalize(cur).isEmpty() -> curF.error = "Needed to change the phrases"
                    l.length < 4 -> lockF.error = "At least 4 letters"
                    u.length < 4 -> unlockF.error = "At least 4 letters"
                    u != u2 -> unlock2F.error = "Doesn't match"
                    l == u -> unlockF.error = "Must be different from the lock phrase"
                    else -> viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val proofs = if (pl.isSet()) pl.unlockProofs(cur) else emptyList()
                        val msg = pl.buildSetMessage(l, u, proofs)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { send(msg, "Panic phrase saved for your account") }
                    }
                }
            }
            if (pl.isSet()) dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val cur = curF.text?.toString().orEmpty()
                if (pl.normalize(cur).isEmpty()) { curF.error = "Enter the current unlock phrase to turn it off"; return@setOnClickListener }
                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val msg = pl.buildClearMessage(pl.unlockProofs(cur))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { send(msg, "Panic phrase turned off") }
                }
            }
        }
        dlg.show()
    }

    // ── Settings search ───────────────────────────────────────────────────────
    // Typing shows only the settings whose text matches (any word on the row, or the section
    // name), with their sections opened. Clearing puts every section back the way it was.

    private val searchSnapshot = HashMap<View, Int>()

    private fun settingsSections(): List<Pair<View, View>> = listOf(
        binding.headerPrivacy to binding.cardPrivacy,
        binding.headerNotifications to binding.cardNotifications,
        binding.headerSecurity to binding.cardSecurity,
        binding.headerAppearance to binding.cardAppearance,
        binding.headerNavigation to binding.cardNavigation,
        binding.headerBlocked to binding.cardBlocked,
        binding.headerBranding to binding.cardBranding,
        binding.headerAbout to binding.cardAbout,
        binding.headerSupport to binding.cardSupport
    )

    private fun viewText(v: View, out: StringBuilder) {
        when (v) {
            is android.widget.EditText -> { out.append(' ').append(v.hint ?: "") }
            is android.widget.TextView -> { out.append(' ').append(v.text ?: "") }
        }
        v.contentDescription?.let { out.append(' ').append(it) }
        if (v is ViewGroup) for (i in 0 until v.childCount) viewText(v.getChildAt(i), out)
    }

    private fun snapshot(v: View) { if (!searchSnapshot.containsKey(v)) searchSnapshot[v] = v.visibility }

    private fun applySettingsSearch(raw: String) {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) {
            for ((v, vis) in searchSnapshot) v.visibility = vis
            searchSnapshot.clear()
            binding.tvSettingsNoMatch.visibility = View.GONE
            return
        }
        var any = false
        for ((header, card) in settingsSections()) {
            snapshot(header); snapshot(card)
            if (searchSnapshot[header] == View.GONE) continue      // section switched off (support)
            val inner = (card as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: continue
            val title = StringBuilder().also { viewText(header, it) }.toString().lowercase()
            val titleHit = title.contains(q)
            var hits = 0
            for (i in 0 until inner.childCount) {
                val row = inner.getChildAt(i)
                snapshot(row)
                if (searchSnapshot[row] == View.GONE) continue      // hidden for other reasons
                val isDivider = row !is ViewGroup && row !is android.widget.TextView
                val hit = titleHit || (!isDivider && StringBuilder().also { viewText(row, it) }.toString().lowercase().contains(q))
                row.visibility = if (hit && !(isDivider && !titleHit)) View.VISIBLE else View.GONE
                if (hit && !isDivider) hits++
            }
            header.visibility = if (hits > 0) View.VISIBLE else View.GONE
            card.visibility = if (hits > 0) View.VISIBLE else View.GONE
            if (hits > 0) any = true
        }
        binding.tvSettingsNoMatch.visibility = if (any) View.GONE else View.VISIBLE
    }

    private fun setupSettingsSearch() {
        binding.etSettingsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString().orEmpty()
                binding.btnClearSettingsSearch.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
                applySettingsSearch(t)
            }
        })
        binding.etSettingsSearch.setOnEditorActionListener { v, _, _ ->
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        binding.btnClearSettingsSearch.setOnClickListener { binding.etSettingsSearch.setText("") }
    }

    // ── Per-setting reset buttons ─────────────────────────────────────────────

    private fun setupResetButtons() {
        // Helper: show confirmation snack/toast then run action
        fun confirmReset(label: String, action: () -> Unit) {
            com.silvershireinn.app.util.HapticUtil.vibrate(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset Setting")
                .setMessage("Reset \"$label\" to its default value?")
                .setPositiveButton("Reset") { _, _ -> action() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Privacy
        binding.btnResetDiscoverable.setOnClickListener {
            confirmReset("Discoverable") {
                PreferenceManager.discoverable = true
                binding.switchDiscoverable.isChecked = true
                viewModel.setDiscoverable(true)
            }
        }
        binding.rowDiscoverable.setOnLongClickListener {
            confirmReset("Discoverable") {
                PreferenceManager.discoverable = true
                binding.switchDiscoverable.isChecked = true
                viewModel.setDiscoverable(true)
            }; true
        }

        binding.btnResetReadReceipts.setOnClickListener {
            confirmReset("Read Receipts") {
                PreferenceManager.readReceipts = true
                binding.switchReadReceipts.isChecked = true
            }
        }
        binding.rowReadReceipts.setOnLongClickListener {
            confirmReset("Read Receipts") {
                PreferenceManager.readReceipts = true
                binding.switchReadReceipts.isChecked = true
            }; true
        }

        binding.btnResetTypingIndicators.setOnClickListener {
            confirmReset("Typing Indicators") {
                PreferenceManager.typingIndicators = true
                binding.switchTypingIndicators.isChecked = true
            }
        }
        binding.rowTypingIndicators.setOnLongClickListener {
            confirmReset("Typing Indicators") {
                PreferenceManager.typingIndicators = true
                binding.switchTypingIndicators.isChecked = true
            }; true
        }

        binding.btnResetBannerEnabled.setOnClickListener {
            confirmReset("Show Banner") {
                PreferenceManager.bannerEnabled = true
                binding.switchBannerEnabled.isChecked = true
                updateBannerVisibility()
            }
        }
        binding.rowBannerEnabled.setOnLongClickListener {
            confirmReset("Show Banner") {
                PreferenceManager.bannerEnabled = true
                binding.switchBannerEnabled.isChecked = true
                updateBannerVisibility()
            }; true
        }

        binding.btnResetBannerVisible.setOnClickListener {
            confirmReset("Share Banner with Contacts") {
                PreferenceManager.bannerVisibleToContacts = true
                binding.switchBannerVisibleToContacts.isChecked = true
            }
        }
        binding.rowBannerVisible.setOnLongClickListener {
            confirmReset("Share Banner with Contacts") {
                PreferenceManager.bannerVisibleToContacts = true
                binding.switchBannerVisibleToContacts.isChecked = true
            }; true
        }

        // Notifications
        binding.btnResetNotifMaster.setOnClickListener {
            confirmReset("Notifications") {
                PreferenceManager.notificationsEnabled = true
                binding.switchNotificationsEnabled.isChecked = true
            }
        }
        binding.rowNotifMaster.setOnLongClickListener {
            confirmReset("Notifications") {
                PreferenceManager.notificationsEnabled = true
                binding.switchNotificationsEnabled.isChecked = true
            }; true
        }
        binding.btnResetNotifSounds.setOnClickListener {
            confirmReset("Notification Sounds") {
                PreferenceManager.notificationSounds = true
                binding.switchNotificationSounds.isChecked = true
            }
        }
        binding.rowNotifSounds.setOnLongClickListener {
            confirmReset("Notification Sounds") {
                PreferenceManager.notificationSounds = true
                binding.switchNotificationSounds.isChecked = true
            }; true
        }

        binding.btnResetNotifPreview.setOnClickListener {
            confirmReset("Message Preview") {
                PreferenceManager.notificationPreview = true
                binding.switchNotificationPreview.isChecked = true
            }
        }
        binding.rowNotifPreview.setOnLongClickListener {
            confirmReset("Message Preview") {
                PreferenceManager.notificationPreview = true
                binding.switchNotificationPreview.isChecked = true
            }; true
        }

        // Security
        binding.btnResetRequirePassword.setOnClickListener {
            confirmReset("Require Password on Open") {
                PreferenceManager.requirePasswordOnOpen = false
                binding.switchRequirePassword.isChecked = false
                updateSecuritySubsVisibility(false)
            }
        }
        binding.rowRequirePassword.setOnLongClickListener {
            confirmReset("Require Password on Open") {
                PreferenceManager.requirePasswordOnOpen = false
                binding.switchRequirePassword.isChecked = false
                updateSecuritySubsVisibility(false)
            }; true
        }

        binding.btnResetAutoLogout.setOnClickListener {
            confirmReset("Auto-Logout") {
                PreferenceManager.autoLogoutEnabled = false
                PreferenceManager.autoLogoutMinutes = 5
                binding.switchAutoLogout.isChecked = false
                updateAutoLogoutButtonVisibility(false)
                updateAutoLogoutSubtitle()
            }
        }
        binding.rowAutoLogout.setOnLongClickListener {
            confirmReset("Auto-Logout") {
                PreferenceManager.autoLogoutEnabled = false
                PreferenceManager.autoLogoutMinutes = 5
                binding.switchAutoLogout.isChecked = false
                updateAutoLogoutButtonVisibility(false)
                updateAutoLogoutSubtitle()
            }; true
        }

        // Appearance - Chat Message Font Size
        binding.btnResetFontSize.setOnClickListener {
            confirmReset("Chat Message Font") {
                PreferenceManager.fontSize = 1
                binding.seekbarFontSize.progress = 1
                binding.tvFontSizeLabel.text = "Medium"
            }
        }

        // Appearance - UI Text Size
        binding.btnResetUiFontSize.setOnClickListener {
            confirmReset("UI Text Size") {
                PreferenceManager.uiFontSize = 1
                binding.seekbarUiFontSize.progress = 1
                binding.tvUiFontSizeLabel.text = "Medium"
                requireActivity().recreate()
            }
        }

        // Appearance - Compact Bubbles
        binding.btnResetCompactBubbles.setOnClickListener {
            confirmReset("Compact Bubbles") {
                PreferenceManager.compactBubbles = false
                binding.switchCompactBubbles.isChecked = false
            }
        }
        binding.rowCompactBubbles.setOnLongClickListener {
            confirmReset("Compact Bubbles") {
                PreferenceManager.compactBubbles = false
                binding.switchCompactBubbles.isChecked = false
            }; true
        }

        // Appearance - Jump to bottom on send
        binding.btnResetJumpToBottom.setOnClickListener {
            confirmReset("Jump to bottom on send") {
                PreferenceManager.jumpToBottomOnSend = false
                binding.switchJumpToBottom.isChecked = false
            }
        }
        binding.rowJumpToBottom.setOnLongClickListener {
            confirmReset("Jump to bottom on send") {
                PreferenceManager.jumpToBottomOnSend = false
                binding.switchJumpToBottom.isChecked = false
            }; true
        }

        // Appearance - Chat Heads
        binding.btnResetChatHeads.setOnClickListener {
            confirmReset("Chat Heads") {
                PreferenceManager.chatHeads = true
                binding.switchChatHeads.isChecked = true
            }
        }
        binding.rowChatHeads.setOnLongClickListener {
            confirmReset("Chat Heads") {
                PreferenceManager.chatHeads = true
                binding.switchChatHeads.isChecked = true
            }; true
        }

        // Appearance - Chat Head Opacity
        binding.btnResetChatHeadAlpha.setOnClickListener {
            confirmReset("Chat Head Opacity") {
                PreferenceManager.chatHeadAlpha = 100
                binding.seekbarChatHeadAlpha.progress = 100
                binding.tvChatHeadAlphaLabel.text = "100%"
            }
        }

        // Appearance - Button Haptics
        binding.btnResetButtonHaptics.setOnClickListener {
            confirmReset("Button Vibration") {
                PreferenceManager.buttonHapticsEnabled = true
                binding.switchButtonHaptics.isChecked = true
            }
        }
        binding.rowButtonHaptics.setOnLongClickListener {
            confirmReset("Button Vibration") {
                PreferenceManager.buttonHapticsEnabled = true
                binding.switchButtonHaptics.isChecked = true
            }; true
        }

        // Appearance - My Bubble Color
        binding.btnResetMyBubble.setOnClickListener {
            confirmReset("My Bubble Color") {
                PreferenceManager.myBubbleColor = null
                binding.viewMyBubbleColor.setBackgroundResource(R.drawable.circle_color_swatch)
            }
        }
        binding.rowMyBubbleColor.setOnLongClickListener {
            confirmReset("My Bubble Color") {
                PreferenceManager.myBubbleColor = null
                binding.viewMyBubbleColor.setBackgroundResource(R.drawable.circle_color_swatch)
            }; true
        }


        binding.btnResetTheirBubble.setOnClickListener {
            confirmReset("Their Bubble Color") {
                PreferenceManager.theirBubbleColor = null
                binding.viewTheirBubbleColor.setBackgroundResource(R.drawable.circle_color_swatch)
                binding.viewTheirBubbleColor.visibility = android.view.View.INVISIBLE
            }
        }
        binding.rowTheirBubbleColor.setOnLongClickListener {
            confirmReset("Their Bubble Color") {
                PreferenceManager.theirBubbleColor = null
                binding.viewTheirBubbleColor.setBackgroundResource(R.drawable.circle_color_swatch)
                binding.viewTheirBubbleColor.visibility = android.view.View.INVISIBLE
            }; true
        }

        // Appearance - Accent Color
        binding.btnResetAccent.setOnClickListener {
            confirmReset("Accent Color") {
                val oldAccent = PreferenceManager.accentColorInt()
                PreferenceManager.accentColor = null
                val defaultColor = ContextCompat.getColor(requireContext(), R.color.color_gold)
                binding.viewAccentColor.setBackgroundColor(defaultColor)
                AccentColorHelper.apply(binding.root, defaultColor, oldAccent)
                applyDangerButtonColors(defaultColor)
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyAccentColor(oldAccent)
                context?.toast("Accent color reset to default")
            }
        }
        binding.rowAccentColor.setOnLongClickListener {
            confirmReset("Accent Color") {
                val oldAccent = PreferenceManager.accentColorInt()
                PreferenceManager.accentColor = null
                val defaultColor = ContextCompat.getColor(requireContext(), R.color.color_gold)
                binding.viewAccentColor.setBackgroundColor(defaultColor)
                AccentColorHelper.apply(binding.root, defaultColor, oldAccent)
                applyDangerButtonColors(defaultColor)
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyAccentColor(oldAccent)
            }; true
        }

        // Appearance - Font Family
        binding.btnResetFont.setOnClickListener {
            confirmReset("App Font") {
                PreferenceManager.fontFamily = "sans-serif"
                binding.tvFontFamilyLabel.text = "Default (Sans-Serif)"
                // Apply immediately so the user sees it change right now
                com.silvershireinn.app.utils.FontHelper.apply(binding.root, "sans-serif")
                val ctx = requireContext()
                ctx.sendBroadcast(android.content.Intent("com.silvershireinn.CHAT_FONT_CHANGED").apply {
                    `package` = ctx.packageName
                })
            }
        }
        binding.rowFontFamily.setOnLongClickListener {
            confirmReset("App Font") {
                PreferenceManager.fontFamily = "sans-serif"
                binding.tvFontFamilyLabel.text = "Default (Sans-Serif)"
                com.silvershireinn.app.utils.FontHelper.apply(binding.root, "sans-serif")
                val ctx = requireContext()
                ctx.sendBroadcast(android.content.Intent("com.silvershireinn.CHAT_FONT_CHANGED").apply {
                    `package` = ctx.packageName
                })
            }; true
        }

        // Appearance - 24-Hour Clock (default: true)
        binding.btnResetClock.setOnClickListener {
            confirmReset("24-Hour Clock") {
                PreferenceManager.use24HourClock = true
                binding.switchUse24HourClock.isChecked = true
            }
        }
        binding.rowClock.setOnLongClickListener {
            confirmReset("24-Hour Clock") {
                PreferenceManager.use24HourClock = true
                binding.switchUse24HourClock.isChecked = true
            }; true
        }

        // Appearance - UI Opacity
        binding.btnResetUiOpacity.setOnClickListener {
            confirmReset("UI Transparency") {
                PreferenceManager.uiOpacity = 100
                binding.seekbarUiOpacity.progress = 100
                binding.tvUiOpacityLabel.text = "100%"
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyUiOpacity()
            }
        }

        // Navigation - Nav Bar Position
        binding.btnResetNavBar.setOnClickListener {
            confirmReset("Nav Bar Position") {
                PreferenceManager.navBarAtTop = false
                binding.switchNavBarTop.isChecked = false
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyNavPosition()
            }
        }
        binding.rowNavBarTop.setOnLongClickListener {
            confirmReset("Nav Bar Position") {
                PreferenceManager.navBarAtTop = false
                binding.switchNavBarTop.isChecked = false
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyNavPosition()
            }; true
        }

        // Navigation - Tab Order
        binding.btnResetTabOrder.setOnClickListener {
            confirmReset("Tab Order") {
                val defaultOrder = "chats,contacts,profile"
                PreferenceManager.tabOrder = defaultOrder
                binding.tvTabOrderLabel.text = tabOrderDisplayName(defaultOrder)
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyTabOrder()
            }
        }
        binding.rowTabOrder.setOnLongClickListener {
            confirmReset("Tab Order") {
                val defaultOrder = "chats,contacts,profile"
                PreferenceManager.tabOrder = defaultOrder
                binding.tvTabOrderLabel.text = tabOrderDisplayName(defaultOrder)
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyTabOrder()
            }; true
        }
    }

    // ── Master reset all settings ─────────────────────────────────────────────


    private fun refreshPresenceRow() {
        val mode = PreferenceManager.presenceMode
        binding.tvPresenceValue.text = OnlineStatusManager.modeLabel(mode)
        binding.tvPresenceValue.setTextColor(OnlineStatusManager.colorForMode(mode))
        try {
            val dot = binding.presenceDot
            val bg = dot.background?.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) {
                bg.setColor(OnlineStatusManager.colorForMode(mode))
                dot.background = bg
            } else {
                dot.background.setTint(OnlineStatusManager.colorForMode(mode))
            }
        } catch (_: Exception) {
            binding.presenceDot.setBackgroundColor(OnlineStatusManager.colorForMode(mode))
        }
    }

    private fun showPresencePicker() {
        val modes = OnlineStatusManager.PRESENCE_MODES
        val labels = modes.map { OnlineStatusManager.modeLabel(it) }.toTypedArray()
        val current = PreferenceManager.presenceMode
        val checked = modes.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Presence")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = modes[which]
                applyOwnPresence(mode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyOwnPresence(mode: String) {
        val pm = OnlineStatusManager.normalizePresenceMode(mode) ?: "online"
        PreferenceManager.presenceMode = pm
        refreshPresenceRow()
        WebSocketManager.sendPresence(pm)
        // Sparse settings save (parity with web) — full sync also follows via SYNCABLE_KEYS
        WebSocketManager.sendSettings(JSONObject().put("presenceMode", pm))
        context?.toast(OnlineStatusManager.modeLabel(pm))
    }

    private fun showResetAllDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset all settings")
            .setMessage(
                "This will restore all appearance, privacy, notification, and navigation settings " +
                "to their defaults.\n\n" +
                "Your messages, contacts, profile picture, account and lock settings will not be affected."
            )
            .setPositiveButton("Reset all") { _, _ ->
                applyAllDefaults()
                context?.toast("All settings restored to defaults")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyAllDefaults() {
        // Privacy
        PreferenceManager.discoverable             = true
        PreferenceManager.readReceipts             = true
        PreferenceManager.typingIndicators         = true
        PreferenceManager.presenceMode             = "online"
        PreferenceManager.bannerEnabled            = true
        PreferenceManager.bannerVisibleToContacts  = true

        // Notifications
        PreferenceManager.notificationSounds  = true
        PreferenceManager.notificationPreview = true
        PreferenceManager.notificationsEnabled = true

        // Security is deliberately left alone: "reset settings" silently turning the app lock
        // off was a nasty surprise.

        // Appearance
        PreferenceManager.fontSize             = 1
        PreferenceManager.uiFontSize           = 1
        PreferenceManager.compactBubbles          = false
        PreferenceManager.jumpToBottomOnSend      = false
        PreferenceManager.chatHeads               = true
        PreferenceManager.chatHeadAlpha           = 100
        PreferenceManager.chatHeadDismissOnTap    = false
        PreferenceManager.buttonHapticsEnabled = true
        PreferenceManager.myBubbleColor        = null
        PreferenceManager.theirBubbleColor     = null
        val oldAccent = PreferenceManager.accentColorInt()
        PreferenceManager.accentColor     = null
        PreferenceManager.fontFamily      = "sans-serif"
        PreferenceManager.use24HourClock  = android.text.format.DateFormat.is24HourFormat(requireContext())   // the phone's own setting
        PreferenceManager.uiTextColor     = null
        PreferenceManager.chatAvatarMode  = 0
        PreferenceManager.chatHeadSnapEnabled = true
        PreferenceManager.uiOpacity       = 100
        // Clear tab backgrounds
        for (tab in listOf("chats", "contacts", "profile")) {
            PreferenceManager.setTabVideoBg(tab, null)
            PreferenceManager.setTabImageBg(tab, null)
        }

        // Navigation
        PreferenceManager.navBarAtTop = false
        PreferenceManager.tabOrder    = "chats,contacts,profile"

        // Re-sync UI switches
        binding.switchDiscoverable.isChecked           = true
        binding.switchReadReceipts.isChecked           = true
        binding.switchTypingIndicators.isChecked       = true
        binding.switchBannerEnabled.isChecked          = true
        binding.switchBannerVisibleToContacts.isChecked = true
        binding.switchNotificationSounds.isChecked     = true
        binding.switchNotificationPreview.isChecked    = true
        binding.switchNotificationsEnabled.isChecked   = true
        binding.seekbarFontSize.progress               = 1
        binding.tvFontSizeLabel.text                   = "Medium"
        binding.seekbarUiFontSize.progress             = 1
        binding.tvUiFontSizeLabel.text                 = "Medium"
        binding.switchCompactBubbles.isChecked             = false
        binding.switchJumpToBottom.isChecked               = false
        binding.switchChatHeads.isChecked                  = true
        binding.seekbarChatHeadAlpha.progress              = 100
        binding.tvChatHeadAlphaLabel.text                  = "100%"
        binding.switchChatHeadDismissOnTap.isChecked       = false
        binding.switchButtonHaptics.isChecked          = true
        binding.viewMyBubbleColor.setBackgroundResource(R.drawable.circle_color_swatch)
        val defaultColor = ContextCompat.getColor(requireContext(), R.color.color_gold)
        binding.viewAccentColor.setBackgroundColor(defaultColor)
        binding.tvFontFamilyLabel.text                 = "Default (Sans-Serif)"
        binding.switchUse24HourClock.isChecked         = PreferenceManager.use24HourClock
        binding.viewUiTextColor.visibility             = View.INVISIBLE
        binding.tvChatAvatarMode.text                  = "Every message"
        binding.switchChatHeadSnap.isChecked           = true
        AccentColorHelper.applyTextColor(binding.root, null)
        binding.seekbarUiOpacity.progress              = 100
        binding.tvUiOpacityLabel.text                  = "100%"
        binding.switchNavBarTop.isChecked              = false
        binding.tvTabOrderLabel.text                   = "Chats / Contacts / Profile"

        // Apply visual changes
        updateBannerVisibility()
        AccentColorHelper.apply(binding.root, defaultColor, oldAccent)
        applyDangerButtonColors(defaultColor)
        (activity as? com.silvershireinn.app.ui.main.MainActivity)?.let { main ->
            main.applyAccentColor(oldAccent)
            main.applyNavPosition()
            main.applyTabOrder()
            main.applyUiOpacity()
            main.refreshCurrentTabVideoBg()
        }
        viewModel.setDiscoverable(true)
    }

    // ── Security helpers ──────────────────────────────────────────────────────

    private fun updateSecuritySubsVisibility(masterOn: Boolean) {
        binding.layoutSecuritySubs.visibility = if (masterOn) View.VISIBLE else View.GONE
    }

    private fun updateAutoLogoutButtonVisibility(on: Boolean) {
        binding.btnAutoLogoutTime.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun updateAutoLogoutSubtitle() {
        val mins = PreferenceManager.autoLogoutMinutes
        binding.tvAutoLogoutSub.text = if (mins >= 60) "Lock after ${mins / 60} hour${if (mins >= 120) "s" else ""} of inactivity"
            else "Lock after $mins minute${if (mins == 1) "" else "s"} of inactivity"
    }

    private fun showAutoLogoutTimePicker() {
        val options = arrayOf("1 minute","2 minutes","5 minutes","10 minutes","15 minutes","30 minutes","1 hour")
        val values  = intArrayOf(1, 2, 5, 10, 15, 30, 60)
        val current = PreferenceManager.autoLogoutMinutes
        val checked = values.indexOfFirst { it == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Lock after")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                PreferenceManager.autoLogoutMinutes = values[which]
                updateAutoLogoutSubtitle()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogoutConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Log out?")
            .setMessage("Your chats are removed from this phone and you'll sign in again next time. Recent messages come back from the server when you do.")
            .setPositiveButton("Log out") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        // Stop the connection first so nothing is re-saved while we clear, then the shared
        // wipe (DB incl. groups, contact pictures, cached media), notifications and chat heads.
        // Runs on the app scope: the old version used this screen's context after it closed.
        val app = requireContext().applicationContext
        com.silvershireinn.app.network.WebSocketManager.disconnect()
        com.silvershireinn.app.SilvershireApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            listOf(PreferenceManager.profilePicturePath, PreferenceManager.bannerPicPath).forEach { p ->
                if (p.isNotBlank()) try { java.io.File(p).delete() } catch (_: Exception) {}
            }
            com.silvershireinn.app.utils.AccountData.wipeLocal(app)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                PreferenceManager.profilePicturePath = ""
                PreferenceManager.bannerPicPath = ""
                // clearAccountData preserves server config, fonts, colors, backgrounds, etc.
                // isSetupComplete stays false until LoginActivity signs back in.
                PreferenceManager.clearAccountData()
                try { androidx.core.app.NotificationManagerCompat.from(app).cancelAll() } catch (_: Exception) {}
                try { app.stopService(Intent(app, com.silvershireinn.app.ui.chathead.ChatHeadService::class.java)) } catch (_: Exception) {}
                app.startActivity(Intent(app, com.silvershireinn.app.ui.identity.CreateIdentityActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    private fun showBubbleColorPickerDialog() {
        val initial = try {
            Color.parseColor(PreferenceManager.myBubbleColor ?: "#2D3250")
        } catch (_: Exception) { 0xFF2D3250.toInt() }

        com.silvershireinn.app.ui.common.HsvColorPickerDialog(requireContext(), initial) { color ->
            val hex = String.format("#%06X", color and 0xFFFFFF)
            PreferenceManager.myBubbleColor = hex
            binding.viewMyBubbleColor.setBackgroundColor(color)
            context?.toast("Bubble color updated")
        }.show()
    }

    private fun showTheirBubbleColorPickerDialog() {
        val initial = try {
            Color.parseColor(PreferenceManager.theirBubbleColor ?: "#2A2A32")
        } catch (_: Exception) { 0xFF2A2A32.toInt() }

        com.silvershireinn.app.ui.common.HsvColorPickerDialog(requireContext(), initial) { color ->
            val hex = String.format("#%06X", color and 0xFFFFFF)
            PreferenceManager.theirBubbleColor = hex
            binding.viewTheirBubbleColor.setBackgroundColor(color)
            binding.viewTheirBubbleColor.visibility = android.view.View.VISIBLE
            context?.toast("Their bubble color updated")
        }.show()
    }

    private fun showAccentColorPickerDialog() {
        val initial = PreferenceManager.accentColorInt()

        com.silvershireinn.app.ui.common.HsvColorPickerDialog(requireContext(), initial) { color ->
            // Capture the OLD accent BEFORE saving so AccentColorHelper can recognize
            // elements that were already tinted with the previous accent and update them.
            val oldAccent = PreferenceManager.accentColorInt()

            val hex = String.format("#%06X", color and 0xFFFFFF)
            val isGold = hex.equals("D4AF37", ignoreCase = true) || hex.equals("C9A84C", ignoreCase = true) || hex.equals("A8A9AD", ignoreCase = true)
            PreferenceManager.accentColor = if (isGold) null else hex

            binding.viewAccentColor.setBackgroundColor(color)

            // Re-tint this fragment immediately (covers the currently-visible profile tab).
            AccentColorHelper.apply(binding.root, color, oldAccent)
            applyDangerButtonColors(color)

            // Re-tint BottomNav and any other open fragment views via MainActivity.
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyAccentColor(oldAccent)

            context?.toast("Accent color updated")
        }.show()
    }

    private fun showFontFamilyPickerDialog() {
        // Full candidate list - filtered at runtime by FontHelper.isFamilyAvailable() so
        // only fonts that actually produce a distinct typeface on this device are shown.
        val allEntries = listOf(
            "sans-serif"           to "Default (Sans-Serif)",
            "serif"                to "Serif",
            "monospace"            to "Monospace",
            "cursive"              to "Cursive",
            "sans-serif-condensed" to "Condensed",
            "sans-serif-light"     to "Light",
            "sans-serif-thin"      to "Thin",
            "sans-serif-medium"    to "Medium",
            "sans-serif-black"     to "Black",
            "sans-serif-smallcaps" to "Small Caps",
            "noto-serif"           to "Noto Serif",
            "noto-serif-display"   to "Noto Serif Display",
            "noto-sans"            to "Noto Sans",
            "noto-sans-display"    to "Noto Sans Display",
            "serif-monospace"      to "Serif Monospace",
            "casual"               to "Casual",
            "fantasy"              to "Fantasy",
            "Dancing Script"       to "Dancing Script",
            "Pacifico"             to "Pacifico",
            "Lobster"              to "Lobster",
            "Oswald"               to "Oswald",
            "Raleway"              to "Raleway",
            "Lato"                 to "Lato",
            "Montserrat"           to "Montserrat",
            "Open Sans"            to "Open Sans",
            "Roboto Slab"          to "Roboto Slab",
            "Playfair Display"     to "Playfair Display",
            "Merriweather"         to "Merriweather",
            "Ubuntu"               to "Ubuntu",
            "Nunito"               to "Nunito",
            "Poppins"              to "Poppins",
            "Source Code Pro"      to "Source Code Pro",
            "Fira Code"            to "Fira Code",
            "Josefin Sans"         to "Josefin Sans",
            "Quicksand"            to "Quicksand",
            "Comfortaa"            to "Comfortaa",
            "Righteous"            to "Righteous",
            "Orbitron"             to "Orbitron",
            "Press Start 2P"       to "Press Start 2P",
            "Bebas Neue"           to "Bebas Neue",
            "Caveat"               to "Caveat",
            "Sacramento"           to "Sacramento",
            "Special Elite"        to "Special Elite",
            "Courier Prime"        to "Courier Prime",
            "IM Fell English"      to "IM Fell English",
            "Cinzel"               to "Cinzel",
            "Abril Fatface"        to "Abril Fatface",
            "Fredoka One"          to "Fredoka One",
            "Permanent Marker"     to "Permanent Marker",
            "Rock Salt"            to "Rock Salt",
            "Architects Daughter"  to "Architects Daughter",
            "Annie Use Your Telescope" to "Annie Use Your Telescope",
            "Gloria Hallelujah"    to "Gloria Hallelujah"
        )
        // Only show fonts that are actually installed / produce a distinct typeface on this device
        val entries = allEntries.filter { (family, _) ->
            com.silvershireinn.app.utils.FontHelper.isFamilyAvailable(family)
        }
        val families = entries.map { it.first }.toTypedArray()
        val names    = entries.map { it.second }.toTypedArray()

        val current = PreferenceManager.fontFamily
        val checked = families.indexOfFirst { it == current }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("App Font")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                PreferenceManager.fontFamily = families[which]
                binding.tvFontFamilyLabel.text = names[which]
                // Apply the new font to the profile screen immediately
                com.silvershireinn.app.utils.FontHelper.apply(binding.root, families[which])
                dialog.dismiss()
                context?.toast("Font updated - reopen other screens to see it applied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fontFamilyDisplayName(family: String): String {
        val displayNames = mapOf(
            "sans-serif"           to "Default (Sans-Serif)",
            "serif"                to "Serif",
            "monospace"            to "Monospace",
            "cursive"              to "Cursive",
            "sans-serif-condensed" to "Condensed",
            "sans-serif-light"     to "Light",
            "sans-serif-thin"      to "Thin",
            "sans-serif-medium"    to "Medium",
            "sans-serif-black"     to "Black",
            "sans-serif-smallcaps" to "Small Caps",
            "noto-serif"           to "Noto Serif",
            "noto-serif-display"   to "Noto Serif Display",
            "noto-sans"            to "Noto Sans",
            "noto-sans-display"    to "Noto Sans Display",
            "serif-monospace"      to "Serif Monospace",
            "casual"               to "Casual",
            "fantasy"              to "Fantasy"
        )
        return displayNames[family] ?: family
    }

    private fun showTabOrderDialog() {
        // All 6 permutations of 3 tabs
        val keys = listOf(
            "chats,contacts,profile",
            "chats,profile,contacts",
            "contacts,chats,profile",
            "contacts,profile,chats",
            "profile,chats,contacts",
            "profile,contacts,chats",
        )
        val labels = keys.map { tabOrderDisplayName(it) }.toTypedArray()
        val current = PreferenceManager.tabOrder
        val checked = keys.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tab Order")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val chosen = keys[which]
                PreferenceManager.tabOrder = chosen
                binding.tvTabOrderLabel.text = labels[which]
                (activity as? com.silvershireinn.app.ui.main.MainActivity)?.applyTabOrder()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tabOrderDisplayName(order: String): String =
        order.split(",").joinToString(" / ") { tab ->
            when (tab.trim()) {
                "chats"    -> "Chats"
                "contacts" -> "Contacts"
                "profile"  -> "Profile"
                else       -> tab.replaceFirstChar { it.uppercaseChar() }
            }
        }

    private fun updatePinButtonVisibility(pinOn: Boolean) {
        binding.btnSetupPIN.visibility = if (pinOn) View.VISIBLE else View.GONE
        binding.tvPinStatus.text = if (pinOn && PreferenceManager.pinHash.isNotBlank())
            "PIN is set" else "PIN not set"
    }


    /** Two-pass PIN setup dialog. If [enableAfter] the switch is turned on once confirmed. */
    private fun showSetupPinDialog(enableAfter: Boolean) {
        val firstInput = TextInputEditText(requireContext()).apply {
            hint     = "Enter 4-6 digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters  = arrayOf(InputFilter.LengthFilter(6))
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set PIN")
            .setMessage("Choose a 4-6 digit PIN.")
            .setView(firstInput)
            .setPositiveButton("Next") { _, _ ->
                val first = firstInput.text.toString()
                if (first.length < 4 || !first.all { it.isDigit() }) {
                    context?.toast("PIN must be at least 4 digits")
                    return@setPositiveButton
                }
                showConfirmPinDialog(first, enableAfter)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfirmPinDialog(firstPin: String, enableAfter: Boolean) {
        val confirmInput = TextInputEditText(requireContext()).apply {
            hint      = "Re-enter PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters   = arrayOf(InputFilter.LengthFilter(6))
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm PIN")
            .setMessage("Enter the same PIN again to confirm.")
            .setView(confirmInput)
            .setPositiveButton("Save") { _, _ ->
                val second = confirmInput.text.toString()
                if (second != firstPin) {
                    context?.toast("PINs do not match - try again")
                    showSetupPinDialog(enableAfter)
                } else {
                    // Salted slow hash (was plain SHA-256), computed in the background.
                    val app = requireContext().applicationContext
                    viewLifecycleOwner.lifecycleScope.launch {
                        val h = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            com.silvershireinn.app.utils.PinHash.hash(second)
                        }
                        PreferenceManager.pinHash    = h
                        PreferenceManager.pinEnabled = true
                        _binding?.switchUsePIN?.isChecked = true
                        if (_binding != null) updatePinButtonVisibility(true)
                        android.widget.Toast.makeText(app, "PIN saved", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Blocked users ─────────────────────────────────────────────────────────

    private fun refreshBlockedList() {
        val container = binding.containerBlockedUsers
        container.removeAllViews()
        val blocked = PreferenceManager.getBlockedUsers()

        if (blocked.isEmpty()) {
            binding.tvNoBlockedUsers.visibility = View.VISIBLE
            return
        }
        binding.tvNoBlockedUsers.visibility = View.GONE

        for (username in blocked.sorted()) {
            // We can't use a complex layout here without a separate XML; build it programmatically
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.dp48)
                )
                setPadding(48, 0, 16, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(requireContext()).apply {
                text      = username
                textSize  = 15f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.color_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnUnblock = MaterialButton(requireContext(),null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Unblock"
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.color_gold))
                setOnClickListener {
                    PreferenceManager.unblockUser(username)
                    refreshBlockedList()
                    context?.toast("Unblocked. Send them a friend request to chat again")
                }
            }

            rowLayout.addView(tvName)
            rowLayout.addView(btnUnblock)
            container.addView(rowLayout)

            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(48, 0, 0, 0) }
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.color_divider))
            }
            container.addView(divider)
        }
    }

    // ── Collapsible section helper ─────────────────────────────────────────────

    private fun setupCollapsible(key: String, header: View, card: View, chevron: ImageView) {
        val collapsed = PreferenceManager.isSectionCollapsed(key)
        applyCollapsed(card, chevron, collapsed)

        header.setOnClickListener {
            HapticUtil.vibrate(it.context)
            val nowCollapsed = card.visibility == View.VISIBLE
            PreferenceManager.setSectionCollapsed(key, nowCollapsed)
            animateCollapse(card, chevron, nowCollapsed)
        }
    }

    private fun applyCollapsed(card: View, chevron: ImageView, collapsed: Boolean) {
        card.visibility = if (collapsed) View.GONE else View.VISIBLE
        chevron.rotation = if (collapsed) -90f else 0f
    }

    private fun animateCollapse(card: View, chevron: ImageView, collapse: Boolean) {
        card.animate().cancel()
        chevron.animate().rotation(if (collapse) -90f else 0f).setDuration(200).start()
        if (collapse) {
            card.visibility = View.GONE
        } else {
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(200).start()
        }
    }

    // ── Tab video background ──────────────────────────────────────────────────

    /**
     * Copy a picked file into the app in the background (these read whole videos into memory
     * on the main thread before: freezes and out-of-memory crashes on cheap phones). Writes a
     * temp file first so a failed copy never replaces a working one. null = failed.
     */
    private suspend fun copyIn(uri: android.net.Uri, dest: java.io.File, maxBytes: Long = Long.MAX_VALUE): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ctx = context ?: return@withContext null
                val tmp = java.io.File(dest.parentFile, dest.name + ".part")
                val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext null
                var total = 0L
                input.use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        total += n
                        if (total > maxBytes) { tmp.delete(); return@withContext null }
                        o.write(buf, 0, n)
                    }
                } }
                if (total == 0L) { tmp.delete(); return@withContext null }
                dest.delete()
                if (tmp.renameTo(dest)) dest else null
            } catch (_: Exception) { null }
        }

    private fun saveTabImageBg(tabKey: String, uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val dest = copyIn(uri, java.io.File(ctx.filesDir, "tab_bg_${tabKey}.jpg"))
            if (dest == null) { ctx.toast("Failed to save background image"); return@launch }
            // Clear video background for this tab - delete the file so server-settings restore
            // won't find the old file and re-enable the video when settings are synced.
            PreferenceManager.setTabVideoBg(tabKey, null)
            try { java.io.File(ctx.filesDir, "tab_bg_$tabKey.mp4").delete() } catch (_: Exception) {}
            PreferenceManager.setTabImageBg(tabKey, dest.absolutePath)
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.refreshCurrentTabVideoBg()
            ctx.toast("${tabKey.replaceFirstChar { it.uppercaseChar() }} image background set")
        }
    }

    private fun saveTabVideoBg(tabKey: String, uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        ctx.toast("Setting background...")
        viewLifecycleOwner.lifecycleScope.launch {
            val dest = copyIn(uri, java.io.File(ctx.filesDir, "tab_bg_$tabKey.mp4"))
            if (dest == null) { ctx.toast("Failed to save background video"); return@launch }
            // Clear image background for this tab - delete the file so there is no conflict.
            PreferenceManager.setTabImageBg(tabKey, null)
            try { java.io.File(ctx.filesDir, "tab_bg_${tabKey}.jpg").delete() } catch (_: Exception) {}
            PreferenceManager.setTabVideoBg(tabKey, dest.absolutePath)
            (activity as? com.silvershireinn.app.ui.main.MainActivity)?.refreshCurrentTabVideoBg()
            ctx.toast("${tabKey.replaceFirstChar { it.uppercaseChar() }} background set")
        }
    }

    // ── Banner picture ────────────────────────────────────────────────────────

    /**
     * The banner fills the whole top: from the top of the screen down behind the picture, name
     * and the "Search settings" bar. Sized from what sits on it, so it fits every phone and font.
     */
    private val bannerSizer = android.view.ViewTreeObserver.OnGlobalLayoutListener { sizeBanner() }
    private fun sizeBanner() {
        val b = _binding ?: return
        val idLp = b.layoutIdentity.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val dens = resources.displayMetrics.density
        if (b.layoutBanner.visibility != View.VISIBLE) {
            val m = (8 * dens).toInt()
            if (idLp.topMargin != m) { idLp.topMargin = m; b.layoutIdentity.layoutParams = idLp }
            return
        }
        val top = (190 * dens).toInt()                       // banner showing above the picture
        val searchLp = b.layoutSettingsSearch.layoutParams as? ViewGroup.MarginLayoutParams
        val h = top + b.layoutIdentity.height + b.layoutSettingsSearch.height + (searchLp?.bottomMargin ?: 0)
        if (b.layoutIdentity.height == 0 || h <= top) return
        val blp = b.layoutBanner.layoutParams
        if (blp.height != h) { blp.height = h; b.layoutBanner.layoutParams = blp }
        val want = -(h - top)
        if (idLp.topMargin != want) { idLp.topMargin = want; b.layoutIdentity.layoutParams = idLp }
    }

    /** Show or hide the banner area based on the "Show Banner" toggle. */
    private fun updateBannerVisibility() {
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(bannerSizer)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(bannerSizer)
        val enabled = PreferenceManager.bannerEnabled
        if (!enabled) {
            binding.layoutBanner.visibility = View.GONE
            bannerVideoHelper?.clear(binding.ivBanner)
            return
        }
        binding.layoutBanner.visibility = View.VISIBLE
        val file = ProfileMediaHelper.findOwnBanner(requireContext())
        if (file != null && file.exists()) {
            binding.tvBannerPlaceholder.visibility = View.GONE
            if (ProfileMediaHelper.isVideoPath(file.absolutePath)) {
                val frame = ProfileMediaHelper.videoFrameBitmap(file.absolutePath)
                if (frame != null) {
                    binding.ivBanner.setImageBitmap(frame)
                    binding.ivBanner.visibility = View.VISIBLE
                } else {
                    binding.ivBanner.visibility = View.GONE
                }
                bannerVideoHelper?.play(file.absolutePath, binding.ivBanner)
            } else {
                bannerVideoHelper?.clear(binding.ivBanner)
                binding.ivBanner.visibility = View.VISIBLE
                binding.ivBanner.load(file) { crossfade(true) }
            }
        } else {
            bannerVideoHelper?.clear(binding.ivBanner)
            binding.ivBanner.visibility = View.GONE
            binding.tvBannerPlaceholder.visibility = View.VISIBLE
        }
    }

    private fun loadBanner() {
        updateBannerVisibility()
    }

    private fun saveBannerPicture(uri: Uri, mime: String = "image/jpeg") {
        val ctx = requireContext().applicationContext
        val cleanMime = mime.substringBefore(';').trim().ifBlank { "image/jpeg" }
        val isVideo = ProfileMediaHelper.isVideoMime(cleanMime)
        val max = if (isVideo) 25L * 1024 * 1024 else 8L * 1024 * 1024
        val ext = ProfileMediaHelper.extForMime(cleanMime)
        viewLifecycleOwner.lifecycleScope.launch {
            // Copied in the background (a 25 MB video was read on the main thread before).
            val staged = copyIn(uri, File(ctx.cacheDir, "banner_new.$ext"), max)
            if (staged == null) {
                ctx.toast(if (isVideo) "Banner video must be under 25 MB" else "Couldn't use that banner (max 8 MB)")
                return@launch
            }
            ProfileMediaHelper.clearSiblings(ctx.filesDir, "banner_pic", ext)
            val destFile = File(ctx.filesDir, "banner_pic.$ext")
            staged.copyTo(destFile, overwrite = true); staged.delete()
            PreferenceManager.bannerPicPath = destFile.absolutePath
            if (_binding == null) return@launch
            if (!PreferenceManager.bannerEnabled) {
                PreferenceManager.bannerEnabled = true
                binding.switchBannerEnabled.isChecked = true
            }
            loadBanner()
            ctx.toast(if (isVideo) "Banner video updated" else "Banner updated")
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { destFile.readBytes() }
            val ok = FileRepository.uploadBannerPic(bytes, cleanMime)
            if (!ok) ctx.toast("Could not sync banner to server")
        }
    }

    // ── Profile picture ───────────────────────────────────────────────────────

    private fun applyOwnAvatarShape() {
        val shape = AvatarShapeHelper.current()
        AvatarShapeHelper.applyClip(binding.avatarCircle, shape)
        AvatarShapeHelper.applyClip(binding.ivProfilePic, shape)
        try { AvatarShapeHelper.applyClip(binding.vvProfilePic, shape) } catch (_: Exception) {}
        applyOwnFrame()
    }

    /** Own border: full frame around the profile picture, and the settings row label. */
    private fun applyOwnFrame() {
        val b = _binding ?: return
        b.avatarCircle.post {
            val bb = _binding ?: return@post
            val framed = com.silvershireinn.app.utils.ProfileFrames.applyFull(bb.avatarCircle, PreferenceManager.username)
            (bb.tvUsername.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = ((if (framed) 36 else 10) * resources.displayMetrics.density).toInt(); bb.tvUsername.layoutParams = lp }
            bb.btnEditPhoto.translationZ = 40f
        }
        b.tvProfileFrame.text = com.silvershireinn.app.utils.ProfileFrames.myFrame.let { id ->
            if (id == "none" || id.startsWith("gen:none")) "None" else
                (com.silvershireinn.app.utils.ProfileFrames.KINDS[id.split(':').getOrNull(1)] ?: "Custom") + " border" }
    }

    private val framesReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { applyOwnFrame() }
    }

    private fun loadProfilePicture() {
        applyOwnAvatarShape()
        val picFile = ProfileMediaHelper.findOwnProfilePic(requireContext())
        if (picFile != null) {
            if (PreferenceManager.profilePicturePath != picFile.absolutePath) {
                PreferenceManager.profilePicturePath = picFile.absolutePath
            }
            binding.tvInitials.visibility = View.GONE
            if (ProfileMediaHelper.isVideoPath(picFile.absolutePath)) {
                val frame = ProfileMediaHelper.videoFrameBitmap(picFile.absolutePath)
                if (frame != null) {
                    binding.ivProfilePic.setImageBitmap(frame)
                    binding.ivProfilePic.visibility = View.VISIBLE
                } else {
                    binding.ivProfilePic.visibility = View.GONE
                }
                profileVideoHelper?.play(picFile.absolutePath, binding.ivProfilePic)
            } else {
                profileVideoHelper?.clear(binding.ivProfilePic)
                binding.ivProfilePic.visibility = View.VISIBLE
                binding.ivProfilePic.load(picFile) { crossfade(true) }
            }
        } else {
            profileVideoHelper?.clear(binding.ivProfilePic)
            binding.ivProfilePic.visibility = View.GONE
            binding.tvInitials.visibility = View.VISIBLE
        }
    }

    private fun saveProfilePicture(uri: Uri, mime: String = "image/jpeg") {
        val ctx = requireContext().applicationContext
        val cleanMime = mime.substringBefore(';').trim().ifBlank { "image/jpeg" }
        val isVideo = ProfileMediaHelper.isVideoMime(cleanMime)
        val max = if (isVideo) 12L * 1024 * 1024 else 5L * 1024 * 1024
        val ext = ProfileMediaHelper.extForMime(cleanMime)
        viewLifecycleOwner.lifecycleScope.launch {
            val staged = copyIn(uri, File(ctx.cacheDir, "avatar_new.$ext"), max)
            if (staged == null) {
                ctx.toast(if (isVideo) "Profile video must be under 12 MB" else "Couldn't use that picture (max 5 MB)")
                return@launch
            }
            ProfileMediaHelper.clearSiblings(ctx.filesDir, "profile_pic", ext)
            val destFile = File(ctx.filesDir, "profile_pic.$ext")
            staged.copyTo(destFile, overwrite = true); staged.delete()
            PreferenceManager.profilePicturePath = destFile.absolutePath
            if (_binding != null) loadProfilePicture()
            com.silvershireinn.app.ui.chathead.ChatHeadService.refreshAvatar(ctx, destFile.absolutePath)
            ctx.toast(if (isVideo) "Profile video updated" else "Profile picture updated")
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { destFile.readBytes() }
            val ok = FileRepository.uploadProfilePic(bytes, cleanMime)
            if (!ok) ctx.toast("Could not sync your picture to the server. It will be sent next time")
        }
    }

    private fun showChangeUsernameDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "New username"
            setText(PreferenceManager.username)
            setPadding(48, 32, 48, 32)
        }

        // Build without auto-dismiss so we can manually control when it closes
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Change Username")
            .setMessage("Enter your desired username. Availability will be checked before the change.")
            .setView(input)
            .setPositiveButton("Check Availability", null) // listener set below
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Override positive button so the dialog doesn't auto-dismiss on click
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = input.text.toString().trim()
            val oldName = PreferenceManager.username
            if (newName.isBlank() || newName == oldName) {
                context?.toast("Enter a different username")
                return@setOnClickListener
            }
            // Same rules as a safe file / link name: letters, numbers, space . _ - (2-24).
            if (!Regex("^[\\p{L}\\p{N} ._-]{2,24}$").matches(newName)) {
                input.error = "2-24 letters, numbers, spaces, dots, dashes or underscores"
                return@setOnClickListener
            }

            val checkMsg = JSONObject().apply {
                put("type", "check_username")
                put("name", newName)
            }
            val sent = WebSocketManager.sendRaw(checkMsg.toString())
            if (!sent) {
                context?.toast("Not connected - cannot check username")
                return@setOnClickListener
            }

            // Disable button + show spinner while awaiting server response
            val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            btn.text = "Checking..."

            lifecycleScope.launch {
                val result = withTimeoutOrNull(6_000L) {
                    // Filter by name in case an old result is in-flight for a different query
                    com.silvershireinn.app.network.WebSocketService.usernameCheckResults
                        .first { it.first == newName }
                }

                // Restore button regardless of outcome
                btn.isEnabled = true
                btn.text = "Check Availability"

                when {
                    result == null -> {
                        context?.toast("Server didn't respond - try again")
                    }
                    !result.second -> {
                        context?.toast("\"$newName\" is already taken - try a different name")
                    }
                    else -> {
                        // Username is available - show confirmation before committing
                        dialog.dismiss()
                        showUsernameConfirmDialog(oldName, newName)
                    }
                }
            }
        }
    }

    /**
     * Shown after availability is confirmed. Warns the user that [newName] will become
     * their login credential and asks for explicit confirmation before sending the change.
     */
    private fun showUsernameConfirmDialog(oldName: String, newName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Username Change")
            .setMessage(
                "Your username will change from \"$oldName\" to \"$newName\".\n\n" +
                "WARNING: Important: \"$newName\" will be your new login username. " +
                "You'll need to use it the next time you log in - make sure you remember it."
            )
            .setPositiveButton("Confirm Change") { _, _ ->
                val msg = JSONObject().apply {
                    put("type",    "username_change")
                    put("oldName", oldName)
                    put("newName", newName)
                }
                val app = requireContext().applicationContext
                // Listen first, then send: the change only applies once the server confirms
                // (it used to switch locally right away, leaving a broken login if the server
                // refused, e.g. the name was taken a moment earlier).
                viewLifecycleOwner.lifecycleScope.launch {
                    val reply = async {
                        withTimeoutOrNull(8_000L) {
                            WebSocketManager.events.first { j ->
                                j.optString("type") == "username_change_ok" || j.optString("type") == "username_change_error"
                            }
                        }
                    }
                    kotlinx.coroutines.yield()
                    if (!WebSocketManager.sendRaw(msg.toString())) {
                        reply.cancel()
                        app.toast("Not connected - cannot change username")
                        return@launch
                    }
                    val r = reply.await()
                    when (r?.optString("type")) {
                        "username_change_ok" -> {
                            // WebSocketService already stored the new name.
                            _binding?.let { b ->
                                b.tvUsername.text = newName
                                b.tvInitials.text = newName.toInitials()
                                b.avatarCircle.setCardBackgroundColor(newName.toAvatarColor())
                            }
                            app.toast("Username changed to \"$newName\"")
                        }
                        "username_change_error" -> app.toast(r.optString("message").ifBlank { "Couldn't change your username" })
                        else -> app.toast("No answer from the server. Your username wasn't changed")
                    }
                }
            }
            .setNegativeButton("Go Back") { _, _ ->
                // Let the user pick a different name if they changed their mind
                showChangeUsernameDialog()
            }
            .show()
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────


    private fun shareAppDownloadLink() {
        val link = buildApkDownloadUrl()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Silvershire Inn")
            putExtra(
                Intent.EXTRA_TEXT,
                "Install Silvershire Inn (Android):\n$link\n\nOpen the link on your phone to download the APK."
            )
        }
        startActivity(Intent.createChooser(send, "Share app"))
    }

    /**
     * The invite link: the server's own public download page (it learns its public address, or
     * the owner sets it), so the link always matches the server this app uses. Without one, the
     * app's own server address is used as is (scheme and port kept; on home Wi-Fi it only works
     * for people on the same network).
     */
    private fun buildApkDownloadUrl(): String {
        PreferenceManager.shareUrl.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        val raw = PreferenceManager.serverUrl.trim().trimEnd('/')
        val scheme = when {
            raw.startsWith("wss://") || raw.startsWith("https://") -> "https"
            else -> "http"
        }
        val hostPort = raw.substringAfter("://", raw).substringBefore("/")
        return "$scheme://$hostPort/download"
    }

    private fun showEditServerDialog() {
        val input = TextInputEditText(requireContext()).apply {
            setText(PreferenceManager.serverUrl)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Server Address")
            .setMessage("Enter your Silvershire Inn server address.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text.toString().trim().trimEnd('/')
                if (url.isNotBlank()) {
                    if (!url.contains("://")) url = "https://$url"
                    PreferenceManager.serverUrl = url
                    // Reconnect now (it used to say "restart the app").
                    val ctx = requireContext().applicationContext
                    com.silvershireinn.app.network.WebSocketManager.disconnect()
                    try { ctx.startService(Intent(ctx, com.silvershireinn.app.network.WebSocketService::class.java)) } catch (_: Exception) {}
                    ctx.toast("Saved. Reconnecting...")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Updates Panic Wipe and Clear Message History button text colors to ensure
     * they're always readable regardless of the user's chosen accent color.
     *
     * If the accent is too close to the standard danger red (#CF6679), those buttons
     * would be invisible (red on red). In that case we switch to a bright amber instead.
     */
    private fun applyDangerButtonColors(accentColor: Int) {
        // Always use hard red - user wants these visually distinct regardless of accent
        val dangerColor = android.graphics.Color.parseColor("#CF6679")
        binding.btnPanicWipe.setTextColor(dangerColor)
        binding.btnClearHistory.setTextColor(dangerColor)
    }

    private fun showClearHistoryDialog() {
        // Step 1 - confirm intent
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear Message History")
            .setMessage("This will permanently delete ALL messages from this device. Your contacts will not be affected.\n\nAre you sure?")
            .setPositiveButton("Continue") { _, _ ->
                // Step 2 - type CLEAR to confirm
                val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                    hint = "Type CLEAR to confirm"
                    setPadding(48, 32, 48, 32)
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Confirm Clear")
                    .setMessage("Type CLEAR in the box below to permanently delete your message history.")
                    .setView(input)
                    .setPositiveButton("Delete Messages") { _, _ ->
                        if (input.text.toString() == "CLEAR") {
                            viewModel.clearHistory()
                            context?.toast("Message history cleared")
                        } else {
                            context?.toast("You must type CLEAR exactly to confirm")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Export this account's identity key as JSON for the web client.
     * Copies to clipboard - the user pastes it into the browser's login screen.
     * ADDITIVE feature: does not modify any keys or account state.
     */
    private fun showExportIdentityDialog() {
        if (com.silvershireinn.app.utils.PanicLock.locked) return
        val json = com.silvershireinn.app.crypto.CryptoManager.exportIdentityJson(PreferenceManager.username)
        if (json == null) {
            context?.toast("No identity key found")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export Identity Key")
            .setMessage("This key lets you log into the WEB CLIENT (http://<server>:8080/web) as this account.\n\nWARNING: It contains your PRIVATE key. Only paste it into your own browser. Never send it in a chat or to another person.")
            .setPositiveButton("Copy to clipboard") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Silvershire Identity", json)
                // Private key: hidden from clipboard previews / keyboard clipboard history where
                // supported, and cleared from the clipboard after 2 minutes.
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                cm.setPrimaryClip(clip)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val cur = cm.primaryClip
                        if (cur != null && cur.itemCount > 0 && cur.getItemAt(0).text?.toString() == json) {
                            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                            else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                        }
                    } catch (_: Exception) {}
                }, 120_000)
                context?.toast("Identity key copied for 2 minutes - paste it in the web client")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPanicWipeDialog() {
        // Step 1 - warn and confirm intent
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("WARNING: Panic Wipe")
            .setMessage("This will permanently delete your account from the server AND erase ALL local data - messages, contacts, settings, and your identity.\n\nYour account CANNOT be recovered. This CANNOT be undone.\n\nAre you absolutely sure?")
            .setPositiveButton("Continue") { _, _ ->
                // Step 2 - type WIPE to execute
                val input = TextInputEditText(requireContext()).apply {
                    hint = "Type WIPE to confirm"
                    setPadding(48, 32, 48, 32)
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Final Confirmation")
                    .setMessage("Type WIPE in the box below to permanently delete your account and all data.")
                    .setView(input)
                    .setPositiveButton("WIPE EVERYTHING") { _, _ ->
                        if (input.text.toString() == "WIPE") {
                            com.silvershireinn.app.ui.profile.ProfileViewModel.panicWipe(requireContext().applicationContext)
                        } else {
                            context?.toast("You must type WIPE exactly to confirm")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * On physical phones with OEM ROMs (Samsung, Xiaomi, OnePlus, etc.) the Android Bubbles
     * API requires the user to explicitly enable "Bubbles" per-app in notification settings  - 
     * even when the overlay permission is already granted.  This dialog explains that and
     * provides a shortcut to the right settings screen.
     *
     * Only shown on Android 11+ (API 30) because that's when Bubbles became stable.
     * Shown every time the user enables chat heads so it's always easy to find if they
     * forgot to enable it or did it on a fresh install.
     */
    private fun showBubblesGuidanceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return   // Bubbles not stable below API 30
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Enable Chat Head Bubbles")
            .setMessage(
                "Chat heads use Android's Bubble system. On most phones you need to enable " +
                "\"Bubbles\" for Silvershire Inn in your notification settings:\n\n" +
                "1. Tap Open Settings below\n" +
                "2. Tap Bubbles (or \"Floating notifications\")\n" +
                "3. Choose All conversations or All bubbles\n\n" +
                "If you don't see a Bubbles option, your device may not support them."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    }
                )
            }
            .setNegativeButton("Got it", null)
            .show()
    }

    /** Update the "Background reliability" row subtitle to reflect the current exemption state. */
    private fun refreshBatteryOptStatus() {
        if (_binding == null) return
        val ok = com.silvershireinn.app.utils.BatteryOptimizationHelper.isIgnoring(requireContext())
        binding.tvBatteryOptStatus.text =
            if (ok) "Allowed - notifications stay reliable"
            else    "Tap to allow (recommended for reliable notifications)"
    }

    override fun onStart() {
        super.onStart()
        val accentOnStart = PreferenceManager.accentColorInt()
        AccentColorHelper.apply(binding.root, accentOnStart)
        AccentColorHelper.applyTextColor(binding.root, PreferenceManager.uiTextColorInt())
        applyDangerButtonColors(accentOnStart)
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)
        // Reflect the live battery-optimization state (user may have just changed it in Settings).
        refreshBatteryOptStatus()
        // Refresh banner and profile pic in case they were updated (async download on login)
        loadBanner()
        loadProfilePicture()
        // Listen for banner_updated broadcasts (own banner synced from server)
        val bannerFilter = IntentFilter("com.silvershireinn.BANNER_UPDATED")
        val picFilter    = IntentFilter("com.silvershireinn.PROFILE_PIC_RESTORED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(bannerUpdatedReceiver,      bannerFilter, Context.RECEIVER_NOT_EXPORTED)
            requireContext().registerReceiver(profilePicRestoredReceiver, picFilter,    Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(bannerUpdatedReceiver,      bannerFilter)
            requireContext().registerReceiver(profilePicRestoredReceiver, picFilter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(bannerUpdatedReceiver) } catch (_: Exception) {}
        try { requireContext().unregisterReceiver(profilePicRestoredReceiver) } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        try { requireContext().unregisterReceiver(framesReceiver) } catch (_: Exception) {}
        try { _binding?.root?.viewTreeObserver?.removeOnGlobalLayoutListener(bannerSizer) } catch (_: Exception) {}
        super.onDestroyView()
        profileVideoHelper?.release(); profileVideoHelper = null
        bannerVideoHelper?.release(); bannerVideoHelper = null
        _binding = null
    }

    private fun bindBrandingUi() {
        binding.etAppDisplayName.setText(PreferenceManager.appDisplayName)
        binding.etSplashText.setText(PreferenceManager.splashText)
        binding.etMyDisplayName.setText(PreferenceManager.myDisplayName)
        binding.btnSaveBranding.setOnClickListener {
            PreferenceManager.appDisplayName = binding.etAppDisplayName.text?.toString()?.trim() ?: ""
            PreferenceManager.splashText = binding.etSplashText.text?.toString()?.trim() ?: ""
            val mine = binding.etMyDisplayName.text?.toString()?.trim() ?: ""
            PreferenceManager.myDisplayName = mine
            WebSocketManager.sendRaw(JSONObject().apply {
                put("type", "set_display_name")
                put("displayName", mine)
            }.toString())
            context?.toast("Branding saved in-app")
        }
        binding.btnPickBrandLogo.setOnClickListener {
            pendingBrandKind = "logo"
            brandImagePicker.launch("image/*")
        }
        binding.btnPickLauncherIcon.setOnClickListener {
            pendingBrandKind = "launcher"
            brandImagePicker.launch("image/*")
        }
        binding.btnGenerateBrandedApk.setOnClickListener { requestBrandedApk() }
        binding.btnLauncherPack.setOnClickListener {
            val packs = arrayOf("Default (current icon)", "Ember", "Night")
            val keys = arrayOf("default", "ember", "night")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Built-in launcher pack")
                .setItems(packs) { _, which ->
                    if (keys[which] == "default") com.silvershireinn.app.utils.BrandingHelper.restoreDefaultLauncher(requireContext())
                    else com.silvershireinn.app.utils.BrandingHelper.applyLauncherPack(requireContext(), keys[which])
                    context?.toast("Launcher pack: ${packs[which]}. Android may keep the old icon until a reboot.")
                }.show()
        }
    }

    private fun saveBrandImage(uri: Uri, kind: String) {
        try {
            val dest = File(requireContext().filesDir, if (kind == "logo") "brand_logo.png" else "brand_launcher.png")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            if (kind == "logo") PreferenceManager.brandLogoPath = dest.absolutePath
            context?.toast(if (kind == "logo") "Logo saved (in-app)" else "Launcher image saved - tap Generate branded APK")
        } catch (e: Exception) {
            context?.toast("Could not save image: ${e.message}")
        }
    }

    /** Building the branded APK replaces the app everyone downloads: admin password first. */
    private fun requestBrandedApk() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "Admin password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Build branded app")
            .setMessage("This replaces the app download for everyone on this server. Enter the server's admin password.")
            .setView(input)
            .setPositiveButton("Build") { _, _ -> requestBrandedApk(input.text?.toString().orEmpty()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBrandedApk(adminPassword: String) {
        val name = binding.etAppDisplayName.text?.toString()?.trim().orEmpty().ifBlank { "Silvershire Inn" }
        val icon = File(requireContext().filesDir, "brand_launcher.png")
        _binding?.tvBrandingStatus?.text = "Uploading branding to server..."
        lifecycleScope.launch {
            try {
                val ok = FileRepository.uploadBranding(name, if (icon.exists()) icon else null, adminPassword)
                if (!ok) {
                    _binding?.tvBrandingStatus?.text = "Couldn't start the build. Check the admin password and that the server is running."
                    return@launch
                }
                _binding?.tvBrandingStatus?.text = "Build started. Polling..."
                repeat(40) {
                    kotlinx.coroutines.delay(3000)
                    val st = FileRepository.brandingStatus()
                    _binding?.tvBrandingStatus?.text = st["message"] ?: st["status"] ?: "building"
                    if (st["status"] == "ready") {
                        _binding?.tvBrandingStatus?.text = "Branded APK ready - downloading..."
                        val apk = FileRepository.downloadBrandedApk(context ?: return@launch)
                        if (apk != null && isAdded) promptInstallApk(apk)
                        return@launch
                    }
                    if (st["status"] == "error") return@launch
                }
            } catch (e: Exception) {
                _binding?.tvBrandingStatus?.text = "Branding error: ${e.message}"
            }
        }
    }

    private fun promptInstallApk(apk: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), requireContext().packageName + ".provider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            _binding?.tvBrandingStatus?.text = "Sideload prompt opened. Same app id - it updates in place."
        } catch (e: Exception) {
            _binding?.tvBrandingStatus?.text = "APK saved to ${apk.absolutePath}. Install manually."
        }
    }

}

