package app.zxtune.ui.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.zxtune.R
import app.zxtune.databinding.BrowserBreadcrumbsEntryBinding

internal class BreadcrumbsViewAdapter : ListAdapter<BreadcrumbsEntry, BreadcrumbsViewAdapter.ViewHolder>(DiffCallback()) {

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding: BrowserBreadcrumbsEntryBinding = DataBindingUtil.inflate(
            inflater,
            R.layout.browser_breadcrumbs_entry,
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = getItem(position).let {
        holder.bind(it)
    }

    override fun getItemId(position: Int) = getItem(position).uri.hashCode().toLong()

    internal class ViewHolder(private val binding: BrowserBreadcrumbsEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: BreadcrumbsEntry) {
            binding.entry = entry
            binding.executePendingBindings()
            (binding.root as Button).setChipIcon(entry.icon)
        }
    }

    /**
     * Chevrons between chips
     */
    internal class Delimiters(ctx: Context) : RecyclerView.ItemDecoration() {
        private val chevron = requireNotNull(AppCompatResources.getDrawable(ctx, R.drawable.ic_crumb_chevron))
        private val width = chevron.intrinsicWidth + 2 * ctx.resources.getDimensionPixelSize(R.dimen.spacing_half)

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.left = width
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (idx in 0 until parent.childCount) {
                val child = parent.getChildAt(idx)
                val right = child.left + child.translationX.toInt() - (width - chevron.intrinsicWidth) / 2
                val top = (child.top + child.bottom - chevron.intrinsicHeight) / 2
                chevron.setBounds(right - chevron.intrinsicWidth, top, right, top + chevron.intrinsicHeight)
                chevron.draw(c)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<BreadcrumbsEntry>() {
        override fun areItemsTheSame(oldItem: BreadcrumbsEntry, newItem: BreadcrumbsEntry) = oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: BreadcrumbsEntry, newItem: BreadcrumbsEntry) = oldItem == newItem
    }
}

/**
 * Icons of sources are 32dp, chip is smaller
 */
internal fun Button.setChipIcon(@DrawableRes icon: Int?) {
    val drawable = icon?.let { AppCompatResources.getDrawable(context, it) }?.mutate()?.apply {
        val size = resources.getDimensionPixelSize(R.dimen.lhuna_crumb_icon)
        setBounds(0, 0, size, size)
    }
    setCompoundDrawables(drawable, null, null, null)
    compoundDrawablePadding = if (text.isNullOrEmpty()) 0 else resources.getDimensionPixelSize(R.dimen.spacing_full)
}
