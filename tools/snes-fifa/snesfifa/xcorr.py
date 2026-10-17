"""Compare two renders by cross-correlation (lag search + windowed correlation + SNR)."""
import argparse
import json
import wave
from typing import Dict, Optional, Tuple

import numpy as np


def read_wav(path: str) -> Tuple[np.ndarray, int]:
    with wave.open(path, "rb") as w:
        n, ch, sw, rate = w.getnframes(), w.getnchannels(), w.getsampwidth(), w.getframerate()
        raw = w.readframes(n)
    if sw != 2:
        raise ValueError("only 16-bit PCM supported")
    a = np.frombuffer(raw, dtype="<i2").astype(np.float64).reshape(-1, ch)
    return a, rate


def write_wav(path: str, data: np.ndarray, rate: int) -> None:
    d = np.asarray(data)
    if d.ndim == 1:
        d = d[:, None]
    pcm = np.clip(np.round(d), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(d.shape[1])
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def _resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst:
        return x
    n = int(round(len(x) * dst / src))
    t = np.arange(n) * (src / dst)
    return np.interp(t, np.arange(len(x)), x)


def best_lag(ref: np.ndarray, test: np.ndarray, max_lag: int) -> Tuple[int, float]:
    """Lag L (in samples) maximising sum ref[i+L]*test[i]; returns (L, normalised peak)."""
    n = len(ref) + len(test)
    size = 1 << (n - 1).bit_length()
    fr = np.fft.rfft(ref, size)
    ft = np.fft.rfft(test, size)
    cc = np.fft.irfft(fr * np.conj(ft), size)
    lags = np.concatenate([np.arange(0, max_lag + 1), np.arange(-max_lag, 0)])
    vals = np.concatenate([cc[:max_lag + 1], cc[size - max_lag:]])
    k = int(np.argmax(vals))
    lag = int(lags[k])
    a, b = _overlap(ref, test, lag)
    denom = np.sqrt((a * a).sum() * (b * b).sum()) or 1.0
    return lag, float((a * b).sum() / denom)


def _overlap(ref, test, lag):
    if lag >= 0:
        n = min(len(ref) - lag, len(test))
        return ref[lag:lag + n], test[:n]
    n = min(len(ref), len(test) + lag)
    return ref[:n], test[-lag:-lag + n]


def compare(ref_path: str, test_path: str, max_lag_s: float = 5.0, window_s: float = 1.0,
            search_s: float = 20.0, local_s: float = 0.05, expected_lag_s: Optional[float] = None,
            radius_s: float = 0.15, limit_s: Optional[float] = None) -> Dict[str, object]:
    ref, rr = read_wav(ref_path)
    test, tr = read_wav(test_path)
    rate = rr
    refm = ref.mean(axis=1)
    testm = _resample(test.mean(axis=1), tr, rr)
    if limit_s:
        testm = testm[:int(limit_s * rate)]
    max_lag = int(max_lag_s * rate)
    # coarse lag from onset envelopes (robust to phase drift), then waveform refinement
    hop = rate // 100
    def onset(x):
        n = len(x) // hop
        e = np.sqrt((x[:n * hop].reshape(n, hop) ** 2).mean(axis=1))
        return np.diff(e).clip(0)
    eref, etest = onset(refm), onset(testm)
    seg_e = int(search_s * 100)
    if expected_lag_s is None:
        elag, _ = best_lag(eref[:seg_e + max_lag // hop], etest[:seg_e], max_lag // hop)
        coarse = elag * hop
        rad = 2 * hop
    else:
        coarse = int(round(expected_lag_s * rate))
        rad = int(radius_s * rate)
    seg = int(min(search_s, 5.0) * rate)
    # start the refinement where the test signal has content
    act = np.flatnonzero(np.abs(testm) > 100)
    start = int(act[0]) if len(act) else 0
    lo = max(0, coarse + start - rad)
    ref_seg = refm[lo:coarse + start + seg + rad]
    fine, _ = best_lag(ref_seg, testm[start:start + seg], 2 * rad)
    if fine < 0:
        fine = 0
    lag = lo + fine - start
    a, b = _overlap(refm, testm, lag)
    gain = float((a * b).sum() / ((b * b).sum() or 1.0))
    err = a - gain * b
    snr = 10 * np.log10(((a * a).sum() or 1.0) / ((err * err).sum() or 1e-9))
    win = int(window_s * rate)
    corrs = []
    for i in range(0, len(a) - win, win):
        x, y = a[i:i + win], b[i:i + win]
        if np.sqrt((x * x).mean()) < 30 and np.sqrt((y * y).mean()) < 30:
            continue  # both silent
        d = np.sqrt((x * x).sum() * (y * y).sum())
        corrs.append(float((x * y).sum() / d) if d else 0.0)
    corrs_np = np.array(corrs) if corrs else np.array([0.0])
    # timing-tolerant view: re-align every window locally (tracks tempo drift / sub-frame jitter)
    local = []
    drift = 0
    search = int(local_s * rate)
    lags = []
    for i in range(search, len(b) - win - search, win):
        y = b[i:i + win]
        if np.sqrt((y * y).mean()) < 30:
            continue
        j0 = i + lag + drift
        if j0 - search < 0 or j0 + win + search > len(refm):
            continue
        seg_ref = refm[j0 - search:j0 + win + search]
        l2, c2 = best_lag(seg_ref, y, 2 * search)
        l2 -= search
        if abs(l2) <= search:
            drift += l2
            x = refm[j0 + l2:j0 + l2 + win]
            d = np.sqrt((x * x).sum() * (y * y).sum())
            local.append(float((x * y).sum() / d) if d else 0.0)
            lags.append(drift)
    local_np = np.array(local) if local else np.array([0.0])
    return {
        "local_corr_median": float(np.median(local_np)), "local_corr_p10": float(np.percentile(local_np, 10)),
        "local_windows_below_0.9": int((local_np < 0.9).sum()),
        "drift_end_ms": (lags[-1] / rate * 1000.0) if lags else 0.0,
        "lag_samples": lag, "lag_seconds": lag / rate, "rate": rate,
        "overlap_seconds": len(a) / rate, "global_corr": float(np.corrcoef(a, b)[0, 1]) if len(a) > 1 else 0.0,
        "gain": gain, "snr_db": float(snr),
        "window_corr_min": float(corrs_np.min()), "window_corr_median": float(np.median(corrs_np)),
        "window_corr_p10": float(np.percentile(corrs_np, 10)),
        "windows_below_0.9": int((corrs_np < 0.9).sum()), "windows": int(len(corrs_np)),
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("reference")
    ap.add_argument("test")
    ap.add_argument("--max-lag", type=float, default=5.0)
    ap.add_argument("--window", type=float, default=1.0)
    ap.add_argument("--expected-lag", type=float, help="known offset of test inside reference (s)")
    a = ap.parse_args(argv)
    print(json.dumps(compare(a.reference, a.test, a.max_lag, a.window, expected_lag_s=a.expected_lag), indent=1))


if __name__ == "__main__":
    main()
