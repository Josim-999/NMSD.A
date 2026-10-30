#!/usr/bin/env python3
"""Phoneme-aware, sample-based lip sync (CPU only).

Re-times a real talking-head clip of a person so their mouth matches a new voice track.
Every output mouth is a real frame of the person (Video Rewrite style); frames are chosen by
Viterbi over (a) MFCC sound similarity, (b) viseme class from phoneme recognition, with a hard
lip-closure rule on m/b/p (JALI), and (c) continuity. Reports timing + articulation scores.

usage: lipsync.py --face clip25.mp4 --face-audio clip16k.wav --voice voice.wav --out synced.mp4
                  [--clean-frames N] [--work DIR]
"""
import argparse, os, pickle, subprocess
import cv2, dlib, librosa, numpy as np

FPS, SR, HOP, LEAD = 25, 16000, 640, 1          # mouth leads audio by 1 frame (40 ms), as in real speech
CLOSED = 0.012
VIS = {"BILAB": "p b m", "LABDENT": "f v", "ROUND": "u ʊ w o ɔ ʉ uː oː ɒ",
       "OPEN": "a ɑ æ ʌ ɐ aː ɑː ä", "SPREAD": "i ɪ e ɛ j eː iː ɘ"}
VIS = {k: set(v.split()) for k, v in VIS.items()}


def predictor_path():
    p = os.environ.get("LIPSYNC_SHAPE_PREDICTOR",
                       os.path.join(os.path.dirname(__file__), "..", "models", "shape_predictor_68_face_landmarks.dat"))
    return os.path.abspath(p)


class Landmarker:
    def __init__(self):
        self.det = dlib.get_frontal_face_detector()
        self.pred = dlib.shape_predictor(predictor_path())
        self.last = None

    def __call__(self, img):
        s = 0.25 if max(img.shape[:2]) > 1000 else 0.5
        g = cv2.cvtColor(cv2.resize(img, None, fx=s, fy=s), cv2.COLOR_BGR2GRAY)
        r = self.det(g, 1)
        if not r:
            return self.last
        r = max(r, key=lambda r: r.area())
        R = dlib.rectangle(int(r.left() / s), int(r.top() / s), int(r.right() / s), int(r.bottom() / s))
        sh = self.pred(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY), R)
        self.last = np.array([[p.x, p.y] for p in sh.parts()], np.float32)
        return self.last


def read_frames(path):
    cap, F = cv2.VideoCapture(path), []
    while True:
        ok, f = cap.read()
        if not ok:
            return F
        F.append(f)


def aperture(P):
    return np.linalg.norm(P[66] - P[62]) / np.linalg.norm(P[45] - P[36])


def apertures(video):
    lm = Landmarker()
    return np.array([aperture(P) if P is not None else 0.0 for P in map(lm, read_frames(video))])


def envelope(wav, n):
    y, _ = librosa.load(wav, sr=SR)
    S = np.abs(librosa.stft(y, n_fft=1024, hop_length=HOP))
    f = librosa.fft_frequencies(sr=SR, n_fft=1024)
    e = np.log(S[(f > 250) & (f < 3500)].sum(0) + 1e-6)[:n]
    return np.pad(e, (0, max(0, n - len(e))), mode="edge")


def timing_score(ap, env, maxlag=8):
    a, e = (ap - ap.mean()) / ap.std(), (env - env.mean()) / env.std()
    out = {}
    for k in range(-maxlag, maxlag + 1):
        x, y = (a[:len(a) - k], e[k:]) if k >= 0 else (a[-k:], e[:len(e) + k])
        out[k] = float(np.corrcoef(x, y)[0, 1])
    best = max(out, key=out.get)
    return best, out[best]


_allo = None


def phone_events(wav):
    global _allo
    if _allo is None:
        from allosaurus.app import read_recognizer
        _allo = read_recognizer("uni2005")
    rows = [l.split() for l in _allo.recognize(wav, "eng", timestamp=True).strip().split("\n")]
    ph = [(float(a), p) for a, _, p in rows]
    ev = []
    for i, (t, p) in enumerate(ph):
        end = min(ph[i + 1][0] if i + 1 < len(ph) else t + 0.09, t + 0.16)
        c = next((k for k, v in VIS.items() if p in v), "OTHER")
        ev.append((t, end, p, c))
    return ev


def frame_labels(events, n):
    lab = np.array(["SIL"] * n, dtype=object)
    for t, e, p, c in events:
        a = int(round(t * FPS))
        lab[a:min(max(a + 1, int(round(e * FPS))), n)] = c
    return lab


def mfcc_feats(wav, n):
    y, _ = librosa.load(wav, sr=SR)
    m = librosa.feature.mfcc(y=y, sr=SR, n_mfcc=13, n_fft=1024, hop_length=HOP, n_mels=40)
    m = np.vstack([m, librosa.feature.delta(m)])
    m = (m - m.mean(1, keepdims=True)) / (m.std(1, keepdims=True) + 1e-6)
    m = np.pad(m, ((0, 0), (0, max(0, n + 8 - m.shape[1]))), mode="edge")
    ctx = np.stack([np.roll(m, -k - LEAD, axis=1) for k in (-2, -1, 0, 1, 2)]).reshape(-1, m.shape[1])[:, :n]
    w = np.array([0.6, 0.85, 1.0, 0.85, 0.6]).repeat(26)[:, None]
    return (ctx * w).T


def closure_rate(ap, events, cls="BILAB", thr=CLOSED):
    h = [ap[max(0, int(round(t * FPS)) - 2):int(round(e * FPS)) + 2].min() <= thr
         for t, e, p, c in events if c == cls and int(round(t * FPS)) < len(ap)]
    return (float(np.mean(h)) if h else float("nan")), len(h)


def plan(L, ap, src_lab, Fs, Ft, Et, tgt_lab, clean, w_bil=60, w_ld=20, w_v=2, jump=4.0, hold=0.35):
    NS, NT = len(ap), len(Ft)
    silent = Et < (np.percentile(Et, 20) + 0.35 * (np.percentile(Et, 90) - np.percentile(Et, 20)))
    slab, tlab = np.roll(src_lab, -LEAD), np.roll(tgt_lab, -LEAD)
    C = ((Ft[:, None, :] - Fs[None, :, :]) ** 2).mean(2)
    C /= np.median(C)
    C += np.where(silent[:, None], 6.0 * ap[None, :] / ap.max(), 0)
    C += (tlab == "BILAB")[:, None] * w_bil * np.clip((ap[None, :] - 0.010) / 0.01, 0, None)
    C += (tlab == "LABDENT")[:, None] * w_ld * np.clip((ap[None, :] - 0.018) / 0.01, 0, None)
    for cls, lo, hi in [("OPEN", 0.040, 1), ("ROUND", 0.015, 0.045), ("SPREAD", 0.030, 1)]:
        C += (tlab == cls)[:, None] * w_v * (np.clip(lo - ap[None, :], 0, None) + np.clip(ap[None, :] - hi, 0, None)) / 0.01
    C += w_v * 0.5 * (slab[None, :] != tlab[:, None]) * (tlab[:, None] != "SIL") * (tlab[:, None] != "OTHER")
    C[:, clean - 2:] += 1e6
    iod = np.linalg.norm(L[:, 45] - L[:, 36], axis=1)[:, None, None]
    M = (L[:, 48:68] - L[:, 48:68].mean(1, keepdims=True)) / iod
    shape = np.sqrt(((M[:, None] - M[None, :]) ** 2).sum((2, 3)))
    T = jump + 4.0 * shape / shape.max()
    T[np.arange(NS - 1), np.arange(1, NS)] = 0.0
    T[np.arange(NS), np.arange(NS)] = hold
    dp, bp = C[0].copy(), np.zeros((NT, NS), int)
    for t in range(1, NT):
        tot = dp[:, None] + T
        bp[t] = tot.argmin(0)
        dp = tot.min(0) + C[t]
    path = np.zeros(NT, int)
    path[-1] = dp.argmin()
    for t in range(NT - 1, 0, -1):
        path[t - 1] = bp[t, path[t]]
    return path


def render(F, L, path, clean, out):
    k = np.array([1, 2, 3, 2, 1], float) / 9
    Ls = np.stack([np.stack([np.convolve(np.pad(L[:, i, j], 2, mode="edge"), k, "valid") for j in range(2)], 1)
                   for i in range(68)], 1)
    H, W = F[0].shape[:2]
    STABLE = list(range(27, 48))
    ff = subprocess.Popen(["ffmpeg", "-nostdin", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{W}x{H}",
                           "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-crf", "14", "-pix_fmt", "yuv420p", out, "-y"],
                          stdin=subprocess.PIPE)

    def lower_poly(P, iod):
        c = P[27:36].mean(0)
        jaw = c + (P[2:15] - c) * 1.06
        jaw[:, 1] += 0.10 * iod
        top = P[[35, 34, 33, 32, 31]].copy()
        top[:, 1] += 0.10 * iod
        return np.vstack([jaw, top]).astype(np.int32)

    period = 2 * clean - 2
    for t, s in enumerate(path):
        b = t % period
        b = b if b < clean else period - b                       # ping-pong base inside the clean section
        base, Pb, Ps = F[b], Ls[b], Ls[s]
        if s == b:
            ff.stdin.write(base.tobytes())
            continue
        M, _ = cv2.estimateAffinePartial2D(Ps[STABLE], Pb[STABLE], method=cv2.LMEDS)
        warped = cv2.warpAffine(F[s], M, (W, H), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_REFLECT)
        Pw = np.c_[Ps, np.ones(68)] @ M.T
        iod = np.linalg.norm(Pb[45] - Pb[36])
        mask = np.zeros((H, W), np.float32)
        cv2.fillPoly(mask, [cv2.convexHull(np.vstack([lower_poly(Pw, iod), lower_poly(Pb, iod)]))], 1.0)
        mask = cv2.GaussianBlur(mask, (0, 0), 0.09 * iod)
        ring = (mask > 0.05) & (mask < 0.6)
        if ring.sum() > 500:
            warped = np.clip(warped.astype(np.float32) + base[ring].astype(np.float32).mean(0)
                             - warped[ring].astype(np.float32).mean(0), 0, 255)
        m = mask[..., None]
        ff.stdin.write((base * (1 - m) + warped * m).astype(np.uint8).tobytes())
    ff.stdin.close()
    ff.wait()


def main():
    ap_ = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap_.add_argument("--face", required=True, help="real talking clip of the person, 25 fps, no burned-in text over the face")
    ap_.add_argument("--face-audio", required=True, help="that clip's own speech, 16 kHz mono wav")
    ap_.add_argument("--voice", required=True, help="new voice track (any rate); output length follows it")
    ap_.add_argument("--out", required=True)
    ap_.add_argument("--clean-frames", type=int, default=0, help="use only the first N frames (e.g. before captions cover the chin)")
    ap_.add_argument("--work", default="lipsync_work")
    a = ap_.parse_args()
    os.makedirs(a.work, exist_ok=True)
    voice16 = os.path.join(a.work, "voice16k.wav")
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", a.voice, "-ac", "1", "-ar", str(SR), voice16, "-y"], check=True)
    y16, _ = librosa.load(voice16, sr=SR)
    NT = int(len(y16) / SR * FPS)

    F = read_frames(a.face)
    clean = a.clean_frames or len(F)
    lm = Landmarker()
    L = [lm(f) for f in F]
    for i in range(len(L)):
        L[i] = L[i] if L[i] is not None else L[i - 1]
    L = np.stack(L).astype(np.float64)
    ap = np.array([aperture(P) for P in L])

    src_ev, tgt_ev = phone_events(a.face_audio), phone_events(voice16)
    pickle.dump(tgt_ev, open(os.path.join(a.work, "target_phones.pkl"), "wb"))
    lag, r = timing_score(ap, envelope(a.face_audio, len(ap)))
    rb, nb = closure_rate(ap, src_ev)
    print(f"[reference] real clip: timing corr {r:.3f} at {lag * 40:+d} ms | lips close on m/b/p {rb * 100:.0f}% of {nb}")

    Et = envelope(voice16, NT)
    path = plan(L, ap, frame_labels(src_ev, len(ap)), mfcc_feats(a.face_audio, len(ap)), mfcc_feats(voice16, NT), Et,
                frame_labels(tgt_ev, NT), clean)
    render(F, L, path, clean, os.path.join(a.work, "video_only.mp4"))
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", os.path.join(a.work, "video_only.mp4"), "-i", a.voice,
                    "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", a.out, "-y"],
                   check=True)

    out_ap = apertures(os.path.join(a.work, "video_only.mp4"))[:NT]
    lag, r = timing_score(out_ap, Et[:len(out_ap)])
    rb, nb = closure_rate(out_ap, tgt_ev)
    rf, nf = closure_rate(out_ap, tgt_ev, "LABDENT", 0.02)
    print(f"[result] timing corr {r:.3f} at {lag * 40:+d} ms | lips close on m/b/p {rb * 100:.0f}% of {nb} | f/v {rf * 100:.0f}% of {nf}")


if __name__ == "__main__":
    main()
