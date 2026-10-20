/**
 *
 * @file
 *
 * @brief Oscilloscope view rendered by GPU via Qt RHI (Vulkan, OpenGL, Direct3D 11)
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/scope_grid.h"

#include <QtGui/QImage>
#include <QtWidgets/QRhiWidget>

#include <memory>
#include <string>
#include <vector>

class QRhiBuffer;
class QRhiGraphicsPipeline;
class QRhiSampler;
class QRhiShaderResourceBindings;
class QRhiTexture;

namespace Lhuna
{
  class Settings;
  class VisualFeed;

  /**
   * Port of Android's Vulkan oscilloscope (see apps/zxtune-android/zxtune/src/main/vulkan/renderer.h) over QRhi:
   * per frame work is upload of samples and single instanced draw of all the traces over static background texture
   * (grid and labels drawn by QPainter once per layout or size change).
   *
   * QRhiWidget renders to texture composed with the rest of widgets by window's QRhi, so semi-transparent settings
   * panel over it works the same as over raster widgets, unlike native child window of QVulkanWindow.
   */
  class GpuScopeView : public QRhiWidget
  {
    Q_OBJECT
  public:
    //! @return view of backend selected by settings or the best available one, null if there's no usable GPU backend
    static GpuScopeView* Create(VisualFeed& feed, Settings& settings, QWidget* parent);

    ~GpuScopeView() override;

  protected:
    void initialize(QRhiCommandBuffer* cb) override;
    void render(QRhiCommandBuffer* cb) override;
    void releaseResources() override;

  private:
    GpuScopeView(VisualFeed& feed, Settings& settings, Api api, std::string note, QWidget* parent);

    void Reset();
    bool CreateResources();
    bool CreatePipelines();
    void UpdateGrid(int pixelWidth);
    QImage DrawBackground(QSize pixelSize, bool live) const;
    void FallBack();

  private:
    VisualFeed& Feed;
    Settings& Options;
    //! details of selected backend for statistics (e.g. fallback reason)
    const std::string Note;
    QRhi* Rhi = nullptr;
    std::unique_ptr<QRhiBuffer> Uniforms;
    std::unique_ptr<QRhiTexture> Samples;
    bool RawSamples = true;
    std::unique_ptr<QRhiTexture> Background;
    std::unique_ptr<QRhiSampler> Nearest;
    std::unique_ptr<QRhiShaderResourceBindings> BackgroundBindings;
    std::unique_ptr<QRhiShaderResourceBindings> TraceBindings;
    std::unique_ptr<QRhiGraphicsPipeline> TracePipeline;
    std::unique_ptr<QRhiGraphicsPipeline> BackgroundPipeline;

    std::unique_ptr<Visualization::ScopeGrid> Grid;
    const Visualization::VoicesLayout* GridLayout = nullptr;
    int GridChannels = -1;
    //! state of uploaded background, it's redrawn on change
    QSize BackgroundSize;
    bool BackgroundLive = false;
    bool BackgroundDirty = true;
    int Points = 0;

    Visualization::AutoGain Gain;
    //! samples repacked for texture upload when they can't be used as is
    std::vector<int16_t> Packed;
    std::vector<float> Normalized;
    bool Failed = false;
  };
}  // namespace Lhuna
