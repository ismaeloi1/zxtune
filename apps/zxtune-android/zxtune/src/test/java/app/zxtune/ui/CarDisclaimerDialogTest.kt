package app.zxtune.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CarDisclaimerDialogTest {
    @Test
    fun countdown() {
        val deadline = 20_000L
        assertEquals(15, CarDisclaimerDialog.remainingSeconds(deadline, 5_000L))
        assertEquals(15, CarDisclaimerDialog.remainingSeconds(deadline, 5_001L))
        assertEquals(1, CarDisclaimerDialog.remainingSeconds(deadline, 19_999L))
        assertEquals(0, CarDisclaimerDialog.remainingSeconds(deadline, 20_000L))
        assertEquals(0, CarDisclaimerDialog.remainingSeconds(deadline, 30_000L))
    }
}
