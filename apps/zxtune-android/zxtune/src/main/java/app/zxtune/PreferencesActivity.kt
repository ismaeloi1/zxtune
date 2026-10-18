/**
 * @file
 * @brief Preferences activity
 * @author vitamin.caig@gmail.com
 */
package app.zxtune

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import app.zxtune.analytics.Analytics
import app.zxtune.device.car.CarVisualSettings
import app.zxtune.preferences.Mt32Roms
import app.zxtune.preferences.Preferences
import app.zxtune.preferences.VulkanDriver
import app.zxtune.rescue.RescueActivity
import app.zxtune.ui.CarDisclaimerDialog
import app.zxtune.ui.SidEngineBinding
import app.zxtune.ui.TriggerPresetsBinding
import app.zxtune.ui.utils.FragmentIntProperty
import app.zxtune.ui.utils.ThemeUtils
import app.zxtune.ui.utils.applyEdgeToEdge
import app.zxtune.ui.utils.padWithSystemBars
import kotlinx.coroutines.launch

class PreferencesActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    companion object {
        @JvmStatic
        fun createIntent(ctx: Context) = Intent(ctx, PreferencesActivity::class.java)

        private const val CAR_PREFERENCES_KEY = "ui.car"
        private const val CAR_LEGAL_KEY = "ui.car.legal"
        private const val RESCUE_KEY = "rescue"
        private const val OSCILLOSCOPE_KEY = "ui.oscilloscope"

        /**
         * Nested screens opened by preferences with android:fragment attribute
         */
        @XmlRes
        fun nestedScreenOf(key: String?) = when (key) {
            "zxtune.sound.mixer.3" -> R.xml.preferences_mixer3
            CAR_PREFERENCES_KEY -> R.xml.preferences_car
            OSCILLOSCOPE_KEY -> R.xml.preferences_oscilloscope
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No action bar in the theme, so the list starts right at the top of the window
        applyEdgeToEdge()
        findViewById<View>(android.R.id.content).padWithSystemBars()

        ThemeUtils.setupThemeChange(this, this)

        // Need to only create the first fragment
        supportFragmentManager.run {
            if (findFragmentById(android.R.id.content) == null) {
                beginTransaction()
                    .add(android.R.id.content, makeFragment(R.xml.preferences))
                    .disallowAddToBackStack()
                    .commit()
            }
        }

        Analytics.sendUiEvent(Analytics.UiAction.PREFERENCES)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ) = nestedScreenOf(pref.key)?.let { openNested(it) } ?: false

    private fun openNested(@XmlRes layout: Int): Boolean {
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, makeFragment(layout))
            .addToBackStack(null)
            .commit()
        return true
    }

    private fun makeFragment(@XmlRes layout: Int) = PrefFragment().apply {
        this.layout = layout
    }

    class PrefFragment : PreferenceFragmentCompat() {
        var layout by FragmentIntProperty

        private val selectMt32Roms = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { copyMt32Roms(it) }
        }

        private val selectVulkanDriver = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { installVulkanDriver(it) }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = Preferences.getDataStore(requireContext())
            setPreferencesFromResource(layout, rootKey)
            findPreference<Preference>(Mt32Roms.PREF_KEY)?.run {
                updateMt32RomsSummary(this)
                setOnPreferenceClickListener {
                    selectMt32Roms.launch(null)
                    true
                }
            }
            setupCarPreferences()
            setupVulkanDriverPreferences()
            TriggerPresetsBinding.bind(this)
            SidEngineBinding.bind(this)
            findPreference<Preference>(RESCUE_KEY)?.setOnPreferenceClickListener {
                startActivity(RescueActivity.createIntent(requireContext()))
                true
            }
        }

        // Enabling is intercepted to show legal notice first, disabling is immediate
        private fun setupCarPreferences() {
            val visualization = findPreference<SwitchPreferenceCompat>(CarVisualSettings.ENABLED_KEY) ?: return
            visualization.setOnPreferenceChangeListener { _, value ->
                if (value == true) {
                    CarDisclaimerDialog.show(childFragmentManager)
                    false
                } else {
                    true
                }
            }
            childFragmentManager.setFragmentResultListener(CarDisclaimerDialog.REQUEST_KEY, this) { _, result ->
                if (result.getBoolean(CarDisclaimerDialog.ACCEPTED_KEY)) {
                    visualization.isChecked = true
                    CarDisclaimerDialog.askRestart(requireContext())
                }
            }
            findPreference<Preference>(CAR_LEGAL_KEY)?.setOnPreferenceClickListener {
                CarDisclaimerDialog.showInformation(requireContext())
                true
            }
        }

        private fun setupVulkanDriverPreferences() {
            val driver = findPreference<Preference>(VulkanDriver.PATH_KEY) ?: return
            val ctx = requireContext()
            if (!VulkanDriver.isSupported(ctx)) {
                driver.isEnabled = false
                driver.summary = getString(R.string.pref_oscilloscope_vulkan_driver_unsupported)
                findPreference<Preference>(VulkanDriver.ENABLED_KEY)?.isEnabled = false
                return
            }
            updateVulkanDriverSummary(driver)
            driver.setOnPreferenceClickListener {
                // drivers are distributed as zip archives or raw libraries without standard mime type
                selectVulkanDriver.launch(arrayOf("*/*"))
                true
            }
        }

        private fun updateVulkanDriverSummary(pref: Preference) {
            val ctx = requireContext()
            lifecycleScope.launch {
                val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    VulkanDriver.getInstalled(ctx)
                }
                findPreference<Preference>(VulkanDriver.ENABLED_KEY)?.isEnabled = info != null
                pref.summary = info?.let {
                    getString(R.string.pref_oscilloscope_vulkan_driver_summary, it.name)
                } ?: getString(R.string.pref_oscilloscope_vulkan_driver_summary_empty)
            }
        }

        private fun installVulkanDriver(uri: Uri) = lifecycleScope.launch {
            val ctx = requireContext()
            runCatching {
                VulkanDriver.install(ctx, uri, DocumentFile.fromSingleUri(ctx, uri)?.name)
            }.onSuccess {
                findPreference<SwitchPreferenceCompat>(VulkanDriver.ENABLED_KEY)?.isChecked = true
            }.onFailure {
                Toast.makeText(
                    ctx,
                    getString(R.string.pref_oscilloscope_vulkan_driver_failed, it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
            findPreference<Preference>(VulkanDriver.PATH_KEY)?.let { updateVulkanDriverSummary(it) }
        }

        private fun updateMt32RomsSummary(pref: Preference) {
            val path = Preferences.getDataStore(requireContext()).getString(Mt32Roms.PREF_KEY, null)
            val count = Mt32Roms.countFiles(path)
            pref.summary = if (count != 0) {
                getString(R.string.pref_zxtune_mt32_roms_summary, count)
            } else {
                getString(R.string.pref_zxtune_mt32_roms_summary_empty)
            }
        }

        private fun copyMt32Roms(tree: Uri) = lifecycleScope.launch {
            val ctx = requireContext()
            runCatching {
                Mt32Roms.copyFromTree(ctx, tree)
            }.onSuccess {
                Preferences.getDataStore(ctx).putString(Mt32Roms.PREF_KEY, Mt32Roms.getDir(ctx).absolutePath)
            }.onFailure {
                Toast.makeText(ctx, R.string.pref_zxtune_mt32_roms_failed, Toast.LENGTH_LONG).show()
            }
            findPreference<Preference>(Mt32Roms.PREF_KEY)?.let { updateMt32RomsSummary(it) }
        }
    }
}
