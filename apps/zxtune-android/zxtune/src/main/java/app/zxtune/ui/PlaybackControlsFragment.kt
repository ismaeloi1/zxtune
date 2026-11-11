/**
 * @file
 * @brief Playback controls logic
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import app.zxtune.R
import app.zxtune.device.media.MediaModel
import app.zxtune.ui.utils.UiUtils
import app.zxtune.ui.utils.whenLifecycleStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaybackControlsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = container?.let {
        inflater.inflate(R.layout.controls, it, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = MediaModel.of(requireActivity()).run {
        val actions = TransportActions(
            view.findViewById(R.id.controls_prev),
            view.findViewById(R.id.controls_play_pause),
            view.findViewById(R.id.controls_next),
        )
        val shuffle = ShuffleModeControl(view)
        val looping = RepeatModeControl(view)
        val menu = TrackContextMenu(requireActivity(), view.findViewById(R.id.controls_track_menu))
        val info = NowPlayingInfo
        val infoToggle = view.findViewById<View>(R.id.controls_information).apply {
            setOnClickListener { info.toggle(it.context) }
        }
        viewLifecycleOwner.whenLifecycleStarted {
            launch {
                combine(controller, metadata) { ctrl, meta ->
                    menu.bind(ctrl, meta)
                }.collect {}
            }
            launch {
                info.isExpanded.collect { expanded ->
                    infoToggle.run {
                        isActivated = expanded == true
                        // chevron up expands the block above, down collapses it
                        rotation = if (expanded == true) 180f else 0f
                        contentDescription = getString(
                            if (expanded == true) R.string.track_information_hide else R.string.track_information_show
                        )
                    }
                }
            }
            launch {
                controller.collect { controller ->
                    UiUtils.setViewEnabled(view, controller != null)
                    actions.bindController(controller)
                    shuffle.bindController(controller)
                    looping.bindController(controller)
                }
            }
            launch {
                playbackState.collect(actions::bindState)
            }
        }
    }

    private class ShuffleModeControl(view: View) {
        private val button = view.findViewById<View>(R.id.controls_sequence_mode)
        private var shuffleMode = PlaybackStateCompat.SHUFFLE_MODE_INVALID
            set(value) {
                field = value
                button.run {
                    isEnabled = value != PlaybackStateCompat.SHUFFLE_MODE_INVALID
                    isActivated = value == PlaybackStateCompat.SHUFFLE_MODE_ALL
                }
            }

        init {
            bindController(null)
        }

        fun bindController(ctrl: MediaControllerCompat?) {
            shuffleMode = ctrl?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_INVALID
            button.setOnClickListener(ctrl?.transportControls?.let { transport ->
                {
                    getNextMode()?.let { newMode ->
                        transport.setShuffleMode(newMode)
                        shuffleMode = newMode
                    }
                }
            })
        }

        private fun getNextMode() = when (shuffleMode) {
            PlaybackStateCompat.SHUFFLE_MODE_NONE -> PlaybackStateCompat.SHUFFLE_MODE_ALL
            PlaybackStateCompat.SHUFFLE_MODE_ALL -> PlaybackStateCompat.SHUFFLE_MODE_NONE
            else -> null
        }
    }

    private class RepeatModeControl(view: View) {
        private var button = view.findViewById<View>(R.id.controls_track_mode)
        private var repeatMode = PlaybackStateCompat.REPEAT_MODE_INVALID
            set(value) {
                field = value
                button.run {
                    isEnabled = value != PlaybackStateCompat.REPEAT_MODE_INVALID
                    isActivated = value == PlaybackStateCompat.REPEAT_MODE_ONE
                }
            }

        init {
            bindController(null)
        }

        fun bindController(controller: MediaControllerCompat?) {
            repeatMode = controller?.repeatMode ?: PlaybackStateCompat.REPEAT_MODE_INVALID
            button.setOnClickListener(controller?.transportControls?.let { transport ->
                {
                    getNextMode()?.let { newMode ->
                        transport.setRepeatMode(newMode)
                        repeatMode = newMode
                    }
                }
            })
        }

        private fun getNextMode() = when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ONE
            PlaybackStateCompat.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_NONE
            else -> null
        }
    }
}

/**
 * Play/pause and skip buttons, shared by the full transport and the mini player
 */
internal class TransportActions(
    private val prev: View?,
    private val playPause: ImageView,
    private val next: View?,
) {
    private var state = PlaybackStateCompat.STATE_NONE
        set(value) {
            field = value
            val isPlaying = value == PlaybackStateCompat.STATE_PLAYING
            playPause.run {
                isEnabled = value != PlaybackStateCompat.STATE_NONE
                setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                contentDescription =
                    context.getString(if (isPlaying) R.string.playback_pause else R.string.playback_play)
            }
        }

    init {
        bindController(null)
        bindState(null)
    }

    fun bindController(ctrl: MediaControllerCompat?) = ctrl?.transportControls?.run {
        prev?.setOnClickListener { skipToPrevious() }
        next?.setOnClickListener { skipToNext() }
        playPause.setOnClickListener {
            when (state) {
                PlaybackStateCompat.STATE_PLAYING -> stop()
                PlaybackStateCompat.STATE_STOPPED -> play()
                else -> Unit
            }
        }
    } ?: run {
        prev?.setOnClickListener(null)
        next?.setOnClickListener(null)
        playPause.setOnClickListener(null)
    }

    fun bindState(playbackState: PlaybackStateCompat?) {
        state = playbackState?.state ?: PlaybackStateCompat.STATE_NONE
    }
}
