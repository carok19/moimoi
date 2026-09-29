package com.moimoi.analysis;

/**
 * Afinación respecto de La 440, en fracciones de semitono (-0.5 a 0.5), como
 * librosa.estimate_tuning(y, sr, bins_per_octave=12): picos del espectro (piptrack) entre 150 y
 * 4000 Hz, y el desvío más frecuente respecto de la nota temperada (histograma de 0.01).
 */
public final class Tuning {

    private Tuning() {}

    /** features.estimate_tuning: con los primeros 90 s alcanza. */
    public static double estimate(float[] y) {
        boolean any = false;
        for (float v : y) {
            if (Math.abs(v) > 1e-4f) {
                any = true;
                break;
            }
        }
        if (!any) {
            return 0.0;
        }
        int n = Math.min(y.length, Dsp.SR * 90);
        float[] segment = n == y.length ? y : java.util.Arrays.copyOf(y, n);
        return estimate(segment, Dsp.SR);
    }

    static double estimate(float[] y, int sr) {
        final int nFft = 2048;
        final int hop = nFft / 4;
        final int bins = nFft / 2 + 1;
        final double fmin = 150.0;
        final double fmax = Math.min(4000.0, sr / 2.0);
        float[] window = Dsp.hann(nFft, true);
        final java.util.List<float[]> found = new java.util.ArrayList<>(); // {pitch, mag}
        final float[] s = new float[bins];
        Spectrum.power(y, nFft, hop, window, (t, power) -> {
            float max = 0f;
            for (int k = 0; k < bins; k++) {
                s[k] = (float) Math.sqrt(power[k]);
                max = Math.max(max, s[k]);
            }
            float ref = 0.1f * max;
            for (int k = 1; k < bins; k++) {
                double f = k * (double) sr / nFft;
                if (!(fmin <= f && f < fmax)) {
                    continue;
                }
                // máximo local de S * (S > ref)
                float x = s[k] > ref ? s[k] : 0f;
                float prev = s[k - 1] > ref ? s[k - 1] : 0f;
                boolean isMax;
                if (k < bins - 1) {
                    float next = s[k + 1] > ref ? s[k + 1] : 0f;
                    isMax = x > prev && x >= next;
                } else {
                    isMax = x > prev;
                }
                if (!isMax) {
                    continue;
                }
                float shift = 0f;
                float avg;
                if (k < bins - 1) {
                    float a = s[k + 1] + s[k - 1] - 2 * s[k];
                    float b = (s[k + 1] - s[k - 1]) / 2;
                    shift = Math.abs(b) >= Math.abs(a) ? 0f : -b / a;
                    avg = (s[k + 1] - s[k - 1]) / 2f;
                } else {
                    avg = s[k] - s[k - 1];
                }
                float pitch = (float) ((k + shift) * (double) sr / nFft);
                float mag = s[k] + 0.5f * avg * shift;
                found.add(new float[] {pitch, mag});
            }
        });
        int count = 0;
        for (float[] p : found) {
            if (p[0] > 0) {
                count++;
            }
        }
        double threshold = 0.0;
        if (count > 0) {
            double[] mags = new double[count];
            int i = 0;
            for (float[] p : found) {
                if (p[0] > 0) {
                    mags[i++] = p[1];
                }
            }
            threshold = Dsp.median(mags);
        }
        java.util.List<Float> freqs = new java.util.ArrayList<>();
        for (float[] p : found) {
            if (p[0] > 0 && p[1] >= threshold) {
                freqs.add(p[0]);
            }
        }
        return pitchTuning(freqs, 0.01);
    }

    /** librosa.pitch_tuning(frequencies, resolution, bins_per_octave=12). */
    static double pitchTuning(java.util.List<Float> freqs, double resolution) {
        if (freqs.isEmpty()) {
            return 0.0;
        }
        int nBins = (int) Math.ceil(1.0 / resolution);
        double[] edges = new double[nBins + 1];
        double step = 1.0 / nBins;
        for (int i = 0; i <= nBins; i++) {
            edges[i] = -0.5 + i * step;
        }
        edges[nBins] = 0.5;
        int[] counts = new int[nBins];
        for (float f : freqs) {
            if (!(f > 0)) {
                continue;
            }
            // hz_to_octs(f) = log2(f / (440 / 16)); residuo en semitonos respecto de la nota
            float octs = (float) (Math.log(f / (440.0 / 16)) / Math.log(2));
            float value = 12f * octs;
            float residual = (float) (value - Math.floor(value));
            if (residual >= 0.5f) {
                residual -= 1.0f;
            }
            int idx = (int) ((residual - edges[0]) * (nBins / (edges[nBins] - edges[0])));
            if (idx == nBins) {
                idx--;
            }
            if (idx > 0 && residual < edges[idx]) {
                idx--;
            }
            if (idx < nBins - 1 && residual >= edges[idx + 1]) {
                idx++;
            }
            if (idx >= 0 && idx < nBins) {
                counts[idx]++;
            }
        }
        int best = 0;
        for (int i = 1; i < nBins; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return edges[best];
    }
}
