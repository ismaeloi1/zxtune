package app.zxtune.device.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import app.zxtune.R
import app.zxtune.playlist.PlaylistQuery
import app.zxtune.ui.playlist.Entry

/**
 * Media browser hierarchy exposed to external clients (Android Auto etc).
 *
 * Kept intentionally shallow and backed by the playlist only: VFS roots are mostly remote
 * archives with deep trees and network latency, which is not suitable for driving.
 *
 * Playable items ids are playlist item uris, so they can be passed directly to
 * [app.zxtune.playback.service.PlaybackServiceLocal.setNowPlaying] and the whole playlist
 * becomes the playback sequence (next/prev work the same way as from the phone UI).
 */
object BrowseTree {
    const val ROOT_ID = "root"
    const val PLAYLIST_ID = "playlist"

    fun createRootExtras() = Bundle().apply {
        putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
        // Track titles are often long and there are no per-item artworks, so grid is useless
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
    }

    fun getRootChildren(ctx: Context) = listOf(
        MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(PLAYLIST_ID)
                .setTitle(ctx.getString(R.string.playlist)).build(),
            MediaItem.FLAG_BROWSABLE
        )
    )

    // Whole list is sent via binder at once, so huge playlists cause
    // TransactionTooLargeException. Car UIs truncate long lists anyway.
    internal const val MAX_ITEMS = 500

    fun getPlaylistChildren(content: List<Entry>) = content.asSequence().take(MAX_ITEMS)
        .map(::createItem).toList()

    fun search(content: List<Entry>, query: String) = content.filter { it.matches(query) }

    // Only playlist items are accepted to avoid playing arbitrary uris from external clients
    fun getPlayableUri(mediaId: String): Uri? = runCatching {
        Uri.parse(mediaId).takeIf {
            PlaylistQuery.isPlaylistUri(it) && PlaylistQuery.idOf(it) != null
        }
    }.getOrNull()

    fun getPlayableUri(entry: Entry): Uri = PlaylistQuery.uriFor(entry.id)

    private fun createItem(entry: Entry) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(getPlayableUri(entry).toString())
            .setTitle(entry.displayTitle).setSubtitle(entry.author.takeIf { it.isNotEmpty() })
            .build(),
        MediaItem.FLAG_PLAYABLE
    )

    private fun Entry.matches(query: String) = displayTitle.contains(query, ignoreCase = true) || author.contains(
        query,
        ignoreCase = true
    )
}
