package app.zxtune.device.car

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import app.zxtune.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Car connection state tracking.
 *
 * androidx.car.app.connection.CarConnection is not usable directly since the library requires
 * minSdk 23 (app supports 16), so its protocol is reproduced: Android Auto host exposes the state via
 * content provider and broadcasts changes.
 */
object CarConnection {
    private val LOG = Logger(CarConnection::class.java.name)

    // see androidx.car.app.connection.CarConnection
    internal const val AUTHORITY = "androidx.car.app.connection"
    internal const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    internal const val COLUMN_STATE = "CarConnectionState"
    const val TYPE_NOT_CONNECTED = 0
    const val TYPE_NATIVE = 1
    const val TYPE_PROJECTION = 2

    private val URI: Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).build()

    fun isAutomotive(ctx: Context) = Build.VERSION.SDK_INT >= 23 &&
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    /**
     * @return connection type, [TYPE_NOT_CONNECTED] if host is not available
     */
    fun query(ctx: Context): Int {
        if (isAutomotive(ctx)) {
            return TYPE_NATIVE
        }
        return runCatching {
            ctx.contentResolver.query(URI, arrayOf(COLUMN_STATE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(COLUMN_STATE)
                if (column >= 0 && cursor.moveToNext()) cursor.getInt(column) else null
            }
        }.onFailure {
            // provider is missing if Android Auto is not installed
            LOG.d { "No car connection info: ${it.message}" }
        }.getOrNull() ?: TYPE_NOT_CONNECTED
    }

    /**
     * Cold flow of connection state, true if connected to any car
     */
    fun observe(ctx: Context): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            // provider is queried out of main thread
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        // broadcast is sent by Android Auto app
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter(ACTION_UPDATED),
            ContextCompat.RECEIVER_EXPORTED
        )
        trySend(Unit)
        awaitClose {
            ctx.unregisterReceiver(receiver)
        }
    }.map { query(ctx) != TYPE_NOT_CONNECTED }.flowOn(Dispatchers.IO).distinctUntilChanged()
}
