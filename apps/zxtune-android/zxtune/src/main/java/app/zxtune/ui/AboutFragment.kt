package app.zxtune.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.LayoutRes
import androidx.collection.SparseArrayCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.ImageViewCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.zxtune.BuildConfig
import app.zxtune.Features
import app.zxtune.PluginsProvider
import app.zxtune.R
import app.zxtune.analytics.Analytics
import app.zxtune.databinding.AboutBinding
import app.zxtune.playback.EngineBrands
import app.zxtune.ui.about.InfoBuilder
import app.zxtune.ui.about.OpenSourceComponents
import app.zxtune.ui.about.OpenSourceComponentsFormat
import app.zxtune.ui.settings.GroupCardsDecoration
import app.zxtune.ui.utils.padWithSystemBars
import app.zxtune.ui.utils.whenLifecycleStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screen sized dialog: brand header, engines, credits, libraries and system information.
 * Open source licenses and plugins lists are sub pages with own back navigation.
 */
class AboutFragment : DialogFragment(R.layout.about) {
    companion object {
        fun show(activity: FragmentActivity) = AboutFragment().show(activity.supportFragmentManager, null)

        private const val PAGE_MAIN = 0
        private const val PAGE_LICENSES = 1
        private const val PAGE_PLUGINS = 2

        // the rest is summarized by a counter chip leading to licenses
        private const val LIBRARIES_CHIPS = 8

        private class Engine(val system: String, val chips: String, val core: String, @ColorRes val color: Int)

        // systems emulated by own engines (see EngineBrands) and emulation cores they are built on.
        // Not "original driver": that is the tune's own playroutine shown in playback status.
        private val ENGINES = listOf(
            Engine("C64", "C64 · SID 6581/8580", "libsidplayfp (reSID/reSIDfp)", R.color.lhuna_voice_1),
            Engine("SNES", "SNES · S-DSP", "snes_spc", R.color.lhuna_voice_3),
        )

        private const val SEPARATOR = " · "

        /**
         * Static content, not dependent on runtime state
         */
        internal fun fillCredits(binding: AboutBinding, onLibraries: () -> Unit = { showPage(binding, PAGE_LICENSES) }) = binding.run {
            aboutVersion.text = "b${BuildConfig.VERSION}$SEPARATOR${BuildConfig.FLAVOR}"
            fillEngines(aboutEngines)
            fillTeam(aboutTeam)
            fillLibraries(aboutLibraries, onLibraries)
            aboutLicensesHeader.title.setText(R.string.about_licenses_action)
            aboutPluginsHeader.title.setText(R.string.about_plugins)
            setupList(aboutLicenses, licensesAdapter(root.context))
            setupList(aboutPlugins, null)
        }

        /**
         * @param data plugins descriptions by type name
         */
        internal fun fillPlugins(binding: AboutBinding, data: List<Pair<String, List<String>>>) {
            val rows = data.flatMap { (type, plugins) ->
                listOf(Row.Header("$type$SEPARATOR${plugins.size}")) + plugins.map { Row.Entry(it) }
            }
            binding.aboutPlugins.adapter = SectionsAdapter(rows, R.layout.about_plugin_entry) { view, descr ->
                (view as TextView).text = descr
            }
        }

        internal fun showPage(binding: AboutBinding, page: Int) {
            binding.aboutPages.displayedChild = page
        }

        private fun fillEngines(container: LinearLayout) {
            val ctx = container.context
            val inflater = LayoutInflater.from(ctx)
            val gap = ctx.resources.getDimensionPixelSize(R.dimen.lhuna_space_m)
            container.removeAllViews()
            ENGINES.forEachIndexed { idx, engine ->
                val card = inflater.inflate(R.layout.about_engine_card, container, false)
                ImageViewCompat.setImageTintList(
                    card.findViewById<ImageView>(R.id.engine_wave),
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, engine.color)),
                )
                card.findViewById<TextView>(R.id.engine_brand).text = EngineBrands.BRANDS.getValue(engine.system)
                card.findViewById<TextView>(R.id.engine_system).text = engine.chips
                card.findViewById<TextView>(R.id.engine_core).text = ctx.getString(R.string.about_engine_core, engine.core)
                if (idx != 0) {
                    (card.layoutParams as LinearLayout.LayoutParams).leftMargin = gap
                }
                container.addView(card)
            }
        }

        private fun fillTeam(container: LinearLayout) {
            val ctx = container.context
            val rows = arrayListOf(
                ctx.getString(R.string.about_idea) to "Vitamin/CAIG",
                ctx.getString(R.string.about_coauthor_label) to "LHUNA",
                ctx.getString(R.string.about_design) to listOf("Restorer", "Vitamin/CAIG", "Nodeus").joinToString(", "),
                ctx.getString(R.string.about_sources) to listOf("AYEmul" to "S.Bulba", "AYFly" to "Ander", "Pusher" to "Himik/ZxZ")
                    .joinToString("\n") { (source, author) -> "$source$SEPARATOR$author" },
            )
            ctx.getString(R.string.about_translation_label).takeIf { it.isNotEmpty() }?.let {
                rows.add(it to ctx.getString(R.string.about_translation_value))
            }
            rows.add(ctx.getString(R.string.about_dedicated) to "Lizzi")
            val inflater = LayoutInflater.from(ctx)
            container.removeAllViews()
            for ((role, names) in rows) {
                container.addView(
                    inflater.inflate(R.layout.about_credit_row, container, false).apply {
                        findViewById<TextView>(R.id.credit_role).text = role
                        findViewById<TextView>(R.id.credit_names).text = names
                    },
                )
            }
        }

        private fun fillLibraries(container: ViewGroup, onClick: () -> Unit) {
            val inflater = LayoutInflater.from(container.context)
            val libraries = OpenSourceComponents.ALL.filter { it.category == OpenSourceComponents.Category.PLAYBACK }.drop(1)
            val shown = libraries.take(LIBRARIES_CHIPS).map { OpenSourceComponentsFormat.shortName(it) }
            val rest = OpenSourceComponents.ALL.size - 1 - shown.size
            container.removeAllViews()
            for (label in shown + listOf("+$rest")) {
                container.addView(
                    (inflater.inflate(R.layout.about_chip, container, false) as TextView).apply {
                        text = label
                        setOnClickListener { onClick() }
                    },
                )
            }
        }

        private fun licensesAdapter(ctx: Context): SectionsAdapter<OpenSourceComponents.Component> {
            val rows = OpenSourceComponents.ALL.groupBy { it.category }.flatMap { (category, list) ->
                listOf(Row.Header(OpenSourceComponentsFormat.title(category))) + list.map { Row.Entry(it) }
            }
            return SectionsAdapter(rows, R.layout.about_license_entry) { view, component ->
                view.findViewById<TextView>(R.id.license_name).text = component.name
                view.findViewById<TextView>(R.id.license_kind).text = component.license
                view.findViewById<TextView>(R.id.license_authors).text = component.authors
                view.findViewById<TextView>(R.id.license_link).text = OpenSourceComponentsFormat.link(component)
                view.setOnClickListener { openLink(ctx, component.url) }
            }
        }

        private fun setupList(list: RecyclerView, adapter: RecyclerView.Adapter<*>?) {
            val ctx = list.context
            list.layoutManager = LinearLayoutManager(ctx)
            list.adapter = adapter
            val margin = ctx.resources.getDimensionPixelSize(R.dimen.lhuna_card_margin)
            list.addItemDecoration(GroupCardsDecoration(ctx, margin) { rv, pos -> rv.adapter?.getItemViewType(pos) == SectionsAdapter.HEADER })
        }

        private fun openLink(ctx: Context, url: String) = try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private var binding: AboutBinding? = null

    private val subPageBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding?.let { show(it, PAGE_MAIN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.CustomDialog_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = ComponentDialog(requireContext(), theme).apply {
        onBackPressedDispatcher.addCallback(subPageBack)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(DataBindingUtil.bind<AboutBinding>(view)).also { this.binding = it }
        binding.run {
            root.padWithSystemBars()
            fillCredits(this) { show(this, PAGE_LICENSES) }
            aboutSystem.text = getSystemInfo()
            aboutClose.setOnClickListener { dismiss() }
            aboutRate.visibility = if (Features.UserRating.isAvailable()) View.VISIBLE else View.GONE
            aboutRate.setOnClickListener { rate() }
            aboutOpenLicenses.setOnClickListener { show(this, PAGE_LICENSES) }
            aboutOpenPlugins.setOnClickListener { show(this, PAGE_PLUGINS) }
            aboutLicensesHeader.back.setOnClickListener { show(this, PAGE_MAIN) }
            aboutPluginsHeader.back.setOnClickListener { show(this, PAGE_MAIN) }
        }
        viewLifecycleOwner.whenLifecycleStarted {
            val data = withContext(Dispatchers.IO) {
                fetchPlugins()
            }
            fillPlugins(binding, data)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun show(binding: AboutBinding, page: Int) {
        showPage(binding, page)
        subPageBack.isEnabled = page != PAGE_MAIN
    }

    private fun rate() = try {
        startActivity(Features.UserRating.intent)
        Analytics.sendUiEvent(Analytics.UiAction.RATE)
    } catch (_: ActivityNotFoundException) {
    }

    private fun getSystemInfo() = InfoBuilder(requireContext()).apply {
        buildOSInfo()
        buildConfigurationInfo()
    }.result.trim()

    private fun fetchPlugins(): List<Pair<String, List<String>>> {
        val result = SparseArrayCompat<ArrayList<String>>()
        requireContext().contentResolver.query(
            PluginsProvider.getUri(),
            null,
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getInt(PluginsProvider.Columns.Type.ordinal)
                val descr = cursor.getString(PluginsProvider.Columns.Description.ordinal)
                result.getOrPut(type, ArrayList()).add(descr)
            }
        }
        return (0 until result.size()).map { idx -> getPluginTypeString(result.keyAt(idx)) to result.valueAt(idx) }
    }

    private fun getPluginTypeString(type: Int): String {
        val resId = PluginsProvider.Types.entries[type].nameId()
        return getString(resId)
    }
}

private sealed class Row<out T> {
    class Header(val title: String) : Row<Nothing>()

    class Entry<T>(val value: T) : Row<T>()
}

/**
 * Plain list with section headers, sections are drawn as cards by [GroupCardsDecoration]
 */
private class SectionsAdapter<T>(
    private val rows: List<Row<T>>,
    @LayoutRes private val entryLayout: Int,
    private val bind: (View, T) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) HEADER else ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == HEADER) R.layout.about_list_header else entryLayout
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = when (val row = rows[position]) {
        is Row.Header -> (holder.itemView as TextView).text = row.title
        is Row.Entry -> bind(holder.itemView, row.value)
    }

    companion object {
        const val HEADER = 0
        const val ENTRY = 1
    }
}

private fun <T> SparseArrayCompat<T>.getOrPut(key: Int, default: T) = putIfAbsent(key, default) ?: default
