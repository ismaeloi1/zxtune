package app.zxtune.ui

import android.content.res.Resources
import android.support.v4.media.MediaMetadataCompat
import app.zxtune.R
import app.zxtune.core.ModuleAttributes
import app.zxtune.playback.StatusHeader

/**
 * Technical lines of now playing information: stored stream format (or chips emulation description) with output
 * rate after arrow, then the tune's lines of playback status (original driver, engine, timing), without diagnostics.
 */
internal object TrackInfoLines {
    private const val SEPARATOR = " · "
    private const val OUTPUT_PREFIX = "→ "

    // see Module::ATTR_CODEC
    private const val FLOAT_SUFFIX = " float"

    /**
     * @param status playback status, see [app.zxtune.playback.Visualizer.getStatus], empty if unknown
     */
    fun build(res: Resources, metadata: MediaMetadataCompat?, status: String): List<String> {
        val lines = status.lines().filter { it.isNotBlank() && !StatusHeader.isStatistics(it) }
        // the first line is emulation description if not a header one
        val description = lines.firstOrNull()?.takeUnless { StatusHeader.isHeader(it) }
        // language neutral and short to keep the line within the phone's width
        val output = StatusHeader.outputRateOf(status).takeIf { it > 0 }?.let { "$OUTPUT_PREFIX${formatRate(it)}" }
        val stream = metadata?.let { streamOf(res, it) }
        val first = listOfNotNull(stream ?: description, output).joinToString(" ")
        return listOfNotNull(
            first.takeIf { it.isNotEmpty() },
            description.takeIf { stream != null },
        ) + lines.filter { it !== description }
    }

    /**
     * E.g. "FLAC · 44.1 kHz · 16 bit · stereo · 912 kbps", null for not sampled formats
     */
    fun streamOf(res: Resources, metadata: MediaMetadataCompat): String? {
        val codec = metadata.getString(ModuleAttributes.CODEC)?.takeIf { it.isNotBlank() } ?: return null
        fun number(key: String) = metadata.getLong(key).takeIf { it > 0 }
        val bits = number(ModuleAttributes.SAMPLE_BITS)?.toInt()
        // floating point is told by bit depth, e.g. "PCM · 32 bit float"
        val isFloat = bits != null && codec.endsWith(FLOAT_SUFFIX)
        return listOfNotNull(
            if (isFloat) codec.removeSuffix(FLOAT_SUFFIX) else codec,
            number(ModuleAttributes.SAMPLERATE)?.let { formatRate(it.toInt()) },
            bits?.let { formatBits(res, it, isFloat) },
            number(ModuleAttributes.CHANNELS)?.let { formatChannels(res, it.toInt()) },
            number(ModuleAttributes.BITRATE)?.let { formatBitrate(it) },
        ).joinToString(SEPARATOR)
    }

    /**
     * Bit depth of [codec] stream, e.g. "24 bit" or "32 bit float"
     */
    fun formatBits(res: Resources, codec: String?, bits: Int) = formatBits(res, bits, codec?.endsWith(FLOAT_SUFFIX) == true)

    private fun formatBits(res: Resources, bits: Int, isFloat: Boolean) = res.getString(if (isFloat) R.string.info_sample_bits_float else R.string.info_sample_bits, bits)

    fun formatChannels(res: Resources, channels: Int): String = when (channels) {
        1 -> res.getString(R.string.info_channels_mono)
        2 -> res.getString(R.string.info_channels_stereo)
        else -> res.getString(R.string.info_channels_count, channels)
    }

    // 44100 -> "44.1 kHz", 48000 -> "48 kHz", 22050 -> "22.05 kHz"
    fun formatRate(hz: Int): String {
        val khz = hz / 1000
        val rest = (hz % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (rest.isEmpty()) "$khz kHz" else "$khz.$rest kHz"
    }

    fun formatBitrate(bps: Long) = "${(bps + 500) / 1000} kbps"
}
