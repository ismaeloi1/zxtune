package app.zxtune.core

import app.zxtune.Releaseable
import app.zxtune.TimeStamp

/**
 * Player interface
 */
interface Player :
    PropertiesContainer,
    Releaseable {
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
     * @param playing Index of currently heard frame among rendered by this player, negative if unknown
     * @return Packed layout of stored channels, see [app.zxtune.playback.ScopeLayout]
     */
    fun scope(data: ShortArray, points: Int, playing: Long): Int

    /**
     * @param data Array to store [chips][records][CHIP_STATE_SIZE] chip state snapshots
     * @param records Records count per chip, last one is for currently heard frame
     * @param playing see [scope]
     * @return Count of stored chips
     */
    fun scopeStates(data: ByteArray, records: Int, playing: Long): Int

    /**
     * Emulation description and visualization statistics
     */
    val scopeStatus: String

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
