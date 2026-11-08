package app.zxtune.ui

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import app.zxtune.MainActivity
import app.zxtune.R
import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
import app.zxtune.core.ModuleAttributes
import app.zxtune.device.media.MediaModel
import app.zxtune.device.media.getDuration
import app.zxtune.ui.utils.UiUtils
import app.zxtune.ui.utils.whenLifecycleStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Playing tune and the main transport actions on the pages other than now playing one
 */
class MiniPlayerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = container?.let { inflater.inflate(R.layout.mini_player, it, false) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = MediaModel.of(requireActivity()).run {
        val card = view.findViewById<View>(R.id.mini_player_card).apply {
            setOnClickListener {
                (activity as? MainActivity)?.showNowPlaying()
            }
        }
        val actions = TransportActions(
            null,
            view.findViewById(R.id.mini_player_play_pause),
            view.findViewById(R.id.mini_player_next),
        )
        val info = InfoView(view)
        val progress = view.findViewById<ProgressBar>(R.id.mini_player_progress)
        info.bind(null)
        viewLifecycleOwner.whenLifecycleStarted {
            launch {
                controller.collect { controller ->
                    UiUtils.setViewEnabled(card, controller != null)
                    actions.bindController(controller)
                }
            }
            launch {
                playbackState.collect(actions::bindState)
            }
            launch {
                metadata.collect { metadata ->
                    // nothing to control yet
                    view.isVisible = metadata != null
                    info.bind(metadata)
                    progress.max = metadata?.getDuration()?.toProgress() ?: 0
                }
            }
            launch {
                playbackPosition.collect { pos ->
                    // updated while hidden too: paused position is reported only once
                    progress.progress = pos?.toProgress() ?: 0
                    delay(UPDATE_PERIOD_MS)
                }
            }
        }
    }

    private class InfoView(view: View) {
        private val badge = view.findViewById<TextView>(R.id.mini_player_badge)
        private val title = view.findViewById<TextView>(R.id.mini_player_title)
        private val subtitle = view.findViewById<TextView>(R.id.mini_player_subtitle)

        fun bind(metadata: MediaMetadataCompat?) {
            val filename = metadata?.description?.mediaUri?.let { Identifier(it).tuneFilename }.orEmpty()
            val family = ChipFamily.ofType(metadata?.getString(ModuleAttributes.TYPE))
                ?: ChipFamily.ofFilename(filename)
            badge.showChipFamily(family, fallbackBadgeLabel(filename))
            title.text = metadata?.description?.title
            subtitle.text = metadata?.description?.subtitle
            subtitle.isVisible = !subtitle.text.isNullOrEmpty()
        }
    }

    companion object {
        // the thin line moves by a few pixels per update for usual tunes, finer steps are not visible
        private const val UPDATE_PERIOD_MS = 500L
        private const val PROGRESS_UNIT_MS = 100L

        private fun TimeStamp.toProgress() = (toMilliseconds() / PROGRESS_UNIT_MS).toInt()
    }
}
