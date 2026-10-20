/**
 *
 * @file
 *
 * @brief Settings page controls implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/options_form.h"

#include "apps/lhuna-desktop/ui/settings.h"
#include "visualization/trigger_presets.h"

#include <QtWidgets/QCheckBox>
#include <QtWidgets/QComboBox>
#include <QtWidgets/QFormLayout>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QLabel>
#include <QtWidgets/QSlider>

#include <algorithm>

namespace Lhuna
{
  // Updates control when option is changed elsewhere, connection is dropped together with control
  template<class Updater>
  void Bind(Settings& settings, QWidget* control, const QString& key, Updater update)
  {
    update();
    QObject::connect(&settings, &Settings::changed, control, [key, update](const QString& changed) {
      if (changed == key)
      {
        update();
      }
    });
  }

  QWidget* MakeChoice(Settings& settings, const OptionDesc& desc, QWidget* parent)
  {
    auto* combo = new QComboBox(parent);
    for (const auto& [label, value] : desc.Choices)
    {
      combo->addItem(label, value);
    }
    const auto key = desc.Key;
    Bind(settings, combo, key, [&settings, combo, key]() {
      const QSignalBlocker block(combo);
      combo->setCurrentIndex(std::max(combo->findData(settings.Get(key)), 0));
    });
    QObject::connect(combo, &QComboBox::activated, combo,
                     [&settings, combo, key](int idx) { settings.Set(key, combo->itemData(idx).toInt()); });
    return combo;
  }

  QWidget* MakeCheck(Settings& settings, const OptionDesc& desc, QWidget* parent)
  {
    auto* check = new QCheckBox(desc.Title, parent);
    const auto key = desc.Key;
    Bind(settings, check, key, [&settings, check, key]() {
      const QSignalBlocker block(check);
      check->setChecked(settings.Get(key) != 0);
    });
    QObject::connect(check, &QCheckBox::toggled, check, [&settings, key](bool on) { settings.Set(key, on ? 1 : 0); });
    return check;
  }

  QWidget* MakeSlider(Settings& settings, const OptionDesc& desc, QWidget* parent)
  {
    auto* row = new QWidget(parent);
    auto* rowLayout = new QHBoxLayout(row);
    rowLayout->setContentsMargins(0, 0, 0, 0);
    auto* slider = new QSlider(Qt::Horizontal, row);
    slider->setRange(desc.Min, desc.Max);
    auto* value = new QLabel(row);
    value->setMinimumWidth(56);
    value->setAlignment(Qt::AlignRight | Qt::AlignVCenter);
    rowLayout->addWidget(slider, 1);
    rowLayout->addWidget(value);
    const auto key = desc.Key;
    const auto unit = desc.Unit;
    Bind(settings, slider, key, [&settings, slider, value, key, unit]() {
      const QSignalBlocker block(slider);
      slider->setValue(settings.Get(key));
      value->setText(QString::number(settings.Get(key)) + unit);
    });
    QObject::connect(slider, &QSlider::valueChanged, slider, [&settings, key, value, unit](int val) {
      value->setText(QString::number(val) + unit);
      settings.Set(key, val);
    });
    return row;
  }

  QWidget* MakeTriggerPresets(Settings& settings, QWidget* parent)
  {
    auto* presets = new QComboBox(parent);
    for (const auto& preset : Visualization::TriggerPresets::Presets())
    {
      presets->addItem(QString::fromStdString(preset.Title), QString::fromStdString(preset.Id));
    }
    presets->addItem(QObject::tr("Custom"), QString::fromStdString(Visualization::TriggerPresets::CUSTOM));
    const auto update = [&settings, presets]() {
      const QSignalBlocker block(presets);
      presets->setCurrentIndex(presets->findData(settings.TriggerPreset()));
    };
    update();
    // any trigger parameter may change detected preset
    const auto prefix = QString::fromStdString(Visualization::TriggerPresets::PREFIX);
    QObject::connect(&settings, &Settings::changed, presets, [prefix, update](const QString& key) {
      if (key.startsWith(prefix))
      {
        update();
      }
    });
    QObject::connect(presets, &QComboBox::activated, presets,
                     [&settings, presets](int idx) { settings.ApplyTriggerPreset(presets->itemData(idx).toString()); });
    return presets;
  }

  QWidget* MakeOptionsForm(Settings& settings, const OptionsPage& page, const FilterCurveView::LiveSource& live)
  {
    auto* content = new QWidget();
    auto* form = new QFormLayout(content);
    form->setRowWrapPolicy(QFormLayout::WrapLongRows);
    form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    if (page.Id == "trigger")
    {
      form->addRow(QObject::tr("Preset"), MakeTriggerPresets(settings, content));
    }
    for (const auto& desc : page.Options)
    {
      switch (desc.Type)
      {
      case OptionDesc::CHOICE:
        form->addRow(desc.Title, MakeChoice(settings, desc, content));
        break;
      case OptionDesc::BOOLEAN:
        form->addRow(MakeCheck(settings, desc, content));
        break;
      default:
        form->addRow(desc.Title, MakeSlider(settings, desc, content));
        break;
      }
      // curve is right under presets, so the effect of selection is visible at once (as in Android)
      if (desc.Key == "zxtune.core.sid.filter_8580_preset")
      {
        auto* curve = new FilterCurveView(settings, live, content);
        form->addRow(curve);
      }
    }
    return content;
  }
}  // namespace Lhuna
