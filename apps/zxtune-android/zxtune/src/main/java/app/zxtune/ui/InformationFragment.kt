package app.zxtune.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.text.Html
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.zxtune.R
import app.zxtune.core.ModuleAttributes
import app.zxtune.device.media.MediaModel
import app.zxtune.ui.utils.FragmentParcelableProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InformationFragment : DialogFragment(R.layout.information) {

    companion object {
        fun show(activity: FragmentActivity, metadata: MediaMetadataCompat) = InformationFragment().apply {
            this.metadata = metadata
        }.show(activity.supportFragmentManager, "information")
    }

    private var metadata by FragmentParcelableProperty.of<MediaMetadataCompat>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = view.findViewById<TextView>(R.id.information_content).apply {
            text = buildContent("")
            movementMethod = ScrollingMovementMethod.getInstance()
            scrollTo(0, 0)
        }
        // emulation is known only while playing
        viewLifecycleOwner.lifecycleScope.launch {
            val status = MediaModel.of(requireActivity()).visualizer.first()?.let { src ->
                withContext(Dispatchers.IO) { runCatching { src.getStatus() }.getOrDefault("") }
            }.orEmpty()
            if (status.isNotBlank()) {
                content.text = buildContent(status)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).also {
        it.setTitle(R.string.properties)
    }

    private fun buildContent(status: String) = StringBuilder().apply {
        with(metadata) {
            addField(
                getString(R.string.information_location),
                Uri.decode(description.mediaUri?.toString() ?: "")
            )
            addField(getString(R.string.information_title), getString(ModuleAttributes.TITLE))
            addField(getString(R.string.information_author), getString(ModuleAttributes.AUTHOR))
            addField(getString(R.string.information_program), getString(ModuleAttributes.PROGRAM))
            addField(getString(R.string.information_comment), getString(ModuleAttributes.COMMENT))
            addField(getString(R.string.information_format), getString(ModuleAttributes.TYPE))
            addField(getString(R.string.information_codec), getString(ModuleAttributes.CODEC))
            fun number(key: String) = getLong(key).takeIf { it > 0 }
            addField(
                getString(R.string.information_samplerate),
                number(ModuleAttributes.SAMPLERATE)?.let { "$it Hz" }
            )
            addField(
                getString(R.string.information_sample_bits),
                number(ModuleAttributes.SAMPLE_BITS)?.let {
                    TrackInfoLines.formatBits(resources, getString(ModuleAttributes.CODEC), it.toInt())
                }
            )
            addField(
                getString(R.string.information_channels),
                number(ModuleAttributes.CHANNELS)?.let { TrackInfoLines.formatChannels(resources, it.toInt()) }
            )
            addField(
                getString(R.string.information_bitrate),
                number(ModuleAttributes.BITRATE)?.let { TrackInfoLines.formatBitrate(it) }
            )
            // stream line is already split to the fields above
            val emulation = TrackInfoLines.build(resources, null, status)
            addField(getString(R.string.information_playback), emulation.joinToString("\n"))
            addRawField(getString(ModuleAttributes.STRINGS))
        }
    }.asHtml()
}

private const val LINEBREAK = "<br>"

private fun StringBuilder.addField(name: String, value: CharSequence?) = value.takeUnless { it.isNullOrEmpty() }?.let {
    addAnyField(name, it)
} ?: this

private fun StringBuilder.addRawField(value: CharSequence?) = value.takeUnless { it.isNullOrEmpty() }?.let {
    addAnyRawField(it)
} ?: this

private fun StringBuilder.addAnyField(name: String, value: CharSequence) = apply {
    if (isNotEmpty()) {
        append(LINEBREAK)
    }
    val escaped = TextUtils.htmlEncode(value.toString())
    append("<b><big>$name:</big></b><br>&nbsp;${escaped.replaceLinebreaks()}")
}

private fun StringBuilder.addAnyRawField(value: CharSequence) = apply {
    if (isNotEmpty()) {
        append(LINEBREAK)
    }
    val escaped = TextUtils.htmlEncode(value.toString())
    append("<br><tt>${escaped.replaceLinebreaks()}</tt>")
}

private fun StringBuilder.asHtml() = if (Build.VERSION.SDK_INT >= 24) {
    Html.fromHtml(toString(), Html.FROM_HTML_MODE_LEGACY)
} else {
    @Suppress("DEPRECATION")
    Html.fromHtml(toString())
}

private fun String.replaceLinebreaks() = replace("\n", LINEBREAK)
