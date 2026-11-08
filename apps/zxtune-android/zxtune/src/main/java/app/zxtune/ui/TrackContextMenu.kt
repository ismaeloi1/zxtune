package app.zxtune.ui

import android.content.ContentResolver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import app.zxtune.MainService
import app.zxtune.R
import app.zxtune.SharingActivity
import app.zxtune.ui.utils.bindIntent
import app.zxtune.ui.utils.bindOnClick
import app.zxtune.ui.utils.item
import app.zxtune.utils.ifNotNulls

/**
 * Actions on the playing track, shown from now playing title block
 */
internal class TrackContextMenu(private val ctx: FragmentActivity, anchor: View) {
    private val popup by lazy {
        PopupMenu(anchor.context, anchor).apply {
            menuInflater.inflate(R.menu.track, menu)
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

    private fun setup(
        menu: Menu,
        controller: MediaControllerCompat?,
        metadata: MediaMetadataCompat?,
    ) = with(menu) {
        item(R.id.action_add).bindOnClick(ifNotNulls(
            controller?.transportControls,
            metadata?.description?.mediaId?.takeUnless {
                it.startsWith(ContentResolver.SCHEME_CONTENT)
            }
        ) { ctrl, _ ->
            {
                ctrl.sendCustomAction(MainService.CUSTOM_ACTION_ADD_CURRENT, null)
            }
        })
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
