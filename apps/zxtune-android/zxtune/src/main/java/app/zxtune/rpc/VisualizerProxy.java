package app.zxtune.rpc;

import android.os.Build;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SharedMemory;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import app.zxtune.Log;
import app.zxtune.playback.FrameResult;
import app.zxtune.playback.ScopeLayout;
import app.zxtune.playback.Visualizer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public final class VisualizerProxy {

    private static final String TAG = VisualizerProxy.class.getName();

    // shared buffer layout: scope samples (up to 32 channels by 2048 points), then gauges
    private static final int SCOPE_BYTES = 32 * 2048 * 2;
    private static final int GAUGES_BYTES = 64 * 1024;

    // Data of each frame (tens of kilobytes) is not copied through binder: only small request is
    // sent
    @RequiresApi(27)
    private static final class SharedClient {
        final ISharedVisualizer remote;
        // kept to not close mapping
        final SharedMemory memory;
        final ShortBuffer scope;
        final ByteBuffer gauges;

        private SharedClient(ISharedVisualizer remote, SharedMemory memory, ByteBuffer buffer) {
            this.remote = remote;
            this.memory = memory;
            this.scope = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            buffer.position(SCOPE_BYTES);
            this.gauges = buffer.slice();
        }

        @Nullable
        static SharedClient open(IVisualizer delegate) throws Exception {
            final SharedMemory memory =
                    SharedMemory.create("visualizer", SCOPE_BYTES + GAUGES_BYTES);
            final ISharedVisualizer remote = delegate.openShared(memory);
            if (remote == null) {
                memory.close();
                return null;
            }
            return new SharedClient(remote, memory, memory.mapReadWrite());
        }
    }

    public static Visualizer getClient(IBinder binder) {
        return new ClientStub(binder);
    }

    public static IBinder getServer(Visualizer iface) {
        return new ServerStub(iface);
    }

    private static class ClientStub implements Visualizer {

        private final IVisualizer delegate;

        ClientStub(IBinder binder) {
            this.delegate = IVisualizer.Stub.asInterface(binder);
        }

        @Override
        public int getSpectrum(byte[] levels) {
            try {
                return delegate.getSpectrum(levels);
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getSpectrum()");
                return 0;
            }
        }

        @Nullable private Object shared;
        private boolean sharedFailed;

        @Nullable
        private synchronized Object getShared() {
            if (shared == null && !sharedFailed && Build.VERSION.SDK_INT >= 27) {
                try {
                    shared = SharedClient.open(delegate);
                } catch (Exception e) {
                    Log.w(TAG, e, "openShared()");
                }
                sharedFailed = shared == null;
            }
            return shared;
        }

        private final Object scopeLock = new Object();
        private final Object gaugesLock = new Object();
        private byte[] scopeBuffer = new byte[0];

        @Override
        public int getScope(short[] data, int points, int windowMs) {
            final Object sh = getShared();
            if (sh != null && Build.VERSION.SDK_INT >= 27) {
                return getScopeShared((SharedClient) sh, data, points, windowMs);
            }
            synchronized (scopeLock) {
                return getScopeParcel(data, points, windowMs);
            }
        }

        @RequiresApi(27)
        private int getScopeShared(SharedClient sh, short[] data, int points, int windowMs) {
            try {
                synchronized (scopeLock) {
                    final int layout = sh.remote.getScope(points, windowMs);
                    final int count =
                            Math.min(
                                    ScopeLayout.channelsOf(layout) * points,
                                    Math.min(data.length, SCOPE_BYTES / 2));
                    sh.scope.position(0);
                    sh.scope.get(data, 0, count);
                    return layout;
                }
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getScope()");
                return 0;
            }
        }

        @RequiresApi(27)
        private int getGaugesShared(
                SharedClient sh, byte[] data, int waveWindowMs, boolean voices) {
            try {
                synchronized (gaugesLock) {
                    final int size = Math.min(data.length, GAUGES_BYTES);
                    final int result =
                            voices
                                    ? sh.remote.getVoiceGauges(SCOPE_BYTES, size, waveWindowMs)
                                    : sh.remote.getGauges(SCOPE_BYTES, size, waveWindowMs);
                    sh.gauges.position(0);
                    sh.gauges.get(data, 0, size);
                    return result;
                }
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, voices ? "getVoiceGauges()" : "getGauges()");
                return 0;
            }
        }

        private int getScopeParcel(short[] data, int points, int windowMs) {
            try {
                if (scopeBuffer.length != data.length * 2) {
                    scopeBuffer = new byte[data.length * 2];
                }
                final int layout = delegate.getScope(scopeBuffer, points, windowMs);
                final int channels = ScopeLayout.channelsOf(layout);
                ByteBuffer.wrap(scopeBuffer, 0, channels * points * 2)
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer()
                        .get(data, 0, channels * points);
                return layout;
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getScope()");
                return 0;
            }
        }

        @Override
        public long getFrame(
                short[] data,
                int points,
                int windowMs,
                byte[] gauges,
                int gaugesKind,
                int waveWindowMs) {
            final Object sh = getShared();
            if (sh != null && Build.VERSION.SDK_INT >= 27 && gauges.length <= GAUGES_BYTES) {
                return getFrameShared(
                        (SharedClient) sh,
                        data,
                        points,
                        windowMs,
                        gauges,
                        gaugesKind,
                        waveWindowMs);
            }
            return FrameResult.request(
                    this, data, points, windowMs, gauges, gaugesKind, waveWindowMs);
        }

        // Single transaction and the same moment for waveforms and gauges
        @RequiresApi(27)
        private long getFrameShared(
                SharedClient sh,
                short[] data,
                int points,
                int windowMs,
                byte[] gauges,
                int gaugesKind,
                int waveWindowMs) {
            try {
                synchronized (scopeLock) {
                    synchronized (gaugesLock) {
                        final int size = Math.min(gauges.length, GAUGES_BYTES);
                        final long result =
                                sh.remote.getFrame(
                                        points,
                                        windowMs,
                                        SCOPE_BYTES,
                                        size,
                                        gaugesKind,
                                        waveWindowMs);
                        final int layout = (int) result;
                        final int count =
                                Math.min(
                                        ScopeLayout.channelsOf(layout) * points,
                                        Math.min(data.length, SCOPE_BYTES / 2));
                        sh.scope.position(0);
                        sh.scope.get(data, 0, count);
                        if ((result >>> 32) != 0) {
                            sh.gauges.position(0);
                            sh.gauges.get(gauges, 0, size);
                        }
                        return result;
                    }
                }
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getFrame()");
                return 0;
            }
        }

        @Override
        public int getGauges(byte[] data, int waveWindowMs) {
            final Object sh = getShared();
            if (sh != null && Build.VERSION.SDK_INT >= 27 && data.length <= GAUGES_BYTES) {
                return getGaugesShared((SharedClient) sh, data, waveWindowMs, false);
            }
            try {
                return delegate.getGauges(data, waveWindowMs);
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getGauges()");
                return 0;
            }
        }

        @Override
        public int getVoiceGauges(byte[] data, int waveWindowMs) {
            final Object sh = getShared();
            if (sh != null && Build.VERSION.SDK_INT >= 27 && data.length <= GAUGES_BYTES) {
                return getGaugesShared((SharedClient) sh, data, waveWindowMs, true);
            }
            try {
                return delegate.getVoiceGauges(data, waveWindowMs);
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getVoiceGauges()");
                return 0;
            }
        }

        @Override
        public String getLayout() {
            try {
                return delegate.getLayout();
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getLayout()");
                return "";
            }
        }

        @Override
        public String getStatus() {
            try {
                return delegate.getStatus();
            } catch (DeadObjectException e) {
                throw new IllegalStateException(e);
            } catch (RemoteException e) {
                Log.w(TAG, e, "getStatus()");
                return "";
            }
        }
    }

    private static class ServerStub extends IVisualizer.Stub {

        private final Visualizer delegate;

        ServerStub(Visualizer delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getSpectrum(byte[] levels) {
            try {
                return delegate.getSpectrum(levels);
            } catch (Exception e) {
                Log.w(TAG, e, "getSpectrum()");
            }
            return 0;
        }

        private short[] scopeBuffer = new short[0];

        @Override
        public synchronized int getScope(byte[] data, int points, int windowMs) {
            try {
                if (scopeBuffer.length != data.length / 2) {
                    scopeBuffer = new short[data.length / 2];
                }
                final int layout = delegate.getScope(scopeBuffer, points, windowMs);
                final int channels = ScopeLayout.channelsOf(layout);
                ByteBuffer.wrap(data)
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer()
                        .put(scopeBuffer, 0, channels * points);
                return layout;
            } catch (Exception e) {
                Log.w(TAG, e, "getScope()");
            }
            return 0;
        }

        @Override
        public int getGauges(byte[] data, int waveWindowMs) {
            try {
                return delegate.getGauges(data, waveWindowMs);
            } catch (Exception e) {
                Log.w(TAG, e, "getGauges()");
            }
            return 0;
        }

        @Override
        public int getVoiceGauges(byte[] data, int waveWindowMs) {
            try {
                return delegate.getVoiceGauges(data, waveWindowMs);
            } catch (Exception e) {
                Log.w(TAG, e, "getVoiceGauges()");
            }
            return 0;
        }

        @Override
        public String getLayout() {
            try {
                return delegate.getLayout();
            } catch (Exception e) {
                Log.w(TAG, e, "getLayout()");
            }
            return "";
        }

        @Override
        public String getStatus() {
            try {
                return delegate.getStatus();
            } catch (Exception e) {
                Log.w(TAG, e, "getStatus()");
            }
            return "";
        }

        @Override
        @Nullable
        public ISharedVisualizer openShared(SharedMemory buffer) {
            if (Build.VERSION.SDK_INT < 27) {
                return null;
            }
            try {
                return new SharedServer(delegate, buffer);
            } catch (Exception e) {
                Log.w(TAG, e, "openShared()");
                return null;
            }
        }
    }

    // Separate object per client with own buffer
    @RequiresApi(27)
    private static class SharedServer extends ISharedVisualizer.Stub {

        private final Visualizer delegate;
        // kept to not close mapping
        private final SharedMemory memory;
        private final ShortBuffer scope;
        private final ByteBuffer buffer;
        private short[] scopeData = new short[0];
        private byte[] gaugesData = new byte[0];

        SharedServer(Visualizer delegate, SharedMemory memory) throws Exception {
            this.delegate = delegate;
            this.memory = memory;
            this.buffer = memory.mapReadWrite();
            this.scope = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        }

        @Override
        public int getScope(int points, int windowMs) {
            synchronized (scope) {
                return getScopeData(points, windowMs);
            }
        }

        private int getScopeData(int points, int windowMs) {
            try {
                final int capacity = scope.capacity();
                if (scopeData.length != capacity) {
                    scopeData = new short[capacity];
                }
                final int layout = delegate.getScope(scopeData, points, windowMs);
                final int count = Math.min(ScopeLayout.channelsOf(layout) * points, capacity);
                scope.position(0);
                scope.put(scopeData, 0, count);
                return layout;
            } catch (Exception e) {
                Log.w(TAG, e, "getScope()");
            }
            return 0;
        }

        @Override
        public long getFrame(
                int points,
                int windowMs,
                int gaugesOffset,
                int gaugesSize,
                int gaugesKind,
                int waveWindowMs) {
            synchronized (scope) {
                synchronized (buffer) {
                    return getFrameData(
                            points, windowMs, gaugesOffset, gaugesSize, gaugesKind, waveWindowMs);
                }
            }
        }

        private long getFrameData(
                int points,
                int windowMs,
                int gaugesOffset,
                int gaugesSize,
                int gaugesKind,
                int waveWindowMs) {
            try {
                if (gaugesOffset < SCOPE_BYTES
                        || gaugesSize < 0
                        || gaugesOffset > buffer.capacity() - gaugesSize) {
                    return 0;
                }
                final int capacity = scope.capacity();
                if (scopeData.length != capacity) {
                    scopeData = new short[capacity];
                }
                if (gaugesData.length != gaugesSize) {
                    gaugesData = new byte[gaugesSize];
                }
                final long result =
                        delegate.getFrame(
                                scopeData, points, windowMs, gaugesData, gaugesKind, waveWindowMs);
                final int count =
                        Math.min(ScopeLayout.channelsOf((int) result) * points, SCOPE_BYTES / 2);
                scope.position(0);
                scope.put(scopeData, 0, count);
                if ((result >>> 32) != 0) {
                    final ByteBuffer target = buffer.duplicate();
                    target.position(gaugesOffset);
                    target.put(gaugesData, 0, gaugesSize);
                }
                return result;
            } catch (Exception e) {
                Log.w(TAG, e, "getFrame()");
            }
            return 0;
        }

        @Override
        public int getGauges(int offset, int size, int waveWindowMs) {
            return getGaugesData(offset, size, waveWindowMs, false);
        }

        @Override
        public int getVoiceGauges(int offset, int size, int waveWindowMs) {
            return getGaugesData(offset, size, waveWindowMs, true);
        }

        private int getGaugesData(int offset, int size, int waveWindowMs, boolean voices) {
            synchronized (buffer) {
                return getGaugesDataLocked(offset, size, waveWindowMs, voices);
            }
        }

        private int getGaugesDataLocked(int offset, int size, int waveWindowMs, boolean voices) {
            try {
                if (offset < 0 || size < 0 || offset > buffer.capacity() - size) {
                    return 0;
                }
                if (gaugesData.length != size) {
                    gaugesData = new byte[size];
                }
                final int result =
                        voices
                                ? delegate.getVoiceGauges(gaugesData, waveWindowMs)
                                : delegate.getGauges(gaugesData, waveWindowMs);
                final ByteBuffer target = buffer.duplicate();
                target.position(offset);
                target.put(gaugesData, 0, size);
                return result;
            } catch (Exception e) {
                Log.w(TAG, e, voices ? "getVoiceGauges()" : "getGauges()");
            }
            return 0;
        }
    }
}
