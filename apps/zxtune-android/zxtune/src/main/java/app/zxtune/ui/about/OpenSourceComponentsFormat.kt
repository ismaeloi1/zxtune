package app.zxtune.ui.about

object OpenSourceComponentsFormat {
    private val TITLES = mapOf(
        OpenSourceComponents.Category.PLAYBACK to "Playback engines",
        OpenSourceComponents.Category.CODECS to "Codecs",
        OpenSourceComponents.Category.SYSTEM to "Archives and system",
        OpenSourceComponents.Category.ANDROID to "Android libraries",
        OpenSourceComponents.Category.REFERENCE to "Algorithms and data references",
    )

    // two lines per component: name with authors, license with link
    fun toHtml(components: List<OpenSourceComponents.Component>) = buildString {
        components.groupBy { it.category }.forEach { (category, list) ->
            if (isNotEmpty()) {
                append("<br/>")
            }
            append("<big><b>").append(TITLES.getValue(category)).append("</b></big><br/>")
            list.forEach { c ->
                append("<b>").append(escape(c.name)).append("</b> &#8212; ").append(escape(c.authors))
                append("<br/>&#160;&#160;").append(escape(c.license)).append(" &#183; ")
                append("<a href=\"").append(c.url).append("\">")
                append(escape(c.url.removePrefix("https://")))
                append("</a><br/>")
            }
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
