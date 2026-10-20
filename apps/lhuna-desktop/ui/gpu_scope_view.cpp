/**
 *
 * @file
 *
 * @brief Oscilloscope view rendered by GPU implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/gpu_scope_view.h"

#include "apps/lhuna-desktop/ui/qt_canvas.h"
#include "apps/lhuna-desktop/ui/scope_view.h"
#include "apps/lhuna-desktop/ui/settings.h"

#include <QtCore/QFile>
#include <QtGui/QGuiApplication>
#include <QtGui/QOffscreenSurface>
#include <QtGui/QPainter>
#include <QtGui/QScreen>
#include <QtGui/private/qguiapplication_p.h>
#include <QtGui/qpa/qplatformintegration.h>
#include <QtWidgets/QLayout>

#include <rhi/qrhi.h>
#include <rhi/qrhi_platform.h>
// Qt declares Vulkan classes only if Vulkan headers are available (e.g. mingw-w64-x86_64-vulkan-headers of MSYS2),
// otherwise Vulkan is not probed and selected, but other backends work
#if QT_CONFIG(vulkan) && __has_include(<vulkan/vulkan.h>)
#  define LHUNA_SCOPE_VULKAN
#  include <QtGui/QVulkanInstance>
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <cstring>

namespace Lhuna
{
  namespace GpuScope
  {
    const float LABEL_TEXT_SIZE = 12;
    // in logical pixels, a bit thicker than raster's cosmetic line as edges are smoothed by shader
    const float LINE_WIDTH = 1.5f;
    // see shaders/frame.glsl
    const int MAX_CHANNELS = 64;
    // see VisualFeed::Update, scope is requested for all the channels
    static_assert(MAX_CHANNELS == Visualization::ScopeGrid::MAX_CHANNELS, "Channels limit mismatch");
    // see ScopeRenderer::IsDense of Android app
    const int DENSE_REVERSALS_PART = 8;
    // in line widths, average step between points to draw them as band
    const float DENSE_STEP = 2.f;

    // std140 layout of shaders/frame.glsl
    struct Uniforms
    {
      float ClipCorrection[16];
      float View[4];
      float Format[4];
      float Traces[MAX_CHANNELS][4];
      float Params[MAX_CHANNELS][4];
    };
    static_assert(offsetof(Uniforms, Traces) == 96, "Layout mismatch");
    static_assert(sizeof(Uniforms) == 96 + MAX_CHANNELS * 32, "Layout mismatch");

    QShader LoadShader(const QString& name)
    {
      QFile file(":/scope/" + name + ".qsb");
      return file.open(QIODevice::ReadOnly) ? QShader::fromSerialized(file.readAll()) : QShader();
    }

    /**
     * Long windows are delivered as minimums and maximums (see Sound::Scope::Get), so direction of trace is changed
     * at most of points. Smooth waves at short windows change it rarely. Unlike Android's check, steps size is taken
     * into account too: at about one point per pixel column (lower density displays) minimums and maximums of smooth
     * wave differ by a pixel or so, and such a wave looks better as antialiased line than as band.
     * @param pixelsPerUnit vertical scale of sample values
     */
    bool IsDense(const int16_t* data, int points, float pixelsPerUnit, float lineWidth)
    {
      int reversals = 0;
      int prevDelta = 0;
      int64_t steps = 0;
      for (int idx = 1; idx < points; ++idx)
      {
        const int delta = int(data[idx]) - data[idx - 1];
        if (delta != 0)
        {
          reversals += (delta > 0) != (prevDelta > 0) && prevDelta != 0;
          prevDelta = delta;
          steps += std::abs(delta);
        }
      }
      return reversals > points / DENSE_REVERSALS_PART
             && steps * pixelsPerUnit > DENSE_STEP * lineWidth * static_cast<float>(points - 1);
    }

    struct Candidate
    {
      QRhiWidget::Api Api;
      QRhi::Implementation Impl;
      const char* Name;
      const char* Id;
    };

    const Candidate VULKAN = {QRhiWidget::Api::Vulkan, QRhi::Vulkan, "Vulkan", "vulkan"};
    const Candidate OPENGL = {QRhiWidget::Api::OpenGL, QRhi::OpenGLES2, "OpenGL", "opengl"};
#ifdef Q_OS_WIN
    const Candidate D3D11 = {QRhiWidget::Api::Direct3D11, QRhi::D3D11, "Direct3D 11", "d3d11"};
#endif

    const Candidate* FindCandidate(QRhiWidget::Api api)
    {
#ifdef Q_OS_WIN
      for (const auto* cand : {&VULKAN, &OPENGL, &D3D11})
#else
      for (const auto* cand : {&VULKAN, &OPENGL})
#endif
      {
        if (cand->Api == api)
        {
          return cand;
        }
      }
      return nullptr;
    }

    // CPU rasterizers (llvmpipe, lavapipe, WARP etc) compose the whole window slower than raster widgets do
    bool IsSoftware(const QRhiDriverInfo& info)
    {
      if (info.deviceType == QRhiDriverInfo::CpuDevice)
      {
        return true;
      }
      const auto name = QString::fromUtf8(info.deviceName).toLower();
      for (const auto* marker :
           {"llvmpipe", "softpipe", "lavapipe", "swrast", "software rasterizer", "swiftshader", "basic render driver"})
      {
        if (name.contains(QLatin1String(marker)))
        {
          return true;
        }
      }
      return false;
    }

    //! @return true if backend can be initialized, device description is filled then
    bool Probe(const Candidate& cand, QRhiDriverInfo& info)
    {
      switch (cand.Impl)
      {
#ifdef LHUNA_SCOPE_VULKAN
      case QRhi::Vulkan:
      {
        // separate instance, widgets use own one; the same extensions are requested
        QVulkanInstance inst;
        inst.setExtensions(QRhiVulkanInitParams::preferredInstanceExtensions());
        // as widgets' default instance does, zero (unspecified) version is reported as error by validation
        inst.setApiVersion(inst.supportedApiVersion());
        if (!inst.create())
        {
          return false;
        }
        QRhiVulkanInitParams params;
        params.inst = &inst;
        // device is destroyed before instance
        const std::unique_ptr<QRhi> rhi(QRhi::create(QRhi::Vulkan, &params));
        if (rhi)
        {
          info = rhi->driverInfo();
        }
        return static_cast<bool>(rhi);
      }
#endif
#if QT_CONFIG(opengl)
      case QRhi::OpenGLES2:
      {
        QRhiGles2InitParams params;
        params.format = QSurfaceFormat::defaultFormat();
        const std::unique_ptr<QOffscreenSurface> surface(QRhiGles2InitParams::newFallbackSurface(params.format));
        params.fallbackSurface = surface.get();
        // context is destroyed before surface
        const std::unique_ptr<QRhi> rhi(QRhi::create(QRhi::OpenGLES2, &params));
        if (rhi)
        {
          info = rhi->driverInfo();
        }
        return static_cast<bool>(rhi);
      }
#endif
#ifdef Q_OS_WIN
      case QRhi::D3D11:
      {
        QRhiD3D11InitParams params;
        const std::unique_ptr<QRhi> rhi(QRhi::create(QRhi::D3D11, &params));
        if (rhi)
        {
          info = rhi->driverInfo();
        }
        return static_cast<bool>(rhi);
      }
#endif
      default:
        return false;
      }
    }

    //! @return automatic selection order for current platform
    std::vector<const Candidate*> AutoOrder()
    {
#ifdef Q_OS_WIN
      // Direct3D is native API of Windows, OpenGL drivers are the least reliable there
      return {&VULKAN, &D3D11, &OPENGL};
#else
      // Native Wayland: Vulkan presentation of Qt 6.8 widgets with mesa 25 WSI crashes at window destruction
      // (reproduced with minimal QRhiWidget sample), so it's explicit choice only there; OpenGL is Qt's own default
      // for Wayland. XWayland is X11 for the application and doesn't have this issue.
      if (QGuiApplication::platformName().startsWith(QLatin1String("wayland")))
      {
        return {&OPENGL};
      }
      return {&VULKAN, &OPENGL};
#endif
    }
  }  // namespace GpuScope

  GpuScopeView* GpuScopeView::Create(VisualFeed& feed, Settings& settings, QWidget* parent)
  {
    using namespace GpuScope;
    // e.g. offscreen and minimal platforms, widgets are not composed by QRhi there at all
    const auto* platform = QGuiApplicationPrivate::platformIntegration();
    if (!platform || !platform->hasCapability(QPlatformIntegration::RhiBasedRendering))
    {
      feed.SetRenderer("software", "gpu: unsupported by platform " + QGuiApplication::platformName().toStdString());
      return nullptr;
    }
    const auto choice = static_cast<GraphicsBackend>(settings.Get(Settings::GRAPHICS_BACKEND));
    if (choice == GraphicsBackend::SOFTWARE)
    {
      feed.SetRenderer("software", "gpu: disabled by settings");
      return nullptr;
    }
    const Candidate* requested = nullptr;
    switch (choice)
    {
    case GraphicsBackend::VULKAN:
      requested = &VULKAN;
      break;
    case GraphicsBackend::OPENGL:
      requested = &OPENGL;
      break;
#ifdef Q_OS_WIN
    case GraphicsBackend::D3D11:
      requested = &D3D11;
      break;
#endif
    default:
      break;
    }
    std::vector<const Candidate*> order;
    if (requested)
    {
      order.push_back(requested);
    }
    for (const auto* cand : AutoOrder())
    {
      if (cand != requested)
      {
        order.push_back(cand);
      }
    }
    std::string skipped;
    for (const auto* cand : order)
    {
      QRhiDriverInfo info;
      if (!Probe(*cand, info))
      {
        skipped += std::string(skipped.empty() ? "" : ", ") + cand->Name + " unavailable";
        continue;
      }
      // explicitly selected backend is used even with CPU rasterizer (e.g. to check rendering without GPU)
      if (cand != requested && IsSoftware(info))
      {
        skipped += std::string(skipped.empty() ? "" : ", ") + cand->Name + " is software ("
                   + info.deviceName.toStdString() + ")";
        continue;
      }
      return new GpuScopeView(feed, settings, cand->Api, skipped, parent);
    }
    feed.SetRenderer("software", "gpu: " + (skipped.empty() ? std::string("no backends") : skipped));
    return nullptr;
  }

  GpuScopeView::GpuScopeView(VisualFeed& feed, Settings& settings, Api api, std::string note, QWidget* parent)
    : QRhiWidget(parent)
    , Feed(feed)
    , Options(settings)
    , Note(std::move(note))
  {
    setApi(api);
    setMinimumHeight(120);
    connect(&Feed, &VisualFeed::frameReady, this, qOverload<>(&QWidget::update));
    connect(this, &QRhiWidget::frameSubmitted, this, [this]() {
      const auto* scr = screen();
      Feed.FramePresented(scr ? scr->refreshRate() : 0.);
    });
    connect(this, &QRhiWidget::renderFailed, this, &GpuScopeView::FallBack);
    const auto* cand = GpuScope::FindCandidate(api);
    Feed.SetRenderer(cand ? cand->Id : "gpu", std::string("gpu: ") + (cand ? cand->Name : "?") + ", initializing");
  }

  GpuScopeView::~GpuScopeView() = default;

  void GpuScopeView::Reset()
  {
    BackgroundPipeline.reset();
    TracePipeline.reset();
    TraceBindings.reset();
    BackgroundBindings.reset();
    Nearest.reset();
    Background.reset();
    Samples.reset();
    Uniforms.reset();
    BackgroundSize = {};
    BackgroundDirty = true;
    Rhi = nullptr;
  }

  void GpuScopeView::releaseResources()
  {
    // e.g. device loss or move to another top-level window, initialize() is called with new QRhi later
    Reset();
    Feed.SetPacing(VisualFeed::Pacing::TIMER);
  }

  bool GpuScopeView::CreateResources()
  {
    using namespace GpuScope;
    // raw int16 bits are uploaded as is if possible, normalized floats otherwise
    RawSamples = Rhi->isTextureFormatSupported(QRhiTexture::R16);
    const auto samplesFormat = RawSamples ? QRhiTexture::R16 : QRhiTexture::R32F;
    if (!Rhi->isFeatureSupported(QRhi::Instancing) || !Rhi->isTextureFormatSupported(samplesFormat))
    {
      return false;
    }
    Uniforms.reset(Rhi->newBuffer(QRhiBuffer::Dynamic, QRhiBuffer::UniformBuffer, sizeof(GpuScope::Uniforms)));
    Samples.reset(Rhi->newTexture(samplesFormat, QSize(Visualization::ScopeGrid::MAX_POINTS, MAX_CHANNELS)));
    // single channel, 4 times less memory traffic than RGBA
    const auto backgroundFormat = Rhi->isTextureFormatSupported(QRhiTexture::R8) ? QRhiTexture::R8 : QRhiTexture::RGBA8;
    Background.reset(Rhi->newTexture(backgroundFormat, QSize(1, 1)));
    // texels are mapped 1:1 to pixels
    Nearest.reset(Rhi->newSampler(QRhiSampler::Nearest, QRhiSampler::Nearest, QRhiSampler::None,
                                  QRhiSampler::ClampToEdge, QRhiSampler::ClampToEdge));
    if (!Uniforms->create() || !Samples->create() || !Background->create() || !Nearest->create())
    {
      return false;
    }
    // Bindings set per pipeline: OpenGL backend emulates uniform buffers by glUniform calls of program and skips
    // them if the same bindings are set for another program which used them in previous frame (Qt 6.8)
    BackgroundBindings.reset(Rhi->newShaderResourceBindings());
    BackgroundBindings->setBindings({
        QRhiShaderResourceBinding::uniformBuffer(0, QRhiShaderResourceBinding::VertexStage, Uniforms.get()),
        QRhiShaderResourceBinding::sampledTexture(2, QRhiShaderResourceBinding::FragmentStage, Background.get(),
                                                  Nearest.get()),
    });
    TraceBindings.reset(Rhi->newShaderResourceBindings());
    TraceBindings->setBindings({
        QRhiShaderResourceBinding::uniformBuffer(
            0, QRhiShaderResourceBinding::VertexStage | QRhiShaderResourceBinding::FragmentStage, Uniforms.get()),
        QRhiShaderResourceBinding::sampledTexture(1, QRhiShaderResourceBinding::VertexStage, Samples.get(),
                                                  Nearest.get()),
    });
    return BackgroundBindings->create() && TraceBindings->create() && CreatePipelines();
  }

  bool GpuScopeView::CreatePipelines()
  {
    using namespace GpuScope;
    const auto traceVert = LoadShader("trace.vert");
    const auto traceFrag = LoadShader("trace.frag");
    const auto backVert = LoadShader("background.vert");
    const auto backFrag = LoadShader("background.frag");
    if (!traceVert.isValid() || !traceFrag.isValid() || !backVert.isValid() || !backFrag.isValid())
    {
      return false;
    }
    // no vertex inputs: positions are computed from vertex and instance indices
    BackgroundPipeline.reset(Rhi->newGraphicsPipeline());
    BackgroundPipeline->setShaderStages({{QRhiShaderStage::Vertex, backVert}, {QRhiShaderStage::Fragment, backFrag}});
    BackgroundPipeline->setShaderResourceBindings(BackgroundBindings.get());
    BackgroundPipeline->setRenderPassDescriptor(renderTarget()->renderPassDescriptor());

    TracePipeline.reset(Rhi->newGraphicsPipeline());
    TracePipeline->setTopology(QRhiGraphicsPipeline::TriangleStrip);
    QRhiGraphicsPipeline::TargetBlend blend;
    blend.enable = true;
    blend.srcColor = QRhiGraphicsPipeline::SrcAlpha;
    blend.dstColor = QRhiGraphicsPipeline::OneMinusSrcAlpha;
    // widget's texture stays opaque, otherwise it would be blended with window's content under it
    blend.srcAlpha = QRhiGraphicsPipeline::Zero;
    blend.dstAlpha = QRhiGraphicsPipeline::One;
    TracePipeline->setTargetBlends({blend});
    TracePipeline->setShaderStages({{QRhiShaderStage::Vertex, traceVert}, {QRhiShaderStage::Fragment, traceFrag}});
    TracePipeline->setShaderResourceBindings(TraceBindings.get());
    TracePipeline->setRenderPassDescriptor(renderTarget()->renderPassDescriptor());
    return BackgroundPipeline->create() && TracePipeline->create();
  }

  void GpuScopeView::initialize(QRhiCommandBuffer* /*cb*/)
  {
    if (Failed)
    {
      return;
    }
    if (Rhi != rhi())
    {
      // new device (e.g. after loss), all the resources belong to the previous one
      Reset();
      Rhi = rhi();
      if (!Rhi || !CreateResources())
      {
        Reset();
        FallBack();
        return;
      }
      const auto* cand = GpuScope::FindCandidate(api());
      std::string text = std::string("gpu: ") + (cand ? cand->Name : Rhi->backendName()) + ", "
                         + Rhi->driverInfo().deviceName.toStdString();
      if (!Note.empty())
      {
        text += " (" + Note + ")";
      }
      Feed.SetRenderer(cand ? cand->Id : "gpu", text);
      Feed.SetPacing(VisualFeed::Pacing::PRESENTATION);
    }
    else if (!TracePipeline->renderPassDescriptor()->isCompatible(renderTarget()->renderPassDescriptor()))
    {
      // render target is recreated on resize, normally with compatible pass
      if (!CreatePipelines())
      {
        Reset();
        FallBack();
        return;
      }
    }
    BackgroundDirty = true;
  }

  void GpuScopeView::UpdateGrid(int pixelWidth)
  {
    const auto& frame = Feed.Frame();
    if (!Grid || GridLayout != frame.Layout.get() || GridChannels != frame.Channels)
    {
      Grid = std::make_unique<Visualization::ScopeGrid>(*frame.Layout, frame.Channels);
      GridLayout = frame.Layout.get();
      GridChannels = frame.Channels;
      BackgroundDirty = true;
      Points = 0;
    }
    // two points per device pixel column, even count keeps texture rows 4 bytes aligned (OpenGL's unpack default)
    const auto points = (Grid->Points(pixelWidth) + 1) & ~1;
    if (points != Points)
    {
      Points = std::min(points, Visualization::ScopeGrid::MAX_POINTS);
      Feed.SetPoints(Points);
    }
  }

  QImage GpuScopeView::DrawBackground(QSize pixelSize, bool live) const
  {
    QImage image(pixelSize, QImage::Format_RGB32);
    // fractional scale (e.g. 125% on Wayland) is applied by painter, so labels are rasterized at device resolution
    image.setDevicePixelRatio(static_cast<qreal>(pixelSize.width()) / std::max(width(), 1));
    image.fill(Qt::black);
    {
      QPainter painter(&image);
      painter.setRenderHint(QPainter::TextAntialiasing);
      QtCanvas canvas(painter);
      Grid->DrawBackground(canvas, width(), height(), GpuScope::LABEL_TEXT_SIZE);
      if (!live)
      {
        painter.setPen(QColor(255, 255, 255, 90));
        painter.drawText(QRect(0, 0, width(), height()), Qt::AlignCenter, tr("Nothing is playing"));
      }
    }
    // white with alpha over black is luminance, as R8 texture expects
    return image.convertToFormat(Background->format() == QRhiTexture::R8 ? QImage::Format_Grayscale8
                                                                         : QImage::Format_RGBA8888);
  }

  void GpuScopeView::render(QRhiCommandBuffer* cb)
  {
    using namespace GpuScope;
    if (!Rhi || !TracePipeline)
    {
      return;
    }
    const auto start = Feed.Clock();
    const auto& frame = Feed.Frame();
    const auto pixelSize = renderTarget()->pixelSize();
    UpdateGrid(pixelSize.width());
    auto* batch = Rhi->nextResourceUpdateBatch();
    const auto live = frame.Live && frame.Channels > 0;
    if (BackgroundDirty || BackgroundSize != pixelSize || BackgroundLive != live)
    {
      if (Background->pixelSize() != pixelSize)
      {
        // bindings refer to the same object, so they're updated by QRhi itself
        Background->setPixelSize(pixelSize);
        if (!Background->create())
        {
          batch->release();
          FallBack();
          return;
        }
      }
      batch->uploadTexture(Background.get(), DrawBackground(pixelSize, live));
      BackgroundSize = pixelSize;
      BackgroundLive = live;
      BackgroundDirty = false;
    }

    const auto points = std::min(frame.Points, Visualization::ScopeGrid::MAX_POINTS);
    const auto channels = live && points >= 2 ? std::min({frame.Channels, Grid->Channels(), MAX_CHANNELS}) : 0;
    GpuScope::Uniforms uniforms = {};
    std::memcpy(uniforms.ClipCorrection, Rhi->clipSpaceCorrMatrix().constData(), sizeof(uniforms.ClipCorrection));
    const auto scaleX = static_cast<float>(pixelSize.width()) / std::max(width(), 1);
    const auto scaleY = static_cast<float>(pixelSize.height()) / std::max(height(), 1);
    uniforms.View[0] = 2.f / std::max(pixelSize.width(), 1);
    uniforms.View[1] = 2.f / std::max(pixelSize.height(), 1);
    uniforms.View[2] = LINE_WIDTH * scaleY / 2;
    uniforms.View[3] = static_cast<float>(points);
    uniforms.Format[0] = RawSamples ? 1.f : 0.f;
    if (channels > 0)
    {
      const auto traces = Grid->Traces(width(), height(), LABEL_TEXT_SIZE);
      const auto gainPercent = Options.Get(Settings::GAIN);
      for (int chan = 0; chan < channels; ++chan)
      {
        const auto& trace = traces[chan];
        const auto* data = frame.Samples.data() + chan * frame.Points;
        auto* dst = uniforms.Traces[chan];
        dst[0] = trace.Left * scaleX;
        dst[1] = trace.Width * scaleX;
        dst[2] = trace.CenterY * scaleY;
        dst[3] = trace.Amplitude * scaleY;
        const auto gain = Gain.Of(chan, data, points, gainPercent);
        uniforms.Params[chan][0] = gain;
        const auto dense = IsDense(data, points, dst[3] * gain / 32768.f, uniforms.View[2] * 2);
        uniforms.Params[chan][1] = dense ? 1.f : 0.f;
      }
      // rows of texture are tightly packed: each channel's samples follow previous one's
      const auto count = static_cast<std::size_t>(channels) * points;
      const void* upload = frame.Samples.data();
      quint32 bytes = static_cast<quint32>(count * sizeof(int16_t));
      if (!RawSamples)
      {
        Normalized.resize(count);
        for (std::size_t idx = 0; idx < count; ++idx)
        {
          Normalized[idx] = frame.Samples[idx] / 32768.f;
        }
        upload = Normalized.data();
        bytes = static_cast<quint32>(count * sizeof(float));
      }
      else if (points % 2 != 0)
      {
        // e.g. requested by previous view before the first frame of this one, repacked to aligned rows
        const auto stride = points + 1;
        Packed.assign(static_cast<std::size_t>(channels) * stride, 0);
        for (int chan = 0; chan < channels; ++chan)
        {
          std::copy_n(frame.Samples.data() + chan * points, points, Packed.data() + chan * stride);
        }
        upload = Packed.data();
        bytes = static_cast<quint32>(Packed.size() * sizeof(int16_t));
      }
      QRhiTextureSubresourceUploadDescription desc(upload, bytes);
      desc.setSourceSize(QSize(RawSamples && points % 2 != 0 ? points + 1 : points, channels));
      batch->uploadTexture(Samples.get(), QRhiTextureUploadDescription(QRhiTextureUploadEntry(0, 0, desc)));
    }
    batch->updateDynamicBuffer(Uniforms.get(), 0, sizeof(uniforms), &uniforms);

    // background is opaque, clear value doesn't matter, but clearing is free with render pass begin
    cb->beginPass(renderTarget(), Qt::black, {1.0f, 0}, batch);
    const QRhiViewport viewport(0, 0, static_cast<float>(pixelSize.width()), static_cast<float>(pixelSize.height()));
    cb->setGraphicsPipeline(BackgroundPipeline.get());
    cb->setViewport(viewport);
    cb->setShaderResources();
    cb->draw(3);
    if (channels > 0)
    {
      cb->setGraphicsPipeline(TracePipeline.get());
      cb->setViewport(viewport);
      cb->setShaderResources();
      // the only draw of all the traces: instance per channel, two vertices per point
      cb->draw(static_cast<quint32>(points * 2), static_cast<quint32>(channels));
    }
    cb->endPass();
    Feed.FrameDrawn(start, Feed.Clock());
  }

  void GpuScopeView::FallBack()
  {
    if (Failed)
    {
      return;
    }
    // e.g. device is lost and can't be recreated, or driver lacks required features
    Failed = true;
    const auto* cand = GpuScope::FindCandidate(api());
    const auto reason = std::string("gpu: ") + (cand ? cand->Name : "?") + " failed";
    // called while painting, so widgets are replaced later
    QMetaObject::invokeMethod(
        this,
        [this, reason]() {
          auto* parent = parentWidget();
          auto* layout = parent ? parent->layout() : nullptr;
          auto* raster = new ScopeView(Feed, Options, parent);
          if (!layout || !layout->replaceWidget(this, raster))
          {
            raster->setGeometry(geometry());
            raster->show();
          }
          Feed.SetRenderer("software", reason);
          hide();
          deleteLater();
        },
        Qt::QueuedConnection);
  }
}  // namespace Lhuna
