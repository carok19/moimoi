package com.moimoi.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tonalidad (con cambios de tonalidad) y acordes (analysis/harmony.py): croma CQT promediado por
 * pulso, comparado con plantillas de acordes y decidido con Viterbi, con un pequeño favoritismo
 * por los acordes de la tonalidad. El bajo separado ayuda con acordes parecidos e inversiones.
 */
final class Harmony {

    private Harmony() {}

    static final double KAPPA = 10.0;
    static final double KEY_BONUS = 0.6;
    static final double BASS_WEIGHT = 2.0;
    static final double KEY_KAPPA = 8.0;
    static final double KEY_STAY = 0.995;
    static final int MIN_KEY_REGION_BEATS = 24;

    /** Tonalidad como índice 0-23: 0-11 mayores (tónica = índice), 12-23 menores. */
    static int keyIndex(int tonic, boolean major) {
        return (major ? 0 : 12) + tonic;
    }

    static int tonicOf(int key) {
        return key % 12;
    }

    static boolean majorOf(int key) {
        return key < 12;
    }

    static int relative(int key) {
        int tonic = tonicOf(key);
        return majorOf(key) ? keyIndex((tonic + 9) % 12, false) : keyIndex((tonic + 3) % 12, true);
    }

    static final class Chord {
        double start;
        double end;
        int root;      // -1 = sin acorde
        int quality;   // índice en Music.QUALITIES; -1 = "N"
        Integer bass;
        String name;
        final List<Integer> segs = new ArrayList<>();
    }

    static final class KeyChange {
        double time;
        int key;
    }

    static final class Result {
        int key;
        double confidence;
        int keyStart;
        final List<KeyChange> keyChanges = new ArrayList<>();
        double a4;
        int cents;
        List<Chord> chords;
    }

    // ---- tramos ---------------------------------------------------------------------------------

    static final class Segments {
        int[] frames;
        double[] bounds;
        int[] positions;
    }

    /** _segments: un tramo por pulso (más el previo al primer pulso) y su posición en el compás. */
    static Segments segments(Rhythm rhythm, double duration, int nFrames) {
        double[] times;
        int[] positionOf;
        if (rhythm.beats.length >= 2) {
            int meter = rhythm.beatsPerBar > 0 ? rhythm.beatsPerBar : 4;
            int phase = rhythm.downbeatPhase;
            times = rhythm.beats;
            positionOf = new int[times.length];
            for (int j = 0; j < times.length; j++) {
                positionOf[j] = Math.floorMod(j - phase, meter);
            }
        } else {
            double stop = Math.max(duration, 0.5);
            int count = (int) Math.max(0, Math.ceil((stop - 0.5) / 0.5));
            times = new double[count];
            positionOf = new int[count];
            for (int j = 0; j < count; j++) {
                times[j] = 0.5 + j * 0.5;
                positionOf[j] = j % 8 == 0 ? 0 : 1; // sin pulso: "compás" de 4 s
            }
        }
        List<Integer> frames = new ArrayList<>();
        List<Double> kept = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        // Los pulsos vienen ordenados: np.unique se queda con el primero de cada cuadro.
        for (int j = 0; j < times.length; j++) {
            int f = (int) Math.rint(times[j] * Dsp.SR / Dsp.HOP);
            if (f <= 0 || f >= nFrames || f == previous) {
                continue;
            }
            if (f < previous) {
                return segmentsUnsorted(times, positionOf, duration, nFrames);
            }
            previous = f;
            frames.add(f);
            kept.add(times[j]);
            positions.add(positionOf[j]);
        }
        return build(frames, kept, positions, duration);
    }

    private static Segments segmentsUnsorted(double[] times, int[] positionOf, double duration, int nFrames) {
        java.util.TreeMap<Integer, Integer> first = new java.util.TreeMap<>();
        for (int j = 0; j < times.length; j++) {
            int f = (int) Math.rint(times[j] * Dsp.SR / Dsp.HOP);
            if (f > 0 && f < nFrames && !first.containsKey(f)) {
                first.put(f, j);
            }
        }
        List<Integer> frames = new ArrayList<>();
        List<Double> kept = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : first.entrySet()) {
            frames.add(e.getKey());
            kept.add(times[e.getValue()]);
            positions.add(positionOf[e.getValue()]);
        }
        return build(frames, kept, positions, duration);
    }

    private static Segments build(List<Integer> frames, List<Double> times, List<Integer> positions, double duration) {
        Segments s = new Segments();
        s.frames = new int[frames.size()];
        s.bounds = new double[frames.size() + 2];
        s.positions = new int[frames.size() + 1];
        s.positions[0] = -1;
        for (int i = 0; i < frames.size(); i++) {
            s.frames[i] = frames.get(i);
            s.bounds[i + 1] = times.get(i);
            s.positions[i + 1] = positions.get(i);
        }
        s.bounds[frames.size() + 1] = duration;
        return s;
    }

    // ---- utilidades -------------------------------------------------------------------------------

    static double[][] unitColumns(double[][] x) {
        int rows = x.length, cols = x[0].length;
        double[][] out = new double[rows][cols];
        for (int s = 0; s < cols; s++) {
            double sq = 0;
            for (int r = 0; r < rows; r++) {
                sq += x[r][s] * x[r][s];
            }
            double d = Math.max(Math.sqrt(sq), 1e-9);
            for (int r = 0; r < rows; r++) {
                out[r][s] = x[r][s] / d;
            }
        }
        return out;
    }

    static double[] columnSums(double[][] x) {
        double[] out = new double[x[0].length];
        for (double[] row : x) {
            for (int s = 0; s < out.length; s++) {
                out[s] += row[s];
            }
        }
        return out;
    }

    static double positiveMedian(double[] v) {
        int count = 0;
        for (double x : v) {
            if (x > 0) {
                count++;
            }
        }
        if (count == 0) {
            return 1.0;
        }
        double[] p = new double[count];
        int c = 0;
        for (double x : v) {
            if (x > 0) {
                p[c++] = x;
            }
        }
        return Dsp.median(p);
    }

    /**
     * _viterbi: transición "quedarse vs. cambiar a cualquier otro" que varía por paso.
     * emission [estado][paso]; logStay/logSwitch por paso (la transición que entra al paso).
     */
    static int[] viterbi(double[][] emission, double[] logStay, double[] logSwitch) {
        int states = emission.length, steps = emission[0].length;
        double[] score = new double[states];
        for (int k = 0; k < states; k++) {
            score[k] = emission[k][0];
        }
        int[][] back = new int[steps][];
        double[] next = new double[states];
        for (int s = 1; s < steps; s++) {
            int bestPrev = Dsp.argmax(score);
            double sw = score[bestPrev] + logSwitch[s];
            int[] b = new int[states];
            for (int k = 0; k < states; k++) {
                double stay = score[k] + logStay[s];
                if (k != bestPrev && sw > stay) {
                    b[k] = bestPrev;
                    next[k] = sw;
                } else {
                    b[k] = k;
                    next[k] = stay;
                }
                next[k] += emission[k][s];
            }
            back[s] = b;
            double[] t = score;
            score = next;
            next = t;
        }
        int[] path = new int[steps];
        path[steps - 1] = Dsp.argmax(score);
        for (int s = steps - 1; s > 0; s--) {
            path[s - 1] = back[s][path[s]];
        }
        return path;
    }

    // ---- tonalidad --------------------------------------------------------------------------------

    /** track_keys: tonalidad de cada tramo, con suavizado fuerte (solo modulaciones reales). */
    static int[] trackKeys(double[][] harm, double[] durations) {
        Music.Templates profiles = Music.keyProfiles();
        int n = harm[0].length;
        double[][] cumsum = new double[12][n + 1];
        for (int c = 0; c < 12; c++) {
            double acc = 0;
            for (int s = 0; s < n; s++) {
                acc += harm[c][s] * durations[s];
                cumsum[c][s + 1] = acc;
            }
        }
        int half = 16;
        int labels = profiles.vectors.length;
        double[][] emission = new double[labels][n];
        double[] window = new double[12];
        for (int s = 0; s < n; s++) {
            int lo = Math.max(0, s - half), hi = Math.min(n, s + half + 1);
            double mean = 0;
            for (int c = 0; c < 12; c++) {
                window[c] = cumsum[c][hi] - cumsum[c][lo];
                mean += window[c];
            }
            mean /= 12;
            double sq = 0;
            for (int c = 0; c < 12; c++) {
                window[c] -= mean;
                sq += window[c] * window[c];
            }
            double norm = Math.sqrt(sq);
            if (norm > 1e-9) {
                for (int k = 0; k < labels; k++) {
                    double dot = 0;
                    for (int c = 0; c < 12; c++) {
                        dot += profiles.vectors[k][c] * (window[c] / norm);
                    }
                    emission[k][s] = KEY_KAPPA * dot;
                }
            }
        }
        double[] logStay = new double[n];
        double[] logSwitch = new double[n];
        java.util.Arrays.fill(logStay, Math.log(KEY_STAY));
        java.util.Arrays.fill(logSwitch, Math.log((1 - KEY_STAY) / (labels - 1)));
        int[] path = viterbi(emission, logStay, logSwitch);
        int[] keys = new int[n];
        for (int s = 0; s < n; s++) {
            int[] label = profiles.labels[path[s]];
            keys[s] = keyIndex(label[0], label[1] == 1);
        }
        return keys;
    }

    /** _chord_stats: qué tan bien explican los acordes a una tonalidad (0-1 aprox.). */
    static double chordStats(List<Chord> chords, int key) {
        int tonic = tonicOf(key);
        boolean major = majorOf(key);
        double total = 0, diatonic = 0, tonicTime = 0;
        int third = Music.INTERVALS[major ? 0 : 1][1];
        Chord firstReal = null, lastReal = null;
        for (Chord c : chords) {
            if (c.quality < 0) {
                continue;
            }
            double span = c.end - c.start;
            total += span;
            if (Music.isDiatonic(c.root, c.quality, tonic, major)) {
                diatonic += span;
            }
            if (c.root == tonic && Music.INTERVALS[c.quality][1] == third) {
                tonicTime += span;
            }
            if (firstReal == null) {
                firstReal = c;
            }
            lastReal = c;
        }
        if (total == 0) {
            total = 1.0;
        }
        double ends = lastReal != null && lastReal.root == tonic ? 1.0 : 0.0;
        double starts = firstReal != null && firstReal.root == tonic ? 1.0 : 0.0;
        return 0.6 * diatonic / total + 0.5 * tonicTime / total + 0.15 * ends + 0.1 * starts;
    }

    // ---- acordes ------------------------------------------------------------------------------------

    private static boolean[][] diatonicTable;

    /** [tonalidad 0-23][acorde 0-95]: ¿el acorde es de la tonalidad? */
    static synchronized boolean[][] diatonic(Music.Templates templates) {
        if (diatonicTable == null) {
            boolean[][] t = new boolean[24][templates.labels.length];
            for (int key = 0; key < 24; key++) {
                for (int k = 0; k < templates.labels.length; k++) {
                    int[] label = templates.labels[k];
                    t[key][k] = Music.isDiatonic(label[0], label[1], tonicOf(key), majorOf(key));
                }
            }
            diatonicTable = t;
        }
        return diatonicTable;
    }

    static List<Chord> decodeChords(double[][] treble, double[][] bass, double[] bounds, int[] keys, int[] positions) {
        Music.Templates templates = Music.chordTemplates();
        boolean[][] diatonic = diatonic(templates);
        int nChords = templates.labels.length;
        int n = treble[0].length;
        double[] energy = columnSums(treble);
        double medianEnergy = positiveMedian(energy);
        double[][] unitTreble = unitColumns(treble);

        double[] bassSum = columnSums(bass);
        double[][] bassDist = new double[12][n];
        for (int s = 0; s < n; s++) {
            double d = Math.max(bassSum[s], 1e-9);
            double colSum = 0;
            for (int c = 0; c < 12; c++) {
                bassDist[c][s] = Math.max(bass[c][s] / d - 1.0 / 12, 0);
                colSum += bassDist[c][s];
            }
            double d2 = Math.max(colSum, 1e-9);
            for (int c = 0; c < 12; c++) {
                bassDist[c][s] /= d2;
            }
        }
        double bassMedian = positiveMedian(bassSum);
        double[] bassConf = new double[n];
        for (int s = 0; s < n; s++) {
            bassConf[s] = Math.min(1, Math.max(0, bassSum[s] / Math.max(bassMedian, 1e-9)));
        }

        double[][] emission = new double[nChords + 1][n];
        for (int k = 0; k < nChords; k++) {
            int root = templates.labels[k][0], quality = templates.labels[k][1];
            int[] intervals = Music.INTERVALS[quality];
            double[] tpl = templates.vectors[k];
            double prior = Music.PRIOR[quality];
            for (int s = 0; s < n; s++) {
                double sim = 0;
                for (int c = 0; c < 12; c++) {
                    sim += tpl[c] * unitTreble[c][s];
                }
                double fit = bassDist[root % 12][s];
                for (int i = 1; i < 3 && i < intervals.length; i++) {
                    fit += 0.45 * bassDist[(root + intervals[i]) % 12][s];
                }
                double e = KAPPA * sim + prior + BASS_WEIGHT * fit * bassConf[s];
                if (diatonic[keys[s]][k]) {
                    e += KEY_BONUS;
                }
                emission[k][s] = e;
            }
        }
        // Estado "sin acorde" (silencio o solo percusión).
        for (int s = 0; s < n; s++) {
            double rel = energy[s] / Math.max(medianEnergy, 1e-9);
            emission[nChords][s] = KAPPA * (0.6 - 0.35 * Math.min(1, Math.max(0, rel / 0.3)));
        }
        int states = nChords + 1;
        double[] logStay = new double[n];
        double[] logSwitch = new double[n];
        for (int s = 0; s < n; s++) {
            int p = positions[s];
            double change = p == 0 ? 0.35 : (p < 0 ? 0.2 : 0.06);
            if (p == 2) {
                change = 0.15;
            }
            logStay[s] = Math.log(1 - change);
            logSwitch[s] = Math.log(change / (states - 1));
        }
        int[] path = viterbi(emission, logStay, logSwitch);

        List<Chord> chords = new ArrayList<>();
        for (int s = 0; s < path.length; s++) {
            double start = bounds[s], end = bounds[s + 1];
            if (end <= start) {
                continue;
            }
            int root, quality;
            if (path[s] == nChords) {
                root = -1;
                quality = -1;
            } else {
                root = templates.labels[path[s]][0];
                quality = templates.labels[path[s]][1];
            }
            Chord last = chords.isEmpty() ? null : chords.get(chords.size() - 1);
            if (last != null && last.root == root && last.quality == quality) {
                last.end = end;
                last.segs.add(s);
            } else {
                Chord c = new Chord();
                c.start = start;
                c.end = end;
                c.root = root;
                c.quality = quality;
                c.segs.add(s);
                chords.add(c);
            }
        }
        // Nota del bajo de cada acorde -> inversiones (G/B, C/E, D/F#...).
        for (Chord chord : chords) {
            chord.bass = null;
            if (chord.quality < 0) {
                continue;
            }
            double[] dist = new double[12];
            double conf = 0;
            for (int s : chord.segs) {
                for (int c = 0; c < 12; c++) {
                    dist[c] += bassDist[c][s];
                }
                conf += bassConf[s];
            }
            for (int c = 0; c < 12; c++) {
                dist[c] /= chord.segs.size();
            }
            conf /= chord.segs.size();
            int top = Dsp.argmax(dist);
            int[] intervals = Music.INTERVALS[chord.quality];
            boolean isTone = false;
            for (int i = 1; i < intervals.length; i++) {
                if ((chord.root + intervals[i]) % 12 == top) {
                    isTone = true;
                }
            }
            if (conf > 0.4 && top != chord.root && isTone && dist[top] > 0.45 && dist[chord.root] < 0.5 * dist[top]) {
                chord.bass = top;
            }
        }
        return chords;
    }

    // ---- análisis completo ----------------------------------------------------------------------------

    private static final class Region {
        int key;
        int start;
        int end;

        Region(int key, int start, int end) {
            this.key = key;
            this.start = start;
            this.end = end;
        }
    }

    static Result analyze(double[][] trebleChroma, double[][] bassChroma, Rhythm rhythm, double duration, double tuning) {
        Segments seg = segments(rhythm, duration, trebleChroma[0].length);
        int total = trebleChroma[0].length;
        double[][] treble = Beats.syncMedian(trebleChroma, seg.frames, total);
        double[][] bass = Beats.syncMedian(bassChroma, seg.frames, total);
        int n = treble[0].length;
        double[] bounds = seg.bounds;
        if (bounds.length != n + 1 || seg.positions.length != n) {
            throw new IllegalStateException("tramos inconsistentes: " + bounds.length + " " + n);
        }
        double[] durations = new double[n];
        for (int s = 0; s < n; s++) {
            durations[s] = Math.max(bounds[s + 1] - bounds[s], 1e-3);
        }
        double[][] ut = unitColumns(treble);
        double[][] ub = unitColumns(bass);
        double[] trebleSum = columnSums(treble);
        double trebleMedian = Math.max(Dsp.median(trebleSum), 1e-9);
        double[][] harm = new double[12][n];
        for (int s = 0; s < n; s++) {
            double w = Math.min(1, Math.max(0, trebleSum[s] / trebleMedian));
            for (int c = 0; c < 12; c++) {
                harm[c][s] = (ut[c][s] + 0.6 * ub[c][s]) * w;
            }
        }
        int[] keys = trackKeys(harm, durations);

        // Primera pasada de acordes -> refinar la tonalidad (mayor vs. relativa menor).
        List<Chord> chords = decodeChords(treble, bass, bounds, keys, seg.positions);

        List<Region> regions = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            Region last = regions.isEmpty() ? null : regions.get(regions.size() - 1);
            if (last != null && last.key == keys[s]) {
                last.end = s + 1;
            } else {
                regions.add(new Region(keys[s], s, s + 1));
            }
        }
        List<Region> merged = new ArrayList<>();
        for (Region r : regions) {
            if (!merged.isEmpty() && (r.end - r.start) < MIN_KEY_REGION_BEATS) {
                merged.get(merged.size() - 1).end = r.end;
            } else {
                merged.add(new Region(r.key, r.start, r.end));
            }
        }
        if (merged.size() > 1 && (merged.get(0).end - merged.get(0).start) < MIN_KEY_REGION_BEATS) {
            merged.get(1).start = merged.get(0).start;
            merged.remove(0);
        }
        // Mayor o relativa menor: deciden los acordes de cada región.
        for (Region r : merged) {
            double t0 = bounds[r.start], t1 = bounds[Math.min(r.end, n)];
            List<Chord> inRegion = new ArrayList<>();
            for (Chord c : chords) {
                if (c.end > t0 && c.start < t1) {
                    inRegion.add(c);
                }
            }
            int rel = relative(r.key);
            if (chordStats(inRegion, rel) > chordStats(inRegion, r.key) + 0.05) {
                r.key = rel;
            }
        }
        List<Region> finalRegions = new ArrayList<>();
        for (Region r : merged) {
            Region last = finalRegions.isEmpty() ? null : finalRegions.get(finalRegions.size() - 1);
            if (last != null && last.key == r.key) {
                last.end = r.end;
            } else {
                finalRegions.add(r);
            }
        }
        int[] finalKeys = new int[n];
        java.util.Arrays.fill(finalKeys, -1);
        for (Region r : finalRegions) {
            for (int s = r.start; s < Math.min(r.end, n); s++) {
                finalKeys[s] = r.key;
            }
        }
        int fallback = finalRegions.get(finalRegions.size() - 1).key;
        for (int s = 0; s < n; s++) {
            if (finalKeys[s] < 0) {
                finalKeys[s] = fallback;
            }
        }
        chords = decodeChords(treble, bass, bounds, finalKeys, seg.positions);

        // Tonalidad principal = la que más dura.
        Map<Integer, Double> totals = new LinkedHashMap<>();
        for (Region r : finalRegions) {
            double span = bounds[Math.min(r.end, n)] - bounds[r.start];
            Double previous = totals.get(r.key);
            totals.put(r.key, (previous == null ? 0.0 : previous) + span);
        }
        int key = -1;
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, Double> e : totals.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                key = e.getKey();
            }
        }
        Result result = new Result();
        result.key = key;
        result.confidence = Math.min(1, Math.max(0, chordStats(chords, key)));
        result.keyStart = finalRegions.get(0).key;
        boolean flats = Music.usesFlats(tonicOf(key), majorOf(key));
        for (Chord c : chords) {
            c.name = c.quality < 0 ? "N" : Music.chordName(c.root, c.quality, c.bass, flats);
            c.start = Dsp.round(c.start, 3);
            c.end = Dsp.round(c.end, 3);
        }
        for (int i = 1; i < finalRegions.size(); i++) {
            KeyChange change = new KeyChange();
            change.time = Dsp.round(bounds[finalRegions.get(i).start], 3);
            change.key = finalRegions.get(i).key;
            result.keyChanges.add(change);
        }
        result.a4 = Dsp.round(440.0 * Math.pow(2, tuning / 12.0), 1);
        result.cents = (int) Math.rint(tuning * 100);
        result.chords = chords;
        return result;
    }
}
