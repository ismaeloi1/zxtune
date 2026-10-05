/**
 *
 * @file
 *
 * @brief Stub singleton implementation of Visualizer
 *
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.playback.stubs

import app.zxtune.playback.Visualizer

object VisualizerStub : Visualizer {
    override fun getSpectrum(levels: ByteArray) = 0

    override fun getScope(data: ShortArray, points: Int, windowMs: Int) = 0

    override fun getGauges(data: ByteArray, waveWindowMs: Int) = 0

    override fun getStatus() = ""

    // TODO: remove
    @JvmStatic
    fun instance() = VisualizerStub
}
