/**
 * @file
 * @brief Live settings panel over oscilloscope
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.viewpager.widget.PagerTabStrip
import androidx.viewpager.widget.ViewPager
import app.zxtune.PreferencesActivity
import app.zxtune.R
import app.zxtune.preferences.DataStore
import app.zxtune.preferences.Mt32Roms
import app.zxtune.preferences.Preferences
import app.zxtune.preferences.VulkanDriver
import app.zxtune.ui.utils.FragmentIntProperty
import org.xmlpull.v1.XmlPullParser

/**
 * Semi-transparent panel over oscilloscope (it keeps animating behind), so changes are seen immediately. Pages are
 * swiped horizontally: oscilloscope settings, then emulation settings by categories of the main preferences screen
 * (any preference added there is shown here as well).
 * Separate window is used because oscilloscope surface is placed over its own window.
 */
class ScopeSettingsDialog : AppCompatDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?) = AppCompatDialog(requireContext(), R.style.ScopeSettingsDialog).apply {
        setCanceledOnTouchOutside(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pages = pagesOf(ctx)
        val pager = ViewPager(ctx).apply {
            id = R.id.scope_settings_pager
            addView(
                PagerTabStrip(ctx).apply {
                    setTabIndicatorColorResource(R.color.accent)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                },
                ViewPager.LayoutParams().apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.TOP
                }
            )
            adapter = PagesAdapter(childFragmentManager, pages)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = density * 12
            }
            addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
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

    internal class PageDesc(val title: String, @XmlRes val screen: Int, val category: Int)

    private class PagesAdapter(fm: FragmentManager, private val pages: List<PageDesc>) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getCount() = pages.size

        override fun getItem(position: Int) = Page().apply {
            screen = pages[position].screen
            category = pages[position].category
        }

        override fun getPageTitle(position: Int) = pages[position].title
    }

    /**
     * Preferences screen (or its single category) with transparent background
     */
    class Page : PreferenceFragmentCompat() {
        var screen by FragmentIntProperty
        var category by FragmentIntProperty

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.preferenceDataStore = NotifyingDataStore(Preferences.getDataStore(ctx))
            setPreferencesFromResource(screen, rootKey)
            preferenceScreen.run {
                if (category >= 0) {
                    keepOnly(category)
                }
                removeUnsupported(this)
            }
            TriggerPresetsBinding.bind(this)
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

        private fun PreferenceGroup.keepOnly(index: Int) {
            for (idx in preferenceCount - 1 downTo 0) {
                if (idx != index) {
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

    companion object {
        private const val TAG = "scope_settings"

        // ~70% opacity
        private const val BACKGROUND = 0xB3101010.toInt()
        private const val PORTRAIT_HEIGHT = 0.6f
        private const val LANDSCAPE_WIDTH = 0.55f
        private const val EMULATION_PREFIX = "zxtune."
        private val UNSUPPORTED_KEYS = setOf(Mt32Roms.PREF_KEY, VulkanDriver.PATH_KEY, VulkanDriver.ENABLED_KEY)

        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) == null) {
                ScopeSettingsDialog().show(fm, TAG)
            }
        }

        /**
         * Oscilloscope settings and each category of main preferences with emulation options
         */
        internal fun pagesOf(ctx: Context) = listOf(
            PageDesc(ctx.getString(R.string.pref_oscilloscope_title), R.xml.preferences_oscilloscope, -1)
        ) + emulationCategories(ctx).map { (title, index) -> PageDesc(title, R.xml.preferences, index) }

        // Titles are required before pages creation, so screen is parsed instead of inflation of all the preferences
        internal fun emulationCategories(ctx: Context): List<Pair<String, Int>> {
            val result = ArrayList<Pair<String, Int>>()
            val parser = ctx.resources.getXml(R.xml.preferences)
            var index = -1
            var title = ""
            var emulation = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.depth) {
                        2 -> {
                            ++index
                            title = attribute(ctx, parser, "title")
                            emulation = false
                        }

                        3 -> emulation = emulation || attribute(ctx, parser, "key").startsWith(EMULATION_PREFIX)
                    }

                    XmlPullParser.END_TAG -> if (parser.depth == 2 && emulation) {
                        result.add(title to index)
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
