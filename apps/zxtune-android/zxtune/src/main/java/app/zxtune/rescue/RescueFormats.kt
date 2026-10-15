package app.zxtune.rescue

import android.net.Uri
import android.util.Xml
import app.zxtune.playlist.xspf.Attributes
import app.zxtune.playlist.xspf.Meta
import app.zxtune.playlist.xspf.Properties
import app.zxtune.playlist.xspf.Tags
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import org.xmlpull.v1.XmlPullParser

class ExportEntry(
    val entry: RescueEntry,
    // absolute filesystem path of the data file, if local
    val path: String?,
    // path relative to the exported playlist's folder, if on the same volume
    val relativePath: String?,
)

// Unified result of parsing any supported playlist format
class ParsedEntry(
    // app identifier as stored in playlist (xspf location or m3u #ZXTUNE-ID)
    val id: String?,
    // filesystem path or url (m3u line or rescue meta)
    val target: String?,
    val title: String = "",
    val author: String = "",
    val durationMs: Long = 0,
)

/**
 * XSPF is the same as written by the app (playlist/xspf/Builder.kt) so regular "open with" import
 * understands it; the paths are added as <meta>, which the app's parser ignores.
 * M3U8 is for other players and manual recovery, app-specific data is kept in comments.
 */
object RescueFormats {
    private const val REL_BASE = "${Meta.APPLICATION}/rescue/"
    const val REL_PATH = "${REL_BASE}path"
    const val REL_RELATIVE = "${REL_BASE}relative"
    const val REL_COLUMN = "${REL_BASE}column/"
    private const val META = "meta"
    private const val REL = "rel"

    // escaped literal is turned into invisible character by formatter
    private val BOM = Char(0xFEFF)

    private const val M3U_HEADER = "#EXTM3U"
    private const val M3U_INFO = "#EXTINF:"
    private const val M3U_PLAYLIST = "#PLAYLIST:"
    const val M3U_ID = "#ZXTUNE-ID:"
    private const val M3U_TITLE = "#ZXTUNE-TITLE:"
    private const val M3U_AUTHOR = "#ZXTUNE-AUTHOR:"
    private const val M3U_DURATION = "#ZXTUNE-DURATION-MS:"
    private const val M3U_RELATIVE = "#ZXTUNE-RELATIVE:"

    fun writeXspf(output: OutputStream, name: String, entries: List<ExportEntry>) {
        val xml = Xml.newSerializer()
        xml.setOutput(output, Meta.ENCODING)
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        xml.startDocument(Meta.ENCODING, true)
        xml.startTag(null, Tags.PLAYLIST)
            .attribute(null, Attributes.VERSION, Meta.XSPF_VERSION)
            .attribute(null, Attributes.XMLNS, Meta.XMLNS)
        xml.startTag(null, Tags.EXTENSION).attribute(null, Attributes.APPLICATION, Meta.APPLICATION)
        mapOf(
            Properties.PLAYLIST_VERSION to Meta.VERSION.toString(),
            Properties.PLAYLIST_NAME to name,
            Properties.PLAYLIST_ITEMS to entries.size.toString(),
        ).forEach { (key, value) ->
            xml.startTag(null, Tags.PROPERTY).attribute(null, Attributes.NAME, key).text(value)
                .endTag(null, Tags.PROPERTY)
        }
        xml.endTag(null, Tags.EXTENSION)
        if (entries.isNotEmpty()) {
            xml.startTag(null, Tags.TRACKLIST)
            entries.forEach { exp ->
                val e = exp.entry
                xml.startTag(null, Tags.TRACK)
                fun tag(name: String, value: String?) {
                    if (!value.isNullOrEmpty()) {
                        xml.startTag(null, name).text(value).endTag(null, name)
                    }
                }

                fun meta(rel: String, value: String?) {
                    if (!value.isNullOrEmpty()) {
                        xml.startTag(null, META).attribute(null, REL, rel).text(value)
                            .endTag(null, META)
                    }
                }
                tag(Tags.LOCATION, e.location)
                tag(Tags.CREATOR, e.author)
                tag(Tags.TITLE, e.title)
                tag(Tags.DURATION, e.durationMs.toString())
                meta(REL_PATH, exp.path)
                meta(REL_RELATIVE, exp.relativePath)
                e.extras.toSortedMap().forEach { (key, value) -> meta(REL_COLUMN + key, value) }
                xml.endTag(null, Tags.TRACK)
            }
            xml.endTag(null, Tags.TRACKLIST)
        }
        xml.endTag(null, Tags.PLAYLIST)
        xml.endDocument()
        xml.flush()
    }

    fun writeM3u(output: OutputStream, name: String, entries: List<ExportEntry>) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("$M3U_HEADER\n")
        writer.write("$M3U_PLAYLIST${name.oneLine()}\n")
        entries.forEach { exp ->
            val e = exp.entry
            val seconds = if (e.durationMs > 0) (e.durationMs + 500) / 1000 else -1
            val display = listOf(e.author, e.title).filter { it.isNotBlank() }.joinToString(" - ")
            writer.write("\n$M3U_INFO$seconds,${display.oneLine()}\n")
            writer.write("$M3U_ID${e.location.oneLine()}\n")
            if (e.title.isNotEmpty()) writer.write("$M3U_TITLE${e.title.oneLine()}\n")
            if (e.author.isNotEmpty()) writer.write("$M3U_AUTHOR${e.author.oneLine()}\n")
            writer.write("$M3U_DURATION${e.durationMs}\n")
            exp.relativePath?.let { writer.write("$M3U_RELATIVE${it.oneLine()}\n") }
            // Absolute path is the most portable entry for other players, archives' subpaths and
            // remote sources are only meaningful for the app itself
            writer.write("${(exp.path ?: e.location).oneLine()}\n")
        }
        writer.flush()
    }

    private fun String.oneLine() = replace('\r', ' ').replace('\n', ' ')

    fun parse(input: InputStream, nameHint: String?): List<ParsedEntry> {
        val bytes = input.readBytes()
        val looksLikeXml = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
            .trimStart(BOM, ' ', '\t', '\r', '\n').startsWith("<")
        return if (looksLikeXml || nameHint?.endsWith(".xspf", ignoreCase = true) == true) {
            parseXspf(bytes.inputStream())
        } else {
            parseM3u(bytes.inputStream())
        }
    }

    fun parseM3u(input: InputStream): List<ParsedEntry> {
        val result = ArrayList<ParsedEntry>()
        var id: String? = null
        var title = ""
        var author = ""
        var durationMs = 0L
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).lineSequence().forEach { raw ->
            val line = raw.trim().trimStart(BOM)
            when {
                line.isEmpty() -> Unit

                line.startsWith(M3U_ID) -> id = line.substring(M3U_ID.length)

                line.startsWith(M3U_TITLE) -> title = line.substring(M3U_TITLE.length)

                line.startsWith(M3U_AUTHOR) -> author = line.substring(M3U_AUTHOR.length)

                line.startsWith(M3U_DURATION) -> line.substring(M3U_DURATION.length).toLongOrNull()
                    ?.let { durationMs = it }

                line.startsWith(M3U_INFO) -> {
                    val info = line.substring(M3U_INFO.length)
                    info.substringBefore(',').trim().toLongOrNull()?.takeIf { it > 0 }?.let {
                        durationMs = it * 1000
                    }
                    if (title.isEmpty()) {
                        title = info.substringAfter(',', "")
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    result.add(ParsedEntry(id, line, title, author, durationMs))
                    id = null
                    title = ""
                    author = ""
                    durationMs = 0
                }
            }
        }
        return result
    }

    // Same semantics as playlist/xspf/XspfIterator.kt, plus rescue metas
    fun parseXspf(input: InputStream): List<ParsedEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val result = ArrayList<ParsedEntry>()
        var escapedTexts = true
        var desktopPaths = false
        var inTrack = false
        var location: String? = null
        var path: String? = null
        var title = ""
        var author = ""
        var durationMs = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    Tags.PROPERTY -> {
                        val name = parser.getAttributeValue(null, Attributes.NAME)
                        val text = parser.nextText().trim()
                        when (name) {
                            Properties.PLAYLIST_VERSION ->
                                escapedTexts = (text.toIntOrNull() ?: 1) < 2

                            Properties.PLAYLIST_CREATOR -> desktopPaths = text.startsWith("zxtune-qt")
                        }
                    }

                    Tags.TRACK -> {
                        inTrack = true
                        location = null
                        path = null
                        title = ""
                        author = ""
                        durationMs = 0
                    }

                    Tags.LOCATION -> if (inTrack) location = parser.nextText().trim()

                    Tags.TITLE -> if (inTrack) title = decode(parser.nextText(), escapedTexts)

                    Tags.CREATOR -> if (inTrack) author = decode(parser.nextText(), escapedTexts)

                    Tags.DURATION -> if (inTrack) {
                        parser.nextText().trim().toLongOrNull()?.let { durationMs = it }
                    }

                    META -> if (inTrack && parser.getAttributeValue(null, REL) == REL_PATH) {
                        path = parser.nextText().trim()
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == Tags.TRACK) {
                inTrack = false
                val loc = location?.takeIf { it.isNotEmpty() }?.let {
                    if (desktopPaths) it.replace("?#", "#%23").replace('?', '#') else it
                }
                if (loc != null || path != null) {
                    result.add(ParsedEntry(loc, path, title, author, durationMs))
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun decode(str: String, escaped: Boolean) = if (escaped) Uri.decode(str) else str
}
