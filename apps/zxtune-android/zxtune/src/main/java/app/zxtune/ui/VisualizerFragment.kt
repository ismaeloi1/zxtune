package app.zxtune.ui

import android.content.Context
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.zxtune.R
import app.zxtune.coverart.withFadeout
import app.zxtune.device.media.MediaModel
import app.zxtune.playback.Visualizer
import app.zxtune.playback.stubs.VisualizerStub
import app.zxtune.preferences.Preferences
import app.zxtune.ui.utils.whenLifecycleStarted
import app.zxtune.ui.views.OscilloscopeView
import app.zxtune.ui.views.SpectrumAnalyzerView
import app.zxtune.utils.ifNotNulls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class VisualizerFragment : Fragment() {
    private lateinit var analyzer: SpectrumAnalyzerView
    private lateinit var oscilloscope: OscilloscopeView
    private val setVisibilityJob = AtomicReference<Job?>(null)
    private val isTabVisible = MutableStateFlow(true)

    private val scope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = container?.let {
        inflater.inflate(R.layout.visualizer, it, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) =
        MediaModel.of(requireActivity()).run {
            analyzer = view.findViewById(R.id.spectrum)
            oscilloscope = view.findViewById(R.id.oscilloscope)
            val modeButton = view.findViewById<ImageButton>(R.id.visualizer_mode)
            val fullscreenButton = view.findViewById<ImageButton>(R.id.visualizer_fullscreen)
            fullscreenButton.setOnClickListener {
                OscilloscopeActivity.start(requireContext())
            }
            // TODO: use clicks stream, state may switch different modes
            val state = callbackFlow {
                val storage = StateStorage(requireContext())
                var state = storage.load()
                var lastMode = state.takeIf { it.isVisible } ?: State.SPECTRUM
                send(state)
                fun switchTo(next: State) {
                    // failed on busy, avoid hanged clicks
                    trySend(next).onSuccess {
                        state = next
                        if (next.isVisible) {
                            lastMode = next
                        }
                        launch {
                            storage.save(next)
                        }
                    }
                }
                view.setOnClickListener {
                    switchTo(if (state.isVisible) State.OFF else lastMode)
                }
                modeButton.setOnClickListener {
                    switchTo(state.nextModeOf())
                }
                awaitClose {
                    view.setOnClickListener(null)
                    modeButton.setOnClickListener(null)
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(1000), DEFAULT_STATE)
            val playingSource: Flow<Visualizer?> =
                visualizer.combine(playbackState) { visualizer, playbackState ->
                    ifNotNulls(visualizer, playbackState?.state) { src, state ->
                        if (PlaybackStateCompat.STATE_PLAYING == state) {
                            src
                        } else {
                            VisualizerStub
                        }
                    }
                }.distinctUntilChanged { old, new -> old === new }
            viewLifecycleOwner.whenLifecycleStarted {
                launch {
                    playingSource.collectLatest { src ->
                        src?.let {
                            analyzer.drawFrom(it)
                        }
                    }
                }
                launch {
                    combine(playingSource, state, isTabVisible) { src, state, isTabVisible ->
                        src.takeIf { state == State.OSCILLOSCOPE && isTabVisible && it !== VisualizerStub }
                    }.distinctUntilChanged { old, new -> old === new }.collect {
                        oscilloscope.setSource(it)
                    }
                }
                launch {
                    combine(state, isTabVisible) { state, isTabVisible ->
                        state == State.SPECTRUM && isTabVisible
                    }.distinctUntilChanged().collect {
                        setAnalyzerUpdating(it)
                    }
                }
                launch {
                    state.collect {
                        analyzer.isVisible = it == State.SPECTRUM
                        oscilloscope.isVisible = it == State.OSCILLOSCOPE
                        fullscreenButton.isVisible = it == State.OSCILLOSCOPE
                        modeButton.isVisible = it.isVisible
                        val isScope = it == State.OSCILLOSCOPE
                        modeButton.setImageResource(
                            if (isScope) R.drawable.ic_spectrum else R.drawable.ic_oscilloscope
                        )
                        modeButton.contentDescription = getString(
                            if (isScope) R.string.visualizer_spectrum else R.string.visualizer_oscilloscope
                        )
                    }
                }
                launch {
                    val imageView = view.findViewById<ImageView>(R.id.coverart)
                    coverArt.collectIndexed { idx, src ->
                        if (0 == idx) {
                            src.applyTo(imageView)
                        } else {
                            imageView.withFadeout {
                                src.applyTo(it)
                            }
                        }
                    }
                }
            }
        }

    // Show/hide tab
    fun setIsVisible(isVisible: Boolean) {
        isTabVisible.value = isVisible
    }

    private fun setAnalyzerUpdating(isUpdating: Boolean) = setVisibilityJob.exchange(
        scope.launch {
            analyzer.setIsUpdating(isUpdating)
        }
    )?.cancel() ?: Unit

    override fun onDestroy() {
        super.onDestroy()
        if (this::analyzer.isInitialized) {
            analyzer.reportStatistics()
        }
    }

    // TODO: replace with async DataStore
    private class StateStorage(ctx: Context) {
        private val prefs = Preferences.getDataStore(ctx)

        // PREF_KEY keeps backward compatible OFF/SPECTRUM value, mode is stored separately
        suspend fun load() = withContext(Dispatchers.IO) {
            val isVisible = State.OFF.ordinal != prefs.getInt(PREF_KEY, DEFAULT_STATE.ordinal)
            if (isVisible) {
                State.entries.getOrNull(prefs.getInt(PREF_MODE_KEY, -1))?.takeIf { it.isVisible }
                    ?: State.SPECTRUM
            } else {
                State.OFF
            }
        }

        suspend fun save(state: State) = withContext(Dispatchers.IO) {
            prefs.putInt(PREF_KEY, if (state.isVisible) State.SPECTRUM.ordinal else State.OFF.ordinal)
            if (state.isVisible) {
                prefs.putInt(PREF_MODE_KEY, state.ordinal)
            }
        }
    }

    enum class State {
        OFF, SPECTRUM, OSCILLOSCOPE;

        val isVisible
            get() = this != OFF

        fun nextModeOf() = when (this) {
            SPECTRUM -> OSCILLOSCOPE
            OSCILLOSCOPE -> SPECTRUM
            else -> this
        }
    }

    companion object {
        private const val PREF_KEY = "ui.visualizer"
        private const val PREF_MODE_KEY = "ui.visualizer.mode"
        private val DEFAULT_STATE = State.SPECTRUM
    }
}
