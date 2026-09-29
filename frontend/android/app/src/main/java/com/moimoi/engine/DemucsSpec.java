package com.moimoi.engine;

import java.nio.FloatBuffer;

/**
 * Espectrograma y espectrograma inverso tal como los calcula Demucs v4 (HTDemucs._spec e _ispec):
 * STFT de 4096 puntos, salto 1024, ventana de Hann, normalizada (1/√4096), con el relleno por
 * reflexión que deja exactamente ceil(largo/1024) cuadros. Complejo como canales: para cada canal de
 * audio c, el canal 2c es la parte real y el 2c+1 la imaginaria.
 *
 * Internamente los espectrogramas se guardan por cuadro ([frames][BINS], contiguo en frecuencia);
 * el modelo usa [BINS][frames]: {@link #toModel} y {@link #fromModel} trasponen por bloques.
 */
public final class DemucsSpec {

    public static final int NFFT = 4096;
    public static final int HOP = NFFT / 4;
    public static final int BINS = NFFT / 2; // sin la frecuencia de Nyquist
    private static final int PAD = HOP / 2 * 3; // 1536
    private static final float NORM = (float) (1.0 / Math.sqrt(NFFT));
    private static final float INV_SCALE = (float) Math.sqrt(NFFT);
    private static final int TILE = 64;

    private final int length; // muestras del segmento
    private final int frames; // cuadros del espectrograma (ceil(length / HOP))
    private final float[] window = new float[NFFT];
    private final float[] envelope; // suma de ventana² del espectrograma inverso, por muestra

    public DemucsSpec(int length) {
        this.length = length;
        this.frames = (length + HOP - 1) / HOP;
        for (int i = 0; i < NFFT; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / NFFT)); // Hann periódica
        }
        // En la señal completa del inverso hay frames + 4 cuadros (2 vacíos a cada lado) y la
        // muestra de salida i está en la posición 2048 (centrado de la STFT) + 1536 (relleno) + i.
        envelope = new float[length];
        for (int i = 0; i < length; i++) {
            int j = NFFT / 2 + PAD + i;
            double sum = 0;
            int tMin = Math.max(0, (j - NFFT + HOP) / HOP);
            int tMax = Math.min(frames + 3, j / HOP);
            for (int t = tMin; t <= tMax; t++) {
                int k = j - t * HOP;
                if (k >= 0 && k < NFFT) {
                    sum += (double) window[k] * window[k];
                }
            }
            envelope[i] = (float) sum;
        }
    }

    public int frames() {
        return frames;
    }

    public int length() {
        return length;
    }

    public int plane() {
        return BINS * frames;
    }

    /** Índice de la señal con el relleno por reflexión de Demucs (pad1d, modo "reflect"). */
    private int reflect(int i) {
        int k = i - PAD;
        if (k < 0) {
            return -k;
        }
        if (k >= length) {
            return 2 * (length - 1) - k;
        }
        return k;
    }

    /**
     * Espectrograma de un canal x[0 .. length) -> realT/imagT ([frames][BINS]).
     * scratch: al menos NFFT; re/im: al menos BINS+1.
     */
    public void forward(Fft fft, float[] x, float[] realT, float[] imagT, float[] scratch, float[] re, float[] im) {
        for (int t = 0; t < frames; t++) {
            // El cuadro t (sin los dos primeros, que Demucs descarta) empieza en t*HOP de la señal
            // rellenada: el relleno extra del centrado de la STFT nunca llega a usarse.
            int start = t * HOP;
            if (start >= PAD && start + NFFT - PAD <= length) {
                int base = start - PAD;
                for (int n = 0; n < NFFT; n++) {
                    scratch[n] = x[base + n] * window[n];
                }
            } else {
                for (int n = 0; n < NFFT; n++) {
                    scratch[n] = x[reflect(start + n)] * window[n];
                }
            }
            fft.forward(scratch, 0, re, im);
            int row = t * BINS;
            for (int f = 0; f < BINS; f++) {
                realT[row + f] = re[f] * NORM;
                imagT[row + f] = im[f] * NORM;
            }
        }
    }

    /**
     * Espectrograma inverso de realT/imagT ([frames][BINS]) -> out[0 .. length) (se sobrescribe).
     * re/im: al menos BINS+1; frame: al menos NFFT.
     */
    public void inverse(Fft fft, float[] realT, float[] imagT, float[] out, float[] re, float[] im, float[] frame) {
        java.util.Arrays.fill(out, 0f);
        for (int t = 0; t < frames; t++) {
            int row = t * BINS;
            System.arraycopy(realT, row, re, 0, BINS);
            System.arraycopy(imagT, row, im, 0, BINS);
            re[BINS] = 0f; // Nyquist: Demucs lo rellena con ceros
            im[BINS] = 0f;
            fft.inverse(re, im, frame, 0);
            // Posición del cuadro t en la salida (ver el comentario del constructor).
            int start = t * HOP - PAD;
            int n0 = Math.max(0, -start);
            int n1 = Math.min(NFFT, length - start);
            for (int n = n0; n < n1; n++) {
                out[start + n] += frame[n] * INV_SCALE * window[n];
            }
        }
        for (int i = 0; i < length; i++) {
            out[i] /= envelope[i];
        }
    }

    /** [frames][BINS] -> buffer del modelo [BINS][frames] a partir de la posición base. */
    public void toModel(float[] srcT, FloatBuffer dst, int base) {
        for (int f0 = 0; f0 < BINS; f0 += TILE) {
            int f1 = Math.min(BINS, f0 + TILE);
            for (int t0 = 0; t0 < frames; t0 += TILE) {
                int t1 = Math.min(frames, t0 + TILE);
                for (int f = f0; f < f1; f++) {
                    int out = base + f * frames;
                    for (int t = t0; t < t1; t++) {
                        dst.put(out + t, srcT[t * BINS + f]);
                    }
                }
            }
        }
    }

    /** Buffer del modelo [BINS][frames] desde la posición base -> [frames][BINS]. */
    public void fromModel(FloatBuffer src, int base, float[] dstT) {
        for (int f0 = 0; f0 < BINS; f0 += TILE) {
            int f1 = Math.min(BINS, f0 + TILE);
            for (int t0 = 0; t0 < frames; t0 += TILE) {
                int t1 = Math.min(frames, t0 + TILE);
                for (int f = f0; f < f1; f++) {
                    int in = base + f * frames;
                    for (int t = t0; t < t1; t++) {
                        dstT[t * BINS + f] = src.get(in + t);
                    }
                }
            }
        }
    }
}
