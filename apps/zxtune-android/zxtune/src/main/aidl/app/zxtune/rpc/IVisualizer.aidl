/*
 * @file
 * @brief Remote visualizer interface
 * @version $Id:$
 * @author (C) Vitamin/CAIG
 */

package app.zxtune.rpc;

import android.os.SharedMemory;
import app.zxtune.rpc.ISharedVisualizer;

interface IVisualizer {
  int getSpectrum(out byte[] levels);
  // data contains 16-bit native endian samples
  int getScope(out byte[] data, int points, int windowMs);
  int getGauges(out byte[] data, int waveWindowMs);
  int getVoiceGauges(out byte[] data, int waveWindowMs);
  String getLayout();
  String getStatus();
  // API 27+, null if not supported
  ISharedVisualizer openShared(in SharedMemory buffer);
}
