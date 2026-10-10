/**
 * @file
 * @brief
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.device.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import android.view.View
import android.widget.RemoteViews
import androidx.media.session.MediaButtonReceiver
import app.zxtune.MainActivity
import app.zxtune.R
import app.zxtune.Releaseable
import app.zxtune.device.media.isLiveArtFrame

class WidgetHandler : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = update(context, createView(context))

    private class WidgetNotification(private val ctx: Context) : MediaControllerCompat.Callback() {
        private val views by lazy {
            createView(ctx)
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = state?.let {
            val isPlaying = it.state == PlaybackStateCompat.STATE_PLAYING
            views.setImageViewResource(
                R.id.widget_ctrl_play_pause,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            updateView()
        } ?: Unit

        private var lastMediaId: String? = null

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = metadata?.takeIf {
            // widget does not show artwork, so live artwork frames of the same item change nothing
            !it.isLiveArtFrame() || it.description.mediaId != lastMediaId
        }?.run {
            lastMediaId = description.mediaId
            views.setTextViewText(R.id.widget_title, description.title)
            description.subtitle.let {
                views.setTextViewText(R.id.widget_subtitle, it)
                views.setViewVisibility(
                    R.id.widget_subtitle,
                    if (TextUtils.isEmpty(it)) View.GONE else View.VISIBLE
                )
            }
            updateView()
        } ?: Unit

        private fun updateView() = update(ctx, views)
    }

    companion object {
        fun connect(ctx: Context, session: MediaSessionCompat) = session.controller.run {
            val cb = WidgetNotification(ctx)
            registerCallback(cb)
            Releaseable { unregisterCallback(cb) }
        }

        private fun update(ctx: Context, widgetView: RemoteViews) = AppWidgetManager.getInstance(ctx).run {
            getAppWidgetIds(
                ComponentName(
                    ctx,
                    WidgetHandler::class.java
                )
            ).takeIf { it.isNotEmpty() }?.let {
                updateAppWidget(it, widgetView)
            } ?: Unit
        }

        private fun createView(ctx: Context) = RemoteViews(
            ctx.packageName,
            R.layout.widget
        ).apply {
            setOnClickPendingIntent(
                android.R.id.background,
                MainActivity.createPendingIntent(ctx)
            )
            setOnClickPendingIntent(
                R.id.widget_ctrl_prev,
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    ctx,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            setOnClickPendingIntent(
                R.id.widget_ctrl_play_pause,
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    ctx,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
            setOnClickPendingIntent(
                R.id.widget_ctrl_next,
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    ctx,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
        }
    }
}
