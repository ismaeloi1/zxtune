package app.zxtune.playback.service

import app.zxtune.core.Player
import app.zxtune.playback.FrameResult
import app.zxtune.playback.Visualizer

/**
 * @param playingFrame provides index of currently heard frame among rendered by player, negative if unknown
 * @param outputStatus provides sound output diagnostics
 */
internal class PlaybackVisualizer(
    private val player: Player,
    private val playingFrame: () -> Long,
    private val outputStatus: () -> String,
) : Visualizer {
    override fun getSpectrum(levels: ByteArray) = player.analyze(levels)

    override fun getScope(data: ShortArray, points: Int, windowMs: Int) = player.scope(data, points, playingFrame(), windowMs)

    override fun getGauges(data: ByteArray, waveWindowMs: Int) = player.scopeGauges(data, playingFrame(), waveWindowMs)

    override fun getVoiceGauges(data: ByteArray, waveWindowMs: Int) = player.scopeVoiceGauges(data, playingFrame(), waveWindowMs)

    override fun getFrame(data: ShortArray, points: Int, windowMs: Int, gauges: ByteArray, gaugesKind: Int, waveWindowMs: Int): Long {
        // the same heard moment for all the views
        val frame = playingFrame()
        val scope = player.scope(data, points, frame, windowMs)
        val count = when (gaugesKind) {
            FrameResult.GAUGES_CHIPS -> player.scopeGauges(gauges, frame, waveWindowMs)
            FrameResult.GAUGES_VOICES -> player.scopeVoiceGauges(gauges, frame, waveWindowMs)
            else -> 0
        }
        return FrameResult.pack(scope, count)
    }

    override fun getLayout() = player.scopeLayout

    override fun getStatus() = buildString {
        appendLine(player.scopeStatus)
        appendLine("output: ${outputStatus()}")
        append("render: ${player.performance}% realtime")
    }
}
