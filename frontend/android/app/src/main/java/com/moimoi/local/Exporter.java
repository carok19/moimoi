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
 * Alabanza (.zip con moimoi.json, click y cuenta inicial) y la mezcla actual en WAV.
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

    /** Pulsos y acentos con las correcciones del usuario (doble/mitad, mover el "1"). */
    static Grid effectiveGrid(JSONObject analysis, JSONObject settings) {
        JSONObject tempo = analysis.optJSONObject("tempo");
        int perBar = tempo == null ? 4 : Math.max(1, tempo.optInt("beatsPerBar", 4));
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
        int phase = 0;
        for (int i = 0; i < beats.length; i++) {
            if (downs.contains(r3(beats[i]))) {
                phase = i;
                break;
            }
        }
        Double bpm = tempo == null || tempo.isNull("bpm") || !tempo.has("bpm") ? null : tempo.optDouble("bpm");
        String scale = settings == null ? null : Json.optString(settings, "beatScale");
        if ("double".equals(scale) && beats.length > 1) {
            double[] doubled = new double[beats.length * 2 - 1];
            for (int i = 0; i < beats.length; i++) {
                doubled[2 * i] = beats[i];
                if (i + 1 < beats.length) {
                    doubled[2 * i + 1] = (beats[i] + beats[i + 1]) / 2;
                }
            }
            beats = doubled;
            phase *= 2;
            bpm = bpm == null ? null : bpm * 2;
        } else if ("half".equals(scale) && beats.length > 1) {
            int offset = phase % 2;
            List<Double> kept = new ArrayList<>();
            for (int i = 0; i < beats.length; i++) {
                if (i % 2 == offset) {
                    kept.add(beats[i]);
                }
            }
            beats = new double[kept.size()];
            for (int i = 0; i < beats.length; i++) {
                beats[i] = kept.get(i);
            }
            phase /= 2;
            bpm = bpm == null ? null : bpm / 2;
        }
        int shift = settings == null ? 0 : settings.optInt("downbeatShift", 0);
        int first = Math.floorMod(phase + shift, perBar);
        boolean[] accents = new boolean[beats.length];
        for (int i = 0; i < beats.length; i++) {
            accents[i] = Math.floorMod(i - first, perBar) == 0;
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

    /** Pista de click (mono) sobre los pulsos, con acento en el "1" y la cuenta inicial. */
    static float[] clickTrack(Grid grid, int length, Timeline timeline) {
        float[] out = new float[length];
        float[] accent = clickSound(true);
        float[] normal = clickSound(false);
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
        if (Math.abs(tempo - 1.0) >= 1e-3 || semitones != 0) {
            throw new ExportError("Cambiar la velocidad o el tono al exportar todavía no está disponible en el "
                    + "celular: desmarca \"Aplicar los cambios actuales\".");
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
        double duration = song.optDouble("duration", analysis.optDouble("duration", 0));

        if (kind.equals("mix")) {
            return exportMix(paths, params, wanted, grid, baseName, outDir, progress);
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
        if (wantGuide) {
            throw new ExportError("La voz guía en el celular llega en la próxima versión: desmarca \"Guía\".");
        }
        Timeline timeline = planTimeline(grid, tempo, preRollBars);
        JSONArray sections = songSections(analysis, song);
        double lengthS = timeline.preRoll + duration / tempo;
        int length = (int) Math.round(lengthS * SR);
        int pre = (int) Math.round(timeline.preRoll * SR);
        boolean plain = pre == 0;

        String zipName = baseName + ".zip";
        File target = new File(outDir, "paquete.zip");
        int steps = wanted.size() + (wantClick ? 1 : 0) + 1;
        int step = 0;
        JSONArray tracks = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target), 1 << 16))) {
            zip.setMethod(ZipOutputStream.DEFLATED);
            zip.setLevel(Deflater.BEST_SPEED);
            if (wantClick) {
                progress.report(step / (double) steps, "Generando click…");
                float[] click = clickTrack(grid, length, timeline);
                zip.putNextEntry(new ZipEntry("Click.wav"));
                Wav.Writer w = new Wav.Writer(zip, 2, SR, length);
                w.write(click, click, click.length);
                w.close();
                zip.closeEntry();
                tracks.put(track("Click.wav", "Click", "click", 70));
                step++;
            }
            byte[] buffer = new byte[1 << 16];
            for (String name : wanted) {
                Stems.Info info = Stems.INFO.get(name);
                String label = info != null ? info.name : name;
                String fileBase = info != null ? info.fileName : name;
                progress.report(step / (double) steps, "Preparando " + label + "…");
                File stemFile = paths.stem(name);
                if (!stemFile.isFile()) {
                    throw new ExportError("Falta la pista " + label + ": vuelve a separar la canción");
                }
                zip.putNextEntry(new ZipEntry(fileBase + ".wav"));
                if (plain) {
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
            JSONObject manifest = manifest(song, analysis, grid, sections, tracks, timeline, semitones, lengthS, appVersion);
            zip.putNextEntry(new ZipEntry("moimoi.json"));
            zip.write(manifest.toString(2).getBytes(Json.UTF8));
            zip.closeEntry();
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
                                        String baseName, File outDir, Jobs.Reporter progress) throws Exception {
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

    static JSONObject manifest(JSONObject song, JSONObject analysis, Grid grid, JSONArray sections, JSONArray tracks,
                               Timeline timeline, int semitones, double lengthS, String appVersion) throws JSONException {
        JSONObject key = analysis.optJSONObject("key");
        JSONObject cancion = new JSONObject();
        cancion.put("titulo", song.opt("title"));
        cancion.put("artista", song.has("artist") ? song.opt("artist") : JSONObject.NULL);
        cancion.put("duracionMs", Math.round(lengthS * 1000));
        cancion.put("bpm", grid.bpm == null ? JSONObject.NULL : (Object) Json.round(grid.bpm * timeline.tempo, 1));
        cancion.put("compas", grid.beatsPerBar);
        cancion.put("tonalidad", key == null ? JSONObject.NULL : key.opt("name"));
        cancion.put("tonalidadNombre", key == null ? JSONObject.NULL : key.opt("label"));
        cancion.put("tonalidadOriginal", key == null ? JSONObject.NULL : key.opt("name"));
        cancion.put("transposicion", semitones);
        cancion.put("velocidad", timeline.tempo);
        cancion.put("cuentaInicialMs", Math.round(timeline.preRoll * 1000));
        JSONArray chords = new JSONArray();
        JSONArray found = analysis.optJSONArray("chords");
        for (int i = 0; found != null && i < found.length(); i++) {
            JSONObject c = found.optJSONObject(i);
            if (c == null || "N".equals(c.optString("quality")) || c.optString("name", "").isEmpty()) {
                continue;
            }
            JSONObject out = new JSONObject();
            out.put("inicio", r3(timeline.out(c.optDouble("start"))));
            out.put("fin", r3(timeline.out(c.optDouble("end"))));
            out.put("nombre", c.optString("name"));
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
        out.put("guia", new JSONArray());
        out.put("origen", origen);
        return out;
    }
}
