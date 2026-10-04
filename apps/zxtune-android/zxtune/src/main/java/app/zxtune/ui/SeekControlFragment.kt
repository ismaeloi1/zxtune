/**
 * @file
 * @brief Seek control view logic
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.zxtune.R
import app.zxtune.TimeStamp
import app.zxtune.device.media.MediaModel
import app.zxtune.device.media.getDuration
import app.zxtune.device.media.toMediaTime
import app.zxtune.ui.utils.UiUtils
import app.zxtune.ui.utils.whenLifecycleStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SeekControlFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = container?.let { inflater.inflate(R.layout.position, it, false) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = MediaModel.of(requireActivity()).run {
        val position = PositionControl(view)
        val looping = RepeatModeControl(view)

        viewLifecycleOwner.whenLifecycleStarted {
            launch {
                controller.collect { controller ->
                    UiUtils.setViewEnabled(view, controller != null)
                    position.bindController(controller)
                    looping.bindController(controller)
                }
            }
            launch {
                metadata.collect(position::initTrack)
            }
            launch {
                playbackPosition.collect { pos ->
                    position.update(pos)
                    delay(UPDATE_PERIOD_MS)
                }
            }
        }
    }

    private class PositionControl(view: View) {
        private val currentTime = view.findViewById<TextView>(R.id.position_time)
        private val currentPosition = view.findViewById<SeekBar>(R.id.position_seek)
        private val totalTime = view.findViewById<TextView>(R.id.position_duration)
        private val normalTimeColor = currentTime.textColors

        // Position selected by user while dragging, VLC-like: seek is performed only on release
        // because emulated formats should render all the data up to target position
        private var trackingPosition: TimeStamp? = null

        init {
            initTrack(null)
            bindController(null)
        }

        fun initTrack(metadata: MediaMetadataCompat?) = if (metadata != null) {
            metadata.getDuration().run {
                totalTime.text = toString()
                // TODO: use secondary progress to show loop
                currentPosition.max = toSeekBarItem()
            }
        } else {
            totalTime.setText(R.string.stub_time)
            currentTime.setText(R.string.stub_time)
        }

        fun bindController(ctrl: MediaControllerCompat?) {
            currentPosition.setOnSeekBarChangeListener(ctrl?.run {
                object : OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser) {
                            fromSeekBarItem(progress).let {
                                trackingPosition = it
                                currentTime.text = it.toString()
                            }
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        trackingPosition = fromSeekBarItem(currentPosition.progress)
                        currentTime.setTextColor(ContextCompat.getColor(currentTime.context, R.color.accent))
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        currentTime.setTextColor(normalTimeColor)
                        trackingPosition?.let {
                            transportControls.seekTo(it.toMediaTime())
                        }
                        trackingPosition = null
                    }
                }
            })
        }

        fun update(pos: TimeStamp?) {
            if (trackingPosition != null) {
                // do not interfere with user
                return
            }
            currentPosition.isEnabled = pos != null
            if (pos != null) {
                currentPosition.progress = pos.toSeekBarItem()
                currentTime.text = pos.toString()
            } else {
                currentTime.setText(R.string.stub_time)
            }
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

// 100ms resolution for precise positioning
private const val SEEK_BAR_UNIT_MS = 100L
private const val UPDATE_PERIOD_MS = 250L
private fun TimeStamp.toSeekBarItem() = (toMilliseconds() / SEEK_BAR_UNIT_MS).toInt()
private fun fromSeekBarItem(pos: Int) = TimeStamp.fromMilliseconds(pos * SEEK_BAR_UNIT_MS)
