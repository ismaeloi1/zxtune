package app.zxtune.device.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.RepeatMode
import android.support.v4.media.session.PlaybackStateCompat.ShuffleMode
import app.zxtune.Logger
import app.zxtune.MainService
import app.zxtune.R
import app.zxtune.Releaseable
import app.zxtune.ScanService
import app.zxtune.TimeStamp.Companion.fromMilliseconds
import app.zxtune.core.PropertiesAccessor
import app.zxtune.core.PropertiesModifier
import app.zxtune.fs.Vfs
import app.zxtune.fs.VfsFile
import app.zxtune.playback.PlaybackControl
import app.zxtune.playback.service.PlaybackServiceLocal
import app.zxtune.playback.stubs.PlayableItemStub
import app.zxtune.playlist.PlaylistQuery
import app.zxtune.playlist.ProviderClient
import app.zxtune.preferences.RawPropertiesAdapter
import app.zxtune.utils.getParcelableArrayCompat
import app.zxtune.utils.getUntyped
import app.zxtune.utils.ifNotNulls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class ControlCallback(
    private val ctx: Context,
    private val svc: PlaybackServiceLocal,
    private val status: StatusCallback,
) : MediaSessionCompat.Callback(),
    Releaseable {
    // Session callbacks are called on main thread, so database queries are moved out of it
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ctrl
        get() = svc.playbackControl
    private val seek
        get() = svc.seekControl

    override fun onPlay() = ctrl.play()

    override fun onPause() = onStop()

    override fun onStop() = ctrl.stop()

    override fun onSkipToPrevious() = ctrl.prev()

    override fun onSkipToNext() = ctrl.next()

    override fun onSeekTo(ms: Long) {
        try {
            seek.position = fromMilliseconds(ms)
        } catch (e: Exception) {
            LOG.w(e) { "Failed to seek" }
        }
    }

    override fun onCustomAction(action: String, extra: Bundle?) = when (action) {
        MainService.CUSTOM_ACTION_ADD_CURRENT -> addCurrent()

        MainService.CUSTOM_ACTION_ADD -> extra?.getParcelableArrayCompat<Uri>("uris")?.let {
            ScanService.add(ctx, Array(it.size) { idx -> it[idx] as Uri })
        } ?: Unit

        StatusCallback.CUSTOM_ACTION_TOGGLE_SHUFFLE -> {
            ctrl.sequenceMode = if (ctrl.sequenceMode == PlaybackControl.SequenceMode.SHUFFLE) {
                PlaybackControl.SequenceMode.ORDERED
            } else {
                PlaybackControl.SequenceMode.SHUFFLE
            }
            status.updateModes()
        }

        StatusCallback.CUSTOM_ACTION_TOGGLE_REPEAT -> {
            ctrl.trackMode = if (ctrl.trackMode == PlaybackControl.TrackMode.LOOPED) {
                PlaybackControl.TrackMode.REGULAR
            } else {
                PlaybackControl.TrackMode.LOOPED
            }
            status.updateModes()
        }

        else -> Unit
    }

    override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) = when (command) {
        MainService.COMMAND_SET_CURRENT_PARAMETERS -> ifNotNulls(
            extras,
            svc.playbackProperties
        ) { data, props ->
            setProperties(data, props)
            cb?.send(0, data)
        } ?: cb?.send(-1, null) ?: Unit

        MainService.COMMAND_GET_CURRENT_PARAMETERS -> ifNotNulls(
            extras,
            svc.playbackProperties,
            cb
        ) { query, props, receiver ->
            getProperties(props, query)
            receiver.send(0, query)
        } ?: cb?.send(-1, null) ?: Unit

        else -> Unit
    }

    private fun addCurrent() = svc.nowPlaying.takeIf { it !== PlayableItemStub.instance() }?.let {
        ScanService.add(ctx, it)
    } ?: Unit

    override fun onSetShuffleMode(@ShuffleMode mode: Int) = fromShuffleMode(mode)?.let {
        ctrl.sequenceMode = it
        status.updateModes()
    } ?: Unit

    override fun onSetRepeatMode(@RepeatMode mode: Int) = fromRepeatMode(mode)?.let {
        ctrl.trackMode = it
        status.updateModes()
    } ?: Unit

    override fun onPlayFromUri(uri: Uri, extras: Bundle?) = svc.setNowPlaying(uri)

    // Queue is the playlist, see BrowseTree.getQueue
    override fun onSkipToQueueItem(id: Long) = svc.setNowPlaying(PlaylistQuery.uriFor(id))

    // Called by external browsers (Android Auto etc) with ids from BrowseTree
    override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
        val uri = BrowseTree.getPlayableUri(mediaId)
        when {
            uri == null -> reportUnsupported(mediaId)

            BrowseTree.getVfsUri(mediaId) == null -> svc.setNowPlaying(uri)

            // resolving may require network access
            else -> scope.launch {
                val isFile = runCatching { Vfs.resolve(uri) is VfsFile }.onFailure {
                    LOG.w(it) { "Failed to resolve $uri" }
                }.getOrDefault(false)
                if (isFile) {
                    svc.setNowPlaying(uri)
                } else {
                    reportUnsupported(mediaId)
                }
            }
        }
    }

    private fun reportUnsupported(mediaId: String) {
        LOG.d { "Ignore unsupported media id '$mediaId'" }
        status.reportError(
            PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED,
            ctx.getString(R.string.car_error_unsupported)
        )
    }

    // Voice requests like "play <title> on zxtune". Empty query means "play anything".
    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)?.takeIf { it.isNotBlank() }
        val filter = title ?: query?.takeIf { it.isNotBlank() }
        scope.launch {
            val content = runCatching {
                ProviderClient.create(ctx).queryContent()
            }.onFailure {
                LOG.w(it) { "Failed to search in playlist" }
            }.getOrNull().orEmpty()
            when {
                content.isEmpty() -> status.reportError(
                    PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE,
                    ctx.getString(R.string.car_error_empty_playlist)
                )

                filter == null -> svc.setNowPlaying(PlaylistQuery.ALL)

                else -> BrowseTree.search(content, filter).firstOrNull()?.let {
                    svc.setNowPlaying(BrowseTree.getPlayableUri(it))
                } ?: run {
                    LOG.d { "Nothing found for '$filter'" }
                    status.reportError(
                        PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR,
                        ctx.getString(R.string.car_error_not_found, filter)
                    )
                }
            }
        }
    }

    override fun release() = scope.cancel()

    companion object {
        private val LOG = Logger(ControlCallback::class.java.name)

        private fun setProperties(src: Bundle, props: PropertiesModifier) = with(RawPropertiesAdapter(props)) {
            src.keySet().forEach { key ->
                src.getUntyped(key)?.let {
                    LOG.d { "set prop[$key]=$it" }
                    setProperty(key, it)
                }
            }
        }

        private fun getProperties(src: PropertiesAccessor, data: Bundle) = data.keySet().forEach { key ->
            copyProperty(src, key, data)
        }

        private fun copyProperty(src: PropertiesAccessor, key: String, data: Bundle) = when (val obj = data.getUntyped(key)) {
            is String -> src.getProperty(key, obj).let {
                LOG.d { "get prop[$key, $obj]=$it" }
                data.putString(key, it)
            }

            is Long -> src.getProperty(key, obj).let {
                LOG.d { "get prop[$key, $obj]=$it" }
                data.putLong(key, it)
            }

            is Int -> src.getProperty(key, obj.toLong()).let {
                LOG.d { "get prop[$key, $obj]=$it" }
                data.putInt(key, it.toInt())
            }

            else -> Unit
        }
    }
}
