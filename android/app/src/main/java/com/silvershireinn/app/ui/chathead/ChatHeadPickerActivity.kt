package com.silvershireinn.app.ui.chathead

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.silvershireinn.app.ui.media.CameraCaptureActivity
import java.io.File

/**
 * Invisible proxy Activity launched by ChatHeadService to perform picker operations
 * (file pick, camera, video, STT) that require an Activity context.
 *
 * It finishes immediately after launching the system intent and delivers the result
 * back to ChatHeadService via a started-service Intent carrying ACTION_PICKER_RESULT.
 *
 * Theme: Theme.Translucent.NoTitleBar: the Activity is invisible to the user.
 *
 * STT: ChatHeadService hides the overlay before launching us so the Google UI is on top.
 * If the user leaves mid-STT (home/recents/pause without a result), we cancel cleanly
 * and tell the service to restore the overlay without inserting partial text.
 */
class ChatHeadPickerActivity : ComponentActivity() {

    companion object {
        const val ACTION_PICK_FILE    = "PICK_FILE"
        const val ACTION_PICK_GALLERY = "PICK_GALLERY"
        const val ACTION_TAKE_PHOTO   = "TAKE_PHOTO"
        const val ACTION_TAKE_VIDEO   = "TAKE_VIDEO"
        const val ACTION_STT          = "STT"
        /** Ask for the microphone (the floating chat can't show the permission prompt). */
        const val ACTION_MIC_PERMISSION = "MIC_PERMISSION"

        const val EXTRA_ACTION       = "action"
        const val EXTRA_CONTACT_ID   = "contact_id"
        const val EXTRA_GROUP_ID     = "group_id"
        const val EXTRA_GROUP_NAME   = "group_name"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }

    private var pickerAction: String = ""
    private var contactId: String    = ""
    private var groupId: String?     = null
    private var groupName: String?   = null
    private var displayName: String  = ""

    private var pendingCaptureUri: Uri? = null
    private var resultDelivered = false
    private var waitingForSpeech = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        deliverPicked(uri)
    }

    /** Photos AND videos from the phone's photo picker ("Gallery" only offered images). */
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        deliverPicked(uri)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        android.widget.Toast.makeText(this,
            if (granted) "Microphone allowed. Hold the mic to record" else "Microphone not allowed",
            android.widget.Toast.LENGTH_SHORT).show()
        deliverCancel()
    }

    /**
     * Files picked from other apps are copied into our own cache before handing them to the
     * floating chat. The permission to read the original ends when this screen closes, so
     * uploading straight from it failed for many gallery / file picks.
     */
    private fun deliverPicked(uri: Uri?) {
        if (uri == null) { deliverCancel(); return }
        if (uri.authority == "${packageName}.provider") { deliverResult(uri, null); return }
        Thread {
            val copied = try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "file_${System.currentTimeMillis()}"
                // Own folder per pick so the file keeps its real name.
                val dir = File(File(cacheDir, "overlay_pick"), System.currentTimeMillis().toString()).apply { mkdirs() }
                val out = File(dir, name)
                contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                if (out.length() > 0) FileProvider.getUriForFile(this, "${packageName}.provider", out) else null
            } catch (_: Exception) { null }
            runOnUiThread {
                if (copied != null) deliverResult(copied, null)
                else {
                    android.widget.Toast.makeText(this, "Couldn't open that file", android.widget.Toast.LENGTH_SHORT).show()
                    deliverCancel()
                }
            }
        }.start()
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) deliverResult(pendingCaptureUri, null) else deliverCancel()
    }

    private val videoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) deliverResult(pendingCaptureUri, null) else deliverCancel()
    }

    private val inAppCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) deliverResult(result.data?.data, null)
        else deliverCancel()
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        waitingForSpeech = false
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            // Insert text only, never auto-send
            deliverResult(null, text)
        } else {
            deliverCancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickerAction = intent.getStringExtra(EXTRA_ACTION) ?: run { finish(); return }
        contactId    = intent.getStringExtra(EXTRA_CONTACT_ID) ?: ""
        groupId      = intent.getStringExtra(EXTRA_GROUP_ID)
        groupName    = intent.getStringExtra(EXTRA_GROUP_NAME)
        displayName  = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: contactId

        when (pickerAction) {
            ACTION_PICK_FILE -> filePicker.launch("*/*")
            ACTION_TAKE_PHOTO   -> launchCamera()
            ACTION_PICK_GALLERY -> try {
                mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            } catch (_: Exception) { filePicker.launch("*/*") }
            ACTION_MIC_PERMISSION -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            ACTION_TAKE_VIDEO   -> launchVideo()
            ACTION_STT -> {
                waitingForSpeech = true
                val sttIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                }
                try { speechLauncher.launch(sttIntent) }
                catch (_: Exception) { deliverCancel() }
            }
            else -> finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // User left mid-STT (home / recents / overlay gone) without a result
        if (waitingForSpeech && !resultDelivered && !isChangingConfigurations) {
            waitingForSpeech = false
            deliverCancel()
        }
    }

    override fun onDestroy() {
        // Any picker (not just speech) that dies without a result must still tell the
        // service, or the overlay and chat head it hid stay gone until the next message.
        if (!resultDelivered && !isChangingConfigurations) {
            waitingForSpeech = false
            deliverCancel()
        }
        super.onDestroy()
    }

    private fun launchCamera() {
        try {
            inAppCamera.launch(CameraCaptureActivity.intent(this, CameraCaptureActivity.MODE_PHOTO))
        } catch (e: Exception) {
            try {
                val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val uri  = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                pendingCaptureUri = uri
                cameraLauncher.launch(uri)
            } catch (e2: Exception) {
                android.util.Log.e("ChatHeadPicker", "Camera launch failed: ${e2.message}")
                deliverCancel()
            }
        }
    }

    private fun launchVideo() {
        try {
            inAppCamera.launch(CameraCaptureActivity.intent(this, CameraCaptureActivity.MODE_VIDEO))
        } catch (e: Exception) {
            try {
                val file = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
                val uri  = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                pendingCaptureUri = uri
                videoLauncher.launch(uri)
            } catch (e2: Exception) {
                android.util.Log.e("ChatHeadPicker", "Video capture launch failed: ${e2.message}")
                deliverCancel()
            }
        }
    }

    /** Notify service to restore overlay; do not insert text. */
    private fun deliverCancel() {
        deliverResult(null, null)
    }

    private fun deliverResult(uri: Uri?, text: String?) {
        if (resultDelivered) {
            if (!isFinishing) finish()
            return
        }
        resultDelivered = true
        waitingForSpeech = false
        val serviceIntent = Intent(this, ChatHeadService::class.java).apply {
            action = ChatHeadService.ACTION_PICKER_RESULT
            putExtra(ChatHeadService.EXTRA_PICKER_URI,     uri?.toString())
            putExtra(ChatHeadService.EXTRA_PICKER_TEXT,    text)
            putExtra(EXTRA_CONTACT_ID,                     contactId)
            putExtra(EXTRA_GROUP_ID,                       groupId)
            putExtra(EXTRA_GROUP_NAME,                     groupName)
            putExtra(EXTRA_DISPLAY_NAME,                   displayName)
        }
        try { startService(serviceIntent) } catch (_: Exception) {}
        finish()
    }
}
