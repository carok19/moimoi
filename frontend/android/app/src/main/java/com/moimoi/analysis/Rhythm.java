package com.moimoi.analysis;

import java.util.ArrayList;
import java.util.List;

/** Tempo (BPM), pulsos, compás y primer tiempo de cada compás (analysis/beats.py). */
public final class Rhythm {

    public Double bpm;
    public double[] beats = new double[0];
    public double[] downbeats = new double[0];
    public int downbeatPhase;
    public int beatsPerBar = 4;
    public boolean steady;
    public double confidence;
    public double meterConfidence;

    static final int SR = Dsp.SR;
    static final int HOP = Dsp.HOP;

    // ---- envolventes ----------------------------------------------------------------------------

    /** features.onset_envelope(y, fmax, fmin): los mismos parámetros de bandas mel. */
    static float[] onset(float[] y, Double fmax, Double fmin) {
        int nMels = 128;
        int nFft = 2048;
        double hi = SR / 2.0;
        if (fmax != null && fmax < 1000) {
            hi = fmax;
            nMels = 16;
            nFft = 4096;
        } else if (fmax != null) {
            hi = fmax;
            nMels = 40;
        }
        double lo = fmin != null ? fmin : 0.0;
        return Spectrum.onsetStrength(y, SR, HOP, nFft, nMels, lo, hi);
    }

    /** _unit: dividido por el percentil 99 y recortado a [0, 2]. */
    static double[] unit(float[] v) {
        return unit(Dsp.toDouble(v));
    }

    static double[] unit(double[] v) {
        double top = v.length > 0 ? Dsp.percentile(v, 99) : 0.0;
        double[] out = new double[v.length];
        if (top > 1e-9) {
            for (int i = 0; i < v.length; i++) {
                out[i] = Math.min(2.0, Math.max(0.0, v[i] / top));
            }
        }
        return out;
    }

    static boolean audible(float[] y, double thresholdDb) {
        if (y == null || y.length == 0) {
            return false;
        }
        return Dsp.percentile(Dsp.rmsDb(y, 2048, 2048), 90) > thresholdDb;
    }

    // ---- grilla constante ----------------------------------------------------------------------

    static final class Grid {
        double period;
        double offset;
        long k0;
        long k1;
        boolean steady;
    }

    /** fit_steady_grid: si los pulsos siguen un tempo constante (con click), una grilla perfecta. */
    static Grid fitSteadyGrid(double[] t) {
        int n = t.length;
        if (n < 12) {
            return null;
        }
        double[] diffs = diff(t);
        double period = Dsp.median(diffs);
        if (period <= 0) {
            return null;
        }
        double[] k = new double[n];
        for (int i = 1; i < n; i++) {
            k[i] = k[i - 1] + Math.max(1, Math.rint(diffs[i - 1] / period));
        }
        boolean[] mask = new boolean[n];
        java.util.Arrays.fill(mask, true);
        double slope = period, intercept = t[0];
        for (int iter = 0; iter < 4; iter++) {
            int count = 0;
            for (boolean m : mask) {
                if (m) {
                    count++;
                }
            }
            if (count < 8) {
                return null;
            }
            // Mínimos cuadrados de t = slope * k + intercept con los pulsos elegidos.
            double sk = 0, st = 0, skk = 0, skt = 0;
            for (int i = 0; i < n; i++) {
                if (mask[i]) {
                    sk += k[i];
                    st += t[i];
                    skk += k[i] * k[i];
                    skt += k[i] * t[i];
                }
            }
            double den = count * skk - sk * sk;
            slope = (count * skt - sk * st) / den;
            intercept = (st - slope * sk) / count;
            double[] resid = new double[count];
            int c = 0;
            for (int i = 0; i < n; i++) {
                if (mask[i]) {
                    resid[c++] = t[i] - (slope * k[i] + intercept);
                }
            }
            double limit = Math.max(0.03, 3.0 * Dsp.std(resid));
            for (int i = 0; i < n; i++) {
                mask[i] = Math.abs(t[i] - (slope * k[i] + intercept)) < limit;
            }
        }
        double sq = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (mask[i]) {
                double r = t[i] - (slope * k[i] + intercept);
                sq += r * r;
                count++;
            }
        }
        double rms = count > 0 ? Math.sqrt(sq / count) : Double.NaN;
        double coverage = count / (double) n;
        int half = n / 2;
        double d1 = Dsp.median(diff(java.util.Arrays.copyOfRange(t, 0, half)));
        double d2 = Dsp.median(diff(java.util.Arrays.copyOfRange(t, half, n)));
        double drift = Math.abs(d1 - d2) / period;
        Grid g = new Grid();
        g.period = slope;
        g.offset = intercept;
        g.k0 = (long) k[0];
        g.k1 = (long) k[n - 1];
        g.steady = rms < 0.02 && coverage > 0.85 && drift < 0.02;
        return g;
    }

    static double[] diff(double[] v) {
        double[] out = new double[Math.max(0, v.length - 1)];
        for (int i = 0; i + 1 < v.length; i++) {
            out[i] = v[i + 1] - v[i];
        }
        return out;
    }

    /** attack_offset: corrección fina de fase (el detector marca el pulso unos ms tarde). */
    static double attackOffset(double[] beatTimes, Attack attack) {
        if (beatTimes.length < 8 || attack == null || !attack.any || attack.n < 10) {
            return 0.0;
        }
        double[] rise = attack.rise;
        int hop = 32, frame = 64;
        double step = hop / (double) SR;
        List<Double> offsets = new ArrayList<>();
        for (double t : beatTimes) {
            int lo = (int) ((t - 0.08) / step);
            int hi = (int) ((t + 0.03) / step);
            if (lo < 1 || hi >= rise.length || hi <= lo) {
                continue;
            }
            int peak = lo;
            for (int i = lo + 1; i < hi; i++) {
                if (rise[i] > rise[peak]) {
                    peak = i;
                }
            }
            if (rise[peak] > 1.0) { // subida de energía clara (~4 dB en 3 ms)
                offsets.add(peak * step + frame / 2.0 / SR - t);
            }
        }
        if (offsets.size() < Math.max(6, beatTimes.length / 5)) {
            return 0.0;
        }
        double[] o = new double[offsets.size()];
        for (int i = 0; i < o.length; i++) {
            o[i] = offsets.get(i);
        }
        double offset = Dsp.median(o);
        return Math.abs(offset) < 0.07 ? offset : 0.0;
    }

    static double[] atBeats(float[] env, int[] beatFrames) {
        double[] out = new double[beatFrames.length];
        for (int i = 0; i < beatFrames.length; i++) {
            int f = beatFrames[i];
            if (f < env.length) {
                double m = Double.NEGATIVE_INFINITY;
                for (int j = Math.max(0, f - 2); j < Math.min(env.length, f + 3); j++) {
                    m = Math.max(m, env[j]);
                }
                out[i] = m;
            }
        }
        return out;
    }

    static double[] atBeats(double[] env, int[] beatFrames) {
        double[] out = new double[beatFrames.length];
        for (int i = 0; i < beatFrames.length; i++) {
            int f = beatFrames[i];
            if (f < env.length) {
                double m = Double.NEGATIVE_INFINITY;
                for (int j = Math.max(0, f - 2); j < Math.min(env.length, f + 3); j++) {
                    m = Math.max(m, env[j]);
                }
                out[i] = m;
            }
        }
        return out;
    }

    /** half_time_phase: ¿el detector marcó el doble del tempo? Fase (0/1) de los pulsos fuertes. */
    static Integer halfTimePhase(int[] beatFrames, float[] kick, float[] snare) {
        if (beatFrames.length < 16) {
            return null;
        }
        double[] a = unit(atBeats(kick, beatFrames));
        double[] b = unit(atBeats(snare, beatFrames));
        double even = 0, odd = 0;
        int ne = 0, no = 0;
        for (int i = 0; i < a.length; i++) {
            double v = a[i] + b[i];
            if (i % 2 == 0) {
                even += v;
                ne++;
            } else {
                odd += v;
                no++;
            }
        }
        even /= Math.max(1, ne);
        odd /= Math.max(1, no);
        double strong = Math.max(even, odd), weak = Math.min(even, odd);
        if (strong > 0 && weak < 0.35 * strong) {
            return even >= odd ? 0 : 1;
        }
        return null;
    }

    /** estimate_meter_and_downbeats: compás (3 o 4) y en qué pulso cae el "1". {compás, fase, confianza}. */
    static double[] meterAndDownbeats(int[] beatFrames, double[] lowEnv, double[][] trebleChroma, double[][] bassChroma) {
        int n = beatFrames.length;
        if (n < 8) {
            return new double[] {4, 0, 0.0};
        }
        double[] low = atBeats(lowEnv, beatFrames);
        int total = trebleChroma[0].length;
        double[] ct = change(Beats.syncMedian(trebleChroma, beatFrames, total), n);
        double[] cb = change(Beats.syncMedian(bassChroma, beatFrames, total), n);
        double[] nl = Dsp.normalize(low), nt = Dsp.normalize(ct), nb = Dsp.normalize(cb);
        double[] feature = new double[n];
        for (int i = 0; i < n; i++) {
            feature[i] = 0.8 * nl[i] + 1.0 * nt[i] + 0.7 * nb[i];
        }
        int[][] options = {{4, 0}, {4, 1}, {4, 2}, {4, 3}, {3, 0}, {3, 1}, {3, 2}};
        double[] scores = new double[options.length];
        int best = 0;
        for (int o = 0; o < options.length; o++) {
            int meter = options[o][0], phase = options[o][1];
            double on = 0, off = 0;
            int non = 0, noff = 0;
            for (int i = 0; i < n; i++) {
                if (i >= phase && (i - phase) % meter == 0) {
                    on += feature[i];
                    non++;
                } else {
                    off += feature[i];
                    noff++;
                }
            }
            double score = (non > 0 ? on / non : Double.NaN) - (noff > 0 ? off / noff : Double.NaN);
            scores[o] = score + (meter == 4 ? 0.12 : 0.0);
            if (scores[o] > scores[best]) {
                best = o;
            }
        }
        double second = Double.NEGATIVE_INFINITY;
        for (int o = 0; o < options.length; o++) {
            if (o != best) {
                second = Math.max(second, scores[o]);
            }
        }
        double confidence = Math.min(1.0, Math.max(0.0, (scores[best] - second) / 0.5));
        return new double[] {options[best][0], options[best][1], confidence};
    }

    /** Cambio de armonía en cada pulso: 1 - coseno entre el tramo que termina y el que empieza. */
    private static double[] change(double[][] sync, int n) {
        int segs = sync[0].length;
        double[][] norm = new double[12][segs];
        for (int s = 0; s < segs; s++) {
            double sq = 0;
            for (int c = 0; c < 12; c++) {
                sq += sync[c][s] * sync[c][s];
            }
            double d = Math.max(Math.sqrt(sq), 1e-9);
            for (int c = 0; c < 12; c++) {
                norm[c][s] = sync[c][s] / d;
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int before = Math.min(i, segs - 1);
            int after = Math.min(i + 1, segs - 1);
            double dot = 0;
            for (int c = 0; c < 12; c++) {
                dot += norm[c][before] * norm[c][after];
            }
            out[i] = 1.0 - dot;
        }
        return out;
    }

    // ---- análisis completo ------------------------------------------------------------------------

    /** Envolvente fina de energía (saltos de 32 muestras) para attack_offset. */
    static final class Attack {
        boolean any;
        int n;
        double[] rise;
    }

    static Attack attack(float[] y) {
        Attack a = new Attack();
        for (float v : y) {
            if (Math.abs(v) > 1e-4f) {
                a.any = true;
                break;
            }
        }
        int hop = 32, frame = 64;
        a.n = 1 + Math.floorDiv(y.length - frame, hop);
        if (!a.any || a.n < 10) {
            return a;
        }
        double[] energy = new double[a.n];
        for (int f = 0; f < a.n; f++) {
            double s = 0;
            int start = f * hop;
            for (int i = 0; i < frame; i++) {
                double v = y[start + i];
                s += v * v;
            }
            energy[f] = Math.log(s / frame + 1e-10);
        }
        a.rise = new double[a.n];
        for (int f = 1; f < a.n; f++) {
            a.rise[f] = Math.max(energy[f] - energy[f - 1], 0);
        }
        return a;
    }

    /** Lo que el análisis de ritmo necesita de las pistas (analyze_rhythm). */
    static final class Input {
        double duration;
        float[] mixOnset;    // onset(mezcla)
        double[] mixLoud;    // rms_db(mezcla, 2048, HOP)
        boolean drumsOk;     // _is_audible(batería)
        float[] drumsOnset;  // onset(batería)
        float[] drumsKick;   // onset(batería, fmax=160): bombo y también low_env
        float[] drumsSnare;  // onset(batería, fmin=160, fmax=3000)
        float[] mixLow;      // onset(mezcla, fmax=160), si no hay batería audible
        Attack attack;       // de la batería si se oye, si no de la mezcla
        float[] bassOnset;   // onset(bajo, fmax=320) o null
    }

    static Rhythm analyze(Input in, double[][] trebleChroma, double[][] bassChroma) {
        Rhythm r = new Rhythm();
        double[] env = unit(in.mixOnset);
        if (in.drumsOk) {
            double[] d = unit(in.drumsOnset);
            for (int i = 0; i < env.length; i++) {
                env[i] = 0.5 * env[i] + d[i];
            }
        }
        boolean any = false;
        for (double v : env) {
            if (v > 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return r;
        }
        double tempo = Beats.tempo(env, SR, HOP, 120, 240);
        int[] beatFrames = Beats.track(env, SR, HOP, tempo, 120);

        // Quitar pulsos en silencio (antes de que empiece o después de que termine la música).
        double[] loud = in.mixLoud;
        double limit = Math.max(-50.0, Dsp.max(loud) - 45.0);
        int first = -1, last = -1;
        for (int i = 0; i < loud.length; i++) {
            if (loud[i] > limit) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first >= 0) {
            int lo = first - 4, hi = last + 4;
            List<Integer> kept = new ArrayList<>();
            for (int f : beatFrames) {
                if (f >= lo && f <= hi) {
                    kept.add(f);
                }
            }
            beatFrames = toInts(kept);
        }
        if (beatFrames.length < 4) {
            r.bpm = tempo != 0 ? Dsp.round(tempo, 1) : null;
            return r;
        }

        if (in.drumsOk && beatFrames.length >= 16) {
            double[] d = new double[beatFrames.length - 1];
            for (int i = 0; i + 1 < beatFrames.length; i++) {
                d[i] = beatFrames[i + 1] - beatFrames[i];
            }
            double periodS = Dsp.median(d) * HOP / SR;
            if (60.0 / (2 * periodS) >= 50) { // no bajar de 50 BPM
                Integer phase = halfTimePhase(beatFrames, in.drumsKick, in.drumsSnare);
                if (phase != null) {
                    List<Integer> kept = new ArrayList<>();
                    for (int i = phase; i < beatFrames.length; i += 2) {
                        kept.add(beatFrames[i]);
                    }
                    beatFrames = toInts(kept);
                }
            }
        }

        // Confianza del pulso: cuánto más fuerte es el ataque en los pulsos que en el resto.
        double onBeat = 0;
        for (int f : beatFrames) {
            onBeat += env[Math.max(0, Math.min(env.length - 1, f))];
        }
        onBeat /= beatFrames.length;
        double pulseConfidence = Math.min(1, Math.max(0, (onBeat / Math.max(Dsp.mean(env), 1e-9) - 1.0) / 1.5));

        double[] beatTimes = new double[beatFrames.length];
        for (int i = 0; i < beatFrames.length; i++) {
            beatTimes[i] = beatFrames[i] * (double) HOP / SR;
        }
        double offset = attackOffset(beatTimes, in.attack);
        for (int i = 0; i < beatTimes.length; i++) {
            beatTimes[i] += offset;
        }
        Grid grid = fitSteadyGrid(beatTimes);
        boolean steady = grid != null && grid.steady;
        double bpm;
        if (steady) {
            List<Double> times = new ArrayList<>();
            for (long k = grid.k0; k <= grid.k1; k++) {
                double t = grid.offset + grid.period * k;
                if (t >= 0 && t < in.duration) {
                    times.add(t);
                }
            }
            beatTimes = new double[times.size()];
            beatFrames = new int[times.size()];
            for (int i = 0; i < beatTimes.length; i++) {
                beatTimes[i] = times.get(i);
                beatFrames[i] = (int) Math.rint(beatTimes[i] * SR / HOP);
            }
            bpm = 60.0 / grid.period;
        } else {
            bpm = 60.0 / Dsp.median(diff(beatTimes));
        }

        double[] lowEnv = Dsp.toDouble(in.drumsOk ? in.drumsKick : in.mixLow);
        if (in.bassOnset != null) {
            double[] a = unit(lowEnv);
            double[] b = unit(in.bassOnset);
            lowEnv = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                lowEnv[i] = a[i] + 0.6 * b[i];
            }
        }
        double[] meter = meterAndDownbeats(beatFrames, lowEnv, trebleChroma, bassChroma);
        int beatsPerBar = (int) meter[0];
        int phase = (int) meter[1];
        List<Double> downs = new ArrayList<>();
        for (int i = phase; i < beatTimes.length; i += beatsPerBar) {
            downs.add(beatTimes[i]);
        }
        r.bpm = Dsp.round(bpm, 1);
        r.beats = new double[beatTimes.length];
        for (int i = 0; i < beatTimes.length; i++) {
            r.beats[i] = Dsp.round(beatTimes[i], 3);
        }
        r.downbeats = new double[downs.size()];
        for (int i = 0; i < r.downbeats.length; i++) {
            r.downbeats[i] = Dsp.round(downs.get(i), 3);
        }
        r.downbeatPhase = phase;
        r.beatsPerBar = beatsPerBar;
        r.steady = steady;
        r.confidence = Dsp.round(pulseConfidence, 2);
        r.meterConfidence = Dsp.round(meter[2], 2);
        return r;
    }

    static int[] toInts(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
