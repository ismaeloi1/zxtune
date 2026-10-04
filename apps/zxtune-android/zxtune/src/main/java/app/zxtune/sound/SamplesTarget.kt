/**
 * @file
 * @brief Samples target interface
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.sound

import app.zxtune.Releaseable

interface SamplesTarget : Releaseable {
    /**
     * @return target sample rate in Hz
     */
    val sampleRate: Int

    /**
     * @return buffer size in samples
     */
    val preferableBufferSize: Int

    /**
     * @return frames actually presented to user since last start, negative if unknown
     */
    val playedFrames: Long
        get() = -1

    /**
     * @return additional diagnostic information
     */
    val statistics: String
        get() = ""

    /**
     * Initialize target
     */
    @Throws(Exception::class)
    fun start()

    /**
     * @param buffer sound data in S16/stereo/interleaved format
     */
    @Throws(Exception::class)
    fun writeSamples(buffer: ShortArray)

    /**
     * Deinitialize target
     */
    @Throws(Exception::class)
    fun stop()
}
