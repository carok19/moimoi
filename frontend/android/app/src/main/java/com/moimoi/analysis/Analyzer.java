package com.moimoi.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Análisis musical a partir de las pistas separadas, como el de la computadora
 * (analysis/__init__.py analyze_song): tempo, pulsos y compases, tonalidad y cambios de
 * tonalidad, afinación, acordes con inversiones, partes de la canción e instrumentos.
 *
 * Para que alcance la memoria del celular las pistas se leen de a una y de cada una se guarda
 * solo lo que el análisis necesita (envolventes, croma, niveles); nunca están todas juntas.
 */
public final class Analyzer {

    private Analyzer() {}

    public static final int VERSION = 1;
    static final int SR = Dsp.SR;
    static final int HOP = Dsp.HOP;
    static final String[] TREBLE = {"guitar", "piano", "other"};
    static final double C2 = 440.0 * Math.pow(2.0, (36 - 69.0) / 12.0);
    static final double E1 = 440.0 * Math.pow(2.0, (28 - 69.0) / 12.0);

    /** De dónde salen las pistas. */
    public interface Source {
        /** Largo de la pista en muestras mono a 22,05 kHz. */
        int length(String stem) throws IOException;

        /** La pista en mono a 22,05 kHz (features.to_mono_22k); como mucho maxSamples (< 0: toda). */
        float[] load(String stem, int maxSamples) throws IOException;
    }

    public interface Progress {
        /** fraction de 0 a 1. Puede lanzar una excepción para cancelar. */
        void report(double fraction, String message);
    }

    /** Memoria aproximada que necesita el análisis (bytes) para pistas de `samples` muestras. */
    public static long memoryNeeded(List<String> stems, long samples) {
        boolean derivedBass = !stems.contains("bass");
        return (derivedBass ? 5 : 4) * 4L * samples + (48L << 20);
    }

    private static float[] padded(float[] v, int length) {
        return v.length >= length ? v : Arrays.copyOf(v, length);
    }

    public static JSONObject analyze(List<String> stems, Source source, Progress progress) throws IOException, JSONException {
        progress.report(0.0, "Preparando el análisis…");
        int length = 0;
        for (String s : stems) {
            length = Math.max(length, source.length(s));
        }
        if (stems.isEmpty() || length <= 0) {
            throw new IOException("La canción todavía no tiene pistas separadas");
        }
        double duration = length / (double) SR;
        boolean hasVocals = stems.contains("vocals");
        boolean hasDrums = stems.contains("drums");
        boolean hasBass = stems.contains("bass");
        boolean hasInstrumental = stems.contains("instrumental");
        boolean anyTreble = hasInstrumental;
        for (String t : TREBLE) {
            anyTreble |= stems.contains(t);
        }
        boolean harmonicFromMix = !anyTreble && !hasBass;

        // ---- afinación: con los primeros 90 s de la parte armónica alcanza --------------------
        double tuning = Double.NaN;
        int prefix = Math.min(length, SR * 90);
        if (hasBass) {
            float[] treblePrefix = new float[prefix];
            for (String t : TREBLE) {
                if (stems.contains(t)) {
                    add(treblePrefix, source.load(t, prefix));
                }
            }
            if (hasVocals) {
                addScaled(treblePrefix, source.load("vocals", prefix), 0.35f);
            }
            add(treblePrefix, source.load("bass", prefix));
            tuning = Tuning.estimate(treblePrefix);
        }

        // ---- las pistas, de a una ------------------------------------------------------------------
        float[] mix = new float[length];
        float[] treble = new float[length];
        Rhythm.Input rin = new Rhythm.Input();
        rin.duration = duration;
        double[][] bassChroma = null;
        double[] vocalsDb = null;
        double[][] levels = new double[stems.size()][];
        float[] keptVocals = null;

        List<String> order = new ArrayList<>();
        for (String s : stems) {
            if (!s.equals("vocals")) {
                order.add(s);
            }
        }
        if (hasVocals) {
            order.add("vocals"); // la voz se suma a la parte armónica después de filtrarla
        }
        float[] bassSignal = null;
        double loadWeight = 0.5 / order.size();
        double done = 0.02;
        for (String name : order) {
            if (name.equals("vocals") && !hasBass && !harmonicFromMix) {
                bassSignal = deriveBass(treble);
            }
            progress.report(done, "Preparando el análisis…");
            float[] y = padded(source.load(name, -1), length);
            add(mix, y);
            levels[stems.indexOf(name)] = Presence.level(y);
            switch (name) {
                case "drums":
                    drums(rin, y);
                    break;
                case "bass":
                    rin.bassOnset = Rhythm.onset(y, 320.0, null);
                    progress.report(done + 0.5 * loadWeight, "Calculando armonía…");
                    bassChroma = Cqt.chroma(y, tuning, E1, 3);
                    break;
                case "guitar":
                case "piano":
                case "other":
                    add(treble, y);
                    break;
                case "instrumental": {
                    // 2 pistas: el acompañamiento incluye la batería -> la parte armónica.
                    float[][] hp = Hpss.split(y, 1.0, 3.0, true);
                    y = null;
                    add(treble, hp[0]);
                    hp[0] = null;
                    if (!hasDrums) {
                        drums(rin, hp[1]);
                    }
                    break;
                }
                case "vocals":
                    vocalsDb = Dsp.rmsDb(y, 2048, HOP);
                    keptVocals = y;
                    break;
                default:
                    break;
            }
            y = null;
            done += loadWeight;
        }
        if (!hasBass && bassSignal == null) {
            if (harmonicFromMix) {
                treble = Hpss.split(mix, 3.0, 3.0, false)[0];
            }
            bassSignal = deriveBass(treble);
        }
        if (keptVocals != null) {
            // La melodía ayuda cuando el acompañamiento es escaso, pero con poco peso.
            addScaled(treble, keptVocals, 0.35f);
            keptVocals = null;
        }
        if (!hasBass) {
            float[] harmonicPrefix = Arrays.copyOf(treble, prefix);
            add(harmonicPrefix, bassSignal);
            tuning = Tuning.estimate(harmonicPrefix);
            harmonicPrefix = null;
            rin.bassOnset = Rhythm.onset(bassSignal, 320.0, null);
            progress.report(0.55, "Calculando armonía…");
            bassChroma = Cqt.chroma(bassSignal, tuning, E1, 3);
            bassSignal = null;
        }

        // ---- la mezcla -----------------------------------------------------------------------------
        progress.report(0.58, "Detectando el pulso y el compás…");
        rin.mixOnset = Rhythm.onset(mix, null, null);
        rin.mixLoud = Dsp.rmsDb(mix, 2048, HOP);
        if (!rin.drumsOk) {
            rin.mixLow = Rhythm.onset(mix, 160.0, null);
            rin.attack = Rhythm.attack(mix);
        }
        progress.report(0.62, "Detectando las partes de la canción…");
        float[][] mfcc = Spectrum.mfcc(mix, SR, HOP, 13);
        double[] mixLevel = Presence.level(mix);
        mix = null;

        progress.report(0.66, "Calculando armonía…");
        double[][] trebleChroma = Cqt.chroma(treble, tuning, C2, 5);
        treble = null;
        int frames = Math.min(trebleChroma[0].length, bassChroma[0].length);
        trebleChroma = trim(trebleChroma, frames);
        bassChroma = trim(bassChroma, frames);

        progress.report(0.8, "Detectando el pulso y el compás…");
        Rhythm rhythm = Rhythm.analyze(rin, trebleChroma, bassChroma);
        double[] mixLoud = rin.mixLoud;
        rin = null;

        progress.report(0.86, "Detectando acordes y tonalidad…");
        Harmony.Result harmony = Harmony.analyze(trebleChroma, bassChroma, rhythm, duration, tuning);

        progress.report(0.92, "Detectando las partes de la canción…");
        Sections.Input sin = new Sections.Input();
        sin.duration = duration;
        sin.downbeats = rhythm.downbeats;
        sin.trebleChroma = trebleChroma;
        sin.mfcc = mfcc;
        sin.mixDb = mixLoud;
        sin.vocalsDb = vocalsDb;
        List<Sections.Section> sections = Sections.analyze(sin);
        // Un cambio de tonalidad casi siempre coincide con el comienzo de una sección.
        for (Harmony.KeyChange change : harmony.keyChanges) {
            double best = Double.NaN;
            for (int i = 1; i < sections.size(); i++) {
                double t = sections.get(i).start;
                if (Math.abs(t - change.time) <= 10.0 && (Double.isNaN(best) || Math.abs(t - change.time) < Math.abs(best - change.time))) {
                    best = t;
                }
            }
            if (!Double.isNaN(best)) {
                change.time = best;
            }
        }

        progress.report(0.96, "Detectando instrumentos…");
        List<double[]> levelList = new ArrayList<>();
        for (double[] l : levels) {
            levelList.add(l);
        }
        JSONObject instruments = Presence.analyze(stems, levelList, mixLevel);
        JSONObject result = toJson(duration, rhythm, harmony, sections, instruments, stems);
        progress.report(1.0, "Análisis listo");
        return result;
    }

    /** Sin pista de bajo: el bajo sale de la parte armónica (y esta queda sin graves). */
    private static float[] deriveBass(float[] treble) {
        float[] bass = Butter.filtfilt(Butter.design(4, 180, SR, false), treble);
        Butter.filtfilt(Butter.design(2, 150, SR, true), treble, true);
        return bass;
    }

    private static void drums(Rhythm.Input rin, float[] d) {
        rin.drumsOk = Dsp.percentile(Dsp.rmsDb(d, 2048, 2048), 90) > -45.0;
        if (rin.drumsOk) {
            rin.drumsOnset = Rhythm.onset(d, null, null);
            rin.drumsKick = Rhythm.onset(d, 160.0, null);
            rin.drumsSnare = Rhythm.onset(d, 3000.0, 160.0);
            rin.attack = Rhythm.attack(d);
        }
    }

    static void add(float[] acc, float[] v) {
        int n = Math.min(acc.length, v.length);
        for (int i = 0; i < n; i++) {
            acc[i] = acc[i] + v[i];
        }
    }

    static void addScaled(float[] acc, float[] v, float k) {
        int n = Math.min(acc.length, v.length);
        for (int i = 0; i < n; i++) {
            acc[i] = acc[i] + k * v[i];
        }
    }

    static double[][] trim(double[][] c, int frames) {
        if (c[0].length == frames) {
            return c;
        }
        double[][] out = new double[c.length][];
        for (int i = 0; i < c.length; i++) {
            out[i] = Arrays.copyOf(c[i], frames);
        }
        return out;
    }

    // ---- JSON como el de la computadora ----------------------------------------------------------------

    static String mode(int key) {
        return Harmony.majorOf(key) ? "major" : "minor";
    }

    static JSONObject keyJson(int key) throws JSONException {
        int tonic = Harmony.tonicOf(key);
        boolean major = Harmony.majorOf(key);
        JSONObject o = new JSONObject();
        o.put("tonic", tonic);
        o.put("mode", mode(key));
        o.put("name", Music.keyName(tonic, major));
        o.put("label", Music.keyLabel(tonic, major));
        return o;
    }

    static JSONArray numbers(double[] v) throws JSONException {
        JSONArray a = new JSONArray();
        for (double x : v) {
            a.put(x);
        }
        return a;
    }

    static JSONObject toJson(double duration, Rhythm rhythm, Harmony.Result harmony, List<Sections.Section> sections,
                             JSONObject instruments, List<String> stems) throws JSONException {
        Object bpm = rhythm.bpm == null ? JSONObject.NULL : rhythm.bpm;
        JSONObject tempo = new JSONObject();
        tempo.put("bpm", bpm);
        tempo.put("beatsPerBar", rhythm.beatsPerBar);
        tempo.put("steady", rhythm.steady);
        tempo.put("confidence", rhythm.confidence);
        tempo.put("meterConfidence", rhythm.meterConfidence);

        JSONObject key = keyJson(harmony.key);
        key.put("confidence", Dsp.round(harmony.confidence, 2));
        JSONArray changes = new JSONArray();
        for (Harmony.KeyChange c : harmony.keyChanges) {
            JSONObject o = new JSONObject();
            o.put("time", c.time);
            JSONObject k = keyJson(c.key);
            o.put("tonic", k.get("tonic"));
            o.put("mode", k.get("mode"));
            o.put("name", k.get("name"));
            o.put("label", k.get("label"));
            changes.put(o);
        }
        JSONObject tuning = new JSONObject();
        tuning.put("a4", harmony.a4);
        tuning.put("cents", harmony.cents);

        JSONArray chords = new JSONArray();
        for (Harmony.Chord c : harmony.chords) {
            JSONObject o = new JSONObject();
            o.put("start", c.start);
            o.put("end", c.end);
            o.put("root", c.root);
            o.put("quality", c.quality < 0 ? "N" : Music.QUALITIES[c.quality]);
            o.put("bass", c.bass == null ? JSONObject.NULL : c.bass);
            o.put("name", c.name);
            chords.put(o);
        }
        JSONArray parts = new JSONArray();
        for (Sections.Section s : sections) {
            JSONObject o = new JSONObject();
            o.put("start", s.start);
            o.put("end", s.end);
            o.put("label", s.label);
            o.put("group", s.group);
            o.put("bars", s.bars);
            o.put("vocals", s.vocals);
            parts.put(o);
        }

        JSONArray present = new JSONArray();
        for (String name : stems) {
            JSONObject info = instruments.optJSONObject(name);
            if (info != null && !info.optString("level").equals("ausente")) {
                present.put(name);
            }
        }
        JSONObject summary = new JSONObject();
        summary.put("bpm", bpm);
        summary.put("beatsPerBar", rhythm.beatsPerBar);
        summary.put("key", key.get("name"));
        summary.put("keyLabel", key.get("label"));
        summary.put("tonic", key.get("tonic"));
        summary.put("mode", key.get("mode"));
        summary.put("a4", harmony.a4);
        summary.put("instruments", present);

        JSONObject out = new JSONObject();
        out.put("version", VERSION);
        out.put("duration", Dsp.round(duration, 3));
        out.put("tempo", tempo);
        out.put("beats", numbers(rhythm.beats));
        out.put("downbeats", numbers(rhythm.downbeats));
        out.put("key", key);
        out.put("keyStart", keyJson(harmony.keyStart));
        out.put("keyChanges", changes);
        out.put("tuning", tuning);
        out.put("chords", chords);
        out.put("sections", parts);
        out.put("instruments", instruments);
        out.put("summary", summary);
        return out;
    }
}
