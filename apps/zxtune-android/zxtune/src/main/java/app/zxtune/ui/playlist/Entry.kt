package app.zxtune.ui.playlist

import app.zxtune.TimeStamp
import app.zxtune.Util
import app.zxtune.core.Identifier
import app.zxtune.ui.ChipFamily
import app.zxtune.ui.fallbackBadgeLabel
import app.zxtune.ui.tuneFilename

// public for binding
data class Entry(
    val id: Long,
    val location: Identifier,
    val title: String,
    val author: String,
    val duration: TimeStamp,
) {
    val displayTitle
        get() = Util.formatTrackTitle(title, location)

    // playlist does not store module type, so family is guessed by the name of the file
    val family
        get() = ChipFamily.ofFilename(location.tuneFilename)

    val badgeFallback
        get() = fallbackBadgeLabel(location.tuneFilename)
}
