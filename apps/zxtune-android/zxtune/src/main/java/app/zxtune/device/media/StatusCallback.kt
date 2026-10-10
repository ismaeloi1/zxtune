package app.zxtune.device.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import android.widget.Toast
import app.zxtune.Logger
import app.zxtune.R
import app.zxtune.TimeStamp
import app.zxtune.Util
import app.zxtune.core.Identifier
import app.zxtune.core.ModuleAttributes
import app.zxtune.coverart.BitmapLoader
import app.zxtune.coverart.CoverartProviderClient
import app.zxtune.fs.Vfs
import app.zxtune.fs.VfsExtensions
import app.zxtune.fs.shareUrl
import app.zxtune.playback.Callback
import app.zxtune.playback.Item
import app.zxtune.playback.PlayableItem
import app.zxtune.playback.PlaybackControl
import app.zxtune.playback.PlaybackService
import app.zxtune.utils.getParcelableCompat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// ! Events gate from local service to mediasession
internal class StatusCallback private constructor(
    private val ctx: Context,
    private val session: MediaSessionCompat,
    private val ctrl: PlaybackControl,
) : Callback {
    // guarded by this
    private val builder = PlaybackStateCompat.Builder()
    private var state = PlaybackControl.State.STOPPED
    private var position = TimeStamp.EMPTY
    private var customActions = emptyList<PlaybackStateCompat.CustomAction>()

    // guarded by metadataLock
    private val metadataLock = Any()
    private var baseMetadata: MediaMetadataCompat? = null
    private var liveArt: Bitmap? = null
    private var liveArtFrame = 0L
    private val scope = MainScope()
    private val coverartClient = CoverartProviderClient(ctx)
    private val lockscreenImageUrl = AtomicReference<Uri>()
    private val bitmapLoader by lazy {
        BitmapLoader("lockscreen", ctx, maxSize = 5, maxImageSize = 320)
    }

    init {
        builder.setActions(
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SEEK_TO or
                // Required by external controllers (Android Auto) to enable browsing and voice
                PlaybackStateCompat.ACTION_PLAY_FROM_URI or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                PlaybackStateCompat.ACTION_SET_REPEAT_MODE
        )
    }

    override fun onInitialState(state: PlaybackControl.State) = onStateChanged(state, TimeStamp.EMPTY)

    @Synchronized
    override fun onStateChanged(state: PlaybackControl.State, pos: TimeStamp) {
        this.state = state
        position = pos
        // any state change means that previous error is not actual anymore
        builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, null)
        builder.setState(state.toState(), pos.toMilliseconds(), 1f)
        session.run {
            setPlaybackState(buildState())
            isActive = state !== PlaybackControl.State.STOPPED
        }
    }

    /**
     * Shows error on external controllers (e.g. Android Auto). Error does not stop current playback,
     * so it's reported as fatal (STATE_ERROR) only if nothing is played.
     */
    @Synchronized
    fun reportError(code: Int, message: String) {
        val fatal = state === PlaybackControl.State.STOPPED
        builder.setErrorMessage(code, message)
        if (fatal) {
            builder.setState(PlaybackStateCompat.STATE_ERROR, position.toMilliseconds(), 0f)
        }
        session.setPlaybackState(buildState())
        if (fatal) {
            builder.setState(state.toState(), position.toMilliseconds(), 1f)
        }
    }

    /**
     * Custom actions reflect current modes, so they are rebuilt on every change
     */
    @Synchronized
    fun updateModes() {
        val shuffle = ctrl.sequenceMode == PlaybackControl.SequenceMode.SHUFFLE
        val repeat = ctrl.trackMode == PlaybackControl.TrackMode.LOOPED
        session.setShuffleMode(ctrl.sequenceMode.toShuffleMode())
        session.setRepeatMode(ctrl.trackMode.toRepeatMode())
        // Android Auto does not display standard shuffle/repeat controls, only custom actions
        customActions = listOf(
            PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_TOGGLE_SHUFFLE,
                ctx.getString(R.string.car_action_shuffle),
                if (shuffle) R.drawable.ic_car_shuffle_on else R.drawable.ic_car_shuffle_off
            ).build(),
            PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_TOGGLE_REPEAT,
                ctx.getString(R.string.car_action_repeat),
                if (repeat) R.drawable.ic_car_repeat_on else R.drawable.ic_car_repeat_off
            ).build(),
        )
        session.setPlaybackState(buildState())
    }

    // builder cannot remove custom actions, so they are added to a copy
    private fun buildState() = PlaybackStateCompat.Builder(builder.build()).apply {
        customActions.forEach { addCustomAction(it) }
    }.build()

    @Synchronized
    private fun setActiveQueueItem(id: Long) {
        builder.setActiveQueueItemId(id)
        session.setPlaybackState(buildState())
    }

    /**
     * Replaces artwork by live picture (e.g. oscilloscope for car screen) or restores original one if null
     */
    fun setLiveArt(bitmap: Bitmap?) = synchronized(metadataLock) {
        liveArt = bitmap
        baseMetadata?.let { publish(it) }
    }

    private fun setMetadata(metadata: MediaMetadataCompat) = synchronized(metadataLock) {
        baseMetadata = metadata
        publish(metadata)
    }

    private fun publish(base: MediaMetadataCompat) {
        val art = liveArt
        session.setMetadata(art?.let { withLiveArt(base, it, ++liveArtFrame) } ?: base)
    }

    override fun onItemChanged(item: Item) = try {
        val dataId = item.dataId
        val builder = MediaMetadataCompat.Builder().apply {
            val title = Util.formatTrackTitle(item.title, dataId)
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            val author = item.author
            if (author.isNotEmpty()) {
                putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, author)
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            } else {
                // Do not localize
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Unknown artist")
            }
            putNonEmptyString(ModuleAttributes.TITLE, item.title)
            putNonEmptyString(ModuleAttributes.AUTHOR, item.author)
            putNonEmptyString(ModuleAttributes.COMMENT, item.comment)
            putNonEmptyString(ModuleAttributes.PROGRAM, item.program)
            putNonEmptyString(ModuleAttributes.STRINGS, item.strings)
            if (item is PlayableItem) {
                putNonEmptyString(
                    ModuleAttributes.CHANNELS_NAMES,
                    item.module.getProperty(ModuleAttributes.CHANNELS_NAMES, "")
                )
            }
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, item.duration.toMilliseconds())
            putLong(ModuleAttributes.SIZE, item.size)
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, dataId.toString())
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, item.id.toString())
        }
        setActiveQueueItem(BrowseTree.getQueueId(item.id))
        fillObjectUrls(dataId, builder).takeIf { Build.VERSION.SDK_INT <= 29 }?.let {
            // https://developer.android.com/media/legacy/mediasession#album_artwork
            fillAlbumArtwork(it, builder)
        }
        setMetadata(builder.build())
    } catch (e: Exception) {
        LOG.w(e) { "onItemChanged()" }
    }

    private fun fillObjectUrls(
        dataId: Identifier,
        builder: MediaMetadataCompat.Builder
    ): Uri? = with(builder) {
        try {
            val obj = Vfs.resolve(dataId.dataLocation)
            putNonEmptyString(VfsExtensions.SHARE_URL, obj.shareUrl)
            coverartClient.getMediaUris(dataId)?.let { uris ->
                var preferableResult: Uri? = null
                for (key in arrayOf(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadataCompat.METADATA_KEY_ART_URI
                )) {
                    uris.getParcelableCompat<Uri>(key)?.let {
                        preferableResult = it
                        putString(key, it.toString())
                    }
                }
                return preferableResult
            }
        } catch (e: Exception) {
            LOG.w(e) { "Failed to get object urls" }
        }
        return null
    }

    override fun onError(e: String) {
        reportError(PlaybackStateCompat.ERROR_CODE_APP_ERROR, e)
        scope.launch {
            Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fillAlbumArtwork(uri: Uri, builder: MediaMetadataCompat.Builder) {
        bitmapLoader.getCached(uri)?.bitmap?.let {
            LOG.d { "Cached album art" }
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            return
        }
        lockscreenImageUrl.set(uri)
        scope.launch {
            bitmapLoader.load(uri).bitmap?.let { bitmap ->
                if (lockscreenImageUrl.compareAndSet(uri, null)) {
                    LOG.d { "Fetched album art" }
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                    setMetadata(builder.build())
                } else {
                    LOG.d { "Drop outdated album art retrieval" }
                }
            }
        }
    }

    companion object {
        private val LOG = Logger(StatusCallback::class.java.name)

        const val CUSTOM_ACTION_TOGGLE_SHUFFLE = "app.zxtune.CUSTOM_ACTION_TOGGLE_SHUFFLE"
        const val CUSTOM_ACTION_TOGGLE_REPEAT = "app.zxtune.CUSTOM_ACTION_TOGGLE_REPEAT"

        /**
         * Metadata key of live artwork frame number. Absent or 0 for regular metadata, so the
         * consumers not interested in live picture (notification, widget) can skip frames updates
         */
        const val LIVE_ART_FRAME_KEY = "app.zxtune.LIVE_ART_FRAME"

        fun create(ctx: Context, svc: PlaybackService, session: MediaSessionCompat) = StatusCallback(ctx, session, svc.playbackControl).apply {
            updateModes()
        }

        internal fun withLiveArt(base: MediaMetadataCompat, art: Bitmap, frame: Long): MediaMetadataCompat = MediaMetadataCompat.Builder(base).apply {
            putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            // hosts may prefer uris over bitmaps, so original cover art references are hidden
            putString(MediaMetadataCompat.METADATA_KEY_ART_URI, null)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, null)
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, null)
            putLong(LIVE_ART_FRAME_KEY, frame)
        }.build()
    }
}

private fun MediaMetadataCompat.Builder.putNonEmptyString(key: String, value: String?) = apply {
    if (!TextUtils.isEmpty(value)) {
        putString(key, value)
    }
}
