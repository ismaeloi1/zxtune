/**
 *
 * @file
 *
 * @brief Hardware specific visualization of sound chips voices state
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/zxtune-qt/ui/controls/scope_data.h"

#include <QtGui/QFont>
#include <QtGui/QPainter>

#include <array>

/*
 Draws live state of a single voice according to its chip:
 - FM (YM2612/OPN, OPL2/3): operators connection schema (as in chips datasheets) with envelope levels
 - PSG (SN76489), NES 2A03: volume steps, duty/mode glyphs, periods
 - SPC700 S-DSP: envelope phase, ADSR/GAIN, sample source and flags
 - MT-32: patch name, played keys, partials states
 Values are smoothed between frames to avoid flicker of rapidly changing registers.
*/
class ChipWidgets
{
public:
  static const std::size_t SMOOTH_SIZE = 8;
  using Smooth = std::array<float, SMOOTH_SIZE>;

  //! @param text monospace font of state details
  //! @param label font of operators labels
  ChipWidgets(const QFont& text, const QFont& label);

  //! Short text describing current state, displayed in title band
  static QString Summary(const ScopeData::VoiceGauges& g);

  //! @return true if widget is drawn
  bool Draw(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;

  static bool HasSchema(uint_t kind);

  struct Schema;

private:
  void DrawOpnFm(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;
  void DrawOpl(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;
  void DrawSchema(QPainter& painter, const QRectF& rect, const Schema& schema, uint_t ops, uint_t carriers,
                  const float* levels, uint_t keys, uint_t feedback, const QString& info, const char* const* names,
                  const uint_t* waves) const;
  void DrawOperator(QPainter& painter, const QRectF& b, float level, bool carrier, bool key, const QString& name,
                    const uint_t* wave) const;
  void DrawOplWave(QPainter& painter, const QRectF& b, uint_t wave) const;
  void DrawDac(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;
  void DrawPsg(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;
  void DrawSpc(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;
  void DrawMt32Part(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g) const;
  void DrawKeyboard(QPainter& painter, const QRectF& kb, const std::array<bool, 128>& pressed) const;
  void DrawNes(QPainter& painter, const QRectF& rect, const ScopeData::VoiceGauges& g, Smooth& smooth) const;
  void DrawPulseGlyph(QPainter& painter, const QRectF& r, float duty) const;
  void DrawTriangleGlyph(QPainter& painter, const QRectF& r) const;
  void DrawNoiseGlyph(QPainter& painter, const QRectF& r, bool shortMode) const;
  void DrawSegments(QPainter& painter, const QRectF& r, float value, uint_t segments) const;
  void DrawVerticalBar(QPainter& painter, const QRectF& r, float value) const;
  void DrawCenteredBar(QPainter& painter, const QRectF& r, float value) const;
  void DrawFit(QPainter& painter, const QString& str, qreal x, qreal y, qreal maxWidth) const;

private:
  const QFont Text;
  const QFont Label;
  const qreal TextAscent;
  const qreal TextSpacing;
};
