package com.silvershireinn.app.ui.chat

import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.silvershireinn.app.model.Message

/**
 * Swipe a message right to reply. The bubble follows the finger a short way and springs back;
 * letting go past the mark starts the reply.
 *
 * It used to be a real "swipe": RecyclerView slid the message off screen and the app asked for
 * it to be redrawn afterwards. When the list was updating at that moment the redraw was lost and
 * the message stayed invisible until the next refresh ("his message vanished, then came back").
 * Here the swipe never completes, so nothing is ever removed.
 */
class SwipeToReply(
    private val messageAt: (Int) -> Message?,
    private val canReply: (Message) -> Boolean,
    private val onReply: (Message) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private var armed: Message? = null
    private var armedHolder: RecyclerView.ViewHolder? = null

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
        val m = messageAt(vh.bindingAdapterPosition) ?: return 0
        return if (canReply(m)) super.getSwipeDirs(rv, vh) else 0
    }

    // Never counts as swiped away.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 10f
    override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
    override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        // Shouldn't happen (threshold can't be reached); put it back just in case.
        vh.bindingAdapter?.notifyItemChanged(vh.bindingAdapterPosition)
    }

    override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                             dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
        val max = rv.width * 0.22f
        val x = dX.coerceIn(0f, max)
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            if (isCurrentlyActive) {
                if (x >= max * 0.9f && armed == null) {
                    armed = messageAt(vh.bindingAdapterPosition)
                    armedHolder = vh
                    try { com.silvershireinn.app.util.HapticUtil.vibrate(rv.context) } catch (_: Exception) {}
                } else if (x < max * 0.6f && armedHolder === vh) {
                    armed = null; armedHolder = null        // pulled back: cancelled
                }
            } else if (armedHolder === vh) {
                armed?.let(onReply)                          // let go past the mark
                armed = null; armedHolder = null
            }
        }
        super.onChildDraw(c, rv, vh, x, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        super.clearView(rv, vh)
        vh.itemView.translationX = 0f
    }
}
