/**
 *
 * @file
 *
 * @brief Playlists import and export in formats of Android app (XSPF, M3U8)
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/lhuna-desktop/engine/engine.h"

#include <QtCore/QString>

#include <vector>

namespace Lhuna::PlaylistIo
{
  /**
   * Location of track as stored by Android app: file URL with subpath in fragment (e.g.
   * file:///music/pack.zip#dir%2Ftune.pt3, file:///music/tune.sid#%233), so playlists are portable both ways.
   */
  QString ToLocation(const TrackInfo& track);
  //! @return false for non-local locations (e.g. remote archives of Android app)
  bool FromLocation(const QString& location, TrackInfo& track);

  //! XSPF as written by Android app (playlist/xspf/Builder.kt) with rescue path metas and track properties
  bool SaveXspf(const QString& file, const QString& name, const std::vector<TrackInfo>& tracks, QString* error);
  //! M3U8 as Android's rescue export: absolute paths for other players, app data in comments
  bool SaveM3u(const QString& file, const QString& name, const std::vector<TrackInfo>& tracks, QString* error);

  struct LoadResult
  {
    std::vector<TrackInfo> Tracks;
    QString Name;
    //! entries without local files (e.g. remote ones of Android app)
    int Skipped = 0;
  };

  /**
   * Format is detected by content: XSPF of Android app, zxtune-qt (both escaped and plain texts versions) or M3U(8)
   * of any player. Relative paths are resolved against playlist location.
   */
  bool Load(const QString& file, LoadResult& result, QString* error);

  bool IsPlaylist(const QString& file);
}  // namespace Lhuna::PlaylistIo
