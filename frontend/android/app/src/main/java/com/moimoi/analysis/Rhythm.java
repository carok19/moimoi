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
    /** Tramos de tempo (uno solo si la canción no cambia de tempo). */
    public List<Part> parts = new ArrayList<>();
    /** La curva de ataques que usó el detector de pulsos (para volver a acomodarlos a otro tempo). */
    public double[] onset = new double[0];

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

    /**
     * counted_half: el error contrario a halfTimePhase, el detector contó la mitad del tempo (140
     * leído como 70). Pasa cuando el bombo y el redoblante se turnan entre los pulsos y el medio de
     * cada pulso: son el 1-3 y el 2-4 de un pulso al doble.
     */
    static boolean countedHalf(int[] beatFrames, float[] kick, float[] snare) {
        if (beatFrames.length < 16) {
            return false;
        }
        int n = beatFrames.length - 1;
        int[] starts = java.util.Arrays.copyOf(beatFrames, n);
        int[] mids = new int[n];
        for (int i = 0; i < n; i++) {
            mids[i] = Math.floorDiv(beatFrames[i] + beatFrames[i + 1], 2);
        }
        double[] sn = unit(concat(atBeats(snare, starts), atBeats(snare, mids)));
        double[] kk = unit(concat(atBeats(kick, starts), atBeats(kick, mids)));
        double snareOn = meanOf(sn, 0, n), snareMid = meanOf(sn, n, 2 * n);
        double kickOn = meanOf(kk, 0, n), kickMid = meanOf(kk, n, 2 * n);
        // Uno de los dos cambia claramente de lugar y el otro lo acompaña (los platillos suenan en
        // los dos lugares y le bajan el contraste al redoblante).
        return (snareMid > 1.5 * snareOn && kickOn > 1.2 * kickMid) || (kickOn > 1.5 * kickMid && snareMid > 1.2 * snareOn)
                || (kickMid > 1.5 * kickOn && snareOn > 1.2 * snareMid) || (snareOn > 1.5 * snareMid && kickMid > 1.2 * kickOn);
    }

    private static double[] concat(double[] a, double[] b) {
        double[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static double meanOf(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += v[i];
        }
        return to > from ? s / (to - from) : 0;
    }

    /** estimate_meter_and_downbeats: compás (3 o 4) y en qué pulso cae el "1". {compás, fase, confianza}. */
    static double[] meterAndDownbeats(int[] beatFrames, double[] lowEnv, double[][] trebleChroma, double[][] bassChroma) {
        return meterAndDownbeats(beatFrames, lowEnv, trebleChroma, bassChroma, new int[] {4, 3});
    }

    /** Con `meters`: solo esos compases (p. ej. el de la canción, en un tramo corto). */
    static double[] meterAndDownbeats(int[] beatFrames, double[] lowEnv, double[][] trebleChroma, double[][] bassChroma,
                                      int[] meters) {
        int n = beatFrames.length;
        if (n < 8) {
            return new double[] {meters[0], 0, 0.0};
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
        List<int[]> optionList = new ArrayList<>();
        for (int meter : meters) {
            for (int phase = 0; phase < meter; phase++) {
                optionList.add(new int[] {meter, phase});
            }
        }
        int[][] options = optionList.toArray(new int[0][]);
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
        double confidence = options.length > 1 ? Math.min(1.0, Math.max(0.0, (scores[best] - second) / 0.5)) : 0.0;
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

    // ---- mapa de tempo (canciones con cambios de tempo) ------------------------------------------

    static final double TEMPO_MIN = 55.0;
    static final double TEMPO_MAX = 200.0;
    /** Un tramo de tempo dura por lo menos esto (segundos); si no, se junta con el vecino. */
    static final double TEMPO_MIN_SEGMENT = 16.0;

    /** Un tramo del mapa de tempo: cuadros [start, end) y período en cuadros (con decimales). */
    static final class TempoSegment {
        final int start;
        final int end;
        final double lag;

        TempoSegment(int start, int end, double lag) {
            this.start = start;
            this.end = end;
            this.lag = lag;
        }
    }

    /**
     * tempo_columns: autocorrelación de ventanas de 8 s cada `step` cuadros, como el tempograma
     * de librosa (Hann, relleno en rampa hasta 0 y cada columna dividida por su máximo).
     * Devuelve [columna][retardo]; centers recibe el cuadro central de cada columna.
     */
    static double[][] tempoColumns(double[] env, int step, List<Integer> centers) {
        double fps = SR / (double) HOP;
        int win = (int) Math.floor(8.0 * fps);
        int half = win / 2;
        int n = env.length;
        double[] padded = new double[n + 2 * half];
        for (int i = 0; i < half; i++) {
            double ramp = i / (double) half;
            padded[i] = n > 0 ? env[0] * ramp : 0;
            padded[n + 2 * half - 1 - i] = n > 0 ? env[n - 1] * ramp : 0;
        }
        System.arraycopy(env, 0, padded, half, n);
        float[] window = Dsp.hann(win, true);
        int nFft = Integer.highestOneBit(2 * win - 1) << 1;
        com.moimoi.engine.Fft fft = new com.moimoi.engine.Fft(nFft);
        float[] frame = new float[nFft];
        float[] re = new float[nFft / 2 + 1];
        float[] im = new float[nFft / 2 + 1];
        float[] ac = new float[nFft];
        List<double[]> out = new ArrayList<>();
        for (int t = 0; t < n; t += step) {
            java.util.Arrays.fill(frame, 0f);
            for (int i = 0; i < win; i++) {
                frame[i] = (float) (padded[t + i] * window[i]);
            }
            fft.forward(frame, 0, re, im);
            for (int k = 0; k < re.length; k++) {
                re[k] = re[k] * re[k] + im[k] * im[k];
                im[k] = 0f;
            }
            fft.inverse(re, im, ac, 0);
            float peak = 0f;
            for (int l = 0; l < win; l++) {
                peak = Math.max(peak, Math.abs(ac[l]));
            }
            double[] col = new double[win];
            if (peak > 0) {
                for (int l = 0; l < win; l++) {
                    col[l] = ac[l] / peak;
                }
            }
            out.add(col);
            centers.add(t);
        }
        return out.toArray(new double[0][]);
    }

    private static double median(double[] v, int from, int to) {
        return Dsp.median(v, from, to);
    }

    /**
     * tempo_segments: tramos donde el tempo no cambia. Cada segundo se mide la periodicidad de los
     * ataques (reforzada con la del doble del período: el bombo suele marcar cada dos pulsos) con
     * la preferencia por tempos cercanos a 120, y un Viterbi elige el camino más estable: cambiar
     * de tempo cuesta, y cambiar al doble o a la mitad (un cambio de "feel") cuesta más todavía.
     */
    static List<TempoSegment> tempoSegments(double[] env, double startBpm) {
        double fps = SR / (double) HOP;
        int n = env.length;
        int step = Math.max(1, (int) Math.rint(fps));
        List<Integer> centerList = new ArrayList<>();
        double[][] ac = tempoColumns(env, step, centerList);
        int cols = ac.length;
        int win = ac[0].length;
        int half = win / 2;
        double[][] combed = new double[cols][win];
        for (int c = 0; c < cols; c++) {
            for (int l = 0; l < win; l++) {
                double v = ac[c][l];
                if (l >= 1 && l < half) {
                    v += 0.5 * ac[c][2 * l];
                }
                combed[c][l] = v / 1.5;
            }
        }
        List<Integer> lagList = new ArrayList<>();
        for (int l = 1; l < win; l++) {
            double bpm = 60.0 * fps / l;
            if (bpm >= TEMPO_MIN && bpm <= TEMPO_MAX) {
                lagList.add(l);
            }
        }
        int m = lagList.size();
        int[] lags = new int[m];
        double[] prior = new double[m];
        for (int s = 0; s < m; s++) {
            lags[s] = lagList.get(s);
            double bpm = 60.0 * fps / lags[s];
            double d = Math.log(bpm) / Math.log(2) - Math.log(startBpm) / Math.log(2);
            prior[s] = -0.5 * d * d;
        }
        double[][] cost = new double[m][m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                double ratio = lags[b] / (double) lags[a];
                cost[a][b] = Math.abs(Math.log(ratio) / Math.log(2)) > Math.log(1.8) / Math.log(2) ? 18.0 : 6.0;
                if (a == b) {
                    cost[a][b] = 0.0;
                } else if (Math.abs(a - b) == 1) {
                    cost[a][b] = 0.7;
                }
            }
        }
        double[] acc = new double[m];
        int[][] back = new int[cols][m];
        for (int s = 0; s < m; s++) {
            acc[s] = Math.log1p(1e6 * Math.max(combed[0][lags[s]], 0.0)) + prior[s];
        }
        double[] next = new double[m];
        for (int t = 1; t < cols; t++) {
            for (int b = 0; b < m; b++) {
                double best = Double.NEGATIVE_INFINITY;
                int arg = 0;
                for (int a = 0; a < m; a++) {
                    double v = acc[a] - cost[a][b];
                    if (v > best) {
                        best = v;
                        arg = a;
                    }
                }
                back[t][b] = arg;
                next[b] = best + Math.log1p(1e6 * Math.max(combed[t][lags[b]], 0.0)) + prior[b];
            }
            double[] swap = acc;
            acc = next;
            next = swap;
        }
        int[] path = new int[cols];
        int last = 0;
        for (int s = 1; s < m; s++) {
            if (acc[s] > acc[last]) {
                last = s;
            }
        }
        path[cols - 1] = last;
        for (int t = cols - 1; t > 0; t--) {
            path[t - 1] = back[t][path[t]];
        }
        double[] lagPath = new double[cols];
        for (int t = 0; t < cols; t++) {
            lagPath[t] = lags[path[t]];
        }

        // Tramos donde el período no se aleja más de un cuadro de la mediana del tramo.
        List<int[]> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= cols; i++) {
            if (i == cols || Math.abs(lagPath[i] - median(lagPath, start, i)) > 1) {
                runs.add(new int[] {start, i});
                start = i;
            }
        }
        double minColumns = TEMPO_MIN_SEGMENT * fps / step;
        while (runs.size() > 1) {
            int k = 0;
            for (int i = 1; i < runs.size(); i++) {
                if (runs.get(i)[1] - runs.get(i)[0] < runs.get(k)[1] - runs.get(k)[0]) {
                    k = i;
                }
            }
            int[] r = runs.get(k);
            if (r[1] - r[0] >= minColumns) {
                break;
            }
            double here = median(lagPath, r[0], r[1]);
            double left = k > 0 ? Math.abs(median(lagPath, runs.get(k - 1)[0], runs.get(k - 1)[1]) - here) : Double.POSITIVE_INFINITY;
            double right = k + 1 < runs.size() ? Math.abs(median(lagPath, runs.get(k + 1)[0], runs.get(k + 1)[1]) - here)
                    : Double.POSITIVE_INFINITY;
            if (left <= right) {
                runs.get(k - 1)[1] = r[1];
            } else {
                runs.get(k + 1)[0] = r[0];
            }
            runs.remove(k);
        }

        List<TempoSegment> segments = new ArrayList<>();
        for (int s = 0; s < runs.size(); s++) {
            int a = runs.get(s)[0], b = runs.get(s)[1];
            // Período fino: pico de la autocorrelación promedio del tramo (interpolación parabólica).
            double[] mean = new double[win];
            for (int c = a; c < b; c++) {
                for (int l = 0; l < win; l++) {
                    mean[l] += combed[c][l];
                }
            }
            for (int l = 0; l < win; l++) {
                mean[l] /= (b - a);
            }
            int guess = (int) Math.rint(median(lagPath, a, b));
            int lo = Math.max(2, guess - 1), hi = Math.min(win - 2, guess + 1);
            int peak = lo;
            for (int l = lo + 1; l <= hi; l++) {
                if (mean[l] > mean[peak]) {
                    peak = l;
                }
            }
            double y0 = mean[peak - 1], y1 = mean[peak], y2 = mean[peak + 1];
            double den = y0 - 2 * y1 + y2;
            double lag = peak + (den < 0 ? 0.5 * (y0 - y2) / den : 0.0);
            int f0 = s == 0 ? 0 : (int) Math.rint((centerList.get(a - 1) + centerList.get(a)) / 2.0);
            int f1 = s == runs.size() - 1 ? n : (int) Math.rint((centerList.get(b - 1) + centerList.get(b)) / 2.0);
            segments.add(new TempoSegment(f0, f1, lag));
        }
        return segments;
    }

    /** Número de tramo de tempo de un cuadro. */
    static int segmentOf(int frame, List<TempoSegment> segments) {
        int s = 0;
        while (s + 1 < segments.size() && frame >= segments.get(s + 1).start) {
            s++;
        }
        return s;
    }

    /** Un tramo de tempo de la canción ya con sus pulsos. */
    public static final class Part {
        public double start;
        public double end;
        public double bpm;
        public int beatsPerBar;
        public boolean steady;
        double[] times;
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
        r.onset = env;
        // El tempo se mide con el bombo y el redoblante (los platillos suelen marcar subdivisiones).
        double[] tempoEnv = env;
        if (in.drumsOk) {
            double[] k = unit(in.drumsKick), sn = unit(in.drumsSnare);
            tempoEnv = new double[env.length];
            for (int i = 0; i < env.length; i++) {
                tempoEnv[i] = (i < k.length ? k[i] : 0) + (i < sn.length ? sn[i] : 0) + 0.5 * env[i];
            }
        }
        List<TempoSegment> segments = tempoSegments(tempoEnv, 120);
        double fps = SR / (double) HOP;
        double[] bpmCurve = new double[env.length];
        for (TempoSegment seg : segments) {
            java.util.Arrays.fill(bpmCurve, seg.start, Math.min(seg.end, env.length), 60.0 * fps / seg.lag);
        }
        double tempo = 60.0 * fps / segments.get(0).lag;
        int[] beatFrames = Beats.track(env, SR, HOP, bpmCurve, 120);

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

        // ¿El detector marcó el doble o la mitad del tempo? Se mira en cada tramo (en un popurrí
        // puede pasar en uno solo).
        if (in.drumsOk) {
            List<Integer> kept = new ArrayList<>();
            for (int s = 0; s < segments.size(); s++) {
                List<Integer> frames = new ArrayList<>();
                for (int f : beatFrames) {
                    if (segmentOf(f, segments) == s) {
                        frames.add(f);
                    }
                }
                int[] fr = toInts(frames);
                if (fr.length >= 16) {
                    double[] d = new double[fr.length - 1];
                    for (int i = 0; i + 1 < fr.length; i++) {
                        d[i] = fr[i + 1] - fr[i];
                    }
                    double periodS = Dsp.median(d) * HOP / SR;
                    if (2 * 60.0 / periodS <= 180 && countedHalf(fr, in.drumsKick, in.drumsSnare)) {
                        // Contó la mitad: se agrega el pulso del medio.
                        int[] doubled = new int[2 * fr.length - 1];
                        for (int i = 0; i < fr.length; i++) {
                            doubled[2 * i] = fr[i];
                            if (i + 1 < fr.length) {
                                doubled[2 * i + 1] = Math.floorDiv(fr[i] + fr[i + 1], 2);
                            }
                        }
                        fr = doubled;
                    } else if (60.0 / (2 * periodS) >= 50) { // no bajar de 50 BPM
                        Integer phase = halfTimePhase(fr, in.drumsKick, in.drumsSnare);
                        if (phase != null) {
                            List<Integer> halfList = new ArrayList<>();
                            for (int i = phase; i < fr.length; i += 2) {
                                halfList.add(fr[i]);
                            }
                            fr = toInts(halfList);
                        }
                    }
                }
                for (int f : fr) {
                    kept.add(f);
                }
            }
            beatFrames = toInts(kept);
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

        // Cada tramo con tempo constante (grabado con click) pasa a una grilla perfecta.
        List<Part> parts = new ArrayList<>();
        for (int s = 0; s < segments.size(); s++) {
            List<Double> list = new ArrayList<>();
            for (int i = 0; i < beatFrames.length; i++) {
                if (segmentOf(beatFrames[i], segments) == s) {
                    list.add(beatTimes[i]);
                }
            }
            if (list.size() < 2) {
                continue;
            }
            double[] times = new double[list.size()];
            for (int i = 0; i < times.length; i++) {
                times[i] = list.get(i);
            }
            Grid grid = fitSteadyGrid(times);
            boolean steady = grid != null && grid.steady;
            double bpm;
            if (steady) {
                times = new double[(int) (grid.k1 - grid.k0 + 1)];
                for (long k = grid.k0; k <= grid.k1; k++) {
                    times[(int) (k - grid.k0)] = grid.offset + grid.period * k;
                }
                bpm = 60.0 / grid.period;
            } else {
                bpm = 60.0 / Dsp.median(diff(times));
            }
            if (!parts.isEmpty() && Math.abs(bpm / parts.get(parts.size() - 1).bpm - 1) < 0.03) {
                // Tramo vecino con el mismo tempo: es el mismo.
                Part prev = parts.get(parts.size() - 1);
                double weight = prev.times.length / (double) (prev.times.length + times.length);
                double[] joined = java.util.Arrays.copyOf(prev.times, prev.times.length + times.length);
                System.arraycopy(times, 0, joined, prev.times.length, times.length);
                prev.times = joined;
                prev.steady = prev.steady && steady;
                prev.bpm = weight * prev.bpm + (1 - weight) * bpm;
                continue;
            }
            Part part = new Part();
            part.times = times;
            part.steady = steady;
            part.bpm = bpm;
            parts.add(part);
        }
        // Sin pulsos repetidos donde se tocan dos tramos, ni fuera de la canción.
        List<Part> clean = new ArrayList<>();
        double prevLast = Double.NEGATIVE_INFINITY;
        for (Part p : parts) {
            double lim = clean.isEmpty() ? Double.NEGATIVE_INFINITY : prevLast + 0.4 * 60.0 / p.bpm;
            List<Double> kept = new ArrayList<>();
            for (double t : p.times) {
                if (t >= lim && t >= 0 && t < in.duration) {
                    kept.add(t);
                }
            }
            if (kept.size() < 2) {
                continue;
            }
            p.times = new double[kept.size()];
            for (int i = 0; i < p.times.length; i++) {
                p.times[i] = kept.get(i);
            }
            prevLast = p.times[p.times.length - 1];
            clean.add(p);
        }
        parts = clean;
        int total = 0;
        for (Part p : parts) {
            total += p.times.length;
        }
        beatTimes = new double[total];
        int pos = 0;
        for (Part p : parts) {
            System.arraycopy(p.times, 0, beatTimes, pos, p.times.length);
            pos += p.times.length;
        }
        beatFrames = new int[total];
        for (int i = 0; i < total; i++) {
            beatFrames[i] = (int) Math.rint(beatTimes[i] * SR / HOP);
        }
        if (total < 4) {
            r.bpm = tempo != 0 ? Dsp.round(tempo, 1) : null;
            return r;
        }

        // Compás y "1" de cada tramo (en un popurrí cada canción tiene los suyos).
        double[] lowEnv = Dsp.toDouble(in.drumsOk ? in.drumsKick : in.mixLow);
        if (in.bassOnset != null) {
            double[] a = unit(lowEnv);
            double[] b = unit(in.bassOnset);
            lowEnv = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                lowEnv[i] = a[i] + 0.6 * (i < b.length ? b[i] : 0);
            }
        }
        double[] meterAll = meterAndDownbeats(beatFrames, lowEnv, trebleChroma, bassChroma);
        List<Double> downs = new ArrayList<>();
        java.util.Map<Integer, Double> weights = new java.util.LinkedHashMap<>();
        double confidenceSum = 0;
        pos = 0;
        for (int k = 0; k < parts.size(); k++) {
            Part p = parts.get(k);
            int count = p.times.length;
            int[] frames = java.util.Arrays.copyOfRange(beatFrames, pos, pos + count);
            pos += count;
            double[] meter;
            if (parts.size() == 1) {
                meter = meterAll;
            } else if (count >= 16) {
                meter = meterAndDownbeats(frames, lowEnv, trebleChroma, bassChroma);
            } else {
                meter = meterAndDownbeats(frames, lowEnv, trebleChroma, bassChroma, new int[] {(int) meterAll[0]});
            }
            int perBar = (int) meter[0];
            int phase = (int) meter[1];
            for (int i = phase; i < count; i += perBar) {
                downs.add(p.times[i]);
            }
            p.beatsPerBar = perBar;
            p.start = k == 0 ? 0.0 : Dsp.round(p.times[0], 3);
            p.end = Dsp.round(in.duration, 3);
            if (k > 0) {
                parts.get(k - 1).end = p.start;
            }
            double span = count > 1 ? p.times[count - 1] - p.times[0] : 0.0;
            weights.merge(perBar, span, Double::sum);
            confidenceSum += meter[2] * span;
        }
        double spanTotal = 0;
        int beatsPerBar = (int) meterAll[0];
        double bestSpan = -1;
        for (java.util.Map.Entry<Integer, Double> e : weights.entrySet()) {
            spanTotal += e.getValue();
            if (e.getValue() > bestSpan) {
                bestSpan = e.getValue();
                beatsPerBar = e.getKey();
            }
        }
        Part main = parts.get(0);
        boolean allSteady = true;
        for (Part p : parts) {
            if (p.end - p.start > main.end - main.start) {
                main = p;
            }
            allSteady &= p.steady;
            p.bpm = Dsp.round(p.bpm, 1);
        }
        r.bpm = main.bpm;
        r.beats = new double[beatTimes.length];
        for (int i = 0; i < beatTimes.length; i++) {
            r.beats[i] = Dsp.round(beatTimes[i], 3);
        }
        r.downbeats = new double[downs.size()];
        for (int i = 0; i < r.downbeats.length; i++) {
            r.downbeats[i] = Dsp.round(downs.get(i), 3);
        }
        r.downbeatPhase = (int) meterAll[1];
        r.beatsPerBar = beatsPerBar;
        r.steady = allSteady;
        r.confidence = Dsp.round(pulseConfidence, 2);
        r.meterConfidence = Dsp.round(confidenceSum / (spanTotal > 0 ? spanTotal : 1.0), 2);
        r.parts = parts;
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
