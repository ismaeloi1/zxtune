/**
 *
 * @file
 *
 * @brief Persistent settings of desktop player: view options and native core options
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <QtCore/QObject>
#include <QtCore/QSettings>
#include <QtCore/QString>

#include <map>
#include <vector>

namespace Lhuna
{
  class Engine;

  //! Description of single integer option, as Android preferences screens define them
  struct OptionDesc
  {
    enum Kind
    {
      CHOICE,
      BOOLEAN,
      SLIDER
    };

    //! native option name (zxtune.*) or view option (ui.*)
    QString Key;
    QString Title;
    Kind Type = SLIDER;
    int Default = 0;
    int Min = 0;
    int Max = 100;
    //! for CHOICE: labels and values
    std::vector<std::pair<QString, int>> Choices;
    //! suffix of slider value (e.g. "ms")
    QString Unit;
  };

  //! Settings page (as Android preferences screen category)
  struct OptionsPage
  {
    //! chip id (see Visualization::EmulationContext) or "scope", "trigger", "general", "output"
    QString Id;
    QString Title;
    std::vector<OptionDesc> Options;
  };

  /**
   * Options are stored in QSettings and native ones are mirrored to engine options container, which is live, so
   * changes are applied to playing tune immediately.
   */
  class Settings : public QObject
  {
    Q_OBJECT
  public:
    Settings(Engine& engine, QObject* parent);

    static const std::vector<OptionsPage>& Pages();
    static const OptionDesc* Find(const QString& key);

    int Get(const QString& key) const;
    void Set(const QString& key, int value);
    //! Not numeric view options (e.g. output device)
    QString GetString(const QString& key) const;
    void SetString(const QString& key, const QString& value);
    //! Raw access to storage for other persistent state (e.g. window geometry)
    QSettings& Storage()
    {
      return Store;
    }
    //! Applies trigger preset by id (see Visualization::TriggerPresets)
    void ApplyTriggerPreset(const QString& id);
    QString TriggerPreset() const;
    //! Applies output options and latency compensation (estimated by output buffers in automatic mode)
    void ApplyOutput();
    //! Latency compensation in effect
    int EffectiveLatency() const;
    //! Estimation depends on really used backend, which is known after playback start
    void UpdateLatency();

    // view options
    static constexpr auto WINDOW_MS = "ui.oscilloscope.window_ms";
    static constexpr auto GAIN = "ui.oscilloscope.gain";
    static constexpr auto MAX_FPS = "ui.oscilloscope.max_fps";
    static constexpr auto GAUGE_WINDOW_MS = "ui.oscilloscope.gauge_window_ms";
    static constexpr auto LATENCY_MS = "ui.oscilloscope.latency_ms";
    //! estimate latency by output buffers size
    static constexpr auto LATENCY_AUTO = "ui.oscilloscope.latency_auto";
    static constexpr auto SPECTRUM = "ui.view.spectrum";
    static constexpr auto DASHBOARD = "ui.view.dashboard";
    //! strings: backend id and device id, empty for automatic and default
    static constexpr auto OUTPUT_BACKEND = "ui.output.backend";
    static constexpr auto OUTPUT_DEVICE = "ui.output.device";
    static constexpr auto SHUFFLE = "ui.playlist.shuffle";
    static constexpr auto REPEAT = "ui.playlist.repeat";

  signals:
    void changed(const QString& key);

  private:
    void Apply(const QString& key, int value);

  private:
    Engine& Player;
    QSettings Store;
  };
}  // namespace Lhuna
