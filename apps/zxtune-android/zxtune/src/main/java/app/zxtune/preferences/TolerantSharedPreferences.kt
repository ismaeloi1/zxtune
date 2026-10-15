package app.zxtune.preferences

import android.content.SharedPreferences
import app.zxtune.Logger

private val LOG = Logger(TolerantSharedPreferences::class.java.name)

/**
 * Another build of the app installed over this one (e.g. official release) may have stored the same
 * key with different type. Typed read throws ClassCastException then, so such a value is dropped and
 * default is used instead of crashing on each start.
 */
class TolerantSharedPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {

    override fun getString(key: String?, defValue: String?): String? = tolerant(key, defValue) { delegate.getString(key, defValue) }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = tolerant(key, defValues) { delegate.getStringSet(key, defValues) }

    override fun getInt(key: String?, defValue: Int) = tolerant(key, defValue) { delegate.getInt(key, defValue) }

    override fun getLong(key: String?, defValue: Long) = tolerant(key, defValue) { delegate.getLong(key, defValue) }

    override fun getFloat(key: String?, defValue: Float) = tolerant(key, defValue) { delegate.getFloat(key, defValue) }

    override fun getBoolean(key: String?, defValue: Boolean) = tolerant(key, defValue) { delegate.getBoolean(key, defValue) }

    private inline fun <T> tolerant(key: String?, defValue: T, read: () -> T): T = try {
        read()
    } catch (e: ClassCastException) {
        LOG.w(e) { "Drop incompatible value of '$key'" }
        runCatching { delegate.edit().remove(key).apply() }
        defValue
    }
}
