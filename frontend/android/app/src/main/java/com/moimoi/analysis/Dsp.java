package com.moimoi.analysis;

import java.util.Arrays;

/** Funciones numéricas básicas (como las de numpy/scipy que usa el análisis de la computadora). */
public final class Dsp {

    private Dsp() {}

    public static final int SR = 22050;
    public static final int HOP = 512;

    // ---- estadística ------------------------------------------------------------------------

    public static double mean(double[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    public static double mean(float[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (float x : v) {
            s += x;
        }
        return s / v.length;
    }

    /** Desvío estándar (ddof = 0, como numpy.std). */
    public static double std(double[] v) {
        return std(v, 0);
    }

    public static double std(double[] v, int ddof) {
        if (v.length - ddof <= 0) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - ddof));
    }

    /** Mediana (numpy.median: promedio de los dos del medio si la cantidad es par). */
    public static double median(double[] v) {
        return median(v, 0, v.length);
    }

    public static double median(double[] v, int from, int to) {
        int n = to - from;
        if (n <= 0) {
            return Double.NaN;
        }
        double[] c = Arrays.copyOfRange(v, from, to);
        Arrays.sort(c);
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    public static double median(float[] v) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i];
        }
        return median(d);
    }

    /** Percentil con interpolación lineal (numpy.percentile por defecto), q en 0-100. */
    public static double percentile(double[] v, double q) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double[] c = v.clone();
        Arrays.sort(c);
        double pos = q / 100.0 * (c.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(c.length - 1, lo + 1);
        double frac = pos - lo;
        return c[lo] + (c[hi] - c[lo]) * frac;
    }

    public static int argmax(double[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    public static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            m = Math.max(m, x);
        }
        return m;
    }

    public static double[] toDouble(float[] v) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i];
        }
        return d;
    }

    /** (v - media) / desvío; ceros si no varía (features.normalize). */
    public static double[] normalize(double[] v) {
        double m = mean(v);
        double s = std(v);
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = s > 1e-9 ? (v[i] - m) / s : 0.0;
        }
        return out;
    }

    /** round(x, n) de Python: el decimal más cercano al valor exacto (empate al par). */
    public static double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return new java.math.BigDecimal(v).setScale(digits, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    // ---- ventanas y filtros ----------------------------------------------------------------------

    /** Ventana de Hann; periodic = true como scipy.signal.get_window(..., fftbins=True). */
    public static float[] hann(int n, boolean periodic) {
        float[] w = new float[n];
        int d = periodic ? n : n - 1;
        for (int i = 0; i < n; i++) {
            w[i] = d <= 0 ? 1f : (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / d));
        }
        return w;
    }

    public static double besselI0(double x) {
        double sum = 1, term = 1;
        for (int k = 1; k < 200; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < 1e-17 * sum) {
                break;
            }
        }
        return sum;
    }

    /** Ventana de Kaiser simétrica (scipy.signal.windows.kaiser(n, beta)). */
    public static double[] kaiser(int n, double beta) {
        double[] w = new double[n];
        double i0 = besselI0(beta);
        for (int i = 0; i < n; i++) {
            double r = n == 1 ? 0 : 2.0 * i / (n - 1) - 1.0;
            w[i] = besselI0(beta * Math.sqrt(Math.max(0, 1 - r * r))) / i0;
        }
        return w;
    }

    /** Pasabajos FIR (scipy.signal.firwin(taps, cutoff, window=('kaiser', beta))); cutoff relativo a Nyquist. */
    public static double[] firwin(int taps, double cutoff, double beta) {
        double[] h = new double[taps];
        double[] w = kaiser(taps, beta);
        double alpha = 0.5 * (taps - 1);
        double sum = 0;
        for (int i = 0; i < taps; i++) {
            double m = i - alpha;
            double x = cutoff * m;
            double sinc = Math.abs(x) < 1e-12 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
            h[i] = cutoff * sinc * w[i];
            sum += h[i];
        }
        for (int i = 0; i < taps; i++) {
            h[i] /= sum;
        }
        return h;
    }

    private static double[] halfFilter;

    private static synchronized double[] halfFilter() {
        if (halfFilter == null) {
            halfFilter = firwin(41, 0.5, 5.0);
        }
        return halfFilter;
    }

    /**
     * De 44,1 a 22,05 kHz exactamente como scipy.signal.resample_poly(x, 1, 2) (FIR de Kaiser de
     * 41 coeficientes, sin desfase).
     */
    public static float[] resampleHalf(float[] x) {
        double[] h = halfFilter();
        int n = x.length;
        int nOut = (n + 1) / 2;
        float[] y = new float[nOut];
        for (int m = 0; m < nOut; m++) {
            double acc = 0;
            int base = 2 * m - 20;
            for (int i = 0; i < 41; i++) {
                int j = base + i;
                if (j >= 0 && j < n) {
                    acc += h[i] * x[j];
                }
            }
            y[m] = (float) acc;
        }
        return y;
    }

    /** Muestras mono de [start, start + count), con ceros fuera de la señal. */
    public interface MonoInput {
        void read(long start, float[] dst, int count);
    }

    /**
     * resampleHalf leyendo la señal de a bloques (sin tenerla entera a 44,1 kHz en memoria);
     * maxOut limita cuántas muestras se calculan (< 0: todas).
     */
    public static float[] resampleHalf(MonoInput in, long frames, int maxOut) {
        double[] h = halfFilter();
        long total = (frames + 1) / 2;
        int nOut = (int) (maxOut >= 0 ? Math.min(total, maxOut) : total);
        float[] y = new float[nOut];
        int block = 1 << 14;
        float[] buf = new float[2 * block + 41];
        for (int m0 = 0; m0 < nOut; m0 += block) {
            int count = Math.min(block, nOut - m0);
            in.read(2L * m0 - 20, buf, 2 * (count - 1) + 41);
            for (int m = 0; m < count; m++) {
                double acc = 0;
                int base = 2 * m;
                for (int i = 0; i < 41; i++) {
                    acc += h[i] * buf[base + i];
                }
                y[m0 + m] = (float) acc;
            }
        }
        return y;
    }

    // ---- nivel ----------------------------------------------------------------------------------

    /** RMS por ventana en dBFS con piso de -100 dB (features.rms_db). */
    public static double[] rmsDb(float[] x, int frame, int hop) {
        float[] v = x;
        if (v.length < frame) {
            v = Arrays.copyOf(v, frame);
        }
        int n = 1 + (v.length - frame) / hop;
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            double s = 0;
            int start = k * hop;
            for (int i = 0; i < frame; i++) {
                double a = v[start + i];
                s += a * a;
            }
            double rms = Math.sqrt(s / frame);
            out[k] = 20 * Math.log10(Math.max(rms, 1e-5));
        }
        return out;
    }
}
