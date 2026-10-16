/**
 * @file
 * @brief Oscilloscope Vulkan renderer native interface
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.view.Surface
import app.zxtune.Logger
import java.nio.ByteBuffer

private val LOG = Logger("VulkanScope")

/**
 * See src/main/vulkan/android.cpp. All the calls for single handle should be made from the same thread.
 */
internal object VulkanScopeNative {
    const val RESULT_PRESENTED = 0
    const val RESULT_RESIZED = 1
    const val RESULT_SKIPPED = 2
    const val RESULT_ERROR = -1

    /**
     * Small separate library, UI process doesn't load playback engine
     */
    val isLoaded by lazy {
        runCatching {
            System.loadLibrary("zxtunevk")
        }.onFailure {
            LOG.w(it) { "Failed to load library" }
        }.isSuccess
    }

    /**
     * @param driverDir directory with custom driver library or null for system driver
     * @param hooksDir application's native libraries directory, see libadrenotools
     * @return handle or 0 on error
     */
    external fun create(
        surface: Surface,
        driverDir: String?,
        driverName: String?,
        hooksDir: String?,
        tempDir: String?
    ): Long

    external fun describe(handle: Long): String

    external fun update(handle: Long): Int

    /**
     * @param pixels direct buffer with alpha values
     */
    external fun setBackground(handle: Long, pixels: ByteBuffer, width: Int, height: Int, stride: Int): Int

    external fun setTraces(handle: Long, traces: FloatArray, channels: Int, width: Int, height: Int): Int

    external fun render(handle: Long, samples: ShortArray, channels: Int, points: Int, gainPercent: Int): Int

    external fun destroy(handle: Long)
}
