package app.zxtune.ui

import android.graphics.Typeface
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.animation.ValueAnimator
import androidx.fragment.app.Fragment
import app.zxtune.R
import app.zxtune.core.Identifier
import app.zxtune.core.ModuleAttributes
import app.zxtune.device.media.MediaModel
import app.zxtune.preferences.Preferences
import app.zxtune.ui.utils.UiUtils
import app.zxtune.ui.utils.whenLifecycleStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LINES = 8
private const val SHOW_AUTHOR_KEY = "ui.brief.show_author"

class BriefFragment : Fragment() {
    private class DetailsView(private val view: TextView) {
        private var requiredHeight = 0
        private var shown = false

        init {
            view.run {
                height = 0
                movementMethod = ScrollingMovementMethod.getInstance()
                includeFontPadding = false
            }
        }

        fun setText(txt: CharSequence?, font: Typeface) {
            requiredHeight = txt?.run {
                view.lineHeight * (1 + count { it == '\n' }).coerceAtMost(MAX_LINES) + view.paddingTop + view.paddingBottom
            } ?: 0
            resize(slow = true)
            if (shown) {
                view.run {
                    animate().alpha(0f).withEndAction {
                        changeText(txt, font)
                        animate().alpha(1f)
                    }
                }
            } else {
                changeText(txt, font)
            }
        }

        private fun changeText(txt: CharSequence?, font: Typeface) = view.run {
            text = txt
            typeface = font
            scrollTo(0, 0)
        }

        fun setVisibility(visible: Boolean) {
            if (shown != visible) {
                shown = visible
                resize()
            }
        }

        private fun resize(slow: Boolean = false) {
            val currentHeight = view.measuredHeight
            val targetHeight = if (shown) requiredHeight else 0
            if (currentHeight != targetHeight) {
                ValueAnimator.ofInt(currentHeight, targetHeight).apply {
                    addUpdateListener {
                        view.height = animatedValue as Int
                    }
                    if (slow) {
                        duration = view.animate().duration * 2
                    }
                }.start()
            }
        }
    }

    /**
     * What is playing in short: chip family, format if it says more than family, subtune and program
     */
    private class ChipsView(view: View) {
        private val family = view.findViewById<TextView>(R.id.brief_chip_family)
        private val format = view.findViewById<TextView>(R.id.brief_chip_format)
        private val track = view.findViewById<TextView>(R.id.brief_chip_track)
        private val program = view.findViewById<TextView>(R.id.brief_chip_program)

        fun bind(metadata: MediaMetadataCompat) {
            val type = metadata.getString(ModuleAttributes.TYPE).orEmpty()
            val id = metadata.description.mediaUri?.let(::Identifier)
            val chipFamily = ChipFamily.ofType(type) ?: ChipFamily.ofFilename(id?.tuneFilename)
            family.showChipFamilyChip(chipFamily)
            format.showChip(type.takeUnless { it == chipFamily?.label })
            track.showChip(id?.trackIndex?.let { "#$it" })
            program.showChip(metadata.getString(ModuleAttributes.PROGRAM))
        }
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var details: DetailsView
    private lateinit var detailsToggle: ImageButton
    private lateinit var chips: ChipsView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = container?.let {
        inflater.inflate(R.layout.brief, it, false).apply {
            title = findViewById(R.id.brief_title)
            subtitle = findViewById(R.id.brief_subtitle)
            details = DetailsView(findViewById(R.id.brief_details))
            detailsToggle = findViewById(R.id.brief_details_toggle)
            chips = ChipsView(this)
            detailsToggle.setOnClickListener {
                val state = !isActivated
                isActivated = state
                details.setVisibility(state)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = MediaModel.of(requireActivity()).run {
        val menu = TrackContextMenu(requireActivity(), view.findViewById(R.id.brief_track_menu))
        viewLifecycleOwner.whenLifecycleStarted {
            launch {
                combine(controller, metadata) { ctrl, meta ->
                    menu.bind(ctrl, meta)
                }.collect {}
            }
            // re-read on each start, may be changed in preferences
            val showAuthor = withContext(Dispatchers.IO) {
                Preferences.getDataStore(requireContext()).getBoolean(SHOW_AUTHOR_KEY, false)
            }
            metadata.collect { metadata ->
                UiUtils.setViewEnabled(view, metadata != null)
                metadata?.run {
                    title.updateText(description.title)
                    // author is hidden by default to give more space to visualizer
                    subtitle.updateText(description.subtitle.takeIf { showAuthor })
                    chips.bind(this)
                    val strings = getString(ModuleAttributes.STRINGS)
                    val comment = getString(ModuleAttributes.COMMENT)
                    if (strings != null) {
                        details.setText(strings, Typeface.MONOSPACE)
                    } else {
                        details.setText(comment, Typeface.DEFAULT)
                    }
                    detailsToggle.updateVisibility(!strings.isNullOrEmpty() || !comment.isNullOrEmpty())
                }
            }
        }
    }
}

// TODO: extract to animation utils
private fun TextView.updateText(txt: CharSequence?, hiddenVisibility: Int = View.GONE) {
    val visibleText = if (visibility == View.VISIBLE && alpha > 0f) text else ""
    if (visibleText != txt) {
        animate().alpha(0f).withEndAction {
            if (txt.isNullOrEmpty()) {
                visibility = hiddenVisibility
            } else {
                visibility = View.VISIBLE
                text = txt
                animate().alpha(1f)
            }
        }
    }
}

private fun View.updateVisibility(isVisible: Boolean) {
    val nowVisible = visibility == View.VISIBLE && alpha > 0f
    if (nowVisible != isVisible) {
        if (isVisible) {
            visibility = View.VISIBLE
            animate().alpha(1f)
        } else {
            animate().alpha(0f).withEndAction {
                visibility = View.INVISIBLE
            }
        }
    }
}
