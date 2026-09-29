package com.moimoi.local;

import com.moimoi.engine.DemucsSeparator;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;

/**
 * Audio crudo en float32 intercalado (canal 0, canal 1, …) en un archivo temporal: lo que sale del
 * decodificador y lo que lee el motor de separación. Se lee mapeado en memoria.
 */
public final class Pcm {

    private Pcm() {}

    public interface Cancel {
        boolean cancelled();
    }

    public static final class Writer implements Closeable {
        public final int channels;
        private final OutputStream out;
        private final ByteBuffer buffer = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
        private long frames;

        public Writer(File file, int channels) throws IOException {
            this.channels = channels;
            out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
        }

        /** Muestras intercaladas (count cuadros de `channels` canales). */
        public void writeInterleaved(float[] samples, int count) throws IOException {
            int n = count * channels;
            for (int i = 0; i < n; i++) {
                if (buffer.remaining() < 4) {
                    flushBuffer();
                }
                buffer.putFloat(samples[i]);
            }
            frames += count;
        }

        private void flushBuffer() throws IOException {
            out.write(buffer.array(), 0, buffer.position());
            buffer.clear();
        }

        public long frames() {
            return frames;
        }

        @Override
        public void close() throws IOException {
            flushBuffer();
            out.close();
        }
    }

    public static final class Reader implements Closeable, DemucsSeparator.AudioInput {
        public final int channels;
        public final int sampleRate;
        public final long frames;
        private final RandomAccessFile raf;
        private final FloatBuffer data;

        public Reader(File file, int channels, int sampleRate) throws IOException {
            this.channels = channels;
            this.sampleRate = sampleRate;
            raf = new RandomAccessFile(file, "r");
            long bytes = raf.length();
            frames = bytes / (4L * channels);
            if (frames * channels > Integer.MAX_VALUE) {
                raf.close();
                throw new IOException("Audio demasiado largo");
            }
            data = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, frames * channels * 4L)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        }

        @Override
        public int length() {
            return (int) frames;
        }

        @Override
        public void read(int channel, int start, float[] dst, int count) {
            read(channel, start, dst, 0, count);
        }

        public void read(int channel, long start, float[] dst, int offset, int count) {
            int c = Math.min(channel, channels - 1);
            for (int i = 0; i < count; i++) {
                long f = start + i;
                dst[offset + i] = f < 0 || f >= frames ? 0f : data.get((int) (f * channels + c));
            }
        }

        public float peak() {
            float peak = 0f;
            int n = (int) (frames * channels);
            for (int i = 0; i < n; i++) {
                peak = Math.max(peak, Math.abs(data.get(i)));
            }
            return peak;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static double bessel0(double x) {
        double sum = 1, term = 1;
        for (int k = 1; k < 50; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < 1e-12 * sum) {
                break;
            }
        }
        return sum;
    }

    /**
     * Pasa `src` a estéreo y a `dstRate` Hz (filtro sinc con ventana de Kaiser, polifásico: exacto
     * para las frecuencias habituales, p. ej. 48000 -> 44100). Mono se duplica; con más de dos
     * canales se usan los dos primeros (izquierdo y derecho).
     */
    public static long toStereo(Reader src, int dstRate, File dst, Cancel cancel) throws IOException {
        int srcRate = src.sampleRate;
        int g = gcd(srcRate, dstRate);
        int up = dstRate / g; // L
        int down = srcRate / g; // M
        try (Writer out = new Writer(dst, 2)) {
            int block = 1 << 15;
            float[] l = new float[block];
            float[] r = new float[block];
            float[] inter = new float[block * 2];
            if (up == 1 && down == 1) {
                for (long start = 0; start < src.frames; start += block) {
                    if (cancel != null && cancel.cancelled()) {
                        throw new DemucsSeparator.CancelledException();
                    }
                    int n = (int) Math.min(block, src.frames - start);
                    src.read(0, start, l, 0, n);
                    src.read(1, start, r, 0, n);
                    for (int i = 0; i < n; i++) {
                        inter[2 * i] = l[i];
                        inter[2 * i + 1] = r[i];
                    }
                    out.writeInterleaved(inter, n);
                }
                return out.frames();
            }
            // Frecuencias "raras" (p. ej. 44056 Hz): la fase se redondea a 1/2048 de muestra (inaudible).
            final boolean exact = up <= 4096;
            final int phases = exact ? up : 2048;
            final double ratio = srcRate / (double) dstRate;
            double fc = 0.95 * Math.min(1.0, dstRate / (double) srcRate); // corte, en fracción del Nyquist de entrada
            int zeroCrossings = 24;
            int half = (int) Math.ceil(zeroCrossings / fc); // taps a cada lado, en muestras de entrada
            int taps = 2 * half;
            double beta = 8.0;
            double i0b = bessel0(beta);
            float[][] table = new float[phases][taps];
            for (int p = 0; p < phases; p++) {
                double frac = p / (double) phases;
                double sum = 0;
                for (int k = 0; k < taps; k++) {
                    double d = (k - half + 1) - frac; // distancia a la muestra de entrada
                    double x = fc * d;
                    double sinc = Math.abs(x) < 1e-12 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                    double w = Math.abs(d) >= half ? 0.0 : bessel0(beta * Math.sqrt(1 - (d / half) * (d / half))) / i0b;
                    double h = fc * sinc * w;
                    table[p][k] = (float) h;
                    sum += h;
                }
                for (int k = 0; k < taps; k++) {
                    table[p][k] /= (float) sum; // ganancia 1 en continua
                }
            }
            long outFrames = exact ? (src.frames * up + down - 1) / down : (long) Math.ceil(src.frames / ratio);
            float[] winL = new float[block + taps + 2];
            float[] winR = new float[block + taps + 2];
            float[] outL = new float[block];
            float[] outR = new float[block];
            long[] base = new long[block];
            int[] phase = new int[block];
            for (long n0 = 0; n0 < outFrames; n0 += block) {
                if (cancel != null && cancel.cancelled()) {
                    throw new DemucsSeparator.CancelledException();
                }
                int count = (int) Math.min(block, outFrames - n0);
                for (int i = 0; i < count; i++) {
                    long n = n0 + i;
                    if (exact) {
                        long pos = n * down;
                        base[i] = pos / up;
                        phase[i] = (int) (pos - base[i] * up);
                    } else {
                        double x = n * ratio;
                        long b = (long) Math.floor(x);
                        int ph = (int) Math.round((x - b) * phases);
                        if (ph == phases) {
                            b++;
                            ph = 0;
                        }
                        base[i] = b;
                        phase[i] = ph;
                    }
                }
                long firstIn = base[0] - half + 1;
                long lastIn = base[count - 1] + half;
                int span = (int) (lastIn - firstIn + 1);
                if (span > winL.length) {
                    winL = new float[span];
                    winR = new float[span];
                }
                src.read(0, firstIn, winL, 0, span);
                src.read(1, firstIn, winR, 0, span);
                for (int i = 0; i < count; i++) {
                    int start = (int) (base[i] - half + 1 - firstIn);
                    float[] h = table[phase[i]];
                    float accL = 0f, accR = 0f;
                    for (int k = 0; k < taps; k++) {
                        accL += winL[start + k] * h[k];
                        accR += winR[start + k] * h[k];
                    }
                    outL[i] = accL;
                    outR[i] = accR;
                }
                for (int i = 0; i < count; i++) {
                    inter[2 * i] = outL[i];
                    inter[2 * i + 1] = outR[i];
                }
                out.writeInterleaved(inter, count);
            }
            return out.frames();
        }
    }

    /** Copia float32 intercalado desde un búfer de bytes de PCM 16 bits (salida típica de MediaCodec). */
    public static int pcm16ToFloat(ByteBuffer pcm, float[] dst) {
        pcm.order(ByteOrder.LITTLE_ENDIAN);
        int n = pcm.remaining() / 2;
        for (int i = 0; i < n; i++) {
            dst[i] = pcm.getShort() / 32768f;
        }
        return n;
    }
}
