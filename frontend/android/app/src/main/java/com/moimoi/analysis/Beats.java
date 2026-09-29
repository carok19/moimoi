package com.moimoi.analysis;

import com.moimoi.engine.Fft;
import java.util.ArrayList;
import java.util.List;

/**
 * Tempo y pulsos como librosa: librosa.feature.tempo (tempograma de autocorrelación con una
 * preferencia log-normal alrededor de 120 BPM) y librosa.beat.beat_track (programación dinámica
 * de Ellis).
 */
public final class Beats {

    private Beats() {}

    /** librosa.feature.tempo(onset_envelope, sr, hop, start_bpm, max_tempo) con ac_size = 8 s. */
    public static double tempo(float[] env, int sr, int hop, double startBpm, double maxTempo) {
        return tempo(Dsp.toDouble(env), sr, hop, startBpm, maxTempo);
    }

    public static double tempo(double[] env, int sr, int hop, double startBpm, double maxTempo) {
        int win = (int) Math.floor(8.0 * sr / hop); // time_to_frames(8.0)
        int n = env.length;
        int half = win / 2;
        // Relleno "linear_ramp" hasta 0 en los extremos.
        double[] padded = new double[n + 2 * half];
        for (int i = 0; i < half; i++) {
            padded[i] = env.length > 0 ? env[0] * (double) i / half : 0;
            padded[n + 2 * half - 1 - i] = env.length > 0 ? env[n - 1] * (double) i / half : 0;
        }
        System.arraycopy(env, 0, padded, half, n);
        float[] window = Dsp.hann(win, true);
        int nFft = Integer.highestOneBit(2 * win - 1) << 1;
        Fft fft = new Fft(nFft);
        float[] frame = new float[nFft];
        float[] re = new float[nFft / 2 + 1];
        float[] im = new float[nFft / 2 + 1];
        float[] ac = new float[nFft];
        double[] mean = new double[win];
        for (int t = 0; t < n; t++) {
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
            // normalize(norm=inf): dividido por el máximo absoluto de la autocorrelación.
            float peak = 0f;
            for (int l = 0; l < win; l++) {
                peak = Math.max(peak, Math.abs(ac[l]));
            }
            if (peak > 0) {
                for (int l = 0; l < win; l++) {
                    mean[l] += ac[l] / peak;
                }
            }
        }
        double best = Double.NEGATIVE_INFINITY;
        double tempo = 0;
        for (int l = 0; l < win; l++) {
            double tg = mean[l] / n;
            double bpm = l == 0 ? Double.POSITIVE_INFINITY : 60.0 * sr / (hop * (double) l);
            if (!(bpm < maxTempo)) {
                continue; // max_tempo: se descartan los períodos más cortos
            }
            double prior = -0.5 * Math.pow((Math.log(bpm) / Math.log(2) - Math.log(startBpm) / Math.log(2)), 2);
            double score = Math.log1p(1e6 * tg) + prior;
            if (score > best) {
                best = score;
                tempo = bpm;
            }
        }
        return tempo;
    }

    /** librosa.beat.beat_track(onset_envelope, bpm, tightness, trim=False): cuadros de los pulsos. */
    public static int[] track(float[] env, int sr, int hop, double bpm, double tightness) {
        return track(Dsp.toDouble(env), sr, hop, bpm, tightness);
    }

    public static int[] track(double[] env, int sr, int hop, double bpm, double tightness) {
        return track(env, sr, hop, new double[] {bpm}, tightness);
    }

    /**
     * Con tempo variable (bpm.length == env.length, un tempo por cuadro), como beat_track de
     * librosa con bpm = arreglo: la ventana del puntaje local y la búsqueda del pulso anterior usan
     * el tempo de cada cuadro. Con un solo valor es el tempo fijo de siempre.
     */
    public static int[] track(double[] env, int sr, int hop, double[] bpm, double tightness) {
        int n = env.length;
        boolean any = false;
        for (double v : env) {
            if (v != 0f) {
                any = true;
                break;
            }
        }
        if (!any || n == 0) {
            return new int[0];
        }
        double frameRate = sr / (double) hop;
        boolean varying = bpm.length == n && n > 1;
        double[] fpbs = new double[varying ? n : 1];
        for (int i = 0; i < fpbs.length; i++) {
            fpbs[i] = Math.rint(frameRate * 60.0 / bpm[i]); // np.round: al par más cercano
        }
        // Normalizar (desvío con ddof = 1).
        double m = 0;
        for (double v : env) {
            m += v;
        }
        m /= n;
        double sq = 0;
        for (double v : env) {
            sq += (v - m) * (v - m);
        }
        double norm = Math.sqrt(sq / Math.max(1, n - 1)) + Double.MIN_NORMAL;
        double[] onsets = new double[n];
        for (int i = 0; i < n; i++) {
            onsets[i] = env[i] / norm;
        }
        // Puntaje local: convolución con una gaussiana del ancho de un pulso (con tempo variable,
        // la del tempo de cada cuadro).
        java.util.Map<Integer, double[]> windows = new java.util.HashMap<>();
        double[] local = new double[n];
        for (int i = 0; i < n; i++) {
            double fpb = fpbs[varying ? i : 0];
            double[] window = windows.get((int) fpb);
            if (window == null) {
                int kLen = (int) (2 * fpb + 1);
                window = new double[kLen];
                for (int k = 0; k < kLen; k++) {
                    double x = (k - fpb) * 32.0 / fpb;
                    window[k] = Math.exp(-0.5 * x * x);
                }
                windows.put((int) fpb, window);
            }
            int kLen = window.length;
            int halfK = kLen / 2;
            double acc = 0;
            int kFrom = Math.max(0, i + halfK - n + 1);
            int kTo = Math.min(i + halfK, kLen);
            for (int k = kFrom; k < kTo; k++) {
                acc += window[k] * onsets[i + halfK - k];
            }
            local[i] = acc;
        }
        // Programación dinámica.
        int[] backlink = new int[n];
        double[] cum = new double[n];
        double maxLocal = Double.NEGATIVE_INFINITY;
        for (double v : local) {
            maxLocal = Math.max(maxLocal, v);
        }
        double thresh = 0.01 * maxLocal;
        boolean first = true;
        backlink[0] = -1;
        cum[0] = local[0];
        for (int i = 0; i < n; i++) {
            double fpb = fpbs[varying ? i : 0];
            int from = (int) Math.rint(fpb / 2);
            double logFpb = Math.log(fpb);
            double bestScore = Double.NEGATIVE_INFINITY;
            int loc = -1;
            int stop = (int) (i - 2 * fpb - 1);
            for (int j = i - from; j > stop; j--) {
                if (j < 0) {
                    break;
                }
                double d = Math.log(i - j) - logFpb;
                double score = cum[j] - tightness * d * d;
                if (score > bestScore) {
                    bestScore = score;
                    loc = j;
                }
            }
            cum[i] = loc >= 0 ? local[i] + bestScore : local[i];
            if (first && local[i] < thresh) {
                backlink[i] = -1;
            } else {
                backlink[i] = loc;
                first = false;
            }
        }
        // Último pulso: el último máximo local con puntaje >= la mitad de la mediana de los máximos.
        boolean[] isMax = new boolean[n];
        List<Double> maxima = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            boolean lm = i < n - 1 ? cum[i] > cum[i - 1] && cum[i] >= cum[i + 1] : cum[i] > cum[i - 1];
            isMax[i] = lm;
            if (lm) {
                maxima.add(cum[i]);
            }
        }
        double threshold;
        if (maxima.isEmpty()) {
            threshold = Double.NaN;
        } else {
            double[] mx = new double[maxima.size()];
            for (int i = 0; i < mx.length; i++) {
                mx[i] = maxima.get(i);
            }
            threshold = 0.5 * Dsp.median(mx);
        }
        int tail = n - 1;
        for (int i = n - 1; i >= 0; i--) {
            if (isMax[i] && cum[i] >= threshold) {
                tail = i;
                break;
            }
        }
        boolean[] beats = new boolean[n];
        for (int i = tail; i >= 0; i = backlink[i]) {
            beats[i] = true;
        }
        // trim=False: solo se sacan los pulsos en el silencio del principio y del final.
        for (int i = 0; i < n && local[i] <= 0.0; i++) {
            beats[i] = false;
        }
        for (int i = n - 1; i >= 0 && local[i] <= 0.0; i--) {
            beats[i] = false;
        }
        int count = 0;
        for (boolean b : beats) {
            if (b) {
                count++;
            }
        }
        int[] out = new int[count];
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (beats[i]) {
                out[c++] = i;
            }
        }
        return out;
    }

    /**
     * librosa.util.sync(data, frames, aggregate=np.median, pad=True) sobre el eje del tiempo:
     * mediana de cada tramo entre bordes (0 y el total se agregan). data es [fila][cuadro].
     */
    public static double[][] syncMedian(double[][] data, int[] frames, int total) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        set.add(0);
        set.add(total);
        for (int f : frames) {
            set.add(Math.max(0, Math.min(total, f)));
        }
        Integer[] bounds = set.toArray(new Integer[0]);
        int segs = bounds.length - 1;
        double[][] out = new double[data.length][segs];
        for (int r = 0; r < data.length; r++) {
            for (int s = 0; s < segs; s++) {
                out[r][s] = Dsp.median(data[r], bounds[s], bounds[s + 1]);
            }
        }
        return out;
    }
}
