/*
 * @file
 * @brief Remote visualizer interface
 * @version $Id:$
 * @author (C) Vitamin/CAIG
 */

package app.zxtune.rpc;

interface IVisualizer {
  int getSpectrum(out byte[] levels);
  // data contains 16-bit native endian samples
  int getScope(out byte[] data, int points);
}
