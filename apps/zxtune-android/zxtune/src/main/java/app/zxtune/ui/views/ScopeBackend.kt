/**
 * @file
 * @brief Oscilloscope rendering backend selection
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class ScopeBackend {
    VULKAN,
    GLES;

    companion object {
        // see pref_oscilloscope_backend_values
        const val PREF_AUTO = "auto"
        const val PREF_VULKAN = "vulkan"
        const val PREF_GLES = "gles"

        // Vulkan 1.0
        private const val VULKAN_1_0 = 0x400000

        /**
         * @param preference one of PREF_* values, unknown ones are treated as [PREF_AUTO]
         * @param isVulkanSupported device has Vulkan driver and renderer library is loaded
         * @param isVulkanFailed Vulkan renderer failed to start before
         */
        fun select(preference: String?, isVulkanSupported: Boolean, isVulkanFailed: Boolean) = when {
            preference == PREF_GLES -> GLES

            !isVulkanSupported || isVulkanFailed -> GLES

            // the only another choice, so auto is Vulkan if possible: less CPU work per frame
            else -> VULKAN
        }

        /**
         * Vulkan 1.0+ hardware support is declared since Android 7, on older systems there's no Vulkan loader
         */
        fun isVulkanSupported(ctx: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0) &&
            VulkanScopeNative.isLoaded

        /**
         * Session-wide failure flag, e.g. driver doesn't support required functionality
         */
        @Volatile
        var isVulkanFailed = false
    }
}
