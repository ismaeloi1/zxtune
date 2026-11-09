package app.zxtune.ui.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTextsTest {

    @Test
    fun `text is rebuilt only on key change`() {
        val texts = LiveTexts(1)
        assertTrue(texts.changed(0, 5))
        texts[0] = "5"
        val first = texts[0]
        assertFalse(texts.changed(0, 5))
        assertSame(first, texts[0])
        assertTrue(texts.changed(0, 6))
    }
}
