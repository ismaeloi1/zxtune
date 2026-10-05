package app.zxtune.rpc;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import app.zxtune.Log;
import app.zxtune.playback.ScopeLayout;
import app.zxtune.playback.Visualizer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VisualizerProxy {

  private static final String TAG = VisualizerProxy.class.getName();

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

    private byte[] scopeBuffer = new byte[0];

    @Override
    public synchronized int getScope(short[] data, int points, int windowMs) {
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
    public int getGauges(byte[] data, int waveWindowMs) {
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
    public String getStatus() {
      try {
        return delegate.getStatus();
      } catch (Exception e) {
        Log.w(TAG, e, "getStatus()");
      }
      return "";
    }

  }

}
