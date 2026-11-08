/**
 * @file
 * @brief Grouped cards of settings lists
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.DimenRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import app.zxtune.R

/**
 * Rows between section headers are drawn as a single card with dividers, headers stay on the background.
 *
 * Library adapter is not a public API, so headers are recognized by tag of their layout
 * (see lhuna_preference_category.xml) and remembered by view type for rows not laid out yet.
 */
object SettingsCards {
    fun install(fragment: PreferenceFragmentCompat, @DimenRes margin: Int = R.dimen.lhuna_card_margin) {
        val list = fragment.listView
        val tag = list.context.getString(R.string.lhuna_settings_header_tag)
        list.addItemDecoration(GroupCardsDecoration(list.context, list.resources.getDimensionPixelSize(margin), TaggedHeaders(tag)))
    }

    private class TaggedHeaders(private val tag: String) : (RecyclerView, Int) -> Boolean {
        private val types = HashSet<Int>()

        override fun invoke(list: RecyclerView, position: Int): Boolean {
            list.findViewHolderForAdapterPosition(position)?.let { holder ->
                return (holder.itemView.tag == tag).also {
                    if (it) {
                        types.add(holder.itemViewType)
                    }
                }
            }
            val adapter = list.adapter ?: return false
            return position in 0 until adapter.itemCount && adapter.getItemViewType(position) in types
        }
    }
}

/**
 * @param isHeader whether item at adapter position is a section header
 */
class GroupCardsDecoration(
    context: Context,
    private val margin: Int,
    private val isHeader: (RecyclerView, Int) -> Boolean,
) : RecyclerView.ItemDecoration() {

    private val radius = context.resources.getDimension(R.dimen.lhuna_radius_l)
    private val dividerInset = context.resources.getDimensionPixelSize(R.dimen.lhuna_space_l)
    private val dividerHeight = maxOf(1f, context.resources.displayMetrics.density)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lhuna_surface_1)
    }
    private val dividerPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.lhuna_outline)
    }
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) = outRect.set(margin, 0, margin, 0)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.adapter?.itemCount ?: return
        for (idx in 0 until parent.childCount) {
            val child = parent.getChildAt(idx)
            val pos = parent.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION } ?: parent.getChildLayoutPosition(child)
            if (pos == RecyclerView.NO_POSITION || isHeader(parent, pos)) {
                continue
            }
            val first = pos == 0 || isHeader(parent, pos - 1)
            val last = pos == count - 1 || isHeader(parent, pos + 1)
            val top = child.top + child.translationY
            val bottom = child.bottom + child.translationY
            rect.set(child.left.toFloat(), top, child.right.toFloat(), bottom)
            val alpha = (child.alpha * 255).toInt()
            cardPaint.alpha = alpha
            setRadii(first, last)
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            c.drawPath(path, cardPaint)
            if (!last) {
                dividerPaint.alpha = alpha
                c.drawRect(rect.left + dividerInset, bottom - dividerHeight, rect.right - dividerInset, bottom, dividerPaint)
            }
            if (Build.VERSION.SDK_INT >= 21) {
                clipCorners(child, first, last)
            }
        }
    }

    private fun setRadii(first: Boolean, last: Boolean) {
        val top = if (first) radius else 0f
        val bottom = if (last) radius else 0f
        radii.fill(top, 0, 4)
        radii.fill(bottom, 4, 8)
    }

    // pressed state of rows must not stick out of rounded corners
    @RequiresApi(21)
    private fun clipCorners(child: View, first: Boolean, last: Boolean) {
        val key = (if (first) 1 else 0) or (if (last) 2 else 0)
        if (child.getTag(R.id.settings_card_corners) == key) {
            return
        }
        child.setTag(R.id.settings_card_corners, key)
        child.clipToOutline = key != 0
        child.outlineProvider = if (key != 0) CornersOutline(radius, first, last) else ViewOutlineProvider.BACKGROUND
    }

    @RequiresApi(21)
    private class CornersOutline(private val radius: Float, private val top: Boolean, private val bottom: Boolean) : ViewOutlineProvider() {
        // single radius only, so not rounded side is extended out of view
        override fun getOutline(view: View, outline: Outline) {
            val extra = radius.toInt() + 1
            outline.setRoundRect(
                0,
                if (top) 0 else -extra,
                view.width,
                if (bottom) view.height else view.height + extra,
                radius,
            )
        }
    }
}
