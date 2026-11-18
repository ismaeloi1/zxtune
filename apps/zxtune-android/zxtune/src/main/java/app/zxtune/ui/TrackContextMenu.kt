package app.zxtune.ui

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.zxtune.MainService
import app.zxtune.R
import app.zxtune.ScanService
import app.zxtune.SharingActivity
import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
import app.zxtune.core.ModuleAttributes
import app.zxtune.playlist.Item
import app.zxtune.playlist.ProviderClient
import app.zxtune.ui.playlist.PlaylistsUi
import app.zxtune.ui.utils.bindIntent
import app.zxtune.ui.utils.bindOnClick
import app.zxtune.ui.utils.item
import app.zxtune.utils.ifNotNulls
import kotlinx.coroutines.launch

/**
 * Actions on the playing track, shown from now playing transport row
 */
internal class TrackContextMenu(
    private val ctx: FragmentActivity,
    anchor: View,
    // track actions or playlist actions (favorites, add to playlist)
    private val menuRes: Int = R.menu.track,
) {
    private val popup by lazy {
        PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(menuRes, menu)
            setForceShowIcon(true)
        }
    }
    private var setupItems: (Menu) -> Unit = { menu -> setup(menu, null, null) }

    init {
        anchor.setOnClickListener {
            popup.apply {
                setupItems(menu)
            }.show()
        }
    }

    fun bind(controller: MediaControllerCompat?, metadata: MediaMetadataCompat?) {
        setupItems = { menu -> setup(menu, controller, metadata) }
    }

    // heart toggles the playing track in favorites list
    private fun setupFavorite(menuItem: MenuItem, metadata: MediaMetadataCompat?) {
        val item = metadata?.let { toItem(it) }
        menuItem.isVisible = item != null
        item ?: return
        val client = ProviderClient.create(ctx)
        menuItem.setTitle(R.string.playlists_favorite_add)
        ctx.lifecycleScope.launch {
            if (client.isFavorite(item.location)) {
                menuItem.setTitle(R.string.playlists_favorite_remove)
            }
        }
        menuItem.setOnMenuItemClickListener {
            ctx.lifecycleScope.launch {
                val added = client.toggleFavorite(item)
                if (added) {
                    PlaylistsUi.notifyAdded(ctx, ctx.getString(R.string.playlists_favorites))
                }
            }
            true
        }
    }

    private fun toItem(metadata: MediaMetadataCompat): Item? {
        val location = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI)?.takeIf { it.isNotEmpty() }
            ?: return null
        return Item(
            Identifier.parse(location),
            metadata.getString(ModuleAttributes.TITLE) ?: "",
            metadata.getString(ModuleAttributes.AUTHOR) ?: "",
            TimeStamp.fromMilliseconds(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        )
    }

    private fun setup(
        menu: Menu,
        controller: MediaControllerCompat?,
        metadata: MediaMetadataCompat?,
    ) = with(menu) {
        if (menuRes == R.menu.track_playlist) {
            setupPlaylist(menu, controller, metadata)
        } else {
            setupTrack(menu, metadata)
        }
    }

    private fun setupPlaylist(
        menu: Menu,
        controller: MediaControllerCompat?,
        metadata: MediaMetadataCompat?,
    ) = with(menu) {
        item(R.id.action_add).bindOnClick(ifNotNulls(
            controller?.transportControls,
            // any playing track, the current playlist one too (to another list)
            metadata?.description?.mediaId
        ) { ctrl, _ ->
            {
                PlaylistsUi.chooseList(ctx) { list, name ->
                    ctrl.sendCustomAction(
                        MainService.CUSTOM_ACTION_ADD_CURRENT,
                        bundleOf(ScanService.EXTRA_LIST to list)
                    )
                    PlaylistsUi.notifyAdded(ctx, name)
                }
            }
        })
        setupFavorite(item(R.id.action_favorite), metadata)
    }

    private fun setupTrack(menu: Menu, metadata: MediaMetadataCompat?) = with(menu) {
        item(R.id.action_send).bindIntent(metadata?.let {
            SharingActivity.maybeCreateSendIntent(
                ctx,
                it
            )
        })
        item(R.id.action_share).bindIntent(metadata?.let {
            SharingActivity.maybeCreateShareIntent(
                ctx,
                it
            )
        })
        item(R.id.action_make_ringtone).bindOnClick(metadata?.description?.mediaUri?.let {
            { RingtoneFragment.show(ctx, it) }
        })
        item(R.id.action_properties).bindOnClick(metadata?.let {
            { InformationFragment.show(ctx, it) }
        })
        item(R.id.action_sound_controls).bindOnClick(if (SoundControlFragment.needShow(metadata)) {
            { SoundControlFragment.show(ctx) }
        } else {
            null
        })
    }
}
