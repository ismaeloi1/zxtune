package app.zxtune.ui

import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import app.zxtune.R
import kotlin.math.abs

/**
 * Bottom navigation over the pages of [pager], kept in sync both ways: swipes select the item, taps scroll to the
 * page. Items of [bar] are in the same order as the pages.
 *
 * @param miniPlayer shown on every page but the now playing one, if it is a page at all
 */
class PagerNavigation(
    private val pager: ViewPager,
    private val bar: ViewGroup,
    private val miniPlayer: View?,
) : ViewPager.SimpleOnPageChangeListener() {

    private val pagesIds = IntArray(pager.childCount) { pager.getChildAt(it).id }

    init {
        check(bar.childCount == pagesIds.size) { "${bar.childCount} items for ${pagesIds.size} pages" }
        for (idx in 0 until bar.childCount) {
            bar.getChildAt(idx).setOnClickListener {
                showPage(idx)
            }
        }
        pager.addOnPageChangeListener(this)
        onPageSelected(pager.currentItem)
    }

    /**
     * @return false if there's no such page, e.g. on layouts where the content is always visible
     */
    fun show(@IdRes pageId: Int) = pagesIds.indexOf(pageId).takeIf { it != -1 }?.let {
        showPage(it)
        true
    } ?: false

    private fun showPage(idx: Int) {
        // Scrolling over intermediate page looks like a glitch
        pager.setCurrentItem(idx, abs(idx - pager.currentItem) == 1)
    }

    override fun onPageSelected(position: Int) {
        for (idx in 0 until bar.childCount) {
            bar.getChildAt(idx).isSelected = idx == position
        }
        miniPlayer?.isVisible = pagesIds.getOrNull(position) != NOW_PLAYING_PAGE
    }

    companion object {
        @IdRes
        val NOW_PLAYING_PAGE = R.id.now_playing
    }
}
