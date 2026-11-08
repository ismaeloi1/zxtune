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

    @Before
    fun setUp() {
        // no playback service in tests
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MainService::class.java))
        val session = MediaSessionCompat(app, "test").apply {
            setPlaybackState(playbackState())
        }
        val mediaController = MediaControllerCompat(app, session)
        constructions += construct<MediaModel> {
            on { controller } doReturn flowOf(mediaController)
            on { playbackState } doReturn MutableStateFlow(playbackState())
            on { metadata } doReturn MutableStateFlow(metadata())
            on { visualizer } doReturn flowOf(null)
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
        render(locale, "playlist", 1)
        render(locale, "browser-sources", 2)
        browserState.value = BrowserModel.State().withContent(hvscPath(), hvscFolder())
        render(locale, "browser-folder", 2)
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
        render("en", "tablet-playlist", 0)
        render("en", "tablet-browser", 1)
    }

    private fun setLocale(locale: String) = RuntimeEnvironment.setQualifiers(if (locale == "en") "+en-rUS" else "+$locale")

    private fun render(locale: String, name: String, page: Int) {
        setLocale(locale)
        val scenario = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = scenario.get()
        val pager = activity.findViewById<ViewPager>(R.id.view_pager)
        pager.setCurrentItem(page, false)
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, root.width, root.height)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(page, pager.currentItem)
        assertTrue(root.width > 0)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "$name-$locale.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        scenario.pause().stop().destroy()
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
    }
}
