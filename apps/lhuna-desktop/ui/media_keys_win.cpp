/**
 *
 * @file
 *
 * @brief Global media keys on Windows
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/media_keys.h"

#include <QtCore/QAbstractNativeEventFilter>
#include <QtCore/QCoreApplication>
#include <QtWidgets/QWidget>

#include <windows.h>

#include <vector>

namespace Lhuna
{
  /**
   * Media keys are registered as system wide hotkeys, so they work while window is not focused. Registration fails
   * if another player already owns them, then keys still work in focused window via Qt shortcuts.
   * System media transport controls (SMTC overlay) require WinRT and are not used.
   */
  class WindowsMediaKeys
    : public MediaKeys
    , public QAbstractNativeEventFilter
  {
  public:
    WindowsMediaKeys(QWidget* window, MediaCommands commands)
      : Commands(std::move(commands))
      , Window(reinterpret_cast<HWND>(window->winId()))
    {
      for (const auto key : KEYS)
      {
        if (::RegisterHotKey(Window, key, MOD_NOREPEAT, key))
        {
          Registered.push_back(key);
        }
      }
      QCoreApplication::instance()->installNativeEventFilter(this);
    }

    ~WindowsMediaKeys() override
    {
      QCoreApplication::instance()->removeNativeEventFilter(this);
      for (const auto key : Registered)
      {
        ::UnregisterHotKey(Window, key);
      }
    }

    void Update(const MediaStatus& /*status*/) override {}
    void Seeked(uint32_t /*positionMs*/) override {}

    bool nativeEventFilter(const QByteArray& /*eventType*/, void* message, qintptr* /*result*/) override
    {
      const auto* msg = static_cast<const MSG*>(message);
      if (msg->message != WM_HOTKEY || msg->hwnd != Window)
      {
        return false;
      }
      switch (msg->wParam)
      {
      case VK_MEDIA_PLAY_PAUSE:
        Commands.PlayPause();
        return true;
      case VK_MEDIA_NEXT_TRACK:
        Commands.Next();
        return true;
      case VK_MEDIA_PREV_TRACK:
        Commands.Previous();
        return true;
      case VK_MEDIA_STOP:
        Commands.Stop();
        return true;
      default:
        return false;
      }
    }

  private:
    // hotkey ids are the same as virtual keys
    static constexpr UINT KEYS[] = {VK_MEDIA_PLAY_PAUSE, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PREV_TRACK, VK_MEDIA_STOP};
    const MediaCommands Commands;
    const HWND Window;
    std::vector<UINT> Registered;
  };

  std::unique_ptr<MediaKeys> MediaKeys::Create(QWidget* window, MediaCommands commands)
  {
    return std::make_unique<WindowsMediaKeys>(window, std::move(commands));
  }
}  // namespace Lhuna
