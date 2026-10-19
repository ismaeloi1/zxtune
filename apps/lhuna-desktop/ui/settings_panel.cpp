/**
 *
 * @file
 *
 * @brief Live settings panel over visualization implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/settings_panel.h"

#include "apps/lhuna-desktop/ui/settings.h"
#include "apps/lhuna-desktop/ui/theme.h"
#include "visualization/sid_filter_model.h"
#include "visualization/trigger_presets.h"

#include <QtGui/QKeyEvent>
#include <QtGui/QPainter>
#include <QtGui/QPainterPath>
#include <QtWidgets/QButtonGroup>
#include <QtWidgets/QCheckBox>
#include <QtWidgets/QComboBox>
#include <QtWidgets/QFormLayout>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QLabel>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QScrollArea>
#include <QtWidgets/QSlider>
#include <QtWidgets/QStackedWidget>
#include <QtWidgets/QVBoxLayout>

#include <cmath>

namespace Lhuna
{
  // ~70% opacity, as Android panel
  const QColor PANEL_BACKGROUND(0x10, 0x10, 0x10, 0xb3);

  //! Cutoff curves of SID filter really emulated with current settings (see Android's FilterCurveView)
  class FilterCurveView : public QWidget
  {
  public:
    FilterCurveView(Settings& settings, QWidget* parent)
      : QWidget(parent)
      , Options(settings)
    {
      setMinimumHeight(130);
    }

  protected:
    void paintEvent(QPaintEvent* /*event*/) override
    {
      Visualization::SidFilterModel::Settings model;
      model.ResidFp = Options.Get("zxtune.core.sid.engine") != 0;
      model.Curve6581 = Options.Get("zxtune.core.sid.filter_6581_curve") / 100.0;
      model.Range6581 = Options.Get("zxtune.core.sid.filter_6581_range") / 100.0;
      model.Curve8580 = Options.Get("zxtune.core.sid.filter_8580_curve") / 100.0;
      model.BiasMv = Options.Get("zxtune.core.sid.filter_bias");
      const auto is8580 = Options.Get("zxtune.core.sid.model") != 0;
      const auto curve = is8580 ? Visualization::SidFilterModel::Cutoff8580(model)
                                : Visualization::SidFilterModel::Cutoff6581(model);
      const auto other = is8580 ? Visualization::SidFilterModel::Cutoff6581(model)
                                : Visualization::SidFilterModel::Cutoff8580(model);
      QPainter painter(this);
      painter.setRenderHint(QPainter::Antialiasing);
      const QRectF area = QRectF(rect()).adjusted(34, 6, -6, -18);
      painter.setPen(QColor(255, 255, 255, 40));
      // log scale 30Hz..20kHz
      const auto toY = [&area](double hz) {
        const auto pos =
            (std::log10(std::clamp(hz, 30.0, 20000.0)) - std::log10(30.0)) / (std::log10(20000.0) - std::log10(30.0));
        return area.bottom() - area.height() * pos;
      };
      painter.setFont(QFont(font().family(), 8));
      for (const auto hz : {100.0, 1000.0, 10000.0})
      {
        const auto y = toY(hz);
        painter.setPen(QColor(255, 255, 255, 40));
        painter.drawLine(QPointF(area.left(), y), QPointF(area.right(), y));
        painter.setPen(QColor(255, 255, 255, 130));
        painter.drawText(QRectF(0, y - 8, area.left() - 4, 16), Qt::AlignRight | Qt::AlignVCenter,
                         hz >= 1000 ? QString("%1k").arg(hz / 1000) : QString::number(hz));
      }
      painter.drawText(QRectF(area.left(), area.bottom() + 2, area.width(), 14), Qt::AlignCenter,
                       tr("cutoff register 0..2047, %1 solid").arg(is8580 ? "8580" : "6581"));
      const auto plot = [&](const std::vector<double>& values, const QColor& color) {
        QPainterPath path;
        for (std::size_t fc = 0; fc < values.size(); fc += 8)
        {
          const QPointF pt(area.left() + area.width() * fc / (values.size() - 1), toY(values[fc]));
          if (fc == 0)
          {
            path.moveTo(pt);
          }
          else
          {
            path.lineTo(pt);
          }
        }
        painter.setPen(QPen(color, 1.6));
        painter.drawPath(path);
      };
      plot(other, QColor(255, 255, 255, 70));
      plot(curve, Theme::ACCENT);
    }

  private:
    Settings& Options;
  };

  SettingsPanel::SettingsPanel(Settings& settings, QWidget* parent)
    : QWidget(parent)
    , Options(settings)
  {
    setAttribute(Qt::WA_NoSystemBackground);
    setFocusPolicy(Qt::StrongFocus);
    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(8, 8, 8, 8);
    auto* header = new QHBoxLayout();
    auto* tabsArea = new QScrollArea(this);
    tabsArea->setWidgetResizable(true);
    tabsArea->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    tabsArea->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    tabsArea->setFrameShape(QFrame::NoFrame);
    tabsArea->setStyleSheet("background: transparent;");
    tabsArea->setFixedHeight(44);
    auto* tabsWidget = new QWidget(tabsArea);
    Tabs = new QHBoxLayout(tabsWidget);
    Tabs->setContentsMargins(0, 0, 0, 0);
    Tabs->setSpacing(6);
    Tabs->addStretch();
    tabsArea->setWidget(tabsWidget);
    header->addWidget(tabsArea, 1);
    auto* close = new QPushButton(QString::fromUtf8("✕"), this);
    close->setFlat(true);
    close->setFixedSize(32, 32);
    close->setToolTip(tr("Close (Esc)"));
    connect(close, &QPushButton::clicked, this, &QWidget::hide);
    header->addWidget(close);
    layout->addLayout(header);
    TabsGroup = new QButtonGroup(this);
    Pages = new QStackedWidget(this);
    layout->addWidget(Pages, 1);
    connect(TabsGroup, &QButtonGroup::idClicked, Pages, &QStackedWidget::setCurrentIndex);
    connect(&Options, &Settings::changed, this, [this](const QString& key) {
      const auto [begin, end] = Updaters.equal_range(key);
      for (auto it = begin; it != end; ++it)
      {
        it->second();
      }
      if (key.startsWith(QString::fromStdString(Visualization::TriggerPresets::PREFIX)))
      {
        const auto [pbegin, pend] = Updaters.equal_range("preset");
        for (auto it = pbegin; it != pend; ++it)
        {
          it->second();
        }
      }
    });
  }

  void SettingsPanel::paintEvent(QPaintEvent* /*event*/)
  {
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setPen(Qt::NoPen);
    painter.setBrush(PANEL_BACKGROUND);
    painter.drawRoundedRect(QRectF(rect()), 12, 12);
  }

  void SettingsPanel::keyPressEvent(QKeyEvent* event)
  {
    if (event->key() == Qt::Key_Escape)
    {
      hide();
      return;
    }
    QWidget::keyPressEvent(event);
  }

  void SettingsPanel::AddPage(const QString& title, QWidget* content)
  {
    const auto idx = Pages->count();
    auto* scroll = new QScrollArea(Pages);
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    scroll->setStyleSheet("QScrollArea, QScrollArea > QWidget > QWidget { background: transparent; }");
    scroll->setWidget(content);
    Pages->addWidget(scroll);
    auto* tab = new QPushButton(title);
    tab->setCheckable(true);
    tab->setMinimumHeight(36);
    tab->setStyleSheet(QString("QPushButton { background: rgba(255,255,255,0.2); color: #ddd; border-radius: 18px;"
                               " padding: 0 14px; font-weight: bold; border: none; }"
                               "QPushButton:checked { background: %1; color: white; }")
                           .arg(Theme::ACCENT.name()));
    TabsGroup->addButton(tab, idx);
    Tabs->insertWidget(Tabs->count() - 1, tab);
    if (idx == 0)
    {
      tab->setChecked(true);
    }
  }

  QWidget* SettingsPanel::MakePage(const OptionsPage& page)
  {
    auto* content = new QWidget();
    auto* form = new QFormLayout(content);
    form->setRowWrapPolicy(QFormLayout::WrapLongRows);
    form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    for (const auto& desc : page.Options)
    {
      const auto key = desc.Key;
      switch (desc.Type)
      {
      case OptionDesc::CHOICE:
      {
        auto* combo = new QComboBox(content);
        for (const auto& [label, value] : desc.Choices)
        {
          combo->addItem(label, value);
        }
        const auto update = [this, combo, key]() {
          const QSignalBlocker block(combo);
          combo->setCurrentIndex(std::max(combo->findData(Options.Get(key)), 0));
        };
        update();
        Updaters.emplace(key, update);
        connect(combo, &QComboBox::activated, this,
                [this, combo, key](int idx) { Options.Set(key, combo->itemData(idx).toInt()); });
        form->addRow(desc.Title, combo);
        break;
      }
      case OptionDesc::BOOLEAN:
      {
        auto* check = new QCheckBox(desc.Title, content);
        const auto update = [this, check, key]() {
          const QSignalBlocker block(check);
          check->setChecked(Options.Get(key) != 0);
        };
        update();
        Updaters.emplace(key, update);
        connect(check, &QCheckBox::toggled, this, [this, key](bool on) { Options.Set(key, on ? 1 : 0); });
        form->addRow(check);
        break;
      }
      default:
      {
        auto* row = new QWidget(content);
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(0, 0, 0, 0);
        auto* slider = new QSlider(Qt::Horizontal, row);
        slider->setRange(desc.Min, desc.Max);
        auto* value = new QLabel(row);
        value->setMinimumWidth(56);
        value->setAlignment(Qt::AlignRight | Qt::AlignVCenter);
        rowLayout->addWidget(slider, 1);
        rowLayout->addWidget(value);
        const auto unit = desc.Unit;
        const auto update = [this, slider, value, key, unit]() {
          const QSignalBlocker block(slider);
          slider->setValue(Options.Get(key));
          value->setText(QString::number(Options.Get(key)) + unit);
        };
        update();
        Updaters.emplace(key, update);
        connect(slider, &QSlider::valueChanged, this, [this, key, value, unit](int val) {
          value->setText(QString::number(val) + unit);
          Options.Set(key, val);
        });
        form->addRow(desc.Title, row);
        break;
      }
      }
    }
    if (page.Id == "sid")
    {
      auto* curve = new FilterCurveView(Options, content);
      for (const auto* key :
           {"zxtune.core.sid.engine", "zxtune.core.sid.model", "zxtune.core.sid.filter_6581_curve",
            "zxtune.core.sid.filter_6581_range", "zxtune.core.sid.filter_8580_curve", "zxtune.core.sid.filter_bias"})
      {
        Updaters.emplace(key, [curve]() { curve->update(); });
      }
      form->addRow(tr("Filter cutoff"), curve);
    }
    return content;
  }

  QWidget* SettingsPanel::MakeTriggerPage(const OptionsPage& page)
  {
    auto* content = MakePage(page);
    auto* form = static_cast<QFormLayout*>(content->layout());
    auto* presets = new QComboBox(content);
    for (const auto& preset : Visualization::TriggerPresets::Presets())
    {
      presets->addItem(QString::fromStdString(preset.Title), QString::fromStdString(preset.Id));
    }
    presets->addItem(tr("Custom"), QString::fromStdString(Visualization::TriggerPresets::CUSTOM));
    const auto update = [this, presets]() {
      const QSignalBlocker block(presets);
      presets->setCurrentIndex(presets->findData(Options.TriggerPreset()));
    };
    update();
    Updaters.emplace("preset", update);
    connect(presets, &QComboBox::activated, this,
            [this, presets](int idx) { Options.ApplyTriggerPreset(presets->itemData(idx).toString()); });
    form->insertRow(0, tr("Preset"), presets);
    return content;
  }

  void SettingsPanel::Setup(const QStringList& chips, const QStringList& names)
  {
    Updaters.clear();
    while (Pages->count())
    {
      auto* page = Pages->widget(0);
      Pages->removeWidget(page);
      page->deleteLater();
    }
    for (auto* button : TabsGroup->buttons())
    {
      TabsGroup->removeButton(button);
      button->deleteLater();
    }
    QStringList shown;
    for (const auto& page : Settings::Pages())
    {
      if (page.Id == "scope" || page.Id == "general")
      {
        AddPage(page.Title, MakePage(page));
      }
      else if (page.Id == "trigger")
      {
        AddPage(page.Title, MakeTriggerPage(page));
      }
      else if (chips.contains(page.Id))
      {
        AddPage(page.Title, MakePage(page));
        shown << page.Id;
      }
    }
    // chips without own settings get hint page, so it's clear that nothing is missed
    if (shown.isEmpty() && !names.isEmpty())
    {
      auto* hint = new QLabel(tr("%1\n\nNo emulation settings for these chips").arg(names.join(", ")));
      hint->setWordWrap(true);
      hint->setAlignment(Qt::AlignTop | Qt::AlignLeft);
      hint->setContentsMargins(8, 8, 8, 8);
      AddPage(names.join(", "), hint);
    }
    Pages->setCurrentIndex(0);
  }
}  // namespace Lhuna
