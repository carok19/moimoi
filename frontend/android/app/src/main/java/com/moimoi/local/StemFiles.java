package com.moimoi.local;

import com.moimoi.analysis.Analyzer;
import com.moimoi.analysis.Dsp;
import java.io.File;
import java.io.IOException;

/** Las pistas guardadas (WAV) como las lee el análisis: mono a 22,05 kHz (features.to_mono_22k). */
public final class StemFiles implements Analyzer.Source {

    private final File dir;

    public StemFiles(File dir) {
        this.dir = dir;
    }

    private File file(String stem) throws IOException {
        File f = new File(dir, stem + ".wav");
        if (!f.isFile()) {
            throw new IOException("Falta la pista " + stem);
        }
        return f;
    }

    private static long monoLength(Wav.Reader r) throws IOException {
        if (r.sampleRate == 2 * Dsp.SR) {
            return (r.frames + 1) / 2;
        }
        if (r.sampleRate == Dsp.SR) {
            return r.frames;
        }
        throw new IOException("Frecuencia de muestreo inesperada: " + r.sampleRate);
    }

    @Override
    public int length(String stem) throws IOException {
        try (Wav.Reader r = new Wav.Reader(file(stem))) {
            return (int) monoLength(r);
        }
    }

    @Override
    public float[] load(String stem, int maxSamples) throws IOException {
        try (final Wav.Reader r = new Wav.Reader(file(stem))) {
            long length = monoLength(r);
            final int channels = r.channels;
            Dsp.MonoInput mono = new Dsp.MonoInput() {
                private float[] tmp = new float[0];

                @Override
                public void read(long start, float[] dst, int count) {
                    if (channels == 1) {
                        r.read(0, start, dst, 0, count);
                        return;
                    }
                    if (tmp.length < count) {
                        tmp = new float[count];
                    }
                    r.read(0, start, dst, 0, count);
                    for (int c = 1; c < channels; c++) {
                        r.read(c, start, tmp, 0, count);
                        for (int i = 0; i < count; i++) {
                            dst[i] = dst[i] + tmp[i];
                        }
                    }
                    float div = channels;
                    for (int i = 0; i < count; i++) {
                        dst[i] = dst[i] / div;
                    }
                }
            };
            if (r.sampleRate == Dsp.SR) {
                int n = (int) (maxSamples >= 0 ? Math.min(length, maxSamples) : length);
                float[] out = new float[n];
                mono.read(0, out, n);
                return out;
            }
            return Dsp.resampleHalf(mono, r.frames, maxSamples);
        }
    }
}
