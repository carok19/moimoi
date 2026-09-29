package com.moimoi.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Teoría musical básica (analysis/music.py): nombres de notas, acordes, tonalidades y perfiles. */
public final class Music {

    private Music() {}

    static final String[] SHARP_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    static final String[] FLAT_NAMES = {"C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"};
    static final String[] LATIN_SHARP = {"Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};
    static final String[] LATIN_FLAT = {"Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si"};

    static final Set<Integer> FLAT_MAJOR_TONICS = new HashSet<>(Arrays.asList(5, 10, 3, 8, 1, 6));
    static final Set<Integer> FLAT_MINOR_TONICS = new HashSet<>(Arrays.asList(2, 7, 0, 5, 10, 3));

    /** Calidades de acorde en orden: id, intervalos, sufijo, costo a priori. */
    public static final String[] QUALITIES = {"maj", "min", "7", "maj7", "min7", "sus4", "sus2", "dim"};
    static final int[][] INTERVALS = {
        {0, 4, 7}, {0, 3, 7}, {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}, {0, 5, 7}, {0, 2, 7}, {0, 3, 6},
    };
    static final String[] SUFFIX = {"", "m", "7", "maj7", "m7", "sus4", "sus2", "dim"};
    static final double[] PRIOR = {0.0, 0.0, -0.9, -1.1, -0.9, -0.9, -1.1, -1.6};

    static final double[] KK_MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    static final double[] KK_MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    private static final Set<String> DIATONIC_MAJOR = new HashSet<>(Arrays.asList(
            "0maj", "0maj7", "0sus2", "0sus4", "2min", "2min7", "4min", "4min7", "5maj", "5maj7", "5sus2",
            "7maj", "77", "7sus4", "9min", "9min7", "11dim", "10maj"));
    private static final Set<String> DIATONIC_MINOR = new HashSet<>(Arrays.asList(
            "0min", "0min7", "0sus2", "0sus4", "2dim", "3maj", "3maj7", "5min", "5min7", "7min", "7maj", "77",
            "8maj", "8maj7", "10maj", "107"));

    public static int qualityIndex(String quality) {
        for (int i = 0; i < QUALITIES.length; i++) {
            if (QUALITIES[i].equals(quality)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean usesFlats(int tonic, boolean major) {
        return (major ? FLAT_MAJOR_TONICS : FLAT_MINOR_TONICS).contains(((tonic % 12) + 12) % 12);
    }

    public static String noteName(int pc, boolean flats) {
        return (flats ? FLAT_NAMES : SHARP_NAMES)[((pc % 12) + 12) % 12];
    }

    public static String latinName(int pc, boolean flats) {
        return (flats ? LATIN_FLAT : LATIN_SHARP)[((pc % 12) + 12) % 12];
    }

    public static String chordName(int root, int quality, Integer bass, boolean flats) {
        String name = noteName(root, flats) + SUFFIX[quality];
        if (bass != null && ((bass % 12) + 12) % 12 != ((root % 12) + 12) % 12) {
            name += "/" + noteName(bass, flats);
        }
        return name;
    }

    public static String keyName(int tonic, boolean major) {
        return noteName(tonic, usesFlats(tonic, major)) + (major ? "" : "m");
    }

    public static String keyLabel(int tonic, boolean major) {
        return latinName(tonic, usesFlats(tonic, major)) + (major ? " mayor" : " menor");
    }

    public static boolean isDiatonic(int root, int quality, int tonic, boolean major) {
        int degree = (((root - tonic) % 12) + 12) % 12;
        return (major ? DIATONIC_MAJOR : DIATONIC_MINOR).contains(degree + QUALITIES[quality]);
    }

    /** Plantillas normalizadas [acorde][12] y etiquetas {fundamental, calidad} en el mismo orden. */
    public static final class Templates {
        public final double[][] vectors;
        public final int[][] labels;

        Templates(double[][] vectors, int[][] labels) {
            this.vectors = vectors;
            this.labels = labels;
        }
    }

    public static Templates chordTemplates() {
        List<double[]> vecs = new ArrayList<>();
        List<int[]> labels = new ArrayList<>();
        for (int q = 0; q < QUALITIES.length; q++) {
            int[] intervals = INTERVALS[q];
            for (int root = 0; root < 12; root++) {
                double[] v = new double[12];
                for (int idx = 0; idx < intervals.length; idx++) {
                    double weight = idx == 0 ? 1.0 : (idx < 3 ? 0.85 : 0.7);
                    v[(root + intervals[idx]) % 12] = weight;
                }
                double norm = 0;
                for (double x : v) {
                    norm += x * x;
                }
                norm = Math.sqrt(norm);
                for (int i = 0; i < 12; i++) {
                    v[i] /= norm;
                }
                vecs.add(v);
                labels.add(new int[] {root, q});
            }
        }
        return new Templates(vecs.toArray(new double[0][]), labels.toArray(new int[0][]));
    }

    /** 24 perfiles de Krumhansl-Kessler (media 0, norma 1); etiquetas {tónica, 1 = mayor / 0 = menor}. */
    public static Templates keyProfiles() {
        List<double[]> rows = new ArrayList<>();
        List<int[]> labels = new ArrayList<>();
        for (int m = 0; m < 2; m++) {
            double[] base = m == 0 ? KK_MAJOR : KK_MINOR;
            for (int tonic = 0; tonic < 12; tonic++) {
                double[] prof = new double[12];
                for (int i = 0; i < 12; i++) {
                    prof[(i + tonic) % 12] = base[i]; // np.roll(base, tonic)
                }
                double mean = 0;
                for (double x : prof) {
                    mean += x;
                }
                mean /= 12;
                double norm = 0;
                for (int i = 0; i < 12; i++) {
                    prof[i] -= mean;
                    norm += prof[i] * prof[i];
                }
                norm = Math.sqrt(norm);
                for (int i = 0; i < 12; i++) {
                    prof[i] /= norm;
                }
                rows.add(prof);
                labels.add(new int[] {tonic, m == 0 ? 1 : 0});
            }
        }
        return new Templates(rows.toArray(new double[0][]), labels.toArray(new int[0][]));
    }
}
