package app.zxtune.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager.widget.ViewPager
import app.zxtune.MainActivity
import app.zxtune.MainService
import app.zxtune.R
import app.zxtune.TestUtils.construct
import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
import app.zxtune.core.ModuleAttributes
import app.zxtune.device.media.MediaModel
import app.zxtune.playback.Visualizer
import app.zxtune.playback.stubs.VisualizerStub
import app.zxtune.playlist.ProviderClient
import app.zxtune.ui.browser.BreadcrumbsEntry
import app.zxtune.ui.browser.ListingEntry
import app.zxtune.ui.browser.Model as BrowserModel
import app.zxtune.ui.browser.State as BrowserState
import app.zxtune.ui.playlist.Entry
import app.zxtune.ui.playlist.Model as PlaylistModel
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders main pages with realistic content in several locales to check layout: overlaps, truncation, alignment.
 * Images are stored in directory from ZXTUNE_SCREENSHOTS environment variable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class MainPagesRenderTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val constructions = ArrayList<MockedConstruction<*>>()
    private val browserState = MutableStateFlow(BrowserModel.State())
    private val currentMetadata = MutableStateFlow(metadata())
    private var status = AY_STATUS
    private val visualizer = object : Visualizer by VisualizerStub {
        override fun getStatus() = status
    }

    @Before
    fun setUp() {
        // process-wide state outlives activities, collapsed by default
        NowPlayingInfo.reset()
        // no playback service in tests
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MainService::class.java))
        val session = MediaSessionCompat(app, "test").apply {
            setPlaybackState(playbackState())
        }
        val mediaController = MediaControllerCompat(app, session)
        constructions += construct<MediaModel> {
            on { controller } doReturn flowOf(mediaController)
            on { playbackState } doReturn MutableStateFlow(playbackState())
            on { metadata } doReturn currentMetadata
            on { visualizer } doReturn flowOf(visualizer)
            on { playbackPosition } doReturn flowOf(POSITION)
            on { coverArt } doReturn MutableSharedFlow()
        }
        constructions += construct<PlaylistModel> {
            on { state } doReturn MutableStateFlow(PlaylistModel.createState().withContent(playlist()))
            on { filter } doReturn ""
        }
        constructions += construct<BrowserModel> {
            on { state } doReturn browserState
            on { progress } doReturn flowOf(null)
            on { notification } doReturn flowOf(null)
            on { errors } doReturn emptyFlow()
            on { playbackEvents } doReturn emptyFlow()
        }
        val persistent = mock<BrowserState> {
            onBlocking { getCurrentPath() } doReturn Uri.EMPTY
            onBlocking { updateCurrentPath(any()) } doReturn 0
        }
        BrowserState.create = { persistent }
    }

    @After
    fun tearDown() = constructions.forEach { it.close() }

    @Test
    fun phone() = LOCALES.forEach { locale ->
        // sources names are localized
        setLocale(locale)
        browserState.value = BrowserModel.State().withContent(emptyList(), sources())
        render(locale, "now-playing", 0)
        render(locale, "now-playing-info", 0, expanded = true)
        currentMetadata.value = flacMetadata()
        status = FLAC_STATUS
        render(locale, "now-playing-info-flac", 0, expanded = true)
        currentMetadata.value = metadata()
        status = AY_STATUS
        render(locale, "playlist", 1)
        render(locale, "browser-sources", 2)
        browserState.value = BrowserModel.State().withContent(hvscPath(), hvscFolder())
        render(locale, "browser-folder", 2)
    }

    /**
     * Visualizer gets back at least the height it had before the redesign while information is collapsed (default)
     */
    @Test
    fun compactNowPlaying() {
        val scenario = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = scenario.get()
        // collapsed by default
        assertEquals(false, waitInfoLoaded())
        shadowOf(Looper.getMainLooper()).idle()
        val density = activity.resources.displayMetrics.density
        val visualizer = activity.findViewById<View>(R.id.visualizer)
        assertEquals(false, activity.findViewById<View>(R.id.brief_main).isShown)
        assertTrue("${visualizer.height / density}dp", visualizer.height / density >= OLD_VISUALIZER_HEIGHT_DP)
        // every control of the bottom part has 48dp touch target
        for (id in listOf(
            R.id.controls_information,
            R.id.controls_sequence_mode,
            R.id.controls_prev,
            R.id.controls_play_pause,
            R.id.controls_next,
            R.id.controls_track_mode,
            R.id.controls_track_menu,
            R.id.position_seek,
        )) {
            val view = activity.findViewById<View>(id)
            assertTrue("$id", view.height >= 48 * density - 1 && view.width >= 48 * density - 1)
        }
        val bar = activity.findViewById<ViewGroup>(R.id.navigation_bar)
        assertEquals(48 * density, bar.height.toFloat(), 1f)
        // expanded on demand
        setInfoExpanded(activity, true)
        // and back, the state is persistent
        setInfoExpanded(activity, false)
        scenario.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w915dp-h411dp-land-xxhdpi")
    fun phoneLandscape() {
        render("en", "landscape-now-playing", 0)
        render("fr", "landscape-now-playing-info", 0, expanded = true)
        val scenario = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = scenario.get()
        setInfoExpanded(activity, false)
        val density = activity.resources.displayMetrics.density
        val visualizer = activity.findViewById<View>(R.id.visualizer)
        assertTrue("${visualizer.height / density}dp", visualizer.height / density >= OLD_LANDSCAPE_VISUALIZER_HEIGHT_DP)
        scenario.pause().stop().destroy()
    }

    @Test
    fun navigation() {
        val scenario = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = scenario.get()
        val pager = activity.findViewById<ViewPager>(R.id.view_pager)
        val bar = activity.findViewById<ViewGroup>(R.id.navigation_bar)
        val miniPlayer = activity.findViewById<View>(R.id.mini_player)
        fun checkPage(page: Int) {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(page, pager.currentItem)
            for (idx in 0 until bar.childCount) {
                assertEquals(idx == page, bar.getChildAt(idx).isSelected)
            }
            // now playing page is the first one
            assertEquals(page != 0, miniPlayer.isVisible)
        }
        checkPage(0)
        assertTrue(bar.getChildAt(2).performClick())
        checkPage(2)
        // swipe
        pager.setCurrentItem(1, false)
        checkPage(1)
        assertTrue(activity.findViewById<View>(R.id.mini_player_card).performClick())
        checkPage(0)
        scenario.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tabletNavigation() {
        val scenario = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = scenario.get()
        val pager = activity.findViewById<ViewPager>(R.id.view_pager)
        val bar = activity.findViewById<ViewGroup>(R.id.navigation_bar)
        // now playing is always visible
        assertEquals(null, activity.findViewById<View>(R.id.mini_player))
        assertEquals(2, bar.childCount)
        assertEquals(false, activity.showNowPlaying())
        assertTrue(bar.getChildAt(1).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, pager.currentItem)
        assertTrue(bar.getChildAt(1).isSelected)
        scenario.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tabletLandscape() {
        browserState.value = BrowserModel.State().withContent(emptyList(), sources())
        render("fr", "tablet-info", 0, expanded = true)
        render("en", "tablet-playlist", 0)
        render("en", "tablet-browser", 1)
    }

    private fun setLocale(locale: String) = RuntimeEnvironment.setQualifiers(if (locale == "en") "+en-rUS" else "+$locale")

    private fun render(locale: String, name: String, page: Int, expanded: Boolean = false) {
        setLocale(locale)
        val scenario = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = scenario.get()
        val pager = activity.findViewById<ViewPager>(R.id.view_pager)
        pager.setCurrentItem(page, false)
        shadowOf(Looper.getMainLooper()).idle()
        setInfoExpanded(activity, expanded)
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, root.width, root.height)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(page, pager.currentItem)
        assertTrue(root.width > 0)
        dumpGeometry(activity, "$name-$locale")
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "$name-$locale.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        // the state is persistent
        setInfoExpanded(activity, false)
        scenario.pause().stop().destroy()
    }

    // stored state is read in background
    private fun waitInfoLoaded(): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            NowPlayingInfo.isExpanded.value?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("information state is not loaded")
    }

    private fun setInfoExpanded(activity: MainActivity, expanded: Boolean) {
        if (waitInfoLoaded() != expanded) {
            assertTrue(activity.findViewById<View>(R.id.controls_information).performClick())
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(expanded, activity.findViewById<View>(R.id.brief_main).isShown)
    }

    private fun dumpGeometry(activity: MainActivity, name: String) {
        val density = activity.resources.displayMetrics.density
        val ids = listOf(
            "visualizer" to R.id.visualizer,
            "information" to R.id.information,
            "seek" to R.id.seek_controls,
            "controls" to R.id.playback_controls,
            "mini" to R.id.mini_player,
            "nav" to R.id.navigation_bar,
            "toolbar" to R.id.main_top_panel,
        )
        val parts = ids.mapNotNull { (n, id) ->
            activity.findViewById<View>(id)?.takeIf { it.isShown }?.let { v ->
                val loc = IntArray(2).also { v.getLocationInWindow(it) }
                "$n=${loc[1]}+${v.height}px(${(v.height / density).toInt()}dp)"
            }
        }
        println("GEOMETRY $name ${parts.joinToString(" ")}")
    }

    private fun playbackState() = PlaybackStateCompat.Builder()
        .setState(PlaybackStateCompat.STATE_PLAYING, POSITION.toMilliseconds(), 1f)
        .build()

    private fun metadata() = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Agent X II")
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Agent X II")
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Tim Follin")
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Tim Follin")
        .putString(ModuleAttributes.TYPE, "AY")
        .putString(ModuleAttributes.PROGRAM, "Tim Follin player (in-game)")
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, "file:///music/Agent_X_II.ay#%2F%232")
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ProviderClient.createUri(3).toString())
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 324_000)
        .putLong("app.zxtune.metadata.LOOP_START", 48_000)
        .build()

    private fun flacMetadata() = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Kokuu no Kiseki (Ys Origin OST)")
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Kokuu no Kiseki (Ys Origin OST)")
        .putString(ModuleAttributes.TYPE, "FLAC")
        .putString(ModuleAttributes.CODEC, "FLAC")
        .putLong(ModuleAttributes.SAMPLERATE, 44100)
        .putLong(ModuleAttributes.SAMPLE_BITS, 16)
        .putLong(ModuleAttributes.CHANNELS, 2)
        .putLong(ModuleAttributes.BITRATE, 912_345)
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, "file:///music/ys_origin.flac")
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ProviderClient.createUri(4).toString())
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 201_000)
        .build()

    private fun playlist() = listOf(
        "Commando.sid" to ("Commando" to "Rob Hubbard") to 192,
        "Monty_on_the_Run.sid" to ("Monty on the Run" to "Rob Hubbard") to 361,
        "Last_Ninja_2.sid" to ("Last Ninja 2 — Central Park, with a very long title" to "Matt Gray") to 278,
        "Agent_X_II.ay#%2F%232" to ("Agent X II" to "Tim Follin") to 324,
        "Wings_of_Death.ym" to ("Wings of Death" to "Jochen Hippel") to 237,
        "Corridors_of_Time.spc" to ("Corridors of Time" to "Yasunori Mitsuda") to 165,
        "guitar_slinger.mod" to ("Guitar Slinger" to "Jogeir Liljedahl") to 252,
        "Out_Run.vgz" to ("Magical Sound Shower" to "Hiroshi Kawaguchi") to 241,
        "Mega_Man_2.nsf" to ("Dr. Wily Stage 1" to "Takashi Tateishi") to 98,
        "unknown_file" to ("" to "") to 0,
    ).mapIndexed { idx, (desc, seconds) ->
        val (file, info) = desc
        Entry(
            idx.toLong(),
            Identifier.parse("file:///music/$file"),
            info.first,
            info.second,
            TimeStamp.fromSeconds(seconds.toLong())
        )
    }

    private fun sources() = listOf(
        source("local", R.string.vfs_local_root_name, R.string.vfs_local_root_description, R.drawable.ic_browser_vfs_local),
        source("hvsc", R.string.vfs_hvsc_root_name, R.string.vfs_hvsc_root_description, R.drawable.ic_browser_vfs_hvsc),
        source("zxart", R.string.vfs_zxart_root_name, R.string.vfs_zxart_root_description, R.drawable.ic_browser_vfs_zxart),
        source("zxtunes", R.string.vfs_zxtunes_root_name, R.string.vfs_zxtunes_root_description, R.drawable.ic_browser_vfs_zxtunes),
        source("modland", R.string.vfs_modland_root_name, R.string.vfs_modland_root_description, R.drawable.ic_browser_vfs_modland),
        source("modarchive", R.string.vfs_modarchive_root_name, R.string.vfs_modarchive_root_description, R.drawable.ic_browser_vfs_modarchive),
        source("vgmrips", R.string.vfs_vgmrips_root_name, R.string.vfs_vgmrips_root_description, R.drawable.ic_browser_vfs_vgmrips),
        source("amp", R.string.vfs_amp_root_name, R.string.vfs_amp_root_description, R.drawable.ic_browser_vfs_amp),
        source("joshw", R.string.vfs_joshw_root_name, R.string.vfs_joshw_root_description, R.drawable.ic_browser_vfs_joshw),
        source("aminet", R.string.vfs_aminet_root_name, R.string.vfs_aminet_root_description, R.drawable.ic_browser_vfs_aminet),
    )

    private fun source(scheme: String, @StringRes name: Int, @StringRes description: Int, @DrawableRes icon: Int) = ListingEntry.makeFolder(
        Uri.parse("$scheme:/"),
        app.getString(name),
        app.getString(description),
        ListingEntry.DrawableIcon(icon)
    )

    private fun hvscPath() = listOf(
        BreadcrumbsEntry(Uri.parse("hvsc:/"), "HVSC", R.drawable.ic_browser_vfs_hvsc),
        BreadcrumbsEntry(Uri.parse("hvsc:/C64Music"), "C64Music", null),
        BreadcrumbsEntry(Uri.parse("hvsc:/C64Music/MUSICIANS"), "MUSICIANS", null),
        BreadcrumbsEntry(Uri.parse("hvsc:/C64Music/MUSICIANS/H/Hubbard_Rob"), "Hubbard_Rob", null),
    )

    private fun hvscFolder() = listOf(
        ListingEntry.makeFolder(Uri.parse("hvsc:/C64Music/MUSICIANS/H/Hubbard_Rob/Unreleased"), "Unreleased", ""),
        hvscFile("Commando.sid", "Commando", "5.1K", null, R.drawable.ic_browser_file_track),
        hvscFile("Delta.sid", "Delta", "12K", null, R.drawable.ic_browser_file_archive),
        hvscFile("Monty_on_the_Run.sid", "Monty on the Run", "7.8K", null, R.drawable.ic_browser_file_remote),
        hvscFile("Sanxion.sid", "Sanxion", "4.2K", null, R.drawable.ic_browser_file_unknown),
        hvscFile("readme.txt", "", "1.2K", null, null),
        hvscFile("Hubbard_Rob.zip", "Complete works", "1.1M", null, R.drawable.ic_browser_file_archive),
    )

    private fun hvscFile(name: String, description: String, size: String, icon: ListingEntry.Icon?, @DrawableRes type: Int?) = ListingEntry.makeFile(
        Uri.parse("hvsc:/C64Music/MUSICIANS/H/Hubbard_Rob/$name"),
        name,
        description,
        size,
        type,
        icon,
        ChipFamily.ofFilename(name).takeIf { type != null },
    )

    companion object {
        private val LOCALES = listOf("en", "fr", "de", "ru")
        private val POSITION = TimeStamp.fromSeconds(67)

        // measured before the redesign (6d543c5c, the same now playing layouts as 0469cac2^) with pager tabs 32,
        // toolbar 44, title 40, scrubber 61.7, transport 56: 681.3dp on 411x915dp phone, 177.3dp on 915x411dp
        private const val OLD_VISUALIZER_HEIGHT_DP = 681
        private const val OLD_LANDSCAPE_VISUALIZER_HEIGHT_DP = 177

        private val AY_STATUS = """
            AY-3-8910 1.77MHz, 50Hz
            Original driver: Tim Follin player (in-game)
            Running on NebulaWeaveAY
            scope: 3/3 ch, trigger 120us
            output: 48000Hz buffer=160ms sync=timestamp
            render: 640% realtime
        """.trimIndent()

        private val FLAC_STATUS = """
            Original driver: unknown
            Running on libFLAC
            output: 48000Hz buffer=160ms sync=timestamp
            render: 2400% realtime
        """.trimIndent()
    }
}
