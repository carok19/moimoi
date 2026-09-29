package com.moimoi.analysis;

import com.moimoi.engine.Fft;

/**
 * Espectrogramas como los de librosa (stft centrada con ceros y ventana de Hann, bandas mel de
 * Slaney, power_to_db), y con eso la fuerza de ataques (onset_strength) y los MFCC.
 */
public final class Spectrum {

    private Spectrum() {}

    /** Cantidad de cuadros de una stft centrada: 1 + len // hop. */
    public static int frames(int length, int hop) {
        return 1 + length / hop;
    }

    /**
     * Recorre los cuadros de la stft (centrada, relleno con ceros) y entrega la potencia |X|² de
     * cada uno (nFft/2 + 1 valores).
     */
    public interface FrameSink {
        void frame(int t, float[] power);
    }

    public static void power(float[] y, int nFft, int hop, float[] window, FrameSink sink) {
        Fft fft = new Fft(nFft);
        int bins = nFft / 2 + 1;
        float[] frame = new float[nFft];
        float[] re = new float[bins];
        float[] im = new float[bins];
        float[] pow = new float[bins];
        int n = frames(y.length, hop);
        int pad = nFft / 2;
        for (int t = 0; t < n; t++) {
            int start = t * hop - pad;
            for (int i = 0; i < nFft; i++) {
                int j = start + i;
                frame[i] = j >= 0 && j < y.length ? y[j] * window[i] : 0f;
            }
            fft.forward(frame, 0, re, im);
            for (int k = 0; k < bins; k++) {
                pow[k] = re[k] * re[k] + im[k] * im[k];
            }
            sink.frame(t, pow);
        }
    }

    // ---- bandas mel ------------------------------------------------------------------------------

    static double hzToMel(double f) {
        double fSp = 200.0 / 3;
        double mel = f / fSp;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        if (f >= minLogHz) {
            mel = minLogMel + Math.log(f / minLogHz) / logstep;
        }
        return mel;
    }

    static double melToHz(double m) {
        double fSp = 200.0 / 3;
        double f = fSp * m;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        if (m >= minLogMel) {
            f = minLogHz * Math.exp(logstep * (m - minLogMel));
        }
        return f;
    }

    /** Banco de filtros mel disperso: para cada banda, el primer bin y sus pesos. */
    public static final class Mel {
        final int[] start;
        final float[][] weights;
        public final int bands;

        Mel(int[] start, float[][] weights) {
            this.start = start;
            this.weights = weights;
            this.bands = start.length;
        }

        void apply(float[] power, float[] out) {
            for (int m = 0; m < bands; m++) {
                float[] w = weights[m];
                int s = start[m];
                float acc = 0f;
                for (int k = 0; k < w.length; k++) {
                    acc += w[k] * power[s + k];
                }
                out[m] = acc;
            }
        }
    }

    /** librosa.filters.mel(sr, n_fft, n_mels, fmin, fmax) con norm="slaney" (htk = False). */
    public static Mel mel(int sr, int nFft, int nMels, double fmin, double fmax) {
        int bins = nFft / 2 + 1;
        double[] fftFreqs = new double[bins];
        for (int k = 0; k < bins; k++) {
            fftFreqs[k] = k * (double) sr / nFft;
        }
        double minMel = hzToMel(fmin);
        double maxMel = hzToMel(fmax);
        double[] melF = new double[nMels + 2];
        for (int i = 0; i < nMels + 2; i++) {
            double m = minMel + (maxMel - minMel) * i / (nMels + 1);
            melF[i] = melToHz(m);
        }
        int[] start = new int[nMels];
        float[][] weights = new float[nMels][];
        for (int i = 0; i < nMels; i++) {
            double fd0 = melF[i + 1] - melF[i];
            double fd1 = melF[i + 2] - melF[i + 1];
            double enorm = 2.0 / (melF[i + 2] - melF[i]);
            float[] row = new float[bins];
            int first = -1, last = -1;
            for (int k = 0; k < bins; k++) {
                double lower = -(melF[i] - fftFreqs[k]) / fd0;
                double upper = (melF[i + 2] - fftFreqs[k]) / fd1;
                double w = Math.max(0, Math.min(lower, upper));
                row[k] = (float) ((double) (float) w * enorm); // como numpy: float32 *= float64
                if (row[k] != 0f) {
                    if (first < 0) {
                        first = k;
                    }
                    last = k;
                }
            }
            if (first < 0) {
                first = 0;
                last = -1;
            }
            start[i] = first;
            weights[i] = java.util.Arrays.copyOfRange(row, first, last + 1);
        }
        return new Mel(start, weights);
    }

    /** Espectrograma mel de potencia: [cuadro][banda]. */
    public static float[][] melPower(float[] y, int sr, int nFft, int hop, int nMels, double fmin, double fmax) {
        Mel mel = mel(sr, nFft, nMels, fmin, fmax);
        float[][] out = new float[frames(y.length, hop)][nMels];
        power(y, nFft, hop, Dsp.hann(nFft, true), (t, p) -> mel.apply(p, out[t]));
        return out;
    }

    /** power_to_db(S, ref=1, amin=1e-10, top_db=80), en el lugar. */
    public static void powerToDb(float[][] s) {
        float max = Float.NEGATIVE_INFINITY;
        for (float[] row : s) {
            for (int i = 0; i < row.length; i++) {
                float v = (float) (10.0 * Math.log10(Math.max(1e-10, row[i])));
                row[i] = v;
                if (v > max) {
                    max = v;
                }
            }
        }
        float floor = max - 80f;
        for (float[] row : s) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] < floor) {
                    row[i] = floor;
                }
            }
        }
    }

    /**
     * librosa.onset.onset_strength(y, sr, hop_length, aggregate=np.median, n_mels, fmin, fmax, n_fft):
     * mediana entre bandas de la subida (en dB) de un cuadro al siguiente.
     */
    public static float[] onsetStrength(float[] y, int sr, int hop, int nFft, int nMels, double fmin, double fmax) {
        float[][] s = melPower(y, sr, nFft, hop, nMels, fmin, fmax);
        powerToDb(s);
        int frames = s.length;
        float[] env = new float[frames];
        int pad = 1 + nFft / (2 * hop);
        double[] diff = new double[nMels];
        for (int t = pad; t < frames; t++) {
            int j = t - pad; // diferencia entre el cuadro j+1 y el j
            if (j + 1 >= frames) {
                break;
            }
            for (int m = 0; m < nMels; m++) {
                diff[m] = Math.max(0f, s[j + 1][m] - s[j][m]);
            }
            env[t] = (float) Dsp.median(diff);
        }
        return env;
    }

    /** librosa.feature.mfcc(y, sr, n_mfcc, hop_length): [cuadro][coeficiente]. */
    public static float[][] mfcc(float[] y, int sr, int hop, int nMfcc) {
        int nMels = 128;
        float[][] s = melPower(y, sr, 2048, hop, nMels, 0.0, sr / 2.0);
        powerToDb(s);
        double[][] basis = new double[nMfcc][nMels];
        for (int k = 0; k < nMfcc; k++) {
            double scale = k == 0 ? Math.sqrt(1.0 / nMels) : Math.sqrt(2.0 / nMels);
            for (int n = 0; n < nMels; n++) {
                basis[k][n] = scale * Math.cos(Math.PI * k * (2 * n + 1) / (2.0 * nMels));
            }
        }
        float[][] out = new float[s.length][nMfcc];
        for (int t = 0; t < s.length; t++) {
            for (int k = 0; k < nMfcc; k++) {
                double acc = 0;
                for (int n = 0; n < nMels; n++) {
                    acc += basis[k][n] * s[t][n];
                }
                out[t][k] = (float) acc;
            }
        }
        return out;
    }
}
