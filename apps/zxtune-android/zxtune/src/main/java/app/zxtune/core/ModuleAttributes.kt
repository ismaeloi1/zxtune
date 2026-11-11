package app.zxtune.core

/**
 * Attributes 'namespace'
 */
object ModuleAttributes {
    /**
     * Type. Several uppercased letters used to identify format
     */
    const val TYPE = "Type"

    /**
     * Title or name of the module
     */
    const val TITLE = "Title"

    /**
     * Author or creator of the module
     */
    const val AUTHOR = "Author"

    /**
     * Program module created in compatible with
     */
    const val PROGRAM = "Program"

    /**
     * Comment for module
     */
    const val COMMENT = "Comment"

    /**
     * Module strings
     */
    const val STRINGS = "Strings"

    /**
     * Module container
     */
    const val CONTAINER = "Container"

    /**
     * Embedded picture
     */
    const val PICTURE = "Picture"

    /**
     * Raw module size
     */
    const val SIZE = "Size"

    /**
     * Endl-separated channel names if muting is supported
     */
    const val CHANNELS_NAMES = "ChannelsNames"

    /**
     * Stored sound stream of sampled formats (see Module::ATTR_CODEC and next ones): codec name, sample rate in Hz,
     * bits per sample, channels count and average bitrate in bits per second
     */
    const val CODEC = "Codec"
    const val SAMPLERATE = "Samplerate"
    const val SAMPLE_BITS = "SampleBits"
    const val CHANNELS = "Channels"
    const val BITRATE = "Bitrate"

    val STREAM_NUMBERS = listOf(SAMPLERATE, SAMPLE_BITS, CHANNELS, BITRATE)
}
