package app.zxtune.ui

import android.support.v4.media.MediaMetadataCompat
import androidx.test.core.app.ApplicationProvider
import app.zxtune.core.ModuleAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TrackInfoLinesTest {
    private val res = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    @Test
    fun rates() {
        assertEquals("44.1 kHz", TrackInfoLines.formatRate(44100))
        assertEquals("48 kHz", TrackInfoLines.formatRate(48000))
        assertEquals("22.05 kHz", TrackInfoLines.formatRate(22050))
        assertEquals("8 kHz", TrackInfoLines.formatRate(8000))
        assertEquals("912 kbps", TrackInfoLines.formatBitrate(912_345))
    }

    @Test
    fun sampled() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(ModuleAttributes.CODEC, "FLAC")
            .putLong(ModuleAttributes.SAMPLERATE, 44100)
            .putLong(ModuleAttributes.SAMPLE_BITS, 16)
            .putLong(ModuleAttributes.CHANNELS, 2)
            .putLong(ModuleAttributes.BITRATE, 912_345)
            .build()
        val status = "Original driver: unknown\nRunning on libFLAC\noutput: 48000Hz buffer=160ms\nrender: 2400% realtime"
        assertEquals(
            listOf(
                "FLAC · 44.1 kHz · 16 bit · stereo · 912 kbps → 48 kHz",
                "Original driver: unknown",
                "Running on libFLAC",
            ),
            TrackInfoLines.build(res, metadata, status),
        )
    }

    @Test
    fun floatPcm() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(ModuleAttributes.CODEC, "PCM float")
            .putLong(ModuleAttributes.SAMPLERATE, 96000)
            .putLong(ModuleAttributes.SAMPLE_BITS, 32)
            .putLong(ModuleAttributes.CHANNELS, 6)
            .build()
        assertEquals("PCM · 96 kHz · 32 bit float · 6 ch", TrackInfoLines.streamOf(res, metadata))
        assertEquals("32 bit float", TrackInfoLines.formatBits(res, "PCM float", 32))
        assertEquals("12 bit", TrackInfoLines.formatBits(res, "PCM", 12))
        // no bit depth for lossy codecs
        val adpcm = MediaMetadataCompat.Builder()
            .putString(ModuleAttributes.CODEC, "Nintendo DSP 4-bit ADPCM")
            .putLong(ModuleAttributes.SAMPLERATE, 32000)
            .putLong(ModuleAttributes.CHANNELS, 2)
            .putLong(ModuleAttributes.BITRATE, 287_000)
            .build()
        assertEquals("Nintendo DSP 4-bit ADPCM · 32 kHz · stereo · 287 kbps", TrackInfoLines.streamOf(res, adpcm))
    }

    @Test
    @Config(qualifiers = "fr")
    fun sampledFrench() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(ModuleAttributes.CODEC, "MP3")
            .putLong(ModuleAttributes.SAMPLERATE, 32000)
            .putLong(ModuleAttributes.CHANNELS, 1)
            .putLong(ModuleAttributes.BITRATE, 64000)
            .build()
        assertEquals("MP3 · 32 kHz · mono · 64 kbps", TrackInfoLines.streamOf(res, metadata))
        assertEquals("6 canaux", TrackInfoLines.formatChannels(res, 6))
        assertEquals("24 bits", TrackInfoLines.formatBits(res, "FLAC", 24))
        assertEquals("32 bits flottant", TrackInfoLines.formatBits(res, "PCM float", 32))
    }

    @Test
    fun chips() {
        val metadata = MediaMetadataCompat.Builder().putString(ModuleAttributes.TYPE, "SID").build()
        assertNull(TrackInfoLines.streamOf(res, metadata))
        val status = "PAL, MOS6581, reSIDfp, resample\nOriginal driver: CheeseCutter 2.x\nRunning on NebulaWeave3\n" +
            "timing: VBI 50.1 Hz (PAL), speed 1x, song 1/1\nscope: 3/3 ch, trigger 4488us\n" +
            "output: 48000Hz buffer=160ms sync=timestamp\nrender: 152% realtime"
        assertEquals(
            listOf(
                "PAL, MOS6581, reSIDfp, resample → 48 kHz",
                "Original driver: CheeseCutter 2.x",
                "Running on NebulaWeave3",
                "timing: VBI 50.1 Hz (PAL), speed 1x, song 1/1",
            ),
            TrackInfoLines.build(res, metadata, status),
        )
        // nothing is known before playback
        assertEquals(emptyList<String>(), TrackInfoLines.build(res, metadata, ""))
    }
}
