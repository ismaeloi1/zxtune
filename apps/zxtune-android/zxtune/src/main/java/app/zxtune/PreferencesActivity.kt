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
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.zxtune.analytics.Analytics
import app.zxtune.preferences.Mt32Roms
import app.zxtune.preferences.Preferences
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
    ) = if ("zxtune.sound.mixer.3" == pref.key) {
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, makeFragment(R.xml.preferences_mixer3))
            .addToBackStack(null)
            .commit()
        true
    } else {
        false
    }

    private fun makeFragment(@XmlRes layout: Int) = PrefFragment().apply {
        this.layout = layout
    }

    class PrefFragment : PreferenceFragmentCompat() {
        var layout by FragmentIntProperty

        private val selectMt32Roms = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { copyMt32Roms(it) }
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
