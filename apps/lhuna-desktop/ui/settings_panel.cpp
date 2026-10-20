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

#include "apps/lhuna-desktop/ui/options_form.h"
#include "apps/lhuna-desktop/ui/settings.h"
#include "apps/lhuna-desktop/ui/theme.h"

#include <QtGui/QKeyEvent>
#include <QtGui/QPainter>
#include <QtWidgets/QButtonGroup>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QLabel>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QScrollArea>
#include <QtWidgets/QStackedWidget>
#include <QtWidgets/QVBoxLayout>

namespace Lhuna
{
  // ~70% opacity, as Android panel
  const QColor PANEL_BACKGROUND(0x10, 0x10, 0x10, 0xb3);

  SettingsPanel::SettingsPanel(Settings& settings, FilterCurveView::LiveSource live, QWidget* parent)
    : QWidget(parent)
    , Options(settings)
    , Live(std::move(live))
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

  void SettingsPanel::Setup(const QStringList& chips, const QStringList& names)
  {
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
      if (page.Id == "scope" || page.Id == "trigger" || page.Id == "general")
      {
        AddPage(page.Title, MakeOptionsForm(Options, page, Live));
      }
      else if (chips.contains(page.Id))
      {
        AddPage(page.Title, MakeOptionsForm(Options, page, Live));
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
