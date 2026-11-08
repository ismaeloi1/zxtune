package app.zxtune.ui.about

object OpenSourceComponentsFormat {
    private val TITLES = mapOf(
        OpenSourceComponents.Category.PLAYBACK to "Playback engines",
        OpenSourceComponents.Category.CODECS to "Codecs",
        OpenSourceComponents.Category.SYSTEM to "Archives and system",
        OpenSourceComponents.Category.ANDROID to "Android libraries",
        OpenSourceComponents.Category.REFERENCE to "Algorithms and data references",
    )

    fun title(category: OpenSourceComponents.Category) = TITLES.getValue(category)

    // scheme is the same for all, so it's skipped to fit narrow screens
    fun link(component: OpenSourceComponents.Component) = component.url.removePrefix("https://")

    // short name for chips, e.g. "libsidplayfp" for "libsidplayfp, reSID, reSIDfp"
    fun shortName(component: OpenSourceComponents.Component) = component.name.substringBefore(',').substringBefore(" (").substringBefore(" and ").trim()
}
