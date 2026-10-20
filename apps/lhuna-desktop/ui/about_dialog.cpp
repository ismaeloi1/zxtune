/**
 *
 * @file
 *
 * @brief About dialog implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/about_dialog.h"

#include "apps/lhuna-desktop/engine/engine.h"
#include "apps/lhuna-desktop/ui/open_source_components.h"
#include "visualization/engine_brands.h"

#include "platform/version/api.h"

#include <QtCore/QSysInfo>
#include <QtGui/QGuiApplication>
#include <QtWidgets/QDialogButtonBox>
#include <QtWidgets/QHeaderView>
#include <QtWidgets/QLabel>
#include <QtWidgets/QTabWidget>
#include <QtWidgets/QTextBrowser>
#include <QtWidgets/QTreeWidget>
#include <QtWidgets/QVBoxLayout>

namespace Lhuna
{
  QWidget* MakeCredits()
  {
    auto* label = new QLabel();
    label->setTextFormat(Qt::RichText);
    label->setAlignment(Qt::AlignTop | Qt::AlignLeft);
    label->setWordWrap(true);
    label->setContentsMargins(12, 12, 12, 12);
    label->setTextInteractionFlags(Qt::TextBrowserInteraction);
    label->setOpenExternalLinks(true);
    const auto version = QString::fromStdString(Platform::Version::GetProgramVersionString());
    label->setText(QObject::tr("<p><big><b>Lhuna</b></big><br/>%1</p>"
                               "<p>Idea, code: <b>Vitamin/CAIG</b><br/>Co-author: <b>LHUNA</b><br/>"
                               "Sound engines: %2 (C64), %3 (SNES)</p>"
                               "<p>Based on <a href=\"https://github.com/vitamin-caig/zxtune\">ZXTune</a> core.</p>"
                               "<p>Qt %4 on %5 (%6)</p>"
                               "<p>Dedicated to <b>Lizzi</b></p>")
                       .arg(version.toHtmlEscaped(), QString::fromStdString(Visualization::EngineBrands::Brand("C64")),
                            QString::fromStdString(Visualization::EngineBrands::Brand("SNES")), qVersion(),
                            QSysInfo::prettyProductName().toHtmlEscaped(), QGuiApplication::platformName()));
    return label;
  }

  QWidget* MakeLicenses()
  {
    auto* browser = new QTextBrowser();
    browser->setOpenExternalLinks(true);
    browser->setHtml(QString::fromStdString(OpenSourceComponents::ToHtml(OpenSourceComponents::All())));
    return browser;
  }

  QWidget* MakePlugins()
  {
    auto* tree = new QTreeWidget();
    tree->setColumnCount(2);
    tree->setHeaderLabels({QObject::tr("Id"), QObject::tr("Description")});
    tree->header()->setSectionResizeMode(0, QHeaderView::ResizeToContents);
    auto* players = new QTreeWidgetItem(tree, {QObject::tr("Formats")});
    auto* containers = new QTreeWidgetItem(tree, {QObject::tr("Containers and archives")});
    for (const auto& plugin : Engine::Plugins())
    {
      new QTreeWidgetItem(plugin.Container ? containers : players,
                          {QString::fromStdString(plugin.Id), QString::fromStdString(plugin.Description)});
    }
    players->setText(1, QObject::tr("%1 plugins").arg(players->childCount()));
    containers->setText(1, QObject::tr("%1 plugins").arg(containers->childCount()));
    return tree;
  }

  AboutDialog::AboutDialog(QWidget* parent)
    : QDialog(parent)
  {
    setWindowTitle(tr("About Lhuna"));
    resize(620, 560);
    auto* layout = new QVBoxLayout(this);
    auto* tabs = new QTabWidget(this);
    tabs->addTab(MakeCredits(), tr("Credits"));
    tabs->addTab(MakeLicenses(), tr("Open source"));
    tabs->addTab(MakePlugins(), tr("Plugins"));
    layout->addWidget(tabs);
    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Close, this);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    layout->addWidget(buttons);
  }
}  // namespace Lhuna
