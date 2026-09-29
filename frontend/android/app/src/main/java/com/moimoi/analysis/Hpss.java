package com.moimoi.analysis;

import com.moimoi.engine.Fft;

/**
 * Separación armónico/percusiva como librosa.effects.hpss (medianas de 31 cuadros en el tiempo y
 * de 31 bins en la frecuencia, máscaras suaves de potencia 2 y stft/istft de 2048 con salto 512).
 * Se hace de a cuadros: no hace falta tener el espectrograma entero en memoria.
 */
public final class Hpss {

    private Hpss() {}

    /** {armónica, percusiva}; percussive = false devuelve solo la armónica (effects.harmonic). */
    public static float[][] split(float[] y, double marginHarm, double marginPerc, boolean percussive) {
        final int nFft = 2048;
        final int hop = 512;
        final int bins = nFft / 2 + 1;
        final int k = 31;
        final int half = k / 2;
        int n = y.length;
        int frames = Spectrum.frames(n, hop);
        float[] window = Dsp.hann(nFft, true);
        Fft fft = new Fft(nFft);
        int ring = 64;
        float[][] re = new float[ring][bins];
        float[][] im = new float[ring][bins];
        float[][] mag = new float[ring][bins];
        float[] frame = new float[nFft];
        int pad = nFft / 2;
        // Se acumula directamente en la salida (sin el relleno del centrado): menos memoria.
        float[] outH = new float[n];
        float[] outP = percussive ? new float[n] : null;
        float[] harm = new float[bins];
        float[] perc = new float[bins];
        float[][] timeWin = new float[bins][k];
        float[] freqWin = new float[k];
        float[] specRe = new float[bins];
        float[] specIm = new float[bins];
        float[] ifft = new float[nFft];
        int computed = 0;
        for (int t = 0; t < frames; t++) {
            // Calcular la stft hasta el cuadro t + 15 (lo que necesita la mediana en el tiempo).
            int need = Math.min(frames - 1, t + half);
            while (computed <= need) {
                int start = computed * hop - pad;
                for (int i = 0; i < nFft; i++) {
                    int j = start + i;
                    frame[i] = j >= 0 && j < n ? y[j] * window[i] : 0f;
                }
                int slot = computed % ring;
                fft.forward(frame, 0, re[slot], im[slot]);
                for (int b = 0; b < bins; b++) {
                    mag[slot][b] = (float) Math.sqrt(re[slot][b] * re[slot][b] + im[slot][b] * im[slot][b]);
                }
                computed++;
            }
            int slot = t % ring;
            float[] s = mag[slot];
            // Mediana en el tiempo (modo "reflect" de scipy.ndimage): ventanas ordenadas por bin que
            // se corren de a un cuadro (en los bordes se arman de nuevo).
            boolean interior = t > half && t + half < frames;
            for (int b = 0; b < bins; b++) {
                float[] win = timeWin[b];
                if (interior && t - 1 >= half) {
                    replace(win, mag[(t - half - 1) % ring][b], mag[(t + half) % ring][b]);
                } else {
                    for (int d = -half; d <= half; d++) {
                        win[d + half] = mag[reflect(t + d, frames) % ring][b];
                    }
                    java.util.Arrays.sort(win);
                }
                harm[b] = win[half];
            }
            // Mediana en la frecuencia: ventana ordenada que se corre de a un bin.
            for (int b = 0; b < bins; b++) {
                if (b > half && b + half < bins) {
                    replace(freqWin, s[b - half - 1], s[b + half]);
                } else {
                    for (int d = -half; d <= half; d++) {
                        freqWin[d + half] = s[reflect(b + d, bins)];
                    }
                    java.util.Arrays.sort(freqWin);
                }
                perc[b] = freqWin[half];
            }
            for (int pass = 0; pass < (percussive ? 2 : 1); pass++) {
                for (int b = 0; b < bins; b++) {
                    float x = pass == 0 ? harm[b] : perc[b];
                    float ref = pass == 0 ? (float) (perc[b] * marginHarm) : (float) (harm[b] * marginPerc);
                    float z = Math.max(x, ref);
                    float m;
                    if (z < Float.MIN_NORMAL) {
                        m = 0f;
                    } else {
                        float a = (x / z) * (x / z);
                        float r = (ref / z) * (ref / z);
                        m = a / (a + r);
                    }
                    specRe[b] = re[slot][b] * m;
                    specIm[b] = im[slot][b] * m;
                }
                fft.inverse(specRe, specIm, ifft, 0);
                float[] out = pass == 0 ? outH : outP;
                int pos = t * hop - pad;
                int from = Math.max(0, -pos), to = Math.min(nFft, n - pos);
                for (int i = from; i < to; i++) {
                    out[pos + i] += ifft[i] * window[i];
                }
            }
        }
        // Normalizar por la suma de las ventanas al cuadrado y quitar el relleno del centrado.
        float[] w2 = new float[nFft];
        for (int i = 0; i < nFft; i++) {
            w2[i] = window[i] * window[i];
        }
        for (int j = 0; j < n; j++) {
            int p = j + pad;
            int tFrom = Math.max(0, (p - nFft + hop) / hop);
            int tTo = Math.min(frames - 1, p / hop);
            float sum = 0f;
            for (int t = tFrom; t <= tTo; t++) {
                int i = p - t * hop;
                if (i >= 0 && i < nFft) {
                    sum += w2[i];
                }
            }
            if (sum > Float.MIN_NORMAL) {
                outH[j] /= sum;
                if (percussive) {
                    outP[j] /= sum;
                }
            } else {
                outH[j] = 0f;
                if (percussive) {
                    outP[j] = 0f;
                }
            }
        }
        return new float[][] {outH, outP};
    }

    private static int reflect(int i, int n) {
        while (i < 0 || i >= n) {
            if (i < 0) {
                i = -i - 1;
            }
            if (i >= n) {
                i = 2 * n - i - 1;
            }
        }
        return i;
    }

    /** En una ventana ordenada, cambia el valor `out` por `in` manteniendo el orden. */
    private static void replace(float[] win, float out, float in) {
        int n = win.length;
        int i = java.util.Arrays.binarySearch(win, out);
        if (i < 0) {
            // No debería pasar (el valor está en la ventana); por las dudas se reordena.
            i = 0;
        }
        // Sacar win[i] e insertar `in` en su lugar ordenado.
        if (in >= out) {
            int j = i;
            while (j + 1 < n && win[j + 1] < in) {
                win[j] = win[j + 1];
                j++;
            }
            win[j] = in;
        } else {
            int j = i;
            while (j > 0 && win[j - 1] > in) {
                win[j] = win[j - 1];
                j--;
            }
            win[j] = in;
        }
    }
}
