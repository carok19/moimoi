package com.moimoi.local;

import com.moimoi.analysis.Dsp;
import com.moimoi.engine.Fft;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Cambia la velocidad y el tono del audio sin afectar el otro (en la computadora lo hace
 * pedalboard/Rubber Band). Vocoder de fase de 4096 muestras con:
 * - bloqueo de fase alrededor de los picos del espectro (Laroche y Dolson), que evita el sonido
 *   "de lata" de un vocoder simple;
 * - los golpes (batería, ataques) se buscan antes en toda la pista y alrededor de cada uno no se
 *   estira (el estiramiento se reparte entre golpes): así caen justo en su lugar y no se
 *   "borronean"; en el golpe, las frecuencias que suben vuelven a la fase original, sin cortar las
 *   notas que siguen sonando;
 * - los dos canales con los mismos cuadros y golpes, y un canal que es copia de otro (mono o
 *   paneado) queda igual.
 * El tono se cambia estirando el tiempo y después remuestreando (filtro sinc con ventana de Kaiser).
 */
public final class Stretch {

    private Stretch() {}

    /** De dónde se lee: canal 0 = izquierdo, 1 = derecho, con ceros fuera del audio. */
    public interface Source {
        long frames();

        void read(int channel, long start, float[] dst, int offset, int count);
    }

    public interface Sink {
        void write(float[] left, float[] right, int count) throws IOException;
    }

    public interface Progress {
        /** Avance 0-1; puede lanzar una excepción para cancelar. */
        void report(double fraction);
    }

    static final int N = 4096;
    static final int HS = 1024; // salto de síntesis
    static final int BINS = N / 2 + 1;
    /** Suma de las ventanas de Hann al cuadrado con salto N/4. */
    static final float OLA_GAIN = 1.5f;

    public static boolean needed(double tempo, double semitones) {
        return Math.abs(tempo - 1.0) >= 1e-3 || Math.abs(semitones) >= 1e-3;
    }

    /** Largo de la salida para `frames` muestras de entrada. */
    public static long outputFrames(long frames, double tempo) {
        return Math.round(frames / tempo);
    }

    public static void process(Source in, double tempo, double semitones, Sink out, Progress progress) throws IOException {
        long inFrames = in.frames();
        long total = outputFrames(inFrames, tempo);
        double pitch = Math.pow(2, semitones / 12.0);
        double alpha = pitch / tempo; // cuánto estira el vocoder
        long pvFrames = Math.round(inFrames * alpha);
        Resampler resampler = new Resampler(pitch, out, total);
        long[] onsets = Onsets.find(in, f -> progress.report(0.15 * f));
        TimeMap map = TimeMap.build(onsets, alpha, inFrames, pvFrames);
        Vocoder vocoder = new Vocoder(map);
        float[] l = new float[HS];
        float[] r = new float[HS];
        long frames = (pvFrames + N / 2 + HS - 1) / HS + 1;
        for (long m = 0; ; m++) {
            long base = m * HS - N / 2; // primera muestra de salida que toca este cuadro
            if (base >= pvFrames) {
                break;
            }
            vocoder.frame(in, m, l, r);
            // Las primeras HS muestras desde `base` ya están completas.
            int from = (int) Math.max(0, -base);
            int to = (int) Math.min(HS, pvFrames - base);
            if (to > from) {
                resampler.push(l, r, from, to - from);
            }
            if ((m & 15) == 0) {
                progress.report(0.15 + 0.85 * Math.min(1.0, m / (double) frames));
            }
        }
        resampler.finish();
        progress.report(1.0);
    }

    // ---- vocoder de fase ---------------------------------------------------------------------

    static final class Vocoder {
        final TimeMap map;
        final float[] window = Dsp.hann(N, true);
        final Fft fft = new Fft(N);
        final float[][] frame = new float[2][N];
        final float[][] re = new float[2][BINS];
        final float[][] im = new float[2][BINS];
        final float[][] prevRe = new float[2][BINS];
        final float[][] prevIm = new float[2][BINS];
        final double[][] outCos = new double[2][BINS];
        final double[][] outSin = new double[2][BINS];
        final float[][] acc = new float[2][N];
        float[] energy = new float[BINS];
        float[] prevEnergy = new float[BINS];
        float[] reference = null;
        int region = -1;
        final boolean[] reset = new boolean[BINS];
        final int[] peaks = new int[BINS];
        final float[] yRe = new float[BINS];
        final float[] yIm = new float[BINS];
        final float[] ifft = new float[N];
        long prevStart = Long.MIN_VALUE;

        Vocoder(TimeMap map) {
            this.map = map;
        }

        void frame(Source in, long m, float[] outL, float[] outR) {
            double u = m * (double) HS;
            long start = Math.round(map.inputAt(u)) - N / 2;
            for (int c = 0; c < 2; c++) {
                in.read(c, start, frame[c], 0, N);
                float[] f = frame[c];
                for (int i = 0; i < N; i++) {
                    f[i] *= window[i];
                }
                fft.forward(f, 0, re[c], im[c]);
            }
            boolean first = prevStart == Long.MIN_VALUE;
            int dt = first ? HS : (int) Math.max(1, start - prevStart);
            markTransient(map.regionAt(u), first);
            for (int c = 0; c < 2; c++) {
                synthesize(c, first, dt);
                // A la salida, con la ventana de síntesis.
                fft.inverse(yRe, yIm, ifft, 0);
                float[] a = acc[c];
                for (int i = 0; i < N; i++) {
                    a[i] += ifft[i] * window[i];
                }
                System.arraycopy(re[c], 0, prevRe[c], 0, BINS);
                System.arraycopy(im[c], 0, prevIm[c], 0, BINS);
            }
            prevStart = start;
            // Las primeras HS muestras quedan completas: afuera, y se corre el acumulador.
            for (int i = 0; i < HS; i++) {
                outL[i] = acc[0][i] / OLA_GAIN;
                outR[i] = acc[1][i] / OLA_GAIN;
            }
            for (int c = 0; c < 2; c++) {
                float[] a = acc[c];
                System.arraycopy(a, HS, a, 0, N - HS);
                java.util.Arrays.fill(a, N - HS, N, 0f);
            }
        }

        /**
         * Dentro de la zona de un golpe (los cuadros cuya ventana lo contiene), las frecuencias que
         * suben más de 3 dB respecto del cuadro anterior a la zona vuelven a la fase original.
         */
        private void markTransient(int r, boolean first) {
            float[] t = prevEnergy;
            prevEnergy = energy;
            energy = t;
            for (int k = 0; k < BINS; k++) {
                float e = 0;
                for (int c = 0; c < 2; c++) {
                    e += re[c][k] * re[c][k] + im[c][k] * im[c][k];
                }
                energy[k] = e;
            }
            java.util.Arrays.fill(reset, false);
            if (r < 0 || first) {
                region = -1;
                return;
            }
            if (r != region) {
                region = r;
                reference = prevEnergy.clone(); // el cuadro de antes: su ventana termina antes del golpe
            }
            for (int k = 0; k < BINS; k++) {
                reset[k] = energy[k] > 2f * reference[k];
            }
        }

        private void synthesize(int c, boolean first, int dt) {
            float[] xr = re[c], xi = im[c];
            double[] oc = outCos[c], os = outSin[c];
            if (first) {
                for (int k = 0; k < BINS; k++) {
                    yRe[k] = xr[k];
                    yIm[k] = xi[k];
                }
                savePhase(c);
                return;
            }
            // Picos: máximos locales en ±2 frecuencias.
            int count = 0;
            float max = 0;
            for (int k = 0; k < BINS; k++) {
                max = Math.max(max, xr[k] * xr[k] + xi[k] * xi[k]);
            }
            float floor = max * 1e-10f;
            for (int k = 1; k < BINS - 1; k++) {
                float e = xr[k] * xr[k] + xi[k] * xi[k];
                if (e <= floor) {
                    continue;
                }
                if (e > mag2(xr, xi, k - 1) && e >= mag2(xr, xi, k + 1)
                        && (k < 2 || e > mag2(xr, xi, k - 2)) && (k + 2 >= BINS || e >= mag2(xr, xi, k + 2))) {
                    peaks[count++] = k;
                }
            }
            float[] pr = prevRe[c], pi = prevIm[c];
            if (count == 0) {
                for (int k = 0; k < BINS; k++) {
                    yRe[k] = xr[k];
                    yIm[k] = xi[k];
                }
                savePhase(c);
                return;
            }
            int regionStart = 0;
            for (int q = 0; q < count; q++) {
                int p = peaks[q];
                int regionEnd = q + 1 < count ? (p + peaks[q + 1]) / 2 + 1 : BINS; // [start, end)
                double rr = 1, ri = 0; // rotación de la región
                double mag = Math.sqrt(mag2(xr, xi, p));
                double prevMag = Math.sqrt(mag2(pr, pi, p));
                if (!reset[p] && prevMag > 1e-9 && mag > 1e-12 && (oc[p] != 0 || os[p] != 0)) {
                    // Frecuencia verdadera del pico, por cuánto giró su fase desde el cuadro anterior.
                    double cr = xr[p] * (double) pr[p] + xi[p] * (double) pi[p];
                    double ci = xi[p] * (double) pr[p] - xr[p] * (double) pi[p];
                    double omega = 2 * Math.PI * p / N;
                    double dev = princarg(Math.atan2(ci, cr) - omega * dt);
                    double advance = (omega + dev / dt) * HS;
                    double ca = Math.cos(advance), sa = Math.sin(advance);
                    double or = oc[p] * ca - os[p] * sa;
                    double oi = oc[p] * sa + os[p] * ca;
                    double ir = xr[p] / mag, ii = xi[p] / mag;
                    rr = or * ir + oi * ii;
                    ri = oi * ir - or * ii;
                }
                for (int k = regionStart; k < regionEnd; k++) {
                    if (reset[k]) {
                        yRe[k] = xr[k];
                        yIm[k] = xi[k];
                    } else {
                        yRe[k] = (float) (xr[k] * rr - xi[k] * ri);
                        yIm[k] = (float) (xr[k] * ri + xi[k] * rr);
                    }
                }
                regionStart = regionEnd;
            }
            savePhase(c);
        }

        /** Guarda la fase de salida de cada frecuencia (como giro unitario). */
        private void savePhase(int c) {
            double[] oc = outCos[c], os = outSin[c];
            for (int k = 0; k < BINS; k++) {
                double mag = Math.sqrt(yRe[k] * (double) yRe[k] + yIm[k] * (double) yIm[k]);
                if (mag > 1e-12) {
                    oc[k] = yRe[k] / mag;
                    os[k] = yIm[k] / mag;
                }
            }
        }

        private static float mag2(float[] r, float[] i, int k) {
            return r[k] * r[k] + i[k] * i[k];
        }
    }

    // ---- golpes ------------------------------------------------------------------------------

    /** Dónde empiezan los golpes (en muestras de entrada), buscados en toda la pista antes de estirar. */
    static final class Onsets {
        static final int FFT = 2048;
        static final int HOP = 256;

        static long[] find(Source in, Progress progress) {
            long frames = in.frames();
            int count = (int) (frames / HOP) + 1;
            int bins = FFT / 2 + 1;
            float[] window = Dsp.hann(FFT, true);
            Fft fft = new Fft(FFT);
            float[] l = new float[FFT], r = new float[FFT];
            float[] re = new float[bins], im = new float[bins];
            float[][] ring = new float[5][bins]; // energía de los últimos 5 cuadros
            double[] df = new double[count];
            for (int j = 0; j < count; j++) {
                long start = (long) j * HOP - FFT / 2;
                in.read(0, start, l, 0, FFT);
                in.read(1, start, r, 0, FFT);
                for (int i = 0; i < FFT; i++) {
                    l[i] = (l[i] + r[i]) * 0.5f * window[i];
                }
                fft.forward(l, 0, re, im);
                float[] e = ring[j % 5];
                float max = 0;
                for (int k = 0; k < bins; k++) {
                    e[k] = re[k] * re[k] + im[k] * im[k];
                    max = Math.max(max, e[k]);
                }
                if (j >= 4) {
                    float[] before = ring[(j - 4) % 5]; // ~1024 muestras antes
                    float floor = Math.max(max * 1e-6f, 1e-7f);
                    int considered = 0, rising = 0;
                    for (int k = 4; k < bins; k++) { // desde ~86 Hz
                        if (e[k] > floor) {
                            considered++;
                            if (e[k] > 2f * before[k]) {
                                rising++;
                            }
                        }
                    }
                    df[j] = considered > 20 ? rising / (double) considered : 0;
                }
                if ((j & 1023) == 0) {
                    progress.report(j / (double) count);
                }
            }
            java.util.List<Long> onsets = new java.util.ArrayList<>();
            long last = Long.MIN_VALUE / 2;
            for (int j = 1; j + 1 < count; j++) {
                if (df[j] > 0.35 && df[j] >= df[j - 1] && df[j] > df[j + 1]) {
                    long at = refine(in, (long) j * HOP);
                    if (at - last >= 1024) {
                        onsets.add(at);
                        last = at;
                    }
                }
            }
            long[] out = new long[onsets.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = onsets.get(i);
            }
            return out;
        }

        /**
         * El comienzo exacto del golpe: la mayor subida de energía (ventanas de 64 muestras) dentro de
         * la ventana del cuadro donde se detectó (suele detectarse apenas entra, por la derecha).
         */
        static long refine(Source in, long center) {
            int from = -1088, to = 1216, win = 64, step = 16, memory = 40; // ~15 ms hacia atrás
            int n = to - from + win;
            float[] l = new float[n], r = new float[n];
            in.read(0, center + from, l, 0, n);
            in.read(1, center + from, r, 0, n);
            int count = (n - win) / step + 1;
            double[] energy = new double[count];
            for (int q = 0; q < count; q++) {
                double s = 0;
                for (int i = q * step; i < q * step + win; i++) {
                    double v = 0.5 * (l[i] + r[i]);
                    s += v * v;
                }
                energy[q] = s / win + 1e-12;
            }
            // Cuánto supera la energía al máximo de los ~15 ms anteriores (un golpe lo supera de
            // golpe; las ondulaciones de un acorde, no).
            double best = 0;
            long at = center;
            for (int q = memory; q < count; q++) {
                double before = 0;
                for (int b = q - memory; b < q; b++) {
                    before = Math.max(before, energy[b]);
                }
                double score = energy[q] / before;
                if (score > best) {
                    best = score;
                    at = center + from + (long) q * step + win - step; // entró en los últimos `step` de la ventana
                }
            }
            return at;
        }
    }

    /**
     * De la salida del vocoder a la entrada: alrededor de cada golpe la relación es 1 a 1 (sin
     * estirar) y el golpe cae donde debe (entrada × estiramiento); entre golpes se estira lo que
     * haga falta para llegar.
     */
    static final class TimeMap {
        final double[] u; // salida (muestras del vocoder)
        final double[] x; // entrada
        final double[] regionStart;
        final double[] regionEnd;

        TimeMap(double[] u, double[] x, double[] regionStart, double[] regionEnd) {
            this.u = u;
            this.x = x;
            this.regionStart = regionStart;
            this.regionEnd = regionEnd;
        }

        static TimeMap build(long[] onsets, double alpha, long inFrames, long pvFrames) {
            java.util.List<double[]> anchors = new java.util.ArrayList<>();
            java.util.List<double[]> regions = new java.util.ArrayList<>();
            anchors.add(new double[] {0, 0});
            double prevU = 0, prevX = 0;
            double h = N / 2.0;
            for (long o : onsets) {
                double tau = o * alpha;
                double xs = o - h, xe = o + h, us = tau - h, ue = tau + h;
                if (xs < prevX + HS || us < prevU + HS || xe > inFrames - HS || ue > pvFrames - HS) {
                    continue;
                }
                double ratio = (us - prevU) / (xs - prevX);
                if (ratio < alpha / 2 || ratio > alpha * 2) {
                    continue; // demasiado cerca del golpe anterior: se estira normal
                }
                double endRatio = (pvFrames - ue) / Math.max(1.0, inFrames - xe);
                if (endRatio < alpha / 4 || endRatio > alpha * 4) {
                    continue;
                }
                anchors.add(new double[] {us, xs});
                anchors.add(new double[] {ue, xe});
                regions.add(new double[] {us, ue});
                prevU = ue;
                prevX = xe;
            }
            anchors.add(new double[] {Math.max(pvFrames, prevU + 1), Math.max(inFrames, prevX + 1)});
            double[] u = new double[anchors.size()], x = new double[anchors.size()];
            for (int i = 0; i < u.length; i++) {
                u[i] = anchors.get(i)[0];
                x[i] = anchors.get(i)[1];
            }
            double[] rs = new double[regions.size()], re = new double[regions.size()];
            for (int i = 0; i < rs.length; i++) {
                rs[i] = regions.get(i)[0];
                re[i] = regions.get(i)[1];
            }
            return new TimeMap(u, x, rs, re);
        }

        double inputAt(double out) {
            if (out <= u[0]) {
                return x[0] + (out - u[0]) * (x[1] - x[0]) / (u[1] - u[0]);
            }
            int lo = 0, hi = u.length - 1;
            if (out >= u[hi]) {
                return x[hi] + (out - u[hi]) * (x[hi] - x[hi - 1]) / (u[hi] - u[hi - 1]);
            }
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (u[mid] <= out) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            return x[lo] + (out - u[lo]) * (x[hi] - x[lo]) / (u[hi] - u[lo]);
        }

        /** Zona de golpe (sin estirar) que contiene esta salida, o -1. */
        int regionAt(double out) {
            int lo = 0, hi = regionStart.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (out < regionStart[mid]) {
                    hi = mid - 1;
                } else if (out >= regionEnd[mid]) {
                    lo = mid + 1;
                } else {
                    return mid;
                }
            }
            return -1;
        }
    }

    static double princarg(double phase) {
        double p = phase + Math.PI;
        p -= 2 * Math.PI * Math.floor(p / (2 * Math.PI));
        return p - Math.PI;
    }

    // ---- remuestreo (para el tono) -----------------------------------------------------------

    /**
     * Pasa la salida del vocoder (estirada `ratio` veces) al largo final: cada muestra de salida n
     * lee la entrada en n·ratio con un filtro sinc de Kaiser (con corte para no generar alias).
     */
    static final class Resampler {
        static final int ZEROS = 16;          // cruces por cero a cada lado
        static final int RES = 512;           // pasos de la tabla por muestra
        final double ratio;
        final Sink out;
        final long total;
        final boolean passthrough;
        final double fc;
        final double width;                    // semiancho del filtro, en muestras de entrada
        final float[] table;
        float[] bufL = new float[1 << 15];
        float[] bufR = new float[1 << 15];
        long bufStart = 0;                     // índice absoluto de bufL[0]
        int bufCount = 0;
        long produced = 0;
        final float[] outL = new float[4096];
        final float[] outR = new float[4096];
        int outCount = 0;

        Resampler(double ratio, Sink out, long total) {
            this.ratio = ratio;
            this.out = out;
            this.total = total;
            this.passthrough = Math.abs(ratio - 1.0) < 1e-9;
            this.fc = 0.97 * Math.min(1.0, 1.0 / ratio);
            this.width = ZEROS / fc;
            int size = (int) Math.ceil(width * RES) + 2;
            table = new float[size];
            double beta = 8.0;
            double i0 = Dsp.besselI0(beta);
            for (int t = 0; t < size; t++) {
                double d = t / (double) RES; // distancia en muestras de entrada
                if (d >= width) {
                    table[t] = 0f;
                    continue;
                }
                double x = fc * d;
                double sinc = x < 1e-12 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double u = d / width;
                double w = Dsp.besselI0(beta * Math.sqrt(Math.max(0, 1 - u * u))) / i0;
                table[t] = (float) (fc * sinc * w);
            }
        }

        void push(float[] l, float[] r, int offset, int count) throws IOException {
            if (passthrough) {
                for (int i = 0; i < count && produced < total; i++) {
                    emit(l[offset + i], r[offset + i]);
                }
                return;
            }
            if (bufCount + count > bufL.length) {
                compact();
                if (bufCount + count > bufL.length) {
                    int size = Math.max(bufL.length * 2, bufCount + count);
                    bufL = java.util.Arrays.copyOf(bufL, size);
                    bufR = java.util.Arrays.copyOf(bufR, size);
                }
            }
            System.arraycopy(l, offset, bufL, bufCount, count);
            System.arraycopy(r, offset, bufR, bufCount, count);
            bufCount += count;
            produce(false);
        }

        /** Descarta la entrada que ya no hace falta. */
        private void compact() {
            double x = produced * ratio;
            long keepFrom = (long) Math.floor(x - width) - 1;
            int drop = (int) Math.max(0, Math.min(bufCount, keepFrom - bufStart));
            if (drop > 0) {
                System.arraycopy(bufL, drop, bufL, 0, bufCount - drop);
                System.arraycopy(bufR, drop, bufR, 0, bufCount - drop);
                bufCount -= drop;
                bufStart += drop;
            }
        }

        private void produce(boolean ending) throws IOException {
            long available = bufStart + bufCount; // índice absoluto de la próxima entrada que falta
            while (produced < total) {
                double x = produced * ratio;
                long hi = (long) Math.floor(x + width);
                if (!ending && hi >= available) {
                    return;
                }
                long lo = (long) Math.ceil(x - width);
                double sl = 0, sr = 0;
                for (long j = Math.max(lo, bufStart); j <= hi && j < available; j++) {
                    double d = Math.abs(x - j) * RES;
                    int t = (int) d;
                    if (t + 1 >= table.length) {
                        continue;
                    }
                    double frac = d - t;
                    double k = table[t] + (table[t + 1] - table[t]) * frac;
                    int idx = (int) (j - bufStart);
                    sl += k * bufL[idx];
                    sr += k * bufR[idx];
                }
                emit((float) sl, (float) sr);
            }
        }

        private void emit(float l, float r) throws IOException {
            outL[outCount] = l;
            outR[outCount] = r;
            outCount++;
            produced++;
            if (outCount == outL.length) {
                out.write(outL, outR, outCount);
                outCount = 0;
            }
        }

        void finish() throws IOException {
            if (!passthrough) {
                produce(true);
            }
            while (produced < total) {
                emit(0f, 0f);
            }
            if (outCount > 0) {
                out.write(outL, outR, outCount);
                outCount = 0;
            }
        }
    }
}
