package com.moimoi.local;

import com.moimoi.engine.DemucsSeparator;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Recibe las 6 fuentes del modelo a medida que salen, arma las pistas del tipo de separación
 * (p. ej. "Acompañamiento" = todo menos la voz) y las guarda en WAV de 16 bits junto con las formas
 * de onda (picos), sin tener nunca la canción entera en memoria.
 *
 * Como en audio_io.peak_normalize_set, todas las pistas llevan la misma ganancia para no saturar
 * (ni la suma ni ninguna pista): se elige antes de empezar con el pico de la mezcla original.
 */
public final class StemsSink implements DemucsSeparator.Sink {

    public static final int PEAKS_PER_SECOND = 25;

    private final List<String> stems;
    private final int[][] mapping;
    private final int nSources;
    private final int sampleRate;
    private final float gain;
    private final Wav.Writer[] writers;
    private final File[] files;
    private final float[][] left;
    private final float[][] right;
    private final float[] mixL;
    private final float[] mixR;
    private final Peaks[] peaks;
    private final Peaks mixPeaks;
    private float maxPeak;
    private long written;

    /**
     * @param gain ganancia de todas las pistas (1 = sin cambio).
     * @param capacity máximo de muestras por bloque que puede entregar el separador.
     */
    public StemsSink(List<String> stems, int[][] mapping, int nSources, File dir, int sampleRate, float gain,
                     long totalFrames, int capacity) throws IOException {
        this.stems = stems;
        this.mapping = mapping;
        this.nSources = nSources;
        this.sampleRate = sampleRate;
        this.gain = gain;
        int n = stems.size();
        writers = new Wav.Writer[n];
        files = new File[n];
        left = new float[n][capacity];
        right = new float[n][capacity];
        mixL = new float[capacity];
        mixR = new float[capacity];
        peaks = new Peaks[n];
        long buckets = totalFrames / Math.max(1, sampleRate / PEAKS_PER_SECOND) + 2;
        for (int i = 0; i < n; i++) {
            files[i] = new File(dir, stems.get(i) + ".wav.part");
            writers[i] = new Wav.Writer(files[i], 2, sampleRate);
            peaks[i] = new Peaks(sampleRate, (int) buckets);
        }
        mixPeaks = new Peaks(sampleRate, (int) buckets);
    }

    @Override
    public void write(int source, float[] l, float[] r, int count) throws IOException {
        if (source == 0) {
            for (int i = 0; i < stems.size(); i++) {
                java.util.Arrays.fill(left[i], 0, count, 0f);
                java.util.Arrays.fill(right[i], 0, count, 0f);
            }
        }
        for (int i = 0; i < stems.size(); i++) {
            for (int s : mapping[i]) {
                if (s == source) {
                    float[] dl = left[i];
                    float[] dr = right[i];
                    for (int k = 0; k < count; k++) {
                        dl[k] += l[k];
                        dr[k] += r[k];
                    }
                }
            }
        }
        if (source == nSources - 1) {
            flush(count);
        }
    }

    private void flush(int count) throws IOException {
        java.util.Arrays.fill(mixL, 0, count, 0f);
        java.util.Arrays.fill(mixR, 0, count, 0f);
        for (int i = 0; i < stems.size(); i++) {
            float[] l = left[i];
            float[] r = right[i];
            for (int k = 0; k < count; k++) {
                float a = l[k] * gain;
                float b = r[k] * gain;
                l[k] = a;
                r[k] = b;
                mixL[k] += a;
                mixR[k] += b;
            }
            maxPeak = Math.max(maxPeak, peaks[i].add(l, r, count));
            writers[i].write(l, r, count);
        }
        maxPeak = Math.max(maxPeak, mixPeaks.add(mixL, mixR, count));
        written += count;
    }

    /** Pico más alto que quedó en las pistas (o en su suma), ya con la ganancia. */
    public float peak() {
        return maxPeak;
    }

    public long frames() {
        return written;
    }

    /** Cierra los archivos y los deja con su nombre definitivo (&lt;pista&gt;.wav). */
    public void finish() throws IOException {
        for (int i = 0; i < writers.length; i++) {
            writers[i].close();
            File target = new File(files[i].getParentFile(), stems.get(i) + ".wav");
            if (target.exists() && !target.delete()) {
                throw new IOException("No se pudo reemplazar " + target.getName());
            }
            if (!files[i].renameTo(target)) {
                throw new IOException("No se pudo guardar " + target.getName());
            }
        }
    }

    /** Borra los archivos a medio escribir (si se canceló o falló). */
    public void abort() {
        for (int i = 0; i < writers.length; i++) {
            try {
                writers[i].close();
            } catch (IOException ignored) {
                // se borra igual
            }
            files[i].delete();
        }
    }

    /** picos.json: {"perSecond", "duration", "peaks": {pista: base64, ..., "mix": base64}}. */
    public JSONObject peaksJson() throws JSONException {
        JSONObject all = new JSONObject();
        for (int i = 0; i < stems.size(); i++) {
            all.put(stems.get(i), peaks[i].encode());
        }
        all.put("mix", mixPeaks.encode());
        JSONObject out = new JSONObject();
        out.put("perSecond", PEAKS_PER_SECOND);
        out.put("duration", Json.round(written / (double) sampleRate, 3));
        out.put("peaks", all);
        return out;
    }

    /** Pico absoluto por tramo de 1/25 s (uint8 0-255), como audio_io.compute_peaks. */
    static final class Peaks {
        private final int bucket;
        private byte[] values;
        private int count;
        private int filled;
        private float current;

        Peaks(int sampleRate, int capacity) {
            bucket = Math.max(1, sampleRate / PEAKS_PER_SECOND);
            values = new byte[Math.max(1, capacity)];
        }

        /** Agrega muestras; devuelve el pico del bloque. */
        float add(float[] l, float[] r, int n) {
            float blockPeak = 0f;
            for (int k = 0; k < n; k++) {
                float v = Math.max(Math.abs(l[k]), Math.abs(r[k]));
                if (v > current) {
                    current = v;
                }
                if (v > blockPeak) {
                    blockPeak = v;
                }
                if (++filled == bucket) {
                    push();
                }
            }
            return blockPeak;
        }

        private void push() {
            if (count == values.length) {
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            values[count++] = (byte) Math.round(Math.min(1f, current) * 255f);
            current = 0f;
            filled = 0;
        }

        String encode() {
            if (filled > 0 || count == 0) {
                push();
            }
            return Json.base64(values, count);
        }
    }
}
