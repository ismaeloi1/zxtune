/**
 *
 * @file
 *
 * @brief Oscilloscope Vulkan renderer JNI entry points
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/driver.h"
#include "apps/zxtune-android/zxtune/src/main/vulkan/swapchain.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#define EXPORTED extern "C" JNIEXPORT

namespace VulkanScope
{
  const char LOG_TAG[] = "zxtune.VulkanScope";

  struct Context
  {
    ANativeWindow* Window = nullptr;
    std::unique_ptr<Swapchain> Chain;
    std::vector<int16_t> Samples;

    ~Context()
    {
      // surface is destroyed before window release
      Chain.reset();
      if (Window)
      {
        ANativeWindow_release(Window);
      }
    }
  };

  std::string ToString(JNIEnv* env, jstring str)
  {
    if (!str)
    {
      return {};
    }
    const auto* chars = env->GetStringUTFChars(str, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(str, chars);
    return result;
  }

  std::unique_ptr<Context> CreateContext(JNIEnv* env, jobject surface, const DriverParameters& driver)
  {
    auto ctx = std::make_unique<Context>();
    ctx->Window = ANativeWindow_fromSurface(env, surface);
    if (!ctx->Window)
    {
      throw Error("No native window");
    }
    auto lib = OpenDriver(driver);
    Device::Options opts;
    opts.InstanceExtensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    auto* window = ctx->Window;
    opts.CreateSurface = [window](const Api& vk, VkInstance instance) {
      if (!vk.vkCreateAndroidSurfaceKHR)
      {
        throw Error("No Android surface support");
      }
      VkAndroidSurfaceCreateInfoKHR info = {};
      info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
      info.window = window;
      VkSurfaceKHR result = VK_NULL_HANDLE;
      Check(vk.vkCreateAndroidSurfaceKHR(instance, &info, nullptr, &result), "vkCreateAndroidSurfaceKHR");
      return result;
    };
    ctx->Chain = std::make_unique<Swapchain>(std::make_unique<Device>(std::move(lib), opts));
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Created: %s", ctx->Chain->GetDescription().c_str());
    return ctx;
  }

  Context* FromHandle(jlong handle)
  {
    return reinterpret_cast<Context*>(handle);
  }

  template<class F>
  auto Call(const char* name, F&& func, decltype(func()) onError) -> decltype(func())
  {
    try
    {
      return func();
    }
    catch (const std::exception& e)
    {
      __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s: %s", name, e.what());
      return onError;
    }
  }

  // see VulkanScopeNative.kt
  const jint RESULT_ERROR = -1;
}  // namespace VulkanScope

using namespace VulkanScope;

EXPORTED jlong JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_create(JNIEnv* env, jobject /*self*/, jobject surface,
                                                                         jstring driverDir, jstring driverName,
                                                                         jstring hooksDir, jstring tempDir)
{
  const DriverParameters driver{ToString(env, driverDir), ToString(env, driverName), ToString(env, hooksDir),
                                ToString(env, tempDir)};
  return Call(
      "create", [&]() { return reinterpret_cast<jlong>(CreateContext(env, surface, driver).release()); }, jlong(0));
}

EXPORTED jstring JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_describe(JNIEnv* env, jobject /*self*/,
                                                                             jlong handle)
{
  const auto desc = Call(
      "describe", [&]() { return FromHandle(handle)->Chain->GetDescription(); }, std::string());
  return env->NewStringUTF(desc.c_str());
}

EXPORTED jint JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_update(JNIEnv* /*env*/, jobject /*self*/, jlong handle)
{
  return Call(
      "update", [&]() { return static_cast<jint>(FromHandle(handle)->Chain->Update()); }, RESULT_ERROR);
}

EXPORTED jint JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_setBackground(JNIEnv* env, jobject /*self*/,
                                                                               jlong handle, jobject pixels, jint width,
                                                                               jint height, jint stride)
{
  return Call(
      "setBackground",
      [&]() {
        const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
        if (!data || env->GetDirectBufferCapacity(pixels) < jlong(stride) * height || stride < width)
        {
          throw Error("Invalid background");
        }
        FromHandle(handle)->Chain->Renderer().SetBackground(data, width, height, stride);
        return jint(0);
      },
      RESULT_ERROR);
}

EXPORTED jint JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_setTraces(JNIEnv* env, jobject /*self*/, jlong handle,
                                                                           jfloatArray traces, jint channels,
                                                                           jint width, jint height)
{
  return Call(
      "setTraces",
      [&]() {
        const auto count = std::min<jint>(channels, env->GetArrayLength(traces) / 4);
        float data[ScopeRenderer::MAX_CHANNELS * 4];
        const auto used = std::min<jint>(count, ScopeRenderer::MAX_CHANNELS);
        env->GetFloatArrayRegion(traces, 0, used * 4, data);
        FromHandle(handle)->Chain->Renderer().SetTraces(data, used, width, height);
        return jint(0);
      },
      RESULT_ERROR);
}

EXPORTED jint JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_render(JNIEnv* env, jobject /*self*/, jlong handle,
                                                                        jshortArray samples, jint channels, jint points,
                                                                        jint gainPercent)
{
  return Call(
      "render",
      [&]() {
        const auto size = env->GetArrayLength(samples);
        if (channels < 0 || points < 0 || jlong(channels) * points > size)
        {
          throw Error("Invalid samples");
        }
        // copied before rendering: critical access to array would block garbage collector while waiting for vsync
        auto* ctx = FromHandle(handle);
        ctx->Samples.resize(std::size_t(channels) * points);
        env->GetShortArrayRegion(samples, 0, channels * points, ctx->Samples.data());
        return static_cast<jint>(ctx->Chain->Render(ctx->Samples.data(), channels, points, gainPercent));
      },
      RESULT_ERROR);
}

EXPORTED void JNICALL Java_app_zxtune_ui_views_VulkanScopeNative_destroy(JNIEnv* /*env*/, jobject /*self*/,
                                                                         jlong handle)
{
  delete FromHandle(handle);
}
