package com.moimoi.analysis;

import com.moimoi.engine.Fft;

/**
 * Transformada Q constante como librosa.cqt (filtros de Hann por octava, submuestreo de a una
 * octava, núcleos dispersos) y el cromagrama de features.chroma: log1p(100·|CQT|) sumado por clase
 * de nota (12 × cuadros).
 */
public final class Cqt {

    private Cqt() {}

    static final double HANN_BANDWIDTH = 1.50018310546875;

    /** Núcleo disperso de un filtro: índices de bins y valores complejos. */
    private static final class Kernel {
        int[] bins;
        float[] re;
        float[] im;
    }

    // ---- FFT compleja (para armar los núcleos) --------------------------------------------------

    private static void fftComplex(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < len / 2; k++) {
                    double wr = Math.cos(ang * k), wi = Math.sin(ang * k);
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    /** Filtros de una octava a la frecuencia de muestreo sr (filters.wavelet + __vqt_filter_fft). */
    private static Kernel[] kernels(double[] freqs, double sr, double q, double scale, int[] nFftOut) {
        int n = freqs.length;
        double[] lengths = new double[n];
        double maxLen = 0;
        for (int i = 0; i < n; i++) {
            lengths[i] = q * sr / freqs[i];
            maxLen = Math.max(maxLen, lengths[i]);
        }
        int nFft = 1 << (int) Math.ceil(Math.log(maxLen) / Math.log(2) - 1e-12);
        nFftOut[0] = nFft;
        Kernel[] out = new Kernel[n];
        for (int f = 0; f < n; f++) {
            double ilen = lengths[f];
            int lo = (int) Math.floor(-ilen / 2);
            int hi = (int) Math.floor(ilen / 2);
            int len = hi - lo;
            double[] w = new double[len]; // get_window('hann', len) en float64
            double sumW = 0;
            for (int i = 0; i < len; i++) {
                w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / len);
                sumW += w[i];
            }
            double[] re = new double[nFft];
            double[] im = new double[nFft];
            int lpad = (nFft - len) / 2;
            double gain = ilen / nFft / sumW;
            for (int i = 0; i < len; i++) {
                double phase = (lo + i) * 2 * Math.PI * freqs[f] / sr;
                // complex64, como librosa
                float wr = (float) (Math.cos(phase) * w[i] / sumW);
                float wi = (float) (Math.sin(phase) * w[i] / sumW);
                re[lpad + i] = (float) (wr * (ilen / nFft));
                im[lpad + i] = (float) (wi * (ilen / nFft));
            }
            fftComplex(re, im);
            int bins = nFft / 2 + 1;
            // sparsify_rows(quantile = 0.01): se descartan los valores más chicos que suman < 1 %.
            double[] mags = new double[bins];
            double total = 0;
            for (int k = 0; k < bins; k++) {
                mags[k] = Math.hypot((float) re[k], (float) im[k]);
                total += mags[k];
            }
            double[] sorted = mags.clone();
            java.util.Arrays.sort(sorted);
            double cum = 0;
            double threshold = sorted[bins - 1];
            for (int k = 0; k < bins; k++) {
                cum += sorted[k] / total;
                if (!(cum < 0.01)) {
                    threshold = sorted[k];
                    break;
                }
            }
            int count = 0;
            for (int k = 0; k < bins; k++) {
                if (mags[k] >= threshold) {
                    count++;
                }
            }
            Kernel kernel = new Kernel();
            kernel.bins = new int[count];
            kernel.re = new float[count];
            kernel.im = new float[count];
            int c = 0;
            for (int k = 0; k < bins; k++) {
                if (mags[k] >= threshold) {
                    kernel.bins[c] = k;
                    kernel.re[c] = (float) ((float) re[k] * scale);
                    kernel.im[c] = (float) ((float) im[k] * scale);
                    c++;
                }
            }
            out[f] = kernel;
        }
        return out;
    }

    private static double[] decimateFilter;

    /** De a una octava (res_type soxr_hq de librosa): FIR de Kaiser sin desfase, ×√2 (scale=True). */
    static float[] halve(final float[] x) {
        double[] h;
        synchronized (Cqt.class) {
            if (decimateFilter == null) {
                decimateFilter = Dsp.firwin(257, 0.475, 10.0);
            }
            h = decimateFilter;
        }
        final int half = h.length / 2;
        final int nOut = (x.length + 1) / 2;
        final float[] y = new float[nOut];
        final double s2 = Math.sqrt(2.0);
        final double[] taps = h;
        Parallel.range(nOut, 1 << 15, (lo, hi) -> {
            for (int m = lo; m < hi; m++) {
                double acc = 0;
                int base = 2 * m - half;
                int from = Math.max(0, -base);
                int to = Math.min(taps.length, x.length - base);
                for (int i = from; i < to; i++) {
                    acc += taps[i] * x[base + i];
                }
                y[m] = (float) (acc * s2);
            }
        });
        return y;
    }

    /**
     * |librosa.cqt(y, sr, hop, fmin, n_bins, bins_per_octave, tuning)|: [bin][cuadro] (magnitud).
     */
    public static float[][] magnitude(float[] y, int srIn, int hopIn, double fmin, int nBins, int bpo, double tuning) {
        int nOctaves = (int) Math.ceil(nBins / (double) bpo);
        int nFilters = Math.min(bpo, nBins);
        double fminT = fmin * Math.pow(2.0, tuning / bpo);
        double[] freqs = new double[nBins];
        for (int k = 0; k < nBins; k++) {
            freqs[k] = fminT * Math.pow(2.0, k / (double) bpo);
        }
        double alpha = (Math.pow(2.0, 2.0 / bpo) - 1) / (Math.pow(2.0, 2.0 / bpo) + 1);
        double q = 1.0 / alpha;
        double cutoff = freqs[nBins - 1] * (1 + 0.5 * HANN_BANDWIDTH / q);
        double nyquist = srIn / 2.0;
        int count1 = Math.max(0, (int) Math.ceil(Math.log(nyquist / cutoff) / Math.log(2)) - 1 - 1);
        int twos = Integer.numberOfTrailingZeros(hopIn);
        int count2 = Math.max(0, twos - nOctaves + 1);
        int count = Math.min(count1, count2);
        double sr = srIn;
        int hop = hopIn;
        float[] cur = y;
        for (int i = 0; i < count; i++) {
            cur = halve(cur);
            sr /= 2;
            hop /= 2;
        }
        double baseSr = sr;
        float[][][] resp = new float[nOctaves][][];
        int minFrames = Integer.MAX_VALUE;
        double mySr = sr;
        int myHop = hop;
        for (int o = 0; o < nOctaves; o++) {
            int hiBin = nBins - nFilters * o;
            int loBin = Math.max(0, hiBin - nFilters);
            double[] f = java.util.Arrays.copyOfRange(freqs, loBin, hiBin);
            int[] nFftBox = new int[1];
            Kernel[] ks = kernels(f, mySr, q, Math.sqrt(baseSr / mySr), nFftBox);
            final int nFft = nFftBox[0];
            int frames = 1 + cur.length / myHop;
            final float[][] out = new float[f.length][frames];
            final float[] signal = cur;
            final int hopO = myHop;
            final Kernel[] kernels = ks;
            Parallel.range(frames, 256, (from, to) -> {
                Fft fft = new Fft(nFft);
                float[] frame = new float[nFft];
                float[] re = new float[nFft / 2 + 1];
                float[] im = new float[nFft / 2 + 1];
                int pad = nFft / 2;
                for (int t = from; t < to; t++) {
                    int start = t * hopO - pad;
                    for (int i = 0; i < nFft; i++) {
                        int j = start + i;
                        frame[i] = j >= 0 && j < signal.length ? signal[j] : 0f;
                    }
                    fft.forward(frame, 0, re, im);
                    for (int b = 0; b < kernels.length; b++) {
                        Kernel k = kernels[b];
                        float sr0 = 0f, si0 = 0f;
                        for (int c = 0; c < k.bins.length; c++) {
                            int bin = k.bins[c];
                            sr0 += k.re[c] * re[bin] - k.im[c] * im[bin];
                            si0 += k.re[c] * im[bin] + k.im[c] * re[bin];
                        }
                        out[b][t] = (float) Math.hypot(sr0, si0);
                    }
                }
            });
            resp[o] = out;
            minFrames = Math.min(minFrames, frames);
            if (myHop % 2 == 0 && o + 1 < nOctaves) {
                myHop /= 2;
                mySr /= 2;
                cur = halve(cur);
            }
        }
        float[][] v = new float[nBins][];
        int end = nBins;
        for (int o = 0; o < nOctaves; o++) {
            float[][] r = resp[o];
            for (int b = r.length - 1; b >= 0 && end > 0; b--) {
                end--;
                float[] row = java.util.Arrays.copyOf(r[b], minFrames);
                double len = q * baseSr / freqs[end];
                float inv = (float) (1.0 / Math.sqrt(len));
                for (int t = 0; t < minFrames; t++) {
                    row[t] *= inv;
                }
                v[end] = row;
            }
        }
        return v;
    }

    /**
     * features.chroma(y, tuning, fmin_note, n_octaves): CQT de 36 bins por octava, log1p(100·x) y
     * suma por clase de nota (fila 0 = Do). Devuelve [12][cuadro].
     */
    public static double[][] chroma(float[] y, double tuning, double fmin, int nOctaves) {
        int bpo = 36;
        int maxOctaves = (int) Math.floor(Math.log((Dsp.SR / 2.0) / fmin) / Math.log(2));
        nOctaves = Math.max(1, Math.min(nOctaves, maxOctaves));
        int nBins = bpo * nOctaves;
        float[][] c = magnitude(y, Dsp.SR, Dsp.HOP, fmin, nBins, bpo, tuning);
        int frames = c[0].length;
        // cq_to_chroma: cada clase suma los 3 bins centrados en su nota, en todas las octavas.
        double midi = 12 * (Math.log(fmin / 440.0) / Math.log(2)) + 69;
        int roll = (int) Math.round(((midi % 12) + 12) % 12);
        double[][] out = new double[12][frames];
        for (int bin = 0; bin < nBins; bin++) {
            int within = bin % bpo;
            // columnas 3c-1, 3c, 3c+1 (mod 36) -> clase c
            int cls = ((within + 1) % bpo) / 3;
            int row = (cls + roll) % 12;
            float[] src = c[bin];
            double[] dst = out[row];
            for (int t = 0; t < frames; t++) {
                dst[t] += (float) Math.log1p(100.0 * src[t]);
            }
        }
        return out;
    }
}
