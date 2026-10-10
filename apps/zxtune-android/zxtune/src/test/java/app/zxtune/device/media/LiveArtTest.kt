package app.zxtune.device.media

import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveArtTest {

    @Test
    fun liveArt() {
        val base = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Title")
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "id")
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, "content://art")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 1000)
            .build()
        assertFalse(base.isLiveArtFrame())
        val art = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)
        with(StatusCallback.withLiveArt(base, art, 5)) {
            assertTrue(isLiveArtFrame())
            assertEquals(5L, getLong(StatusCallback.LIVE_ART_FRAME_KEY))
            assertSame(art, getBitmap(MediaMetadataCompat.METADATA_KEY_ART))
            assertSame(art, getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON))
            // original artwork reference is hidden to make hosts use the bitmap
            assertNull(getString(MediaMetadataCompat.METADATA_KEY_ART_URI))
            // everything else is kept
            assertEquals("Title", getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertEquals("id", description.mediaId)
            assertEquals(1000L, getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        }
        // base is not modified
        assertEquals("content://art", base.getString(MediaMetadataCompat.METADATA_KEY_ART_URI))
    }
}
