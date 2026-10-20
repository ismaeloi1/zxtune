/**
 *
 * @file
 *
 * @brief All settings dialog implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/all_settings_dialog.h"

#include "apps/lhuna-desktop/engine/engine.h"
#include "apps/lhuna-desktop/ui/options_form.h"
#include "apps/lhuna-desktop/ui/settings.h"

#include <QtWidgets/QComboBox>
#include <QtWidgets/QDialogButtonBox>
#include <QtWidgets/QFormLayout>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QLabel>
#include <QtWidgets/QListWidget>
#include <QtWidgets/QScrollArea>
#include <QtWidgets/QStackedWidget>
#include <QtWidgets/QVBoxLayout>

namespace Lhuna
{
  AllSettingsDialog::AllSettingsDialog(Engine& engine, Settings& settings, FilterCurveView::LiveSource live,
                                       const QString& page, QWidget* parent)
    : QDialog(parent)
    , Player(engine)
    , Options(settings)
    , Live(std::move(live))
  {
    setWindowTitle(tr("Settings"));
    resize(820, 620);
    auto* layout = new QVBoxLayout(this);
    auto* body = new QHBoxLayout();
    auto* list = new QListWidget(this);
    list->setFixedWidth(200);
    auto* pages = new QStackedWidget(this);
    int initial = 0;
    for (const auto& desc : Settings::Pages())
    {
      if (desc.Id == page)
      {
        initial = list->count();
      }
      auto* scroll = new QScrollArea(pages);
      scroll->setWidgetResizable(true);
      scroll->setFrameShape(QFrame::NoFrame);
      if (desc.Id == "output")
      {
        auto* content = new QWidget(scroll);
        auto* contentLayout = new QVBoxLayout(content);
        contentLayout->setContentsMargins(0, 0, 0, 0);
        contentLayout->addWidget(MakeOutputPage());
        contentLayout->addWidget(MakeOptionsForm(Options, desc, Live));
        contentLayout->addStretch();
        scroll->setWidget(content);
      }
      else
      {
        scroll->setWidget(MakeOptionsForm(Options, desc, Live));
      }
      pages->addWidget(scroll);
      list->addItem(desc.Title);
    }
    connect(list, &QListWidget::currentRowChanged, pages, &QStackedWidget::setCurrentIndex);
    list->setCurrentRow(initial);
    body->addWidget(list);
    body->addWidget(pages, 1);
    layout->addLayout(body, 1);
    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Close, this);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    layout->addWidget(buttons);
  }

  QWidget* AllSettingsDialog::MakeOutputPage()
  {
    auto* content = new QWidget();
    auto* form = new QFormLayout(content);
    form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    auto* backends = new QComboBox(content);
    backends->addItem(tr("Automatic"), QString());
    for (const auto& backend : Player.Backends())
    {
      const auto id = QString::fromStdString(backend.Id);
      backends->addItem(QString("%1 (%2)").arg(QString::fromStdString(backend.Description), id), id);
      if (!backend.Available)
      {
        // shown disabled, so it's clear why the backend is not used
        const auto idx = backends->count() - 1;
        backends->setItemData(idx, false, Qt::UserRole - 1);
        backends->setItemData(idx, QString::fromStdString(backend.Status), Qt::ToolTipRole);
      }
    }
    auto* devices = new QComboBox(content);
    auto* info = new QLabel(content);
    info->setWordWrap(true);
    info->setStyleSheet("color: #9a9a9a;");
    const auto fillDevices = [this, devices]() {
      const QSignalBlocker block(devices);
      devices->clear();
      devices->addItem(tr("Default"), QString());
      for (const auto& device : Engine::Devices(Options.GetString(Settings::OUTPUT_BACKEND).toStdString()))
      {
        devices->addItem(QString::fromStdString(device.Name), QString::fromStdString(device.Id));
      }
      devices->setCurrentIndex(std::max(devices->findData(Options.GetString(Settings::OUTPUT_DEVICE)), 0));
      devices->setEnabled(devices->count() > 1);
    };
    const auto updateInfo = [this, info]() {
      const auto current = Player.BackendId();
      const auto estimated = Player.EstimatedLatencyMs();
      info->setText(tr("Playing via: %1. Estimated output latency: %2. Latency compensation in effect: %3 ms. "
                       "Buffers are applied on the next track.")
                        .arg(current.empty() ? tr("none") : QString::fromStdString(current),
                             estimated ? tr("%1 ms").arg(estimated) : tr("unknown"))
                        .arg(Options.EffectiveLatency()));
    };
    backends->setCurrentIndex(std::max(backends->findData(Options.GetString(Settings::OUTPUT_BACKEND)), 0));
    fillDevices();
    updateInfo();
    connect(backends, &QComboBox::activated, this, [this, backends, fillDevices](int idx) {
      Options.SetString(Settings::OUTPUT_DEVICE, QString());
      Options.SetString(Settings::OUTPUT_BACKEND, backends->itemData(idx).toString());
      fillDevices();
    });
    connect(devices, &QComboBox::activated, this, [this, devices](int idx) {
      Options.SetString(Settings::OUTPUT_DEVICE, devices->itemData(idx).toString());
    });
    connect(&Options, &Settings::changed, info, [updateInfo](const QString& /*key*/) { updateInfo(); });
    form->addRow(tr("Output"), backends);
    form->addRow(tr("Device"), devices);
    form->addRow(info);
    return content;
  }
}  // namespace Lhuna
