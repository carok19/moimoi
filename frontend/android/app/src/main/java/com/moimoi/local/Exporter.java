package com.moimoi.local;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Exportaciones en el celular (como exports.py): pistas sueltas en .zip, paquete para Multitrack
 * Alabanza (.zip con moimoi.json, click, voz guía y cuenta inicial) y la mezcla actual en WAV.
 */
public final class Exporter {

    public static final class ExportError extends Exception {
        public ExportError(String message) {
            super(message);
        }
    }

    static final String MIME_WAV = "audio/wav";
    static final String MIME_ZIP = "application/zip";
    static final double MAX_GAIN = 2.0;
    private static final int SR = Processor.SAMPLE_RATE;
    private static final int BLOCK = 1 << 15;

    private Exporter() {}

    // ---- nombres -------------------------------------------------------------------------

    public static String safeFilename(String text) {
        String t = text == null ? "" : text.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]+", " ").trim();
        while (t.startsWith(".")) {
            t = t.substring(1);
        }
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        t = t.trim().replaceAll("\\s+", " ");
        if (t.length() > 120) {
            t = t.substring(0, 120);
        }
        return t.isEmpty() ? "cancion" : t;
    }

    public static String displayName(JSONObject song) {
        String title = song.optString("title", "");
        if (title.isEmpty()) {
            title = "Canción";
        }
        String artist = Json.optString(song, "artist");
        return artist != null && !artist.isEmpty() ? artist + " - " + title : title;
    }

    // ---- mezcla --------------------------------------------------------------------------

    /** Paneo de potencia constante (-1 izquierda, 0 centro, 1 derecha). */
    static double[] panGains(double pan) {
        pan = Math.max(-1.0, Math.min(1.0, pan));
        double angle = (pan + 1) * Math.PI / 4;
        return new double[] {Math.cos(angle) * Math.sqrt(2), Math.sin(angle) * Math.sqrt(2)};
    }

    /** Aplica mute/solo como el mezclador: {pista: {volume, pan}} de las que suenan. */
    static Map<String, double[]> activeChannels(JSONObject mixer, List<String> stems) {
        Map<String, JSONObject> settings = new LinkedHashMap<>();
        boolean anySolo = false;
        for (String s : stems) {
            JSONObject v = mixer == null ? null : mixer.optJSONObject(s);
            if (v == null) {
                v = new JSONObject();
            }
            settings.put(s, v);
            anySolo |= v.optBoolean("solo", false);
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> e : settings.entrySet()) {
            JSONObject v = e.getValue();
            boolean audible = anySolo ? v.optBoolean("solo", false) : !v.optBoolean("mute", false);
            double volume = v.optDouble("volume", 1.0);
            if (Double.isNaN(volume)) {
                volume = 1.0;
            }
            if (audible && volume > 0) {
                double pan = v.optDouble("pan", 0.0);
                out.put(e.getKey(), new double[] {Math.min(MAX_GAIN, Math.max(0.0, volume)), Double.isNaN(pan) ? 0 : pan});
            }
        }
        return out;
    }

    // ---- pulso, click y cuenta inicial -----------------------------------------------------

    static final class Grid {
        final double[] beats;
        final boolean[] accents;
        final int beatsPerBar;
        final Double bpm;

        Grid(double[] beats, boolean[] accents, int beatsPerBar, Double bpm) {
            this.beats = beats;
            this.accents = accents;
            this.beatsPerBar = beatsPerBar;
            this.bpm = bpm;
        }
    }

    static final class Timeline {
        final double tempo;
        final double preRoll;
        final double[] countTimes;
        final int beatsPerBar;

        Timeline(double tempo, double preRoll, double[] countTimes, int beatsPerBar) {
            this.tempo = tempo;
            this.preRoll = preRoll;
            this.countTimes = countTimes;
            this.beatsPerBar = beatsPerBar;
        }

        double out(double t) {
            return preRoll + t / tempo;
        }
    }

    private static double r3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * Pulsos y acentos con las correcciones del usuario (doble/mitad, mover el "1"), como
     * exports.effective_grid. El "1" de cada compás sale de los downbeats del análisis (en un
     * popurrí cada tramo tiene el suyo).
     */
    static Grid effectiveGrid(JSONObject analysis, JSONObject settings) {
        JSONObject tempo = analysis.optJSONObject("tempo");
        int perBar = tempo == null ? 4 : Math.max(1, tempo.optInt("beatsPerBar", 4));
        JSONObject saved = settings == null ? null : settings.optJSONObject("grid");
        JSONArray savedBeats = saved == null ? null : saved.optJSONArray("beats");
        if (savedBeats != null && savedBeats.length() >= 2) {
            // El tempo que puso el usuario en el reproductor: la grilla ya viene lista.
            double[] beats = new double[savedBeats.length()];
            for (int i = 0; i < beats.length; i++) {
                beats[i] = savedBeats.optDouble(i);
            }
            Set<Double> downs = new HashSet<>();
            JSONArray d = saved.optJSONArray("downbeats");
            for (int i = 0; d != null && i < d.length(); i++) {
                downs.add(r3(d.optDouble(i)));
            }
            boolean[] accents = new boolean[beats.length];
            for (int i = 0; i < beats.length; i++) {
                accents[i] = downs.contains(r3(beats[i]));
            }
            double savedBpm = saved.optDouble("bpm", Double.NaN);
            Double bpm = !Double.isNaN(savedBpm) && savedBpm > 0 ? Double.valueOf(savedBpm)
                    : tempo == null || tempo.isNull("bpm") || !tempo.has("bpm") ? null : tempo.optDouble("bpm");
            return new Grid(beats, accents, perBar, bpm);
        }
        JSONArray b = analysis.optJSONArray("beats");
        double[] beats = new double[b == null ? 0 : b.length()];
        for (int i = 0; i < beats.length; i++) {
            beats[i] = b.optDouble(i);
        }
        Set<Double> downs = new HashSet<>();
        JSONArray d = analysis.optJSONArray("downbeats");
        for (int i = 0; d != null && i < d.length(); i++) {
            downs.add(r3(d.optDouble(i)));
        }
        boolean[] accents = new boolean[beats.length];
        boolean any = false;
        for (int i = 0; i < beats.length; i++) {
            accents[i] = downs.contains(r3(beats[i]));
            any |= accents[i];
        }
        if (!any) {
            for (int i = 0; i < beats.length; i++) {
                accents[i] = i % perBar == 0;
            }
        }
        Double bpm = tempo == null || tempo.isNull("bpm") || !tempo.has("bpm") ? null : tempo.optDouble("bpm");
        String scale = settings == null ? null : Json.optString(settings, "beatScale");
        if ("double".equals(scale) && beats.length > 1) {
            double[] doubled = new double[beats.length * 2 - 1];
            boolean[] marks = new boolean[doubled.length];
            for (int i = 0; i < beats.length; i++) {
                doubled[2 * i] = beats[i];
                marks[2 * i] = accents[i];
                if (i + 1 < beats.length) {
                    doubled[2 * i + 1] = (beats[i] + beats[i + 1]) / 2;
                }
            }
            beats = doubled;
            accents = marks;
            bpm = bpm == null ? null : bpm * 2;
        } else if ("half".equals(scale) && beats.length > 1) {
            int first = 0;
            while (first < accents.length && !accents[first]) {
                first++;
            }
            if (first == accents.length) {
                first = 0;
            }
            List<Double> kept = new ArrayList<>();
            List<Boolean> marks = new ArrayList<>();
            int since = first % 2; // pulsos antes del primer "1": misma paridad que él
            for (int i = 0; i < beats.length; i++) {
                if (accents[i]) {
                    since = 0;
                }
                if (since % 2 == 0) {
                    kept.add(beats[i]);
                    marks.add(accents[i]);
                }
                since++;
            }
            beats = new double[kept.size()];
            accents = new boolean[kept.size()];
            for (int i = 0; i < beats.length; i++) {
                beats[i] = kept.get(i);
                accents[i] = marks.get(i);
            }
            bpm = bpm == null ? null : bpm / 2;
        }
        int shift = Math.floorMod(settings == null ? 0 : settings.optInt("downbeatShift", 0), perBar);
        if (shift != 0) {
            boolean[] moved = new boolean[beats.length];
            for (int i = 0; i < beats.length; i++) {
                int from = i >= shift ? i - shift : i - shift + perBar;
                moved[i] = from < accents.length && accents[from];
            }
            accents = moved;
        }
        return new Grid(beats, accents, perBar, bpm);
    }

    static Timeline planTimeline(Grid grid, double tempo, int preRollBars) {
        if (preRollBars <= 0 || grid.beats.length < 2) {
            return new Timeline(tempo, 0.0, new double[0], grid.beatsPerBar);
        }
        double[] diffs = new double[grid.beats.length - 1];
        for (int i = 0; i < diffs.length; i++) {
            diffs[i] = grid.beats[i + 1] - grid.beats[i];
        }
        Arrays.sort(diffs);
        int m = diffs.length;
        double period = m % 2 == 1 ? diffs[m / 2] : (diffs[m / 2 - 1] + diffs[m / 2]) / 2;
        double first = grid.beats[0];
        for (int i = 0; i < grid.beats.length; i++) {
            if (grid.accents[i]) {
                first = grid.beats[i];
                break;
            }
        }
        int count = preRollBars * grid.beatsPerBar;
        double step = period / tempo;
        double preRoll = Math.max(0.0, count * step + 0.05 - first / tempo);
        double start = preRoll + first / tempo - count * step;
        double[] times = new double[count];
        for (int i = 0; i < count; i++) {
            times[i] = start + i * step;
        }
        return new Timeline(tempo, preRoll, times, grid.beatsPerBar);
    }

    static float[] clickSound(boolean accent) {
        int length = (int) (0.045 * SR);
        float[] out = new float[length];
        double freq = accent ? 1800.0 : 1250.0;
        double decay = accent ? 0.012 : 0.009;
        double level = accent ? 0.9 : 0.6;
        for (int i = 0; i < length; i++) {
            double t = i / (double) SR;
            double tone = Math.sin(2 * Math.PI * freq * t) + 0.35 * Math.sin(2 * Math.PI * freq * 2.01 * t);
            out[i] = (float) (tone * Math.exp(-t / decay) * Math.min(1.0, t / 0.0005) * level);
        }
        return out;
    }

    /**
     * {acento, pulso} con los sonidos de click de un paquete (exports.click_sounds), o null si no hay
     * ninguno (se usan los de MoiMoi).
     */
    static float[][] clickSounds(Map<String, float[]> samples) {
        Map<String, float[]> ok = new LinkedHashMap<>();
        if (samples != null) {
            for (Map.Entry<String, float[]> e : samples.entrySet()) {
                if (e.getValue() != null && e.getValue().length > 0) {
                    ok.put(e.getKey(), e.getValue());
                }
            }
        }
        if (ok.isEmpty()) {
            return null;
        }
        float[] beat = null;
        for (String role : new String[] {"beat", "eighth", "sixteenth", "accent"}) {
            if (beat == null && ok.containsKey(role)) {
                beat = ok.get(role);
            }
        }
        float[] accent = ok.containsKey("accent") ? ok.get("accent") : beat;
        return new float[][] {accent, beat};
    }

    /** Pista de click (mono) sobre los pulsos, con acento en el "1" y la cuenta inicial. */
    static float[] clickTrack(Grid grid, int length, Timeline timeline) {
        return clickTrack(grid, length, timeline, null);
    }

    static float[] clickTrack(Grid grid, int length, Timeline timeline, float[][] sounds) {
        float[] out = new float[length];
        float[] accent = sounds != null ? sounds[0] : clickSound(true);
        float[] normal = sounds != null ? sounds[1] : clickSound(false);
        List<double[]> events = new ArrayList<>();
        for (int i = 0; i < timeline.countTimes.length; i++) {
            events.add(new double[] {timeline.countTimes[i], i % timeline.beatsPerBar == 0 ? 1 : 0});
        }
        for (int i = 0; i < grid.beats.length; i++) {
            events.add(new double[] {timeline.out(grid.beats[i]), grid.accents[i] ? 1 : 0});
        }
        for (double[] e : events) {
            int start = (int) Math.round(e[0] * SR);
            if (start < 0 || start >= length) {
                continue;
            }
            float[] sound = e[1] > 0 ? accent : normal;
            int end = Math.min(length, start + sound.length);
            for (int k = start; k < end; k++) {
                out[k] += sound[k - start];
            }
        }
        return out;
    }

    // ---- la Guía y el click en el reproductor ----------------------------------------------

    /**
     * Para escuchar la Guía y el click en el reproductor, igual que en el paquete para Multitrack:
     * dónde suena cada voz (segundos de la canción), los audios de esas voces (y de los números,
     * para la cuenta) y los del sonido de click elegido.
     */
    public static JSONObject playerGuide(JSONObject song, JSONObject analysis, JSONObject settings, Guide kit)
            throws JSONException {
        Grid grid = effectiveGrid(analysis, song.optJSONObject("settings"));
        JSONArray sections = songSections(analysis, song);
        Map<String, File> voices = kit.assignments();
        Map<Integer, String> extras = settings.optBoolean("guideKeyChanges", true)
                ? keyChangeExtras(analysis, sections) : new java.util.HashMap<Integer, String>();
        String numbering = settings.optString("guideNumbering", "verses");
        List<Guide.Placement> plan = Guide.plan(sections, grid.beats, grid.beatsPerBar, voices.keySet(), t -> t,
                new double[0], numbering.isEmpty() ? "verses" : numbering, extras, null);
        JSONArray placements = new JSONArray();
        Set<String> used = new java.util.TreeSet<>();
        for (Guide.Placement p : plan) {
            placements.put(new JSONObject().put("cue", p.cue).put("time", r3(p.time)).put("label", p.label));
            used.add(p.cue);
        }
        for (int i = 1; i <= Math.max(4, grid.beatsPerBar); i++) {
            used.add("n" + i); // para la cuenta antes de empezar
        }
        JSONObject urls = new JSONObject();
        for (String cue : used) {
            File f = voices.get(cue);
            if (f != null) {
                urls.put(cue, kit.urlOf(f));
            }
        }
        String style = settings.optString("exportClickSound", "classic");
        JSONObject described = kit.describe(null);
        String setName = null;
        JSONArray sets = described.optJSONArray("sets");
        for (int i = 0; sets != null && i < sets.length(); i++) {
            if (sets.getJSONObject(i).optBoolean("active")) {
                setName = sets.getJSONObject(i).optString("name");
            }
        }
        String clickName = null;
        JSONArray clicks = described.optJSONArray("clicks");
        for (int i = 0; clicks != null && i < clicks.length(); i++) {
            if (clicks.getJSONObject(i).optString("id").equals(style)) {
                clickName = clicks.getJSONObject(i).optString("name");
            }
        }
        return new JSONObject().put("placements", placements).put("voices", urls)
                .put("click", kit.clickUrls(style))
                .put("voiceSet", setName == null ? JSONObject.NULL : setName)
                .put("clickName", clickName == null ? JSONObject.NULL : clickName);
    }

    // ---- partes de la canción --------------------------------------------------------------

    static JSONArray songSections(JSONObject analysis, JSONObject song) {
        JSONObject settings = song.optJSONObject("settings");
        JSONArray edited = settings == null ? null : settings.optJSONArray("sections");
        if (edited != null && edited.length() > 0) {
            return edited;
        }
        JSONArray detected = analysis.optJSONArray("sections");
        return detected == null ? new JSONArray() : detected;
    }

    /** {número de parte: "sube" | "baja"} donde la canción cambia de tonalidad (mismo modo). */
    static Map<Integer, String> keyChangeExtras(JSONObject analysis, JSONArray sections) {
        Map<Integer, String> extras = new java.util.HashMap<>();
        JSONObject previous = analysis.optJSONObject("keyStart");
        if (previous == null) {
            previous = analysis.optJSONObject("key");
        }
        double[] starts = new double[sections.length()];
        for (int i = 0; i < starts.length; i++) {
            JSONObject s = sections.optJSONObject(i);
            starts[i] = s == null ? 0 : s.optDouble("start", 0);
        }
        JSONArray changes = analysis.optJSONArray("keyChanges");
        for (int c = 0; changes != null && c < changes.length(); c++) {
            JSONObject change = changes.optJSONObject(c);
            if (change == null) {
                continue;
            }
            if (previous != null && change.optString("mode").equals(previous.optString("mode")) && starts.length > 0) {
                int diff = Math.floorMod(change.optInt("tonic") - previous.optInt("tonic"), 12);
                double time = change.optDouble("time", 0);
                int index = 0;
                for (int i = 1; i < starts.length; i++) {
                    if (Math.abs(starts[i] - time) < Math.abs(starts[index] - time)) {
                        index = i;
                    }
                }
                if (diff != 0 && index > 0 && Math.abs(starts[index] - time) <= 1.0) {
                    extras.put(index, diff <= 6 ? "sube" : "baja");
                }
            }
            previous = change;
        }
        return extras;
    }

    private static final Map<String, String> PALETTE = new LinkedHashMap<>();

    static {
        PALETTE.put("Intro", "#8e9aaf");
        PALETTE.put("Verso", "#4fa3ff");
        PALETTE.put("Pre-coro", "#b388ff");
        PALETTE.put("Coro", "#ff5d8f");
        PALETTE.put("Puente", "#ffb74d");
        PALETTE.put("Instrumental", "#3fd9b0");
        PALETTE.put("Final", "#8e9aaf");
    }

    /** Marcadores con el formato de Multitrack Alabanza (nombre, tiempoMs, color). */
    static JSONArray sectionMarkers(JSONArray sections, Timeline timeline) throws JSONException {
        JSONArray markers = new JSONArray();
        for (int i = 0; i < sections.length(); i++) {
            JSONObject s = sections.optJSONObject(i);
            if (s == null) {
                continue;
            }
            String label = s.optString("label", "");
            if (label.isEmpty()) {
                label = "Parte";
            }
            String base = label.replaceAll("[ 0-9]+$", "");
            double start = s.optDouble("start", 0);
            double time = i == 0 && start <= 0.01 ? 0.0 : timeline.out(start);
            JSONObject m = new JSONObject();
            m.put("nombre", label);
            m.put("tiempoMs", Math.round(time * 1000));
            String color = PALETTE.get(base);
            m.put("color", color == null ? "#9fb3c8" : color);
            markers.put(m);
        }
        return markers;
    }

    // ---- exportar ------------------------------------------------------------------------

    public static JSONObject run(JSONObject song, Store.SongFiles paths, JSONObject params, File outDir,
                                 Jobs.Reporter progress, String appVersion) throws Exception {
        return run(song, paths, params, outDir, progress, appVersion, null);
    }

    public static JSONObject run(JSONObject song, Store.SongFiles paths, JSONObject params, File outDir,
                                 Jobs.Reporter progress, String appVersion, Guide kit) throws Exception {
        String kind = params.optString("type", "multitrack");
        List<String> available = new ArrayList<>();
        JSONArray have = song.optJSONArray("stems");
        for (int i = 0; have != null && i < have.length(); i++) {
            available.add(have.optString(i));
        }
        List<String> wanted = new ArrayList<>();
        JSONArray asked = params.optJSONArray("stems");
        if (asked == null || asked.length() == 0) {
            wanted.addAll(available);
        } else {
            for (int i = 0; i < asked.length(); i++) {
                if (available.contains(asked.optString(i)) && !wanted.contains(asked.optString(i))) {
                    wanted.add(asked.optString(i));
                }
            }
        }
        if (wanted.isEmpty()) {
            throw new ExportError("Elige al menos una pista");
        }
        wanted = Stems.ordered(wanted);
        String fmt = params.optString("format", "wav");
        if (!Arrays.asList("wav", "mp3", "flac").contains(fmt)) {
            throw new ExportError("Formato no soportado: " + fmt);
        }
        if (!fmt.equals("wav")) {
            throw new ExportError("En el celular por ahora se exporta en WAV (elige WAV).");
        }
        double tempo = params.optDouble("tempo", 1.0);
        if (Double.isNaN(tempo) || tempo == 0) {
            tempo = 1.0;
        }
        if (tempo < 0.25 || tempo > 4.0) {
            throw new ExportError("Velocidad fuera de rango");
        }
        int semitones = (int) Math.round(params.optDouble("semitones", 0));
        if (semitones < -12 || semitones > 12) {
            throw new ExportError("Transposición fuera de rango (-12 a 12)");
        }
        int preRollBars = params.optInt("preRollBars", 0);
        if (preRollBars < 0 || preRollBars > 4) {
            throw new ExportError("Cuenta inicial fuera de rango (0 a 4 compases)");
        }
        JSONObject analysis = Json.readObject(paths.analysis());
        if (analysis == null) {
            analysis = new JSONObject();
        }
        JSONObject settings = song.optJSONObject("settings");
        Grid grid = effectiveGrid(analysis, settings);
        String baseName = safeFilename(displayName(song));
        String suffix = variantSuffix(tempo, semitones, analysis);
        boolean changed = Stretch.needed(tempo, semitones);
        double duration = song.optDouble("duration", analysis.optDouble("duration", 0));

        if (kind.equals("mix")) {
            return exportMix(paths, params, wanted, grid, baseName + suffix, outDir, progress, tempo, semitones);
        }
        if (!kind.equals("multitrack") && !kind.equals("stems")) {
            throw new ExportError("Tipo de exportación desconocido: " + kind);
        }
        boolean multitrack = kind.equals("multitrack");
        boolean wantClick = params.optBoolean("click", false) && multitrack;
        boolean wantGuide = params.optBoolean("guide", false) && multitrack;
        if (!multitrack) {
            preRollBars = 0;
        }
        if ((wantClick || preRollBars > 0) && grid.beats.length == 0) {
            throw new ExportError("No se detectó el pulso de esta canción: no se puede generar el click ni la cuenta");
        }
        Map<String, float[]> cues = new LinkedHashMap<>();
        if (wantGuide) {
            if (kit != null) {
                cues = kit.loadCues();
            }
            if (cues.isEmpty()) {
                throw new ExportError("Primero carga las voces guía en Ajustes → Voz guía");
            }
        }
        float[][] sounds = null;
        String style = params.isNull("clickSound") ? "" : params.optString("clickSound", "");
        if (wantClick && !style.isEmpty() && !style.equals("moimoi") && kit != null) {
            sounds = clickSounds(kit.clickSounds(style)); // si se borró ese sonido, el de MoiMoi
        }
        Timeline timeline = planTimeline(grid, tempo, preRollBars);
        JSONArray sections = songSections(analysis, song);
        double lengthS = timeline.preRoll + duration / tempo;
        int length = (int) Math.round(lengthS * SR);
        int pre = (int) Math.round(timeline.preRoll * SR);
        boolean plain = !changed && pre == 0;

        // El paquete se llama como la canción (Multitrack Alabanza usa ese nombre); las pistas sueltas, no.
        String zipName = baseName + suffix + (multitrack ? "" : " (pistas)") + ".zip";
        // Con otra velocidad o tono: primero se estiran todas las pistas, varias a la vez.
        Map<String, File> stretchedFiles = new LinkedHashMap<>();
        Map<String, Float> stretchedPeaks = new LinkedHashMap<>();
        if (changed) {
            stretchAll(paths, wanted, tempo, semitones, outDir, progress, stretchedFiles, stretchedPeaks);
        }
        File target = new File(outDir, "paquete.zip");
        int steps = wanted.size() + (wantClick ? 1 : 0) + (wantGuide ? 1 : 0) + 1;
        List<Guide.Placement> placements = new ArrayList<>();
        int step = 0;
        JSONArray tracks = new JSONArray();
        try {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target), 1 << 16))) {
            zip.setMethod(ZipOutputStream.DEFLATED);
            zip.setLevel(Deflater.BEST_SPEED);
            if (wantClick) {
                progress.report(step / (double) steps, "Generando click…");
                float[] click = clickTrack(grid, length, timeline, sounds);
                zip.putNextEntry(new ZipEntry("Click.wav"));
                Wav.Writer w = new Wav.Writer(zip, 2, SR, length);
                w.write(click, click, click.length);
                w.close();
                zip.closeEntry();
                tracks.put(track("Click.wav", "Click", "click", 70));
                step++;
            }
            if (wantGuide) {
                progress.report(step / (double) steps, "Armando la voz guía…");
                Map<Integer, String> extras = params.optBoolean("guideKeyChanges", true)
                        ? keyChangeExtras(analysis, sections) : new java.util.HashMap<Integer, String>();
                String numbering = params.isNull("guideNumbering") ? "" : params.optString("guideNumbering", "");
                final Timeline tl = timeline;
                placements = Guide.plan(sections, grid.beats, grid.beatsPerBar, cues.keySet(), tl::out, tl.countTimes,
                        numbering.isEmpty() ? "verses" : numbering, extras, null);
                float[] guide = Guide.render(placements, cues, length);
                zip.putNextEntry(new ZipEntry("Guia.wav"));
                Wav.Writer w = new Wav.Writer(zip, 2, SR, length);
                w.write(guide, guide, guide.length);
                w.close();
                zip.closeEntry();
                tracks.put(track("Guia.wav", "Guía", "guia", 80));
                step++;
            }
            byte[] buffer = new byte[1 << 16];
            for (String name : wanted) {
                Stems.Info info = Stems.INFO.get(name);
                String label = info != null ? info.name : name;
                String fileBase = info != null ? info.fileName : name;
                progress.report(changed ? 0.85 + 0.15 * step / steps : step / (double) steps, "Preparando " + label + "…");
                File stemFile = paths.stem(name);
                if (!stemFile.isFile()) {
                    throw new ExportError("Falta la pista " + label + ": vuelve a separar la canción");
                }
                zip.putNextEntry(new ZipEntry(fileBase + ".wav"));
                if (changed) {
                    File tmp = stretchedFiles.get(name);
                    // Como soft_limit(…, 0.99): si el estiramiento subió algún pico, se baja todo un poco.
                    float peak = stretchedPeaks.get(name);
                    float gain = peak > 0.99f ? 0.99f / peak : 1f;
                    try (Pcm.Reader stretched = new Pcm.Reader(tmp, 2, SR)) {
                        writeFitted(stretched, gain, zip, length, pre, progress);
                    }
                    tmp.delete();
                } else if (plain) {
                    try (InputStream in = new FileInputStream(stemFile)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            if (progress.cancelled()) {
                                throw new Jobs.Cancelled();
                            }
                            zip.write(buffer, 0, n);
                        }
                    }
                } else {
                    writeFitted(stemFile, zip, length, pre, progress);
                }
                zip.closeEntry();
                tracks.put(track(fileBase + ".wav", label, name, 80));
                step++;
            }
            JSONObject manifest = manifest(song, analysis, grid, sections, tracks, timeline, semitones, lengthS, appVersion,
                    placements);
            zip.putNextEntry(new ZipEntry("moimoi.json"));
            zip.write(manifest.toString(2).getBytes(Json.UTF8));
            zip.closeEntry();
        }
        } finally {
            for (File f : stretchedFiles.values()) {
                f.delete();
            }
        }
        progress.report(1.0, "Listo");
        return result(target, zipName, MIME_ZIP);
    }

    private static JSONObject track(String file, String name, String instrument, int volume) throws JSONException {
        JSONObject t = new JSONObject();
        t.put("archivo", file);
        t.put("pan", 0);
        t.put("mute", false);
        t.put("solo", false);
        t.put("nombre", name);
        t.put("instrumento", instrument);
        t.put("volumen", volume);
        return t;
    }

    private static JSONObject result(File file, String name, String mime) throws JSONException {
        JSONObject r = new JSONObject();
        r.put("file", file.getName());
        r.put("name", name);
        r.put("size", file.length());
        r.put("mime", mime);
        return r;
    }

    /** Copia una pista con `pre` muestras de silencio adelante, recortada o completada a `length`. */
    private static void writeFitted(File stemFile, OutputStream out, int length, int pre, Jobs.Reporter progress)
            throws IOException {
        try (Wav.Reader in = new Wav.Reader(stemFile)) {
            Wav.Writer w = new Wav.Writer(out, 2, SR, length);
            float[] l = new float[BLOCK];
            float[] r = new float[BLOCK];
            for (int start = 0; start < length; start += BLOCK) {
                if (progress.cancelled()) {
                    throw new Jobs.Cancelled();
                }
                int n = Math.min(BLOCK, length - start);
                in.read(0, start - pre, l, 0, n);
                in.read(1, start - pre, r, 0, n);
                w.write(l, r, n);
            }
            w.finish();
        }
    }

    private static JSONObject exportMix(Store.SongFiles paths, JSONObject params, List<String> wanted, Grid grid,
                                        String baseName, File outDir, Jobs.Reporter progress, double tempo,
                                        int semitones) throws Exception {
        if (Stretch.needed(tempo, semitones)) {
            return exportMixStretched(paths, params, wanted, grid, baseName, outDir, progress, tempo, semitones);
        }
        progress.report(0.05, "Leyendo pistas…");
        Map<String, double[]> channels = activeChannels(params.optJSONObject("mixer"), wanted);
        if (channels.isEmpty()) {
            throw new ExportError("Todas las pistas elegidas están en silencio");
        }
        List<Wav.Reader> readers = new ArrayList<>();
        List<double[]> gains = new ArrayList<>();
        try {
            long frames = 0;
            for (Map.Entry<String, double[]> e : channels.entrySet()) {
                Wav.Reader reader = new Wav.Reader(paths.stem(e.getKey()));
                readers.add(reader);
                double[] pan = panGains(e.getValue()[1]);
                gains.add(new double[] {e.getValue()[0] * pan[0], e.getValue()[0] * pan[1]});
                frames = Math.max(frames, reader.frames);
            }
            boolean withClick = params.optBoolean("click", false) && grid.beats.length > 0;
            float[] click = withClick ? clickTrack(grid, (int) frames,
                    new Timeline(1.0, 0.0, new double[0], grid.beatsPerBar)) : null;
            double clickGain = params.optDouble("clickVolume", 0.6);
            float[] l = new float[BLOCK];
            float[] r = new float[BLOCK];
            float[] tl = new float[BLOCK];
            float[] tr = new float[BLOCK];
            // Dos pasadas: primero el pico (para no saturar, como soft_limit), después se escribe.
            float peak = 0f;
            for (int pass = 0; pass < 2; pass++) {
                float scale = pass == 1 && peak > 0.97f ? 0.97f / peak : 1f;
                Wav.Writer writer = pass == 1 ? new Wav.Writer(new File(outDir, "mezcla.wav"), 2, SR) : null;
                try {
                    for (long start = 0; start < frames; start += BLOCK) {
                        if (progress.cancelled()) {
                            throw new Jobs.Cancelled();
                        }
                        int n = (int) Math.min(BLOCK, frames - start);
                        Arrays.fill(l, 0, n, 0f);
                        Arrays.fill(r, 0, n, 0f);
                        for (int k = 0; k < readers.size(); k++) {
                            readers.get(k).read(0, start, tl, 0, n);
                            readers.get(k).read(1, start, tr, 0, n);
                            float gl = (float) gains.get(k)[0];
                            float gr = (float) gains.get(k)[1];
                            for (int i = 0; i < n; i++) {
                                l[i] += tl[i] * gl;
                                r[i] += tr[i] * gr;
                            }
                        }
                        if (click != null) {
                            for (int i = 0; i < n; i++) {
                                float c = (float) (clickGain * click[(int) start + i]);
                                l[i] += c;
                                r[i] += c;
                            }
                        }
                        if (pass == 0) {
                            for (int i = 0; i < n; i++) {
                                peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
                            }
                        } else {
                            for (int i = 0; i < n; i++) {
                                l[i] *= scale;
                                r[i] *= scale;
                            }
                            writer.write(l, r, n);
                        }
                        progress.report(0.1 + 0.45 * pass + 0.45 * (start + n) / (double) frames,
                                pass == 0 ? "Mezclando…" : "Guardando…");
                    }
                } finally {
                    if (writer != null) {
                        writer.close();
                    }
                }
            }
        } finally {
            for (Wav.Reader reader : readers) {
                reader.close();
            }
        }
        progress.report(1.0, "Listo");
        return result(new File(outDir, "mezcla.wav"), baseName + " (mezcla).wav", MIME_WAV);
    }

    /**
     * Mezcla con otra velocidad y tono (como exports.py): se mezcla, se estira la mezcla, se suma el
     * click (en los pulsos ya estirados) y, si hace falta, se baja todo para no saturar.
     */
    private static JSONObject exportMixStretched(Store.SongFiles paths, JSONObject params, List<String> wanted,
                                                 Grid grid, String baseName, File outDir, Jobs.Reporter progress,
                                                 double tempo, int semitones) throws Exception {
        progress.report(0.02, "Leyendo pistas…");
        Map<String, double[]> channels = activeChannels(params.optJSONObject("mixer"), wanted);
        if (channels.isEmpty()) {
            throw new ExportError("Todas las pistas elegidas están en silencio");
        }
        File mixed = new File(outDir, "tmp-mezcla.f32");
        File stretched = new File(outDir, "tmp-mezcla-estirada.f32");
        try {
            List<Wav.Reader> readers = new ArrayList<>();
            List<double[]> gains = new ArrayList<>();
            try (Pcm.Writer w = new Pcm.Writer(mixed, 2)) {
                long frames = 0;
                for (Map.Entry<String, double[]> e : channels.entrySet()) {
                    Wav.Reader reader = new Wav.Reader(paths.stem(e.getKey()));
                    readers.add(reader);
                    double[] pan = panGains(e.getValue()[1]);
                    gains.add(new double[] {e.getValue()[0] * pan[0], e.getValue()[0] * pan[1]});
                    frames = Math.max(frames, reader.frames);
                }
                float[] tl = new float[BLOCK], tr = new float[BLOCK], inter = new float[2 * BLOCK];
                for (long start = 0; start < frames; start += BLOCK) {
                    if (progress.cancelled()) {
                        throw new Jobs.Cancelled();
                    }
                    int n = (int) Math.min(BLOCK, frames - start);
                    Arrays.fill(inter, 0, 2 * n, 0f);
                    for (int k = 0; k < readers.size(); k++) {
                        readers.get(k).read(0, start, tl, 0, n);
                        readers.get(k).read(1, start, tr, 0, n);
                        float gl = (float) gains.get(k)[0], gr = (float) gains.get(k)[1];
                        for (int i = 0; i < n; i++) {
                            inter[2 * i] += tl[i] * gl;
                            inter[2 * i + 1] += tr[i] * gr;
                        }
                    }
                    w.writeInterleaved(inter, n);
                    progress.report(0.02 + 0.08 * (start + n) / (double) frames, "Mezclando…");
                }
            } finally {
                for (Wav.Reader reader : readers) {
                    reader.close();
                }
            }
            try (Pcm.Reader in = new Pcm.Reader(mixed, 2, SR)) {
                stretchToFile(source(in), tempo, semitones, stretched,
                        f -> progress.report(0.1 + 0.75 * f, "Aplicando velocidad y tono…"));
            }
            mixed.delete();
            try (Pcm.Reader in = new Pcm.Reader(stretched, 2, SR)) {
                long frames = in.frames;
                boolean withClick = params.optBoolean("click", false) && grid.beats.length > 0;
                float[] click = withClick ? clickTrack(grid, (int) frames,
                        new Timeline(tempo, 0.0, new double[0], grid.beatsPerBar)) : null;
                double clickGain = params.optDouble("clickVolume", 0.6);
                float[] l = new float[BLOCK], r = new float[BLOCK];
                float peak = 0f;
                for (int pass = 0; pass < 2; pass++) {
                    float scale = pass == 1 && peak > 0.97f ? 0.97f / peak : 1f;
                    Wav.Writer writer = pass == 1 ? new Wav.Writer(new File(outDir, "mezcla.wav"), 2, SR) : null;
                    try {
                        for (long start = 0; start < frames; start += BLOCK) {
                            if (progress.cancelled()) {
                                throw new Jobs.Cancelled();
                            }
                            int n = (int) Math.min(BLOCK, frames - start);
                            in.read(0, start, l, 0, n);
                            in.read(1, start, r, 0, n);
                            if (click != null) {
                                for (int i = 0; i < n; i++) {
                                    float c = (float) (clickGain * click[(int) start + i]);
                                    l[i] += c;
                                    r[i] += c;
                                }
                            }
                            if (pass == 0) {
                                for (int i = 0; i < n; i++) {
                                    peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
                                }
                            } else {
                                for (int i = 0; i < n; i++) {
                                    l[i] *= scale;
                                    r[i] *= scale;
                                }
                                writer.write(l, r, n);
                            }
                        }
                        progress.report(0.85 + 0.07 * (pass + 1), "Guardando…");
                    } finally {
                        if (writer != null) {
                            writer.close();
                        }
                    }
                }
            }
        } finally {
            mixed.delete();
            stretched.delete();
        }
        progress.report(1.0, "Listo");
        return result(new File(outDir, "mezcla.wav"), baseName + " (mezcla).wav", MIME_WAV);
    }

    /** Tonalidad transpuesta {tonic, mode, name, label}, o null si no hay análisis. */
    static JSONObject transposedKey(JSONObject analysis, int semitones) throws JSONException {
        JSONObject key = analysis.optJSONObject("key");
        if (key == null || !key.has("tonic")) {
            return null;
        }
        int tonic = Math.floorMod(key.optInt("tonic") + semitones, 12);
        boolean major = !"minor".equals(key.optString("mode"));
        return new JSONObject().put("tonic", tonic).put("mode", key.optString("mode", "major"))
                .put("name", com.moimoi.analysis.Music.keyName(tonic, major))
                .put("label", com.moimoi.analysis.Music.keyLabel(tonic, major));
    }

    /** " (en A, 90%)": el nombre del archivo dice la tonalidad y la velocidad si cambiaron. */
    static String variantSuffix(double tempo, int semitones, JSONObject analysis) throws JSONException {
        List<String> parts = new ArrayList<>();
        if (semitones != 0) {
            JSONObject key = transposedKey(analysis, semitones);
            parts.add(key != null ? "en " + key.optString("name") : String.format(java.util.Locale.US, "%+d st", semitones));
        }
        if (Math.abs(tempo - 1.0) >= 1e-3) {
            parts.add(String.format(java.util.Locale.US, "%.0f%%", tempo * 100));
        }
        return parts.isEmpty() ? "" : " (" + Json.join(", ", parts) + ")";
    }

    static Stretch.Source source(final Wav.Reader in) {
        return new Stretch.Source() {
            @Override
            public long frames() {
                return in.frames;
            }

            @Override
            public void read(int channel, long start, float[] dst, int offset, int count) {
                in.read(channel, start, dst, offset, count);
            }
        };
    }

    static Stretch.Source source(final Pcm.Reader in) {
        return new Stretch.Source() {
            @Override
            public long frames() {
                return in.frames;
            }

            @Override
            public void read(int channel, long start, float[] dst, int offset, int count) {
                in.read(channel, start, dst, offset, count);
            }
        };
    }

    /**
     * Estira las pistas (hasta 4 a la vez, según los núcleos) a archivos temporales. El avance y la
     * cancelación se atienden en este hilo; si se cancela o falla una, se paran todas.
     */
    static void stretchAll(Store.SongFiles paths, List<String> stems, final double tempo, final int semitones,
                           File outDir, Jobs.Reporter progress, Map<String, File> files, Map<String, Float> peaks)
            throws Exception {
        int threads = Math.max(1, Math.min(4, Math.min(stems.size(), Runtime.getRuntime().availableProcessors())));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        final double[] fractions = new double[stems.size()];
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        List<java.util.concurrent.Future<Float>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < stems.size(); i++) {
                final int index = i;
                final File stemFile = paths.stem(stems.get(i));
                final File tmp = new File(outDir, "tmp-" + stems.get(i) + ".f32");
                files.put(stems.get(i), tmp);
                futures.add(pool.submit(() -> {
                    if (!stemFile.isFile()) {
                        throw new ExportError("Falta la pista " + stems.get(index) + ": vuelve a separar la canción");
                    }
                    try (Wav.Reader in = new Wav.Reader(stemFile)) {
                        return stretchToFile(source(in), tempo, semitones, tmp, f -> {
                            if (stop.get()) {
                                throw new Jobs.Cancelled();
                            }
                            synchronized (fractions) {
                                fractions[index] = f;
                            }
                        });
                    }
                }));
            }
            while (true) {
                boolean done = true;
                for (java.util.concurrent.Future<Float> f : futures) {
                    done &= f.isDone();
                }
                double total = 0;
                synchronized (fractions) {
                    for (double f : fractions) {
                        total += f;
                    }
                }
                progress.report(0.85 * total / stems.size(), "Cambiando velocidad y tono…"); // lanza Cancelled
                if (done) {
                    break;
                }
                Thread.sleep(200);
            }
            for (int i = 0; i < stems.size(); i++) {
                try {
                    peaks.put(stems.get(i), futures.get(i).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw e;
                }
            }
        } catch (Exception e) {
            stop.set(true);
            for (File f : files.values()) {
                f.delete();
            }
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Estira a un archivo temporal (float estéreo) y devuelve el pico. */
    static float stretchToFile(Stretch.Source src, double tempo, int semitones, File tmp, Stretch.Progress progress)
            throws IOException {
        final float[] peak = {0f};
        try (final Pcm.Writer w = new Pcm.Writer(tmp, 2)) {
            final float[] inter = new float[2 * 8192];
            Stretch.process(src, tempo, semitones, (l, r, n) -> {
                for (int done = 0; done < n; ) {
                    int m = Math.min(n - done, inter.length / 2);
                    for (int i = 0; i < m; i++) {
                        float a = l[done + i], b = r[done + i];
                        inter[2 * i] = a;
                        inter[2 * i + 1] = b;
                        peak[0] = Math.max(peak[0], Math.max(Math.abs(a), Math.abs(b)));
                    }
                    w.writeInterleaved(inter, m);
                    done += m;
                }
            }, progress);
        }
        return peak[0];
    }

    /** Como writeFitted, desde el archivo estirado y con una ganancia. */
    private static void writeFitted(Pcm.Reader in, float gain, OutputStream out, int length, int pre,
                                    Jobs.Reporter progress) throws IOException {
        Wav.Writer w = new Wav.Writer(out, 2, SR, length);
        float[] l = new float[BLOCK];
        float[] r = new float[BLOCK];
        for (int start = 0; start < length; start += BLOCK) {
            if (progress.cancelled()) {
                throw new Jobs.Cancelled();
            }
            int n = Math.min(BLOCK, length - start);
            in.read(0, start - (long) pre, l, 0, n);
            in.read(1, start - (long) pre, r, 0, n);
            if (gain != 1f) {
                for (int i = 0; i < n; i++) {
                    l[i] *= gain;
                    r[i] *= gain;
                }
            }
            w.write(l, r, n);
        }
        w.finish();
    }

    static JSONObject manifest(JSONObject song, JSONObject analysis, Grid grid, JSONArray sections, JSONArray tracks,
                               Timeline timeline, int semitones, double lengthS, String appVersion,
                               List<Guide.Placement> placements) throws JSONException {
        JSONObject original = analysis.optJSONObject("key");
        JSONObject key = transposedKey(analysis, semitones);
        JSONObject cancion = new JSONObject();
        cancion.put("titulo", song.opt("title"));
        cancion.put("artista", song.has("artist") ? song.opt("artist") : JSONObject.NULL);
        cancion.put("duracionMs", Math.round(lengthS * 1000));
        cancion.put("bpm", grid.bpm == null ? JSONObject.NULL : (Object) Json.round(grid.bpm * timeline.tempo, 1));
        cancion.put("compas", grid.beatsPerBar);
        cancion.put("tonalidad", key == null ? JSONObject.NULL : key.opt("name"));
        cancion.put("tonalidadNombre", key == null ? JSONObject.NULL : key.opt("label"));
        cancion.put("tonalidadOriginal", original == null ? JSONObject.NULL : original.opt("name"));
        cancion.put("transposicion", semitones);
        cancion.put("velocidad", timeline.tempo);
        cancion.put("cuentaInicialMs", Math.round(timeline.preRoll * 1000));
        JSONArray chords = new JSONArray();
        JSONArray found = analysis.optJSONArray("chords");
        boolean flats = key != null && com.moimoi.analysis.Music.usesFlats(key.optInt("tonic"), !"minor".equals(key.optString("mode")));
        for (int i = 0; found != null && i < found.length(); i++) {
            JSONObject c = found.optJSONObject(i);
            int quality = c == null ? -1 : com.moimoi.analysis.Music.qualityIndex(c.optString("quality"));
            if (c == null || quality < 0) {
                continue;
            }
            int root = Math.floorMod(c.optInt("root") + semitones, 12);
            Integer bass = c.isNull("bass") || !c.has("bass") ? null : Math.floorMod(c.optInt("bass") + semitones, 12);
            JSONObject out = new JSONObject();
            out.put("inicio", r3(timeline.out(c.optDouble("start"))));
            out.put("fin", r3(timeline.out(c.optDouble("end"))));
            out.put("nombre", com.moimoi.analysis.Music.chordName(root, quality, bass, flats));
            chords.put(out);
        }
        JSONObject origen = new JSONObject();
        origen.put("app", "MoiMoi");
        origen.put("version", appVersion);
        origen.put("cancionId", song.opt("id"));
        origen.put("url", song.has("source_url") ? song.opt("source_url") : JSONObject.NULL);
        JSONObject out = new JSONObject();
        out.put("formato", "moimoi-multitrack");
        out.put("version", 1);
        out.put("cancion", cancion);
        out.put("pistas", tracks);
        out.put("marcadores", sectionMarkers(sections, timeline));
        out.put("acordes", chords);
        JSONArray guia = new JSONArray();
        for (Guide.Placement p : placements == null ? new ArrayList<Guide.Placement>() : placements) {
            guia.put(new JSONObject().put("voz", p.cue).put("parte", p.label).put("tiempoMs", Math.round(p.time * 1000)));
        }
        out.put("guia", guia);
        out.put("origen", origen);
        return out;
    }
}
