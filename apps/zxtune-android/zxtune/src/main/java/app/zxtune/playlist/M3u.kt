/**
 * @file
 * @brief Extended M3U import and export of playlists
 */
package app.zxtune.playlist

import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
import java.io.InputStream
import java.io.OutputStream

object M3u {
    // #EXTINF:seconds,Author - Title then location (full identifier, so archives and online catalogs are kept)
    fun write(out: OutputStream, items: List<Item>) = out.bufferedWriter(Charsets.UTF_8).run {
        write("#EXTM3U\n")
        for (item in items) {
            val seconds = item.duration.toMilliseconds() / 1000
            val name = listOf(item.author, item.title).filter { it.isNotEmpty() }.joinToString(" - ")
            write("#EXTINF:$seconds,$name\n")
            write(item.location.toString())
            write("\n")
        }
        flush()
    }

    fun read(input: InputStream): List<Item> = ArrayList<Item>().apply {
        var title = ""
        var author = ""
        var seconds = 0L
        input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val line = raw.trim().removePrefix("")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substring(8)
                    seconds = info.substringBefore(',').trim().toLongOrNull()?.coerceAtLeast(0) ?: 0
                    val name = info.substringAfter(',', "")
                    author = name.substringBefore(" - ", "").trim()
                    title = (if (name.contains(" - ")) name.substringAfter(" - ") else name).trim()
                }

                line.startsWith("#") -> Unit

                else -> {
                    add(Item(toIdentifier(line), title, author, TimeStamp.fromSeconds(seconds)))
                    title = ""
                    author = ""
                    seconds = 0
                }
            }
        }
    }

    // plain paths of other players are local files
    private fun toIdentifier(line: String) = if (line.startsWith("/")) {
        Identifier.parse("file://$line")
    } else {
        Identifier.parse(line)
    }
}
