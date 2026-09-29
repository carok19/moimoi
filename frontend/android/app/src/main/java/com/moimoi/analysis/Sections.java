package com.moimoi.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Estructura de la canción: intro, versos, coros, puente, final (analysis/sections.py). Cada
 * compás se describe con armonía, timbre, volumen y voz; se buscan los cortes fuertes, se arman
 * frases de 4 compases, las frases parecidas reciben la misma letra y se nombran las partes.
 */
final class Sections {

    private Sections() {}

    static final int PHRASE_BARS = 4;
    static final double SAME_PHRASE = 0.78;
    static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    static final class Section {
        double start;
        double end;
        String label;
        String group;
        int bars;
        boolean vocals;
    }

    /** Datos de la canción que usan las secciones. */
    static final class Input {
        double duration;
        double[] downbeats;
        double[][] trebleChroma; // [12][cuadro]
        float[][] mfcc;          // [cuadro][13] de la mezcla
        double[] mixDb;          // rms_db(mezcla, 2048, HOP)
        double[] vocalsDb;       // rms_db(voz, 2048, HOP) o null
        double[] tempoStarts = new double[0]; // dónde empieza cada tramo de tempo (menos el primero)
    }

    // ---- compases ---------------------------------------------------------------------------------

    static double[] barBounds(double[] downbeats, double duration) {
        List<Double> down = new ArrayList<>();
        for (double t : downbeats) {
            if (t >= 0 && t < duration) {
                down.add(t);
            }
        }
        List<Double> bounds = new ArrayList<>();
        if (down.size() >= 6) {
            bounds.add(0.0);
            for (double t : down) {
                if (t > 0.25) {
                    bounds.add(t);
                }
            }
            bounds.add(duration);
        } else {
            int count = (int) Math.max(0, Math.ceil(duration / 4.0));
            for (int i = 0; i < count; i++) {
                bounds.add(i * 4.0);
            }
            bounds.add(duration);
        }
        TreeSet<Double> set = new TreeSet<>();
        for (double b : bounds) {
            set.add(Dsp.round(b, 3) + 0.0);
        }
        double[] out = new double[set.size()];
        int i = 0;
        for (double b : set) {
            out[i++] = b;
        }
        if (out.length > 2 && out[out.length - 1] - out[out.length - 2] < 1.0) {
            double[] cut = new double[out.length - 1];
            System.arraycopy(out, 0, cut, 0, out.length - 2);
            cut[out.length - 2] = out[out.length - 1];
            out = cut;
        }
        return out;
    }

    /** Estandariza cada columna (media 0, desvío 1). */
    static double[][] zscore(double[][] x) {
        int n = x.length, d = x[0].length;
        double[][] out = new double[n][d];
        for (int j = 0; j < d; j++) {
            double mean = 0;
            for (double[] row : x) {
                mean += row[j];
            }
            mean /= n;
            double sq = 0;
            for (double[] row : x) {
                sq += (row[j] - mean) * (row[j] - mean);
            }
            double std = Math.sqrt(sq / n);
            double div = std > 1e-9 ? std : 1.0;
            for (int i = 0; i < n; i++) {
                out[i][j] = (x[i][j] - mean) / div;
            }
        }
        return out;
    }

    static double[][] column(double[] v) {
        double[][] out = new double[v.length][1];
        for (int i = 0; i < v.length; i++) {
            out[i][0] = v[i];
        }
        return out;
    }

    static double[] checkerboardNovelty(double[][] feats, int half) {
        int n = feats.length;
        double[][] dist = new double[n][n];
        double[] all = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double sq = 0;
                for (int k = 0; k < feats[i].length; k++) {
                    double d = feats[i][k] - feats[j][k];
                    sq += d * d;
                }
                dist[i][j] = Math.sqrt(sq);
                all[i * n + j] = dist[i][j];
            }
        }
        double sigma = Dsp.median(all);
        if (sigma == 0 || Double.isNaN(sigma)) {
            sigma = 1.0;
        }
        double[][] ssm = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double r = dist[i][j] / sigma;
                ssm[i][j] = Math.exp(-(r * r));
            }
        }
        int size = 2 * half;
        double[] sign = new double[size];
        double[] gauss = new double[size];
        for (int t = 0; t < size; t++) {
            double axis = -half + t + 0.5;
            sign[t] = Math.signum(axis);
            double g = axis / (half * 0.7);
            gauss[t] = Math.exp(-0.5 * (g * g));
        }
        double[] novelty = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int a = 0; a < size; a++) {
                int r = Math.min(n - 1, Math.max(0, i + a - half));
                for (int b = 0; b < size; b++) {
                    int c = Math.min(n - 1, Math.max(0, i + b - half));
                    sum += ssm[r][c] * ((sign[a] * sign[b]) * (gauss[a] * gauss[b]));
                }
            }
            novelty[i] = Math.max(0.0, sum);
        }
        return novelty;
    }

    static List<Integer> strongCuts(double[] novelty, double[] vocal, int nBars) {
        TreeSet<Integer> cuts = new TreeSet<>();
        boolean[] singing = new boolean[vocal.length];
        for (int i = 0; i < vocal.length; i++) {
            singing[i] = vocal[i] > 0.35;
        }
        for (int i = 1; i < nBars; i++) {
            int b0 = Math.max(0, i - 2), a1 = Math.min(singing.length, i + 2);
            if (i - b0 <= 0 || a1 - i <= 0) {
                continue;
            }
            Boolean before = uniform(singing, b0, i);
            Boolean after = uniform(singing, i, a1);
            if (before != null && after != null && !before.equals(after)) {
                cuts.add(i);
            }
        }
        if (novelty.length > 2) {
            double threshold = Dsp.mean(novelty) + 1.5 * Dsp.std(novelty);
            for (int i = 2; i < nBars - 1; i++) {
                if (novelty[i] > threshold && novelty[i] >= novelty[i - 1] && novelty[i] >= novelty[i + 1]) {
                    boolean far = true;
                    for (int c : cuts) {
                        if (Math.abs(i - c) < 2) {
                            far = false;
                            break;
                        }
                    }
                    if (far) {
                        cuts.add(i);
                    }
                }
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int c : cuts) {
            if (c > 0 && c < nBars) {
                out.add(c);
            }
        }
        return out;
    }

    /** El valor si todos son iguales; null si hay de los dos. */
    private static Boolean uniform(boolean[] v, int from, int to) {
        boolean first = v[from];
        for (int i = from + 1; i < to; i++) {
            if (v[i] != first) {
                return null;
            }
        }
        return first;
    }

    // ---- frases -------------------------------------------------------------------------------------

    static List<int[]> grid(int a, int b, int phase) {
        List<Integer> starts = new ArrayList<>();
        starts.add(a);
        for (int i = a + phase; i < b; i += PHRASE_BARS) {
            if (i > a) {
                starts.add(i);
            }
        }
        List<int[]> region = new ArrayList<>();
        for (int k = 0; k < starts.size(); k++) {
            int s = starts.get(k);
            int e = k + 1 < starts.size() ? starts.get(k + 1) : b;
            if (!region.isEmpty() && e - s < 2) {
                region.get(region.size() - 1)[1] = e; // resto de 1 compás: se une a la frase anterior
            } else {
                region.add(new int[] {s, e});
            }
        }
        if (region.size() > 1 && region.get(0)[1] - region.get(0)[0] < 2) {
            region.get(1)[0] = region.get(0)[0];
            region.remove(0);
        }
        return region;
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    static double repetitionScore(List<int[]> phrases, double[][] chroma) {
        List<int[]> full = new ArrayList<>();
        for (int[] p : phrases) {
            if (p[1] - p[0] == PHRASE_BARS) {
                full.add(p);
            }
        }
        if (full.size() < 2) {
            return 0.0;
        }
        double sumBest = 0;
        for (int i = 0; i < full.size(); i++) {
            int[] p = full.get(i);
            double best = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < full.size(); j++) {
                if (j == i) {
                    continue;
                }
                int[] q = full.get(j);
                double s = 0;
                for (int k = 0; k < PHRASE_BARS; k++) {
                    s += dot(chroma[p[0] + k], chroma[q[0] + k]);
                }
                best = Math.max(best, s / PHRASE_BARS);
            }
            sumBest += best;
        }
        return sumBest / full.size();
    }

    static List<int[]> phrases(int nBars, List<Integer> cuts, double[][] chroma) {
        List<Integer> edges = new ArrayList<>();
        edges.add(0);
        edges.addAll(cuts);
        edges.add(nBars);
        List<int[]> phrases = new ArrayList<>();
        for (int k = 0; k + 1 < edges.size(); k++) {
            int a = edges.get(k), b = edges.get(k + 1);
            if (b - a <= PHRASE_BARS + 1) {
                phrases.add(new int[] {a, b});
                continue;
            }
            // Fase de la grilla: la que hace que las frases se repitan mejor.
            double bestScore = Double.NEGATIVE_INFINITY;
            List<int[]> best = null;
            for (int phase = 0; phase < PHRASE_BARS; phase++) {
                List<int[]> g = grid(a, b, phase);
                double score = repetitionScore(g, chroma) + (phase == 0 ? 0.02 : 0.0);
                if (score > bestScore) {
                    bestScore = score;
                    best = g;
                }
            }
            phrases.addAll(best);
        }
        return phrases;
    }

    static double[] meanRows(double[][] x, int from, int to) {
        double[] out = new double[x[0].length];
        for (int i = from; i < to; i++) {
            for (int j = 0; j < out.length; j++) {
                out[j] += x[i][j];
            }
        }
        for (int j = 0; j < out.length; j++) {
            out[j] /= (to - from);
        }
        return out;
    }

    static double distance(double[] a, double[] b) {
        double sq = 0;
        for (int i = 0; i < a.length; i++) {
            sq += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(sq);
    }

    static final int[] SHIFTS = {0, 1, -1, 2, -2, 3, -3};

    static double phraseSimilarity(double[][] chroma, double[][] local, int[] p, int[] q, double sigma) {
        int lp = p[1] - p[0], lq = q[1] - q[0];
        int k = Math.min(lp, lq);
        // Misma secuencia de acordes, también transportada ±1-3 semitonos (con penalización).
        double seq = -1.0;
        for (int shift : SHIFTS) {
            double sum = 0;
            for (int r = 0; r < k; r++) {
                double[] a = chroma[p[0] + r], b = chroma[q[0] + r];
                double d = 0;
                for (int j = 0; j < 12; j++) {
                    d += a[j] * b[Math.floorMod(j - shift, 12)];
                }
                sum += Math.max(0.0, d);
            }
            double value = sum / k - (shift != 0 ? 0.06 : 0.0);
            seq = Math.max(seq, value);
        }
        double d = distance(meanRows(local, p[0], p[1]), meanRows(local, q[0], q[1]));
        double timbre = Math.exp(-((d / sigma) * (d / sigma)));
        double length = 1.0 - Math.abs(lp - lq) / (double) Math.max(lp, lq);
        return 0.6 * seq + 0.3 * timbre + 0.1 * length;
    }

    static double timbreSigma(List<int[]> phrases, double[][] local) {
        int n = phrases.size();
        double[][] means = new double[n][];
        for (int i = 0; i < n; i++) {
            means[i] = meanRows(local, phrases.get(i)[0], phrases.get(i)[1]);
        }
        List<Double> dists = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                dists.add(distance(means[i], means[j]));
            }
        }
        if (dists.isEmpty()) {
            return 1.0;
        }
        double[] d = new double[dists.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = dists.get(i);
        }
        double m = Dsp.median(d);
        return m != 0 ? m : 1.0;
    }

    static int[] cluster(List<int[]> phrases, double[][] chroma, double[][] local) {
        int n = phrases.size();
        double sigma = timbreSigma(phrases, local);
        double[][] sim = new double[n][n];
        for (int i = 0; i < n; i++) {
            sim[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                sim[i][j] = sim[j][i] = phraseSimilarity(chroma, local, phrases.get(i), phrases.get(j), sigma);
            }
        }
        // Agrupamiento aglomerativo (enlace promedio) con umbral.
        List<List<Integer>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Integer> c = new ArrayList<>();
            c.add(i);
            clusters.add(c);
        }
        while (clusters.size() > 1) {
            double best = -1.0;
            int pa = -1, pb = -1;
            for (int a = 0; a < clusters.size(); a++) {
                for (int b = a + 1; b < clusters.size(); b++) {
                    double s = 0;
                    for (int x : clusters.get(a)) {
                        for (int y : clusters.get(b)) {
                            s += sim[x][y];
                        }
                    }
                    s /= clusters.get(a).size() * clusters.get(b).size();
                    if (s > best) {
                        best = s;
                        pa = a;
                        pb = b;
                    }
                }
            }
            if (best < SAME_PHRASE || pa < 0) {
                break;
            }
            clusters.get(pa).addAll(clusters.get(pb));
            clusters.remove(pb);
        }
        List<List<Integer>> sorted = new ArrayList<>(clusters);
        Collections.sort(sorted, (x, y) -> Integer.compare(Collections.min(x), Collections.min(y)));
        int[] labels = new int[n];
        for (int c = 0; c < sorted.size(); c++) {
            for (int m : sorted.get(c)) {
                labels[m] = c;
            }
        }
        return labels;
    }

    interface Similarity {
        double of(int[] p, int[] q);
    }

    static final class Merged {
        List<int[]> segments;
        List<Integer> groups;
    }

    static Merged merge(List<int[]> phrases, int[] labels, double[] energy, double[] vocal, Similarity similarity) {
        // 1) frases seguidas iguales -> una sección
        List<int[]> segments = new ArrayList<>();
        List<Integer> groups = new ArrayList<>();
        for (int i = 0; i < phrases.size(); i++) {
            int a = phrases.get(i)[0], b = phrases.get(i)[1], g = labels[i];
            if (!groups.isEmpty() && groups.get(groups.size() - 1) == g) {
                int[] last = segments.get(segments.size() - 1);
                if ((b - a) + (last[1] - last[0]) <= 16) {
                    segments.set(segments.size() - 1, new int[] {last[0], b});
                    continue;
                }
            }
            segments.add(new int[] {a, b});
            groups.add(g);
        }
        // 2) un tipo que siempre va seguido del mismo otro tipo y suena igual es la primera mitad
        //    de esa parte (p. ej. las dos frases de un coro).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int x : new TreeSet<>(groups)) {
                List<Integer> positions = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) {
                    if (groups.get(i) == x) {
                        positions.add(i);
                    }
                }
                boolean atEnd = false;
                for (int i : positions) {
                    if (i + 1 >= groups.size()) {
                        atEnd = true;
                    }
                }
                if (atEnd) {
                    continue;
                }
                TreeSet<Integer> followers = new TreeSet<>();
                for (int i : positions) {
                    followers.add(groups.get(i + 1));
                }
                if (followers.size() != 1) {
                    continue;
                }
                int y = followers.first();
                if (y == x || (positions.size() < 2 && Collections.frequency(groups, y) < 2)) {
                    continue;
                }
                boolean tooLong = false;
                for (int i : positions) {
                    if (segments.get(i + 1)[1] - segments.get(i)[0] > 16) {
                        tooLong = true;
                    }
                }
                if (tooLong) {
                    continue;
                }
                double ex = 0, ey = 0, vx = 0, vy = 0;
                int nx = 0, ny = 0;
                for (int i : positions) {
                    for (int k = segments.get(i)[0]; k < segments.get(i)[1]; k++) {
                        ex += energy[k];
                        vx += vocal[k];
                        nx++;
                    }
                    for (int k = segments.get(i + 1)[0]; k < segments.get(i + 1)[1]; k++) {
                        ey += energy[k];
                        vy += vocal[k];
                        ny++;
                    }
                }
                if (Math.abs(ex / nx - ey / ny) > 1.5 || Math.abs(vx / nx - vy / ny) > 0.25) {
                    continue;
                }
                double simSum = 0;
                for (int i : positions) {
                    simSum += similarity.of(segments.get(i), segments.get(i + 1));
                }
                if (simSum / positions.size() < 0.72) {
                    continue;
                }
                int target = Collections.frequency(groups, y) >= Collections.frequency(groups, x) ? y : x;
                List<int[]> newSegments = new ArrayList<>();
                List<Integer> newGroups = new ArrayList<>();
                int i = 0;
                while (i < groups.size()) {
                    if (groups.get(i) == x && i + 1 < groups.size() && groups.get(i + 1) == y) {
                        newSegments.add(new int[] {segments.get(i)[0], segments.get(i + 1)[1]});
                        newGroups.add(target);
                        i += 2;
                    } else {
                        newSegments.add(segments.get(i));
                        newGroups.add(groups.get(i));
                        i += 1;
                    }
                }
                segments = newSegments;
                groups = newGroups;
                changed = true;
                break;
            }
        }
        // renumerar por orden de aparición
        Map<Integer, Integer> order = new HashMap<>();
        List<Integer> renumbered = new ArrayList<>();
        for (int g : groups) {
            if (!order.containsKey(g)) {
                order.put(g, order.size());
            }
            renumbered.add(order.get(g));
        }
        Merged m = new Merged();
        m.segments = segments;
        m.groups = renumbered;
        return m;
    }

    /**
     * _consolidate: red de seguridad para canciones largas o muy variadas (un popurrí de 11
     * minutos): si quedaron demasiadas partes (más de una cada ~15 s), la más corta se junta con su
     * vecina (la del mismo tipo si la hay, si no la más corta), hasta que queden las que corresponden.
     */
    static Merged consolidate(Merged m, double duration) {
        List<int[]> segments = new ArrayList<>(m.segments);
        List<Integer> groups = new ArrayList<>(m.groups);
        int limit = Math.max(12, (int) Math.rint(duration / 15.0));
        while (segments.size() > limit) {
            int k = 0;
            for (int i = 1; i < segments.size(); i++) {
                if (segments.get(i)[1] - segments.get(i)[0] < segments.get(k)[1] - segments.get(k)[0]) {
                    k = i;
                }
            }
            int left = k - 1, right = k + 1;
            int other;
            if (left >= 0 && groups.get(left).equals(groups.get(k))) {
                other = left;
            } else if (right < segments.size() && groups.get(right).equals(groups.get(k))) {
                other = right;
            } else if (left < 0) {
                other = right;
            } else if (right >= segments.size()) {
                other = left;
            } else {
                int ll = segments.get(left)[1] - segments.get(left)[0];
                int lr = segments.get(right)[1] - segments.get(right)[0];
                other = ll <= lr ? left : right;
            }
            int lk = segments.get(k)[1] - segments.get(k)[0];
            int lo = segments.get(other)[1] - segments.get(other)[0];
            int a = Math.min(segments.get(k)[0], segments.get(other)[0]);
            int b = Math.max(segments.get(k)[1], segments.get(other)[1]);
            int group = lo >= lk ? groups.get(other) : groups.get(k);
            int first = Math.min(k, other);
            segments.remove(first + 1);
            segments.set(first, new int[] {a, b});
            groups.remove(first + 1);
            groups.set(first, group);
        }
        Map<Integer, Integer> order = new HashMap<>();
        List<Integer> renumbered = new ArrayList<>();
        for (int g : groups) {
            if (!order.containsKey(g)) {
                order.put(g, order.size());
            }
            renumbered.add(order.get(g));
        }
        Merged out = new Merged();
        out.segments = segments;
        out.groups = renumbered;
        return out;
    }

    // ---- análisis completo ------------------------------------------------------------------------------

    static List<Section> analyze(Input in) {
        double duration = in.duration;
        double[] bounds = barBounds(in.downbeats, duration);
        int nBars = bounds.length - 1;
        List<Section> result = new ArrayList<>();
        if (nBars < 6) {
            Section s = new Section();
            s.start = 0.0;
            s.end = Dsp.round(duration, 3);
            s.label = "Canción";
            s.group = "A";
            s.bars = Math.max(0, nBars);
            s.vocals = in.vocalsDb != null;
            result.add(s);
            return result;
        }
        int chromaFrames = in.trebleChroma[0].length;
        int[] frames = new int[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            int f = (int) Math.rint(bounds[i] * Dsp.SR / Dsp.HOP);
            frames[i] = Math.max(0, Math.min(chromaFrames, f));
        }
        int mfccFrames = in.mfcc.length;
        double[] mixDb = in.mixDb;
        double[] vocDb = in.vocalsDb;
        if (vocDb == null) {
            vocDb = new double[mixDb.length];
            Arrays.fill(vocDb, -100.0);
        }
        double[][] chromaBars = new double[nBars][12];
        double[][] timbre = new double[nBars][12];
        double[] energy = new double[nBars];
        double[] vocal = new double[nBars];
        int last = chromaFrames - 1;
        for (int i = 0; i < nBars; i++) {
            int f0 = Math.min(frames[i], last);
            int f1 = Math.max(frames[i + 1], f0 + 1);
            double[] c = new double[12];
            for (int k = 0; k < 12; k++) {
                double s = 0;
                for (int t = f0; t < f1; t++) {
                    s += in.trebleChroma[k][t];
                }
                c[k] = s / (f1 - f0);
            }
            // Centrado: con croma "crudo" todos los acordes se parecen.
            double mean = 0;
            for (double v : c) {
                mean += v;
            }
            mean /= 12;
            double sq = 0;
            for (int k = 0; k < 12; k++) {
                c[k] -= mean;
                sq += c[k] * c[k];
            }
            double norm = Math.max(Math.sqrt(sq), 1e-9);
            for (int k = 0; k < 12; k++) {
                chromaBars[i][k] = c[k] / norm;
            }
            int m0 = Math.min(f0, mfccFrames - 1);
            int m1 = Math.max(Math.min(f1, mfccFrames), m0 + 1);
            for (int k = 0; k < 12; k++) {
                double s = 0;
                for (int t = m0; t < m1; t++) {
                    s += in.mfcc[t][k + 1];
                }
                timbre[i][k] = s / (m1 - m0);
            }
            int e0 = Math.min(f0, mixDb.length - 1);
            int e1 = Math.max(Math.min(f1, mixDb.length), e0 + 1);
            double es = 0;
            int voiced = 0;
            for (int t = e0; t < e1; t++) {
                es += mixDb[t];
                if (vocDb[t] > -45 && vocDb[t] > mixDb[t] - 18) {
                    voiced++;
                }
            }
            energy[i] = es / (e1 - e0);
            vocal[i] = voiced / (double) (e1 - e0);
        }

        double sqrt3 = Math.sqrt(3);
        double[][] zt = zscore(timbre);
        double[][] ze = zscore(column(energy));
        double[][] zv = zscore(column(vocal));
        double[][] zc = zscore(chromaBars);
        double[][] local = new double[nBars][14];
        double[][] feats = new double[nBars][26];
        for (int i = 0; i < nBars; i++) {
            for (int k = 0; k < 12; k++) {
                local[i][k] = 0.9 * zt[i][k];
            }
            local[i][12] = 1.0 * ze[i][0] * sqrt3;
            local[i][13] = 1.2 * zv[i][0] * sqrt3;
            System.arraycopy(local[i], 0, feats[i], 0, 14);
            for (int k = 0; k < 12; k++) {
                feats[i][14 + k] = 1.5 * zc[i][k];
            }
        }
        double[] novelty = checkerboardNovelty(feats, nBars >= 24 ? 4 : 2);
        List<Integer> cuts = strongCuts(novelty, vocal, nBars);
        // Donde cambia el tempo (en un popurrí, donde empieza otra canción) también se corta.
        for (double t : in.tempoStarts) {
            int bar = 0;
            while (bar < bounds.length && bounds[bar] < t - 0.05) {
                bar++;
            }
            if (bar > 0 && bar < nBars && !cuts.contains(bar)) {
                cuts.add(bar);
                Collections.sort(cuts);
            }
        }
        List<int[]> phrases = phrases(nBars, cuts, chromaBars);
        int[] labels = cluster(phrases, chromaBars, local);
        final double sigma = timbreSigma(phrases, local);
        final double[][] cb = chromaBars, lc = local;
        Merged merged = consolidate(merge(phrases, labels, energy, vocal, (p, q) -> phraseSimilarity(cb, lc, p, q, sigma)),
                duration);

        int n = merged.segments.size();
        double[] segVocal = new double[n];
        double[] segEnergy = new double[n];
        for (int i = 0; i < n; i++) {
            int a = merged.segments.get(i)[0], b = merged.segments.get(i)[1];
            double sv = 0, se = 0;
            for (int k = a; k < b; k++) {
                sv += vocal[k];
                se += energy[k];
            }
            segVocal[i] = sv / (b - a);
            segEnergy[i] = se / (b - a);
        }
        List<String> names = nameSections(merged.segments, merged.groups, segVocal, segEnergy);
        for (int i = 0; i < n; i++) {
            int a = merged.segments.get(i)[0], b = merged.segments.get(i)[1];
            Section s = new Section();
            s.start = Dsp.round(a > 0 ? bounds[a] : 0.0, 3);
            s.end = Dsp.round(b < nBars ? bounds[b] : duration, 3);
            s.label = names.get(i);
            s.group = String.valueOf(LETTERS.charAt(merged.groups.get(i) % 26));
            s.bars = b - a;
            s.vocals = segVocal[i] > 0.35;
            result.add(s);
        }
        return result;
    }

    static List<String> nameSections(List<int[]> segments, List<Integer> groups, double[] segVocal, double[] segEnergy) {
        int n = segments.size();
        boolean[] singing = new boolean[n];
        String[] kinds = new String[n];
        for (int i = 0; i < n; i++) {
            singing[i] = segVocal[i] > 0.35;
            if (!singing[i]) {
                kinds[i] = i == 0 ? "Intro" : (i == n - 1 ? "Final" : "Instrumental");
            }
        }
        Map<Integer, List<Integer>> vocalGroups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (singing[i]) {
                List<Integer> list = vocalGroups.get(groups.get(i));
                if (list == null) {
                    list = new ArrayList<>();
                    vocalGroups.put(groups.get(i), list);
                }
                list.add(i);
            }
        }
        Map<Integer, List<Integer>> repeated = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : vocalGroups.entrySet()) {
            if (e.getValue().size() >= 2) {
                repeated.put(e.getKey(), e.getValue());
            }
        }
        double mean = Dsp.mean(segEnergy);
        double std = Dsp.std(segEnergy);
        if (std == 0) {
            std = 1.0;
        }
        double[] energyZ = new double[n];
        for (int i = 0; i < n; i++) {
            energyZ[i] = (segEnergy[i] - mean) / std;
        }

        int chorus = -1, verse = -1, pre = -1;
        if (!repeated.isEmpty()) {
            double best = Double.NEGATIVE_INFINITY;
            for (Map.Entry<Integer, List<Integer>> e : repeated.entrySet()) {
                List<Integer> idx = e.getValue();
                double ez = 0;
                for (int i : idx) {
                    ez += energyZ[i];
                }
                double score = ez / idx.size() + 0.35 * idx.size() + 0.2 * (idx.get(idx.size() - 1) / (double) n);
                if (score > best) {
                    best = score;
                    chorus = e.getKey();
                }
            }
            Map<Integer, List<Integer>> others = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<Integer>> e : repeated.entrySet()) {
                if (e.getKey() != chorus) {
                    others.put(e.getKey(), e.getValue());
                }
            }
            if (!others.isEmpty()) {
                int first = Integer.MAX_VALUE;
                for (Map.Entry<Integer, List<Integer>> e : others.entrySet()) {
                    if (e.getValue().get(0) < first) {
                        first = e.getValue().get(0);
                        verse = e.getKey();
                    }
                }
                for (Map.Entry<Integer, List<Integer>> e : others.entrySet()) {
                    if (e.getKey() == verse) {
                        continue;
                    }
                    int follows = 0;
                    for (int i : e.getValue()) {
                        if (i + 1 < n && groups.get(i + 1) == chorus) {
                            follows++;
                        }
                    }
                    if (follows >= Math.max(1, e.getValue().size() - 1)) {
                        pre = e.getKey();
                        break;
                    }
                }
            }
        }
        if (verse < 0) {
            int first = Integer.MAX_VALUE;
            for (Map.Entry<Integer, List<Integer>> e : vocalGroups.entrySet()) {
                int start = e.getValue().get(0);
                if (e.getKey() != chorus && start / (double) n < 0.45 && start < first) {
                    first = start;
                    verse = e.getKey();
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int g = groups.get(i);
            String base;
            List<Integer> sung = vocalGroups.get(g);
            if (kinds[i] != null) {
                base = kinds[i];
            } else if (g == chorus) {
                base = "Coro";
            } else if (g == verse) {
                base = "Verso";
            } else if (g == pre) {
                base = "Pre-coro";
            } else if (sung != null && sung.size() == 1 && i / (double) n >= 0.45) {
                base = "Puente";
            } else {
                base = "Parte " + LETTERS.charAt(g % 26);
            }
            names.add(base);
        }
        Map<String, Integer> totals = new HashMap<>();
        for (String base : names) {
            totals.put(base, totals.containsKey(base) ? totals.get(base) + 1 : 1);
        }
        Map<String, Integer> counters = new HashMap<>();
        List<String> result = new ArrayList<>();
        List<String> numbered = Arrays.asList("Verso", "Coro", "Pre-coro", "Instrumental", "Puente");
        for (String base : names) {
            if (numbered.contains(base) && totals.get(base) > 1) {
                int c = counters.containsKey(base) ? counters.get(base) + 1 : 1;
                counters.put(base, c);
                result.add(base + " " + c);
            } else {
                result.add(base);
            }
        }
        return result;
    }
}
