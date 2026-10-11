package app.zxtune

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import app.zxtune.analytics.Analytics
import app.zxtune.core.jni.Api
import app.zxtune.device.media.AudioFocusConnection
import app.zxtune.device.media.BrowseTree
import app.zxtune.device.media.MediaSessionControl
import app.zxtune.device.media.NoisyAudioConnection
import app.zxtune.device.ui.StatusNotification
import app.zxtune.device.ui.WidgetHandler
import app.zxtune.fs.Vfs
import app.zxtune.fs.VfsDir
import app.zxtune.fs.VfsFile
import app.zxtune.playback.service.PlaybackServiceLocal
import app.zxtune.playlist.ProviderClient
import app.zxtune.preferences.Preferences
import app.zxtune.preferences.Preferences.getDefaultSharedPreferences
import app.zxtune.preferences.SharedPreferencesBridge.subscribe
import app.zxtune.ui.playlist.Entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class MainService : MediaBrowserServiceCompat() {

    // TODO: remove when LifecycleService used
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // bind to instance
    private val trace = Analytics.StageDurationTrace.create("MainService2")
    private val delegate by lazy {
        Delegate(this, trace, scope).apply {
            sessionToken = session.sessionToken
            weakDelegate = this
        }
    }
    private var weakDelegate: Delegate? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LOG.d { "onStartCommand($intent)" }
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(delegate.session, intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        LOG.d { "onGetRoot($clientPackageName)" }
        delegate // preload, also required to have session token for external clients
        // Playback resumption (System UI, car start) expects the last played item as a child of
        // the recent root
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) {
            return BrowserRoot(BrowseTree.RECENT_ROOT_ID, Bundle().apply {
                putBoolean(BrowserRoot.EXTRA_RECENT, true)
            })
        }
        // Any client (Android Auto, Wear, assistants) is allowed: tree exposes only what phone UI does
        return BrowserRoot(BrowseTree.ROOT_ID, BrowseTree.createRootExtras())
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) = when (parentId) {
        BrowseTree.ROOT_ID -> result.sendResult(BrowseTree.getRootChildren(this))

        BrowseTree.RECENT_ROOT_ID -> result.sendResult(BrowseTree.getRecentChildren(delegate.nowPlaying))

        BrowseTree.PLAYLIST_ID -> loadPlaylist(result) { BrowseTree.getPlaylistChildren(it) }

        BrowseTree.BROWSER_ID -> loadDir(result) { Vfs.getRoot() }

        else -> BrowseTree.getVfsUri(parentId)?.let { uri ->
            loadDir(result) { Vfs.resolve(uri) as? VfsDir }
        } ?: result.sendResult(null)
    }

    // Remote archives may respond slowly, so listing is limited in time to keep car UI responsive
    private fun loadDir(
        result: Result<List<MediaBrowserCompat.MediaItem>>,
        resolve: () -> VfsDir?,
    ) {
        result.detach()
        // network operations may ignore interruption, so listing is not awaited after timeout
        val listing = scope.async {
            runInterruptible {
                resolve()?.let { listDir(it) }
            }
        }
        scope.launch {
            val items = runCatching {
                withTimeout(LISTING_TIMEOUT_MS) {
                    listing.await()
                }
            }.onFailure {
                LOG.w(it) { "Failed to list directory" }
                listing.cancel()
            }.getOrNull()
            result.sendResult(items)
        }
    }

    private fun listDir(dir: VfsDir): List<MediaBrowserCompat.MediaItem> {
        val dirs = ArrayList<VfsDir>()
        val files = ArrayList<VfsFile>()
        dir.enumerate(object : VfsDir.Visitor() {
            override fun onDir(dir: VfsDir) {
                dirs.add(dir)
            }

            override fun onFile(file: VfsFile) {
                files.add(file)
            }
        })
        return BrowseTree.getVfsChildren(this, dir, dirs, files)
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) = loadPlaylist(result) {
        BrowseTree.getPlaylistChildren(BrowseTree.search(it, query))
    }

    private fun loadPlaylist(
        result: Result<List<MediaBrowserCompat.MediaItem>>,
        convert: (List<Entry>) -> List<MediaBrowserCompat.MediaItem>,
    ) {
        result.detach()
        scope.launch {
            val items = runCatching {
                ProviderClient.create(this@MainService).queryContent()
            }.onFailure {
                LOG.w(it) { "Failed to load playlist" }
            }.getOrNull()
            result.sendResult(items?.let(convert))
        }
    }

    override fun onDestroy() {
        weakDelegate?.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        weakDelegate?.stop()
        stopSelf()
    }

    private class Delegate(
        svc: Service,
        trace: Analytics.BaseTrace,
        private val scope: CoroutineScope
    ) {

        private val resources = ArrayList<Releaseable>()
        private val service: PlaybackServiceLocal
        val session: MediaSessionCompat
        val nowPlaying
            get() = service.nowPlaying

        init {
            LOG.d { "Initialize" }
            trace.beginMethod("initialize")
            val ctx = svc.applicationContext
            loadJni(ctx)
            trace.checkpoint("jni")
            PlaybackServiceLocal(ctx, Preferences.getDataStore(ctx)).apply {
                service = this
                addResource(this)
            }
            trace.checkpoint("svc")
            session = createSession(ctx)
            addResource(StatusNotification.connect(svc, session))
            addResource(WidgetHandler.connect(ctx, session))
            trace.checkpoint("cbs")
            service.restoreSession()
            trace.checkpoint("session")
            trace.endMethod()
        }

        fun stop() = service.playbackControl.stop()

        fun release() = resources.forEach(Releaseable::release)

        @Synchronized
        private fun addResource(res: Releaseable) {
            resources.add(res)
        }

        private fun loadJni(ctx: Context) = scope.launch {
            val api = Api.load().await()
            runCatching {
                LOG.d { "JNI is ready" }
                val prefs = getDefaultSharedPreferences(ctx)
                val options = api.getOptions()
                addResource(subscribe(prefs, options))
            }.onFailure {
                LOG.w(it) { "Failed to connect native options" }
            }
        }

        private fun createSession(ctx: Context): MediaSessionCompat {
            addResource(AudioFocusConnection.create(ctx, service))
            addResource(NoisyAudioConnection.create(ctx, service))
            val ctrl = MediaSessionControl.subscribe(ctx, service).also {
                addResource(it)
            }
            // live artwork is not attached: Android Auto crossfades each artwork change through white,
            // so animated frames flash instead of animating
            syncQueue(ctx, ctrl.session)
            return ctrl.session
        }

        // External controllers (Android Auto) show the queue, it's the playlist
        private fun syncQueue(ctx: Context, session: MediaSessionCompat) {
            session.setQueueTitle(ctx.getString(R.string.car_queue_title))
            val job = scope.launch {
                runCatching {
                    ProviderClient.create(ctx).observeContent().collect {
                        session.setQueue(BrowseTree.getQueue(it))
                    }
                }.onFailure {
                    LOG.w(it) { "Failed to observe playlist" }
                }
            }
            addResource { job.cancel() }
        }
    }

    companion object {
        private val TAG = MainService::class.java.name
        private val LOG = Logger(TAG)

        val CUSTOM_ACTION_ADD_CURRENT = "$TAG.CUSTOM_ACTION_ADD_CURRENT"
        val CUSTOM_ACTION_ADD = "$TAG.CUSTOM_ACTION_ADD"
        val COMMAND_GET_CURRENT_PARAMETERS = "$TAG.COMMAND_GET_CURRENT_PARAMETERS"
        val COMMAND_SET_CURRENT_PARAMETERS = "$TAG.COMMAND_SET_CURRENT_PARAMETERS"

        private const val LISTING_TIMEOUT_MS = 8000L

        @JvmStatic
        fun createIntent(ctx: Context, action: String?) = Intent(ctx, MainService::class.java).setAction(action)
    }
}
