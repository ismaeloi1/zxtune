package app.zxtune.playlist

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import app.zxtune.TimeStamp
import app.zxtune.analytics.Analytics
import app.zxtune.core.Identifier
import app.zxtune.ui.playlist.Entry
import app.zxtune.ui.utils.observeChanges
import app.zxtune.ui.utils.query
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

class PlaylistContent(size: Int) : ArrayList<Entry>(size)

class ProviderClient @VisibleForTesting constructor(
    private val resolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher,
) {
    // should be name-compatible with Database
    enum class SortBy {
        title,
        author,
        duration
    }

    enum class SortOrder {
        asc,
        desc
    }

    // ! @param list named list id, current playlist if null
    fun addItem(item: Item, list: Long? = null) {
        val uri = list?.let {
            PlaylistQuery.ALL.buildUpon().appendQueryParameter(Provider.PARAM_LIST, it.toString()).build()
        } ?: PlaylistQuery.ALL
        resolver.insert(uri, item.toContentValues())
    }

    class ListInfo(val id: Long, val name: String, val isFavorites: Boolean, val count: Int, val isActive: Boolean)

    private fun callLists(method: String, arg: String? = null, extras: Bundle? = null) = resolver.call(PlaylistQuery.ALL, Provider.LISTS_PREFIX + method, arg, extras)

    suspend fun lists() = withContext(dispatcher) {
        callLists("all")?.run {
            val ids = getLongArray("ids") ?: LongArray(0)
            val names = getStringArray("names") ?: emptyArray()
            val kinds = getIntArray("kinds") ?: IntArray(0)
            val counts = getIntArray("counts") ?: IntArray(0)
            val active = getLong("active")
            List(ids.size) { idx ->
                ListInfo(ids[idx], names[idx], kinds[idx] == Lists.KIND_FAVORITES, counts[idx], ids[idx] == active)
            }
        } ?: emptyList()
    }

    @OptIn(FlowPreview::class)
    fun observeLists() = resolver.observeChanges(PlaylistQuery.ALL).debounce(300).transform {
        emit(lists())
    }

    suspend fun createList(name: String) = withContext(dispatcher) {
        callLists("create", name)?.getLong("id")
    }

    suspend fun renameList(id: Long, name: String) = withContext(dispatcher) {
        callLists("rename", name, bundleOf("id" to id))
        Unit
    }

    suspend fun deleteList(id: Long) = withContext(dispatcher) {
        callLists("delete", id.toString())
        Unit
    }

    suspend fun duplicateList(id: Long, name: String) = withContext(dispatcher) {
        callLists("duplicate", name, bundleOf("id" to id))?.getLong("id")
    }

    suspend fun switchList(id: Long) = withContext(dispatcher) {
        callLists("switch", id.toString())
        Unit
    }

    suspend fun favoritesList() = withContext(dispatcher) {
        callLists("favorites")?.getLong("id")
    }

    suspend fun isFavorite(location: Identifier) = withContext(dispatcher) {
        callLists("is_favorite", location.toString())?.getBoolean("result") ?: false
    }

    // ! @return true if became favorite
    suspend fun toggleFavorite(item: Item) = withContext(dispatcher) {
        callLists("toggle_favorite", null, bundleOf("values" to item.toContentValues()))?.getBoolean("result") ?: false
    }

    fun notifyChanges() = resolver.notifyChange(PlaylistQuery.ALL, null)

    @OptIn(FlowPreview::class)
    fun observeContent() = resolver.observeChanges(PlaylistQuery.ALL).debounce(1000).transform {
        queryContent()?.let {
            emit(it)
        }
    }

    suspend fun queryContent() = resolver.query(PlaylistQuery.ALL) { cursor ->
        PlaylistContent(cursor.count).apply {
            while (cursor.moveToNext()) {
                add(createItem(cursor))
            }
        }
    }

    suspend fun delete(ids: LongArray) {
        deleteItems(PlaylistQuery.selectionFor(ids))
        Analytics.sendPlaylistEvent(Analytics.PlaylistAction.DELETE, ids.size)
    }

    suspend fun deleteAll() {
        deleteItems(null)
        Analytics.sendPlaylistEvent(Analytics.PlaylistAction.DELETE, 0)
    }

    private suspend fun deleteItems(selection: String?) = withContext(dispatcher) {
        resolver.delete(PlaylistQuery.ALL, selection, null)
        notifyChanges()
    }

    suspend fun move(id: Long, delta: Int) = withContext(dispatcher) {
        Provider.move(resolver, id, delta)
        notifyChanges()
        Analytics.sendPlaylistEvent(Analytics.PlaylistAction.MOVE, 1)
    }

    suspend fun sort(by: SortBy, order: SortOrder) = withContext(dispatcher) {
        Provider.sort(resolver, by.name, order.name)
        notifyChanges()
        Analytics.sendPlaylistEvent(
            Analytics.PlaylistAction.SORT,
            100 * by.ordinal + order.ordinal
        )
    }

    suspend fun statistics(ids: LongArray?) = withContext(dispatcher) {
        resolver.query(
            PlaylistQuery.STATISTICS,
            selection = PlaylistQuery.selectionFor(ids)
        ) { cursor ->
            if (cursor.moveToFirst()) {
                Statistics(cursor)
            } else {
                null
            }
        }
    }

    // id => path
    suspend fun getSavedPlaylists(id: String? = null) = withContext(dispatcher) {
        resolver.query(
            PlaylistQuery.SAVED,
            selection = id
        ) { cursor ->
            HashMap<String, String>().apply {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getString(1))
                }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun savePlaylist(id: String, ids: LongArray?) = withContext(dispatcher) {
        Provider.save(resolver, id, ids)
        Analytics.sendPlaylistEvent(Analytics.PlaylistAction.SAVE, ids?.size ?: 0)
    }

    companion object {
        private fun createItem(cursor: Cursor) = cursor.run {
            Entry(
                getLong(Database.Tables.Playlist.Fields._id.ordinal),
                Identifier.parse(getString(Database.Tables.Playlist.Fields.location.ordinal)),
                getString(Database.Tables.Playlist.Fields.title.ordinal),
                getString(Database.Tables.Playlist.Fields.author.ordinal),
                TimeStamp.fromMilliseconds(getLong(Database.Tables.Playlist.Fields.duration.ordinal))
            )
        }

        @JvmStatic
        fun create(ctx: Context) = ProviderClient(ctx.contentResolver, Dispatchers.IO)

        fun createUri(id: Long): Uri = PlaylistQuery.uriFor(id)

        @JvmStatic
        fun findId(uri: Uri) = if (PlaylistQuery.isPlaylistUri(uri)) PlaylistQuery.idOf(uri) else null
    }
}
