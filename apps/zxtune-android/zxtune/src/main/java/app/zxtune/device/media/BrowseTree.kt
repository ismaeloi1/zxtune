package app.zxtune.device.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.DrawableRes
import androidx.media.utils.MediaConstants
import app.zxtune.R
import app.zxtune.Util
import app.zxtune.fs.DefaultComparator
import app.zxtune.fs.VfsDir
import app.zxtune.fs.VfsFile
import app.zxtune.fs.VfsObject
import app.zxtune.fs.comparator
import app.zxtune.fs.icon
import app.zxtune.playback.Item
import app.zxtune.playlist.PlaylistQuery
import app.zxtune.ui.playlist.Entry

/**
 * Media browser hierarchy exposed to external clients (Android Auto etc).
 *
 * Root contains playlist (flat list of items) and browser (VFS hierarchy, the same as on phone).
 * Separate recent root is provided for playback resumption (System UI, Android Auto start).
 *
 * Playlist items ids are playlist item uris, so they can be passed directly to
 * [app.zxtune.playback.service.PlaybackServiceLocal.setNowPlaying] and the whole playlist
 * becomes the playback sequence (next/prev work the same way as from the phone UI).
 * VFS objects ids are prefixed uris to distinguish them from the fixed nodes and playlist items;
 * playing VFS file iterates over its directory as on phone.
 */
object BrowseTree {
    const val ROOT_ID = "root"
    const val PLAYLIST_ID = "playlist"
    const val BROWSER_ID = "browser"
    const val RECENT_ROOT_ID = "recent"
    private const val VFS_PREFIX = "vfs/"

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
        createBrowsable(ctx, PLAYLIST_ID, ctx.getString(R.string.playlist), R.drawable.ic_launcher),
        createBrowsable(ctx, BROWSER_ID, ctx.getString(R.string.browser), R.drawable.ic_browser_folder),
    )

    private fun createBrowsable(ctx: Context, id: String, title: String, @DrawableRes icon: Int) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title)
            .setIconUri(resourceUri(ctx, icon)).build(),
        MediaItem.FLAG_BROWSABLE
    )

    // Whole list is sent via binder at once, so huge playlists cause
    // TransactionTooLargeException. Car UIs truncate long lists anyway.
    internal const val MAX_ITEMS = 500

    fun getPlaylistChildren(content: List<Entry>) = content.asSequence().take(MAX_ITEMS)
        .map(::createItem).toList()

    fun search(content: List<Entry>, query: String) = content.filter { it.matches(query) }

    /**
     * Uri to pass to playback service, only playlist items and VFS objects are accepted to avoid
     * playing arbitrary uris (e.g. content of other providers) from external clients
     */
    fun getPlayableUri(mediaId: String): Uri? = getPlaylistUri(mediaId) ?: getVfsUri(mediaId)

    private fun getPlaylistUri(mediaId: String) = runCatching {
        Uri.parse(mediaId).takeIf {
            PlaylistQuery.isPlaylistUri(it) && PlaylistQuery.idOf(it) != null
        }
    }.getOrNull()

    fun getPlayableUri(entry: Entry): Uri = PlaylistQuery.uriFor(entry.id)

    /**
     * @return VFS object uri for [VfsObject] media ids, null otherwise
     */
    fun getVfsUri(mediaId: String): Uri? = mediaId.takeIf { it.startsWith(VFS_PREFIX) }?.let {
        runCatching { Uri.parse(it.substring(VFS_PREFIX.length)) }.getOrNull()
    }?.takeIf { !it.scheme.isNullOrEmpty() && !PlaylistQuery.isPlaylistUri(it) }

    fun getVfsMediaId(uri: Uri) = VFS_PREFIX + uri.toString()

    /**
     * Directories first, then files, both sorted the same way as in phone browser
     */
    fun getVfsChildren(ctx: Context, dir: VfsDir, dirs: List<VfsDir>, files: List<VfsFile>): List<MediaItem> {
        val comparator = dir.comparator ?: DefaultComparator
        return (dirs.sortedWith(comparator).asSequence() + files.sortedWith(comparator).asSequence())
            .take(MAX_ITEMS).map { createVfsItem(ctx, it) }.toList()
    }

    fun createVfsItem(ctx: Context, obj: VfsObject) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(getVfsMediaId(obj.uri))
            .setTitle(obj.name).setSubtitle(obj.description.takeIf { it.isNotEmpty() })
            .setIconUri(obj.icon?.let { resourceUri(ctx, it) }).build(),
        if (obj is VfsDir) MediaItem.FLAG_BROWSABLE else MediaItem.FLAG_PLAYABLE
    )

    /**
     * Last played item for playback resumption, empty if nothing was played
     */
    fun getRecentChildren(item: Item?): List<MediaItem> {
        val id = item?.id?.takeIf { it != Uri.EMPTY } ?: return emptyList()
        val mediaId = if (PlaylistQuery.isPlaylistUri(id)) id.toString() else getVfsMediaId(id)
        return listOf(
            MediaItem(
                MediaDescriptionCompat.Builder().setMediaId(mediaId)
                    .setTitle(Util.formatTrackTitle(item.title, item.dataId))
                    .setSubtitle(item.author.takeIf { it.isNotEmpty() }).build(),
                MediaItem.FLAG_PLAYABLE
            )
        )
    }

    /**
     * Queue is the playlist, items ids are playlist ids
     */
    fun getQueue(content: List<Entry>) = content.asSequence().take(MAX_ITEMS).map {
        MediaSessionCompat.QueueItem(
            MediaDescriptionCompat.Builder().setMediaId(getPlayableUri(it).toString())
                .setTitle(it.displayTitle).setSubtitle(it.author.takeIf { a -> a.isNotEmpty() })
                .build(),
            it.id
        )
    }.toList()

    fun getQueueId(itemId: Uri) = itemId.takeIf { PlaylistQuery.isPlaylistUri(it) }?.let {
        PlaylistQuery.idOf(it)
    } ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()

    // Car hosts require resource uris with package authority
    private fun resourceUri(ctx: Context, @DrawableRes res: Int): Uri? = runCatching {
        val resources = ctx.resources
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(resources.getResourcePackageName(res))
            .appendPath(resources.getResourceTypeName(res))
            .appendPath(resources.getResourceEntryName(res))
            .build()
    }.getOrNull()

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
