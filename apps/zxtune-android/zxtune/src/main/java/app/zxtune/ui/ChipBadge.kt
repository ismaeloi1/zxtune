package app.zxtune.ui

import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import app.zxtune.R

// Tonal fill keeps the family color as an information without competing with the accent of the active elements
private const val BADGE_FILL_ALPHA = 0.16f

/**
 * Shows chip family badge: family color text on a tonal fill of the same color, neutral surface for unknown ones.
 *
 * @param fallback text for tunes without known family, e.g. filename extension
 */
fun TextView.showChipFamily(family: ChipFamily?, fallback: String = "") {
    val ctx = context
    val color = ContextCompat.getColor(ctx, family?.color ?: R.color.lhuna_text_2)
    val fill = family?.color?.let {
        ColorUtils.setAlphaComponent(color, (255 * BADGE_FILL_ALPHA).toInt())
    } ?: ContextCompat.getColor(ctx, R.color.lhuna_surface_2)
    text = family?.label ?: fallback.takeIf { it.isNotEmpty() } ?: NO_FAMILY
    setTextColor(color)
    background = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.lhuna_radius_m)
        setColor(fill)
    }
}

/**
 * Shows chip family as an outlined chip, hidden for unknown family
 */
fun TextView.showChipFamilyChip(family: ChipFamily?) {
    isVisible = family != null
    family ?: return
    val color = ContextCompat.getColor(context, family.color ?: R.color.lhuna_text_2)
    text = family.label
    setTextColor(color)
    background = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.lhuna_radius_xl)
        setStroke(resources.getDimensionPixelSize(R.dimen.lhuna_chip_stroke), color)
    }
}

/**
 * Shows information chip, hidden for empty text
 */
fun TextView.showChip(value: CharSequence?) {
    isVisible = !value.isNullOrBlank()
    text = value
}

/**
 * Short label for tunes of unknown family: filename extension if it looks like a format id
 */
fun fallbackBadgeLabel(filename: String) = filename.substringAfterLast('.', "").takeIf {
    it.length in 1..4 && it.all(Char::isLetterOrDigit)
}?.uppercase().orEmpty()

private const val NO_FAMILY = "♪"
