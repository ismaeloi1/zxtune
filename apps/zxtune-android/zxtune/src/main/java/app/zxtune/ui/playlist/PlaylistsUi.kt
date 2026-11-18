/**
 * @file
 * @brief Named playlists dialogs shared by player, browser and playlist screens
 */
package app.zxtune.ui.playlist

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.zxtune.R
import app.zxtune.playlist.Lists
import app.zxtune.playlist.ProviderClient
import app.zxtune.ui.utils.DialogSheets
import kotlinx.coroutines.launch

object PlaylistsUi {

    fun displayName(ctx: Context, info: ProviderClient.ListInfo) = when {
        info.isFavorites -> ctx.getString(R.string.playlists_favorites)
        info.name == Lists.DEFAULT_NAME -> ctx.getString(R.string.playlists_default)
        else -> info.name
    }

    // ! "Default playlist · 104 tracks"
    fun displayTitle(ctx: Context, info: ProviderClient.ListInfo) = "${displayName(ctx, info)} · ${ctx.resources.getQuantityString(R.plurals.tracks, info.count, info.count)}"

    /**
     * Chooser as in AIMP/VLC: favorites first, then lists, then "New playlist…"
     * @param onChosen list id and its display name
     */
    fun chooseList(activity: FragmentActivity, onChosen: (Long, String) -> Unit) = activity.lifecycleScope.launch {
        val client = ProviderClient.create(activity)
        client.favoritesList()
        val lists = client.lists()
        val names = lists.map { displayTitle(activity, it) } +
            activity.getString(R.string.playlists_new)
        AlertDialog.Builder(activity).setTitle(R.string.playlists_add_to)
            .setItems(names.toTypedArray()) { _, idx ->
                if (idx < lists.size) {
                    onChosen(lists[idx].id, displayName(activity, lists[idx]))
                } else {
                    promptName(activity, R.string.playlists_new, "") { name ->
                        activity.lifecycleScope.launch {
                            client.createList(name)?.let { onChosen(it, name) }
                        }
                    }
                }
            }.create().let(DialogSheets::apply).show()
    }

    fun promptName(ctx: Context, title: Int, initial: String, onOk: (String) -> Unit) {
        val edit = EditText(ctx).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            hint = ctx.getString(R.string.playlists_name)
            setText(initial)
            setSelection(initial.length)
        }
        val padding = ctx.resources.getDimensionPixelSize(R.dimen.lhuna_space_l)
        val frame = FrameLayout(ctx).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(edit)
        }
        AlertDialog.Builder(ctx).setTitle(title).setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk)
            }.setNegativeButton(android.R.string.cancel, null)
            .create().let(DialogSheets::apply).show()
    }

    fun notifyAdded(ctx: Context, name: String) = Toast.makeText(ctx, ctx.getString(R.string.playlists_added, name), Toast.LENGTH_SHORT).show()
}
