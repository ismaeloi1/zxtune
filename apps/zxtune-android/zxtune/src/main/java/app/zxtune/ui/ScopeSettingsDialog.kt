/**
 * @file
 * @brief Live settings panel over oscilloscope
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.viewpager.widget.ViewPager
import app.zxtune.Logger
import app.zxtune.PreferencesActivity
import app.zxtune.R
import app.zxtune.core.ModuleAttributes
import app.zxtune.device.media.MediaModel
import app.zxtune.playback.VoicesLayout
import app.zxtune.preferences.DataStore
import app.zxtune.preferences.Mt32Roms
import app.zxtune.preferences.Preferences
import app.zxtune.preferences.VulkanDriver
import app.zxtune.ui.utils.FragmentIntProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser

/**
 * Semi-transparent panel over oscilloscope (it keeps animating behind), so changes are seen immediately.
 * Shortcuts at the top lead to pages: oscilloscope display, trigger, emulation settings of the playing tune's chips
 * only (see [EmulationContext]), playback settings and all the emulation settings. Pages are taken from the main
 * preferences screen categories, so any preference added there is shown here as well.
 * Separate window is used because oscilloscope surface is placed over its own window.
 */
class ScopeSettingsDialog : AppCompatDialogFragment() {

    private var pager: ViewPager? = null
    private var tabs: LinearLayout? = null

    override fun onCreateDialog(savedInstanceState: Bundle?) = AppCompatDialog(requireContext(), R.style.ScopeSettingsDialog).apply {
        setCanceledOnTouchOutside(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (density * 8).toInt()
            setPadding(pad, pad, pad, pad / 2)
        }
        tabs = tabsRow
        pager = ViewPager(ctx).apply {
            id = R.id.scope_settings_pager
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) = selectTab(position)
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = density * 12
            }
            addView(
                HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(tabsRow)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: Bundle().also { arguments = it }
        // detected once, so the same pages are restored after configuration change
        if (args.containsKey(ARG_CHIPS)) {
            setupPages(contextOf(args))
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val detected = withTimeoutOrNull(DETECTION_TIMEOUT_MS) { detectPlaying() } ?: EmulationContext.NONE
                args.putAll(argsOf(detected))
                setupPages(detected)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pager = null
        tabs = null
    }

    private suspend fun detectPlaying(): EmulationContext {
        val model = MediaModel.of(requireActivity())
        val src = model.visualizer.filterNotNull().first()
        val type = model.metadata.value?.getString(ModuleAttributes.TYPE) ?: ""
        return withContext(Dispatchers.IO) {
            val groups = runCatching { VoicesLayout.parse(src.getLayout()).groups.map { it.name } }.getOrElse {
                LOG.w(it) { "Failed to get layout" }
                emptyList()
            }
            // emulation description is optional, scope statistics line follows it
            val description = runCatching { src.getStatus().lineSequence().first() }.getOrDefault("")
                .takeUnless { it.startsWith("scope:") } ?: ""
            EmulationContext.detect(type, groups, description)
        }
    }

    private fun setupPages(emulation: EmulationContext) {
        val pager = pager ?: return
        val ctx = requireContext()
        val pages = pagesOf(ctx, emulation)
        pager.adapter = PagesAdapter(childFragmentManager, pages)
        val tabs = tabs ?: return
        tabs.removeAllViews()
        pages.forEachIndexed { idx, page ->
            tabs.addView(makeTab(ctx, page.title) { pager.setCurrentItem(idx, true) })
        }
        selectTab(pager.currentItem)
    }

    private fun selectTab(position: Int) {
        val tabs = tabs ?: return
        for (idx in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(idx) as TextView
            val selected = idx == position
            tab.isSelected = selected
            (tab.background as GradientDrawable).setColor(if (selected) ContextCompat.getColor(tab.context, R.color.accent) else TAB_BACKGROUND)
            tab.setTextColor(if (selected) Color.WHITE else TAB_TEXT)
            if (selected) {
                tab.post { (tabs.parent as? HorizontalScrollView)?.requestChildFocus(tabs, tab) }
            }
        }
    }

    private fun makeTab(ctx: Context, title: String, onClick: () -> Unit) = TextView(ctx).apply {
        val density = resources.displayMetrics.density
        text = title
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = (density * 180).toInt()
        gravity = Gravity.CENTER
        // 40dp height with margins gives comfortable touch target without taking much of the panel
        minHeight = (density * 40).toInt()
        val hpad = (density * 14).toInt()
        setPadding(hpad, 0, hpad, 0)
        background = GradientDrawable().apply {
            cornerRadius = density * 20
            setColor(TAB_BACKGROUND)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (density * 6).toInt()
        }
        setOnClickListener { onClick() }
    }

    // Panel takes part of screen where the less important part of oscilloscope is: dashboard below waveforms in
    // portrait, right voices in landscape
    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val metrics = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (landscape) {
            window.setLayout((metrics.widthPixels * LANDSCAPE_WIDTH).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            window.setGravity(Gravity.END)
        } else {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (metrics.heightPixels * PORTRAIT_HEIGHT).toInt())
            window.setGravity(Gravity.BOTTOM)
        }
    }

    /**
     * @param categories indices of screen's categories to show, empty to show [hint] instead
     */
    internal class PageDesc(val title: String, @XmlRes val screen: Int, val categories: IntArray, val hint: String = "")

    private class PagesAdapter(fm: FragmentManager, private val pages: List<PageDesc>) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getCount() = pages.size

        override fun getItem(position: Int) = Page().apply {
            val desc = pages[position]
            screen = desc.screen
            requireArguments().putIntArray(ARG_CATEGORIES, desc.categories)
            requireArguments().putString(ARG_HINT, desc.hint)
        }

        override fun getPageTitle(position: Int) = pages[position].title
    }

    /**
     * Preferences screen categories with transparent background
     */
    class Page : PreferenceFragmentCompat() {
        var screen by FragmentIntProperty

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val args = requireArguments()
            preferenceManager.preferenceDataStore = NotifyingDataStore(Preferences.getDataStore(ctx))
            setPreferencesFromResource(screen, rootKey)
            preferenceScreen.run {
                keepOnly(args.getIntArray(ARG_CATEGORIES) ?: IntArray(0))
                removeUnsupported(this)
                markNextTrackOnly(this)
                args.getString(ARG_HINT)?.takeIf { it.isNotEmpty() }?.let { hint ->
                    addPreference(
                        Preference(ctx).apply {
                            title = hint
                            summary = ctx.getString(R.string.scope_settings_no_chip_settings)
                            isSelectable = false
                            isIconSpaceReserved = false
                        }
                    )
                }
            }
            TriggerPresetsBinding.bind(this)
            SidEngineBinding.bind(this)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Nested screens (e.g. mixer) are opened in place, there's no preferences activity to handle them
        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.fragment != null) {
                PreferencesActivity.nestedScreenOf(preference.key)?.let {
                    setPreferencesFromResource(it, null)
                    removeUnsupported(preferenceScreen)
                }
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        private fun PreferenceGroup.keepOnly(indices: IntArray) {
            for (idx in preferenceCount - 1 downTo 0) {
                if (idx !in indices) {
                    removePreference(getPreference(idx))
                }
            }
        }

        // files selection requires activity result handling of preferences activity
        private fun removeUnsupported(group: PreferenceGroup) {
            for (idx in group.preferenceCount - 1 downTo 0) {
                val pref = group.getPreference(idx)
                if (pref.key in UNSUPPORTED_KEYS) {
                    group.removePreference(pref)
                } else if (pref is PreferenceGroup) {
                    removeUnsupported(pref)
                }
            }
        }

        // Without a hint changes of such settings look like not working
        private fun markNextTrackOnly(group: PreferenceGroup) {
            for (idx in 0 until group.preferenceCount) {
                val pref = group.getPreference(idx)
                // panel is narrow and preferences have no icons
                pref.isIconSpaceReserved = false
                if (pref is PreferenceGroup) {
                    markNextTrackOnly(pref)
                } else if (pref.key?.startsWith(EMULATION_PREFIX) == true && !EmulationContext.isAppliedLive(pref.key)) {
                    pref.title = withNextTrackHint(requireContext(), pref.title ?: "")
                }
            }
        }
    }

    /**
     * Changes of view settings are applied immediately, see [OscilloscopeSettings.changes]. Native options are
     * delivered to playback service by preferences provider.
     */
    private class NotifyingDataStore(private val delegate: DataStore) : PreferenceDataStore() {
        override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value).also { notify(key) }
        override fun putInt(key: String, value: Int) = delegate.putInt(key, value).also { notify(key) }
        override fun putLong(key: String, value: Long) = delegate.putLong(key, value).also { notify(key) }
        override fun putString(key: String, value: String?) = delegate.putString(key, value).also { notify(key) }
        override fun getBoolean(key: String, defValue: Boolean) = delegate.getBoolean(key, defValue)
        override fun getInt(key: String, defValue: Int) = delegate.getInt(key, defValue)
        override fun getLong(key: String, defValue: Long) = delegate.getLong(key, defValue)
        override fun getString(key: String, defValue: String?) = delegate.getString(key, defValue)

        private fun notify(key: String) {
            if (key.startsWith(OscilloscopeSettings.PREFIX)) {
                OscilloscopeSettings.notifyChanged()
            }
        }
    }

    internal class Category(val title: String, val index: Int, val id: String)

    companion object {
        private val LOG = Logger(ScopeSettingsDialog::class.java.name)
        private const val TAG = "scope_settings"
        private const val ARG_CHIPS = "chips"
        private const val ARG_NAMES = "names"
        private const val ARG_CATEGORIES = "categories"
        private const val ARG_HINT = "hint"

        // binder calls to playback service take milliseconds, the limit is for not connected service
        private const val DETECTION_TIMEOUT_MS = 1000L

        // ~70% opacity
        private const val BACKGROUND = 0xB3101010.toInt()
        private const val TAB_BACKGROUND = 0x33FFFFFF
        private const val TAB_TEXT = 0xDDFFFFFF.toInt()
        private const val PORTRAIT_HEIGHT = 0.6f
        private const val LANDSCAPE_WIDTH = 0.55f
        private const val EMULATION_PREFIX = "zxtune."
        private val UNSUPPORTED_KEYS = setOf(Mt32Roms.PREF_KEY, VulkanDriver.PATH_KEY, VulkanDriver.ENABLED_KEY)

        // see preferences_oscilloscope.xml
        private const val SCOPE_DISPLAY_CATEGORY = 0
        private const val SCOPE_TRIGGER_CATEGORY = 1

        /**
         * @param emulation chips of the playing tune, detected from playback service if not specified
         */
        fun show(fm: FragmentManager, emulation: EmulationContext? = null) {
            if (fm.findFragmentByTag(TAG) == null) {
                ScopeSettingsDialog().apply {
                    arguments = emulation?.let { argsOf(it) } ?: Bundle()
                }.show(fm, TAG)
            }
        }

        private fun argsOf(emulation: EmulationContext) = bundleOf(
            ARG_CHIPS to emulation.chips.toTypedArray(),
            ARG_NAMES to emulation.names.toTypedArray(),
        )

        private fun contextOf(args: Bundle) = EmulationContext(
            args.getStringArray(ARG_CHIPS)?.toSet() ?: emptySet(),
            args.getStringArray(ARG_NAMES)?.toList() ?: emptyList(),
        )

        internal fun withNextTrackHint(ctx: Context, title: CharSequence): CharSequence = SpannableStringBuilder(title).apply {
            val start = length
            append("  ").append(ctx.getString(R.string.scope_settings_next_track))
            setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(NEXT_TRACK_COLOR), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private const val NEXT_TRACK_COLOR = 0xFFFFB74D.toInt()

        /**
         * Shortcuts: oscilloscope display, trigger, emulation of the playing tune's chips (if known), playback
         * settings and all the emulation settings
         */
        internal fun pagesOf(ctx: Context, emulation: EmulationContext): List<PageDesc> {
            val categories = emulationCategories(ctx)
            val result = arrayListOf(
                PageDesc(ctx.getString(R.string.scope_settings_tab_scope), R.xml.preferences_oscilloscope, intArrayOf(SCOPE_DISPLAY_CATEGORY)),
                PageDesc(ctx.getString(R.string.pref_oscilloscope_trigger_title), R.xml.preferences_oscilloscope, intArrayOf(SCOPE_TRIGGER_CATEGORY)),
            )
            chipsPage(ctx, emulation, categories)?.let { result.add(it) }
            categories.filter { it.id == EmulationContext.GENERAL }.takeIf { it.isNotEmpty() }?.let { general ->
                result.add(PageDesc(ctx.getString(R.string.scope_settings_tab_general), R.xml.preferences, general.map { it.index }.toIntArray()))
            }
            result.add(PageDesc(ctx.getString(R.string.scope_settings_tab_all), R.xml.preferences, categories.map { it.index }.toIntArray()))
            return result
        }

        private fun chipsPage(ctx: Context, emulation: EmulationContext, categories: List<Category>): PageDesc? {
            val chips = categories.filter { it.id in emulation.chips }
            return when {
                chips.isNotEmpty() -> PageDesc(
                    chips.joinToString(" + ") { chipLabel(ctx, it) },
                    R.xml.preferences,
                    chips.map { it.index }.toIntArray()
                )

                emulation.names.isNotEmpty() -> {
                    val names = emulation.names.joinToString(" + ")
                    PageDesc(names, R.xml.preferences, IntArray(0), names)
                }

                else -> null
            }
        }

        private fun chipLabel(ctx: Context, category: Category) = when (category.id) {
            EmulationContext.AYM -> R.string.scope_settings_chip_aym
            EmulationContext.Z80 -> R.string.scope_settings_chip_z80
            EmulationContext.FM -> R.string.scope_settings_chip_fm
            EmulationContext.DAC -> R.string.scope_settings_chip_dac
            EmulationContext.SAA -> R.string.scope_settings_chip_saa
            EmulationContext.MT32 -> R.string.scope_settings_chip_mt32
            EmulationContext.SID -> R.string.scope_settings_chip_sid
            else -> null
        }?.let { ctx.getString(it) } ?: category.title

        // Titles are required before pages creation, so screen is parsed instead of inflation of all the preferences
        internal fun emulationCategories(ctx: Context): List<Category> {
            val result = ArrayList<Category>()
            val parser = ctx.resources.getXml(R.xml.preferences)
            var index = -1
            var title = ""
            val keys = ArrayList<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.depth) {
                        2 -> {
                            ++index
                            title = attribute(ctx, parser, "title")
                            keys.clear()
                        }

                        3 -> keys.add(attribute(ctx, parser, "key"))
                    }

                    XmlPullParser.END_TAG -> if (parser.depth == 2) {
                        EmulationContext.categoryOf(keys.filter { it.startsWith(EMULATION_PREFIX) })?.let {
                            result.add(Category(title, index, it))
                        }
                    }
                }
            }
            parser.close()
            return result
        }

        // some of the attributes are specified in application namespace (e.g. app:title)
        private fun attribute(ctx: Context, parser: XmlPullParser, name: String): String {
            val xml = parser as android.content.res.XmlResourceParser
            for (ns in arrayOf(ANDROID_NS, APP_NS)) {
                val res = xml.getAttributeResourceValue(ns, name, 0)
                if (res != 0) {
                    return ctx.getString(res)
                }
                xml.getAttributeValue(ns, name)?.let { return it }
            }
            return ""
        }

        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
