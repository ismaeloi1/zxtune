/*
 * @file
 * @brief Remote visualizer interface with data transfer via shared memory
 * @author (C) Vitamin/CAIG
 */

package app.zxtune.rpc;

// Data is stored into shared buffer passed to IVisualizer.openShared instead of transaction parcel:
// big arrays are not copied by binder at each frame
interface ISharedVisualizer {
  // samples at buffer start, see IVisualizer.getScope
  int getScope(int points, int windowMs);
  // size bytes at offset, see IVisualizer.getGauges
  int getGauges(int offset, int size, int waveWindowMs);
  int getVoiceGauges(int offset, int size, int waveWindowMs);
  // scope samples at buffer start and gauges (see Visualizer.getFrame) at offset by single transaction
  long getFrame(int points, int windowMs, int gaugesOffset, int gaugesSize, int gaugesKind, int waveWindowMs);
}
