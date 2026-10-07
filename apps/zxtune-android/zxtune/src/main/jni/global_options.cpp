/**
 *
 * @file
 *
 * @brief Global parameters implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/jni/global_options.h"

#include "apps/zxtune-android/zxtune/src/main/jni/defines.h"
#include "apps/zxtune-android/zxtune/src/main/jni/properties.h"

#include "make_ptr.h"

#include <mutex>

namespace Parameters
{
  class SynchronizedContainer : public Container
  {
  public:
    uint_t Version() const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->Version();
    }

    std::optional<IntType> FindInteger(Identifier name) const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->FindInteger(name);
    }

    std::optional<StringType> FindString(Identifier name) const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->FindString(name);
    }

    Binary::Data::Ptr FindData(Identifier name) const override
    {
      const std::scoped_lock guard(Lock);
      return Delegate->FindData(name);
    }

    void Process(Visitor& visitor) const override
    {
      // visitor is called without lock to allow it to access this container
      const auto snapshot = [this]() {
        const std::scoped_lock guard(Lock);
        return Container::Clone(*Delegate);
      }();
      snapshot->Process(visitor);
    }

    void SetValue(Identifier name, IntType val) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->SetValue(name, val);
    }

    void SetValue(Identifier name, StringView val) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->SetValue(name, val);
    }

    void SetValue(Identifier name, Binary::View val) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->SetValue(name, val);
    }

    void RemoveValue(Identifier name) override
    {
      const std::scoped_lock guard(Lock);
      Delegate->RemoveValue(name);
    }

  private:
    mutable std::mutex Lock;
    const Container::Ptr Delegate = Container::Create();
  };

  Container::Ptr CreateSynchronizedContainer()
  {
    return MakePtr<SynchronizedContainer>();
  }

  Container& GlobalOptions()
  {
    static const Parameters::Container::Ptr instance = CreateSynchronizedContainer();
    return *instance;
  }
}  // namespace Parameters

EXPORTED jlong JNICALL Java_app_zxtune_core_jni_JniOptions_getProperty__Ljava_lang_String_2J(JNIEnv* env,
                                                                                             jobject /*self*/,
                                                                                             jstring propName,
                                                                                             jlong defVal)
{
  const auto& params = Parameters::GlobalOptions();
  const Jni::PropertiesReadHelper props(env, params);
  return props.Get(propName, defVal);
}

EXPORTED jstring JNICALL Java_app_zxtune_core_jni_JniOptions_getProperty__Ljava_lang_String_2Ljava_lang_String_2(
    JNIEnv* env, jobject /*self*/, jstring propName, jstring defVal)
{
  const auto& params = Parameters::GlobalOptions();
  const Jni::PropertiesReadHelper props(env, params);
  return props.Get(propName, defVal);
}

EXPORTED void JNICALL Java_app_zxtune_core_jni_JniOptions_setProperty__Ljava_lang_String_2J(JNIEnv* env,
                                                                                            jobject /*self*/,
                                                                                            jstring propName,
                                                                                            jlong value)
{
  auto& params = Parameters::GlobalOptions();
  Jni::PropertiesWriteHelper props(env, params);
  props.Set(propName, value);
}

EXPORTED void JNICALL Java_app_zxtune_core_jni_JniOptions_setProperty__Ljava_lang_String_2Ljava_lang_String_2(
    JNIEnv* env, jobject /*self*/, jstring propName, jstring value)
{
  auto& params = Parameters::GlobalOptions();
  Jni::PropertiesWriteHelper props(env, params);
  props.Set(propName, value);
}
