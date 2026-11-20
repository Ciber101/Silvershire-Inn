package com.silvershireinn.app.ui.chathead

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.silvershireinn.app.R

/**
 * Root layout for the chat-head overlay window.
 *
 * Overrides [dispatchKeyEvent] so back-button presses are intercepted even when
 * a child view (EditText, button) holds focus — [setOnKeyListener] on the root
 * LinearLayout would never fire in that case.
 *
 * Overrides [dispatchTouchEvent] to close the file drawer when the user taps
 * anywhere outside of it, without consuming the touch (scroll/tap still works).
 */
class OverlayRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Called when back is pressed. Return true to consume the event. */
    var onBackPressed: (() -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (onBackPressed?.invoke() == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // When the file drawer is open, any touch OUTSIDE it should close it.
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val drawer = findViewById<View>(R.id.overlayFileDrawer)
            if (drawer != null && drawer.visibility == View.VISIBLE) {
                val rect = Rect()
                drawer.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    drawer.visibility = View.GONE
                    // Don't consume — let the touch reach whatever is underneath
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
