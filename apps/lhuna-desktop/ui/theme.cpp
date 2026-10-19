/**
 *
 * @file
 *
 * @brief Dark theme of desktop player implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/theme.h"

#include <QtGui/QPalette>
#include <QtWidgets/QApplication>
#include <QtWidgets/QStyleFactory>

namespace Lhuna::Theme
{
  // Fusion is the only built-in style which follows palette on all platforms
  void Apply(QApplication& app)
  {
    app.setStyle(QStyleFactory::create("Fusion"));
    QPalette pal;
    const QColor window(0x12, 0x12, 0x12);
    const QColor base(0x1a, 0x1a, 0x1a);
    const QColor text(0xe6, 0xe6, 0xe6);
    const QColor disabled(0x70, 0x70, 0x70);
    pal.setColor(QPalette::Window, window);
    pal.setColor(QPalette::WindowText, text);
    pal.setColor(QPalette::Base, base);
    pal.setColor(QPalette::AlternateBase, QColor(0x20, 0x20, 0x20));
    pal.setColor(QPalette::ToolTipBase, QColor(0x2a, 0x2a, 0x2a));
    pal.setColor(QPalette::ToolTipText, text);
    pal.setColor(QPalette::PlaceholderText, disabled);
    pal.setColor(QPalette::Text, text);
    pal.setColor(QPalette::Button, QColor(0x24, 0x24, 0x24));
    pal.setColor(QPalette::ButtonText, text);
    pal.setColor(QPalette::BrightText, Qt::white);
    pal.setColor(QPalette::Highlight, ACCENT);
    pal.setColor(QPalette::HighlightedText, Qt::white);
    pal.setColor(QPalette::Link, ACCENT.lighter(130));
    pal.setColor(QPalette::Mid, QColor(0x30, 0x30, 0x30));
    pal.setColor(QPalette::Dark, QColor(0x0c, 0x0c, 0x0c));
    pal.setColor(QPalette::Disabled, QPalette::Text, disabled);
    pal.setColor(QPalette::Disabled, QPalette::ButtonText, disabled);
    pal.setColor(QPalette::Disabled, QPalette::WindowText, disabled);
    app.setPalette(pal);
    app.setStyleSheet(QString(R"(
      QToolTip { border: 1px solid #333; }
      QTabWidget::pane { border: none; }
      QTabBar::tab { background: transparent; color: #aaa; padding: 8px 14px; border: none; }
      QTabBar::tab:selected { color: white; border-bottom: 2px solid %1; }
      QListView, QTreeView { border: none; outline: none; }
      QListView::item, QTreeView::item { padding: 3px 2px; }
      QSlider::groove:horizontal { height: 4px; background: #3a3a3a; border-radius: 2px; }
      QSlider::sub-page:horizontal { background: %1; border-radius: 2px; }
      QSlider::handle:horizontal { background: white; width: 14px; height: 14px; margin: -5px 0; border-radius: 7px; }
      QToolButton { border: none; border-radius: 18px; padding: 6px; }
      QToolButton:hover { background: #2a2a2a; }
      QToolButton:checked { color: %1; background: #1f2f2d; }
      QSplitter::handle { background: #0c0c0c; }
      QStatusBar { color: #9a9a9a; }
    )")
                          .arg(ACCENT.name()));
  }
}  // namespace Lhuna::Theme
