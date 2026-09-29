package com.moimoi.engine;

/**
 * FFT real de tamaño potencia de 2 (N), hecha con una FFT compleja de N/2 puntos.
 *
 * forward: x (N reales) -> X[0..N/2] (re, im), sin escalar.
 * inverse: X[0..N/2] -> x (N reales), con el factor 1/N (como numpy.fft.irfft).
 *
 * No es segura para usar desde varios hilos a la vez: cada hilo usa su propia instancia.
 */
public final class Fft {

    private final int n;
    private final int m; // n / 2
    private final int[] bitrev;
    private final float[] cosM; // giros de la FFT compleja de m puntos
    private final float[] sinM;
    private final float[] cosN; // giros e^{-2πik/n} para combinar pares e impares
    private final float[] sinN;
    private final float[] zr;
    private final float[] zi;

    public Fft(int n) {
        if (n < 4 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("El tamaño de la FFT tiene que ser potencia de 2");
        }
        this.n = n;
        this.m = n / 2;
        int bits = Integer.numberOfTrailingZeros(m);
        bitrev = new int[m];
        for (int i = 0; i < m; i++) {
            bitrev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        cosM = new float[m / 2];
        sinM = new float[m / 2];
        for (int i = 0; i < m / 2; i++) {
            double a = -2 * Math.PI * i / m;
            cosM[i] = (float) Math.cos(a);
            sinM[i] = (float) Math.sin(a);
        }
        cosN = new float[m + 1];
        sinN = new float[m + 1];
        for (int k = 0; k <= m; k++) {
            double a = -2 * Math.PI * k / n;
            cosN[k] = (float) Math.cos(a);
            sinN[k] = (float) Math.sin(a);
        }
        zr = new float[m];
        zi = new float[m];
    }

    public int size() {
        return n;
    }

    /** FFT compleja en el lugar (inverse = true: sin escalar, con el signo cambiado). */
    private void complexFft(float[] re, float[] im, boolean inverse) {
        for (int i = 0; i < m; i++) {
            int j = bitrev[i];
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        float sign = inverse ? -1f : 1f;
        for (int size = 2; size <= m; size <<= 1) {
            int half = size >> 1;
            int step = m / size;
            for (int start = 0; start < m; start += size) {
                for (int k = 0, t = 0; k < half; k++, t += step) {
                    float wr = cosM[t];
                    float wi = sign * sinM[t];
                    int a = start + k;
                    int b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    /** x[offset .. offset+n) -> outRe/outIm[0..n/2]. */
    public void forward(float[] x, int offset, float[] outRe, float[] outIm) {
        for (int i = 0; i < m; i++) {
            zr[i] = x[offset + 2 * i];
            zi[i] = x[offset + 2 * i + 1];
        }
        complexFft(zr, zi, false);
        for (int k = 0; k <= m; k++) {
            int a = k == m ? 0 : k;
            int b = k == 0 ? 0 : m - k;
            // E = (Z[k] + conj(Z[m-k])) / 2 ; O = (Z[k] - conj(Z[m-k])) / (2i)
            float er = 0.5f * (zr[a] + zr[b]);
            float ei = 0.5f * (zi[a] - zi[b]);
            float or = 0.5f * (zi[a] + zi[b]);
            float oi = -0.5f * (zr[a] - zr[b]);
            float wr = cosN[k];
            float wi = sinN[k];
            outRe[k] = er + (or * wr - oi * wi);
            outIm[k] = ei + (or * wi + oi * wr);
        }
    }

    /**
     * inRe/inIm[0..n/2] -> out[offset .. offset+n), con el factor 1/n. Como numpy/torch irfft, la
     * parte imaginaria de las frecuencias 0 y n/2 se ignora (en una señal real no existe).
     */
    public void inverse(float[] inRe, float[] inIm, float[] out, int offset) {
        for (int k = 0; k < m; k++) {
            // X[k] y conj(X[m-k])
            float xr = inRe[k];
            float xi = k == 0 ? 0f : inIm[k];
            float yr = inRe[m - k];
            float yi = k == 0 ? 0f : -inIm[m - k];
            float er = 0.5f * (xr + yr);
            float ei = 0.5f * (xi + yi);
            // O = (X[k] - conj(X[m-k])) / (2 W^k), W^k = cosN + i sinN  ->  dividir = multiplicar por el conjugado
            float dr = 0.5f * (xr - yr);
            float di = 0.5f * (xi - yi);
            float wr = cosN[k];
            float wi = -sinN[k];
            float or = dr * wr - di * wi;
            float oi = dr * wi + di * wr;
            // Z = E + i O
            zr[k] = er - oi;
            zi[k] = ei + or;
        }
        complexFft(zr, zi, true);
        float scale = 1f / m;
        for (int i = 0; i < m; i++) {
            out[offset + 2 * i] = zr[i] * scale;
            out[offset + 2 * i + 1] = zi[i] * scale;
        }
    }
}
