package app.zxtune.core

import app.zxtune.Releaseable
import app.zxtune.TimeStamp

/**
 * Module interface
 */
interface Module :
    PropertiesAccessor,
    AdditionalFiles,
    Releaseable {
    /**
     * @return Module's duration in milliseconds
     */
    val duration: TimeStamp

    /**
     * @return Duration of the looped part at the end of the module, equal to [duration] if it is looped as a whole
     */
    val loopDuration: TimeStamp

    /**
     * Creates new player object
     *
     * @throws RuntimeException in case of error
     */
    fun createPlayer(samplerate: Int): Player
}
