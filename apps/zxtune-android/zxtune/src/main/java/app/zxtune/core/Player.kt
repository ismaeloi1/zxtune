package app.zxtune.core

import app.zxtune.Releaseable
import app.zxtune.TimeStamp

/**
 * Player interface
 */
interface Player : PropertiesContainer, Releaseable {
    /**
     * @return Position
     */
    /**
     * @param pos Position
     */
    var position: TimeStamp

    /**
     * @param levels Array of levels to store
     * @return Count of actually stored entries
     */
    fun analyze(levels: ByteArray): Int

    /**
     * @param data Array to store [channels][points] waveforms of oscilloscope
     * @param points Points per channel
     * @return Packed layout of stored channels, see [app.zxtune.playback.ScopeLayout]
     */
    fun scope(data: ShortArray, points: Int): Int

    /**
     * Render next result.length bytes of sound data
     *
     * @param result Buffer to put data
     * @return Is there more data to render
     */
    fun render(result: ShortArray): Boolean

    /**
     * @return Rendering performance in percents
     */
    val performance: Int

    /**
     * @return Playback progress in percents (may be >100!!!)
     */
    val progress: Int
}
