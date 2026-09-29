package com.moimoi.local;

import com.moimoi.analysis.Analyzer;
import com.moimoi.engine.DemucsSeparator;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Procesa una canción en el celular (como Worker._process_song): decodifica el archivo, lo pasa a
 * 44,1 kHz estéreo, separa las pistas con Demucs, guarda las pistas y sus formas de onda y analiza
 * la canción (tempo, compás, tonalidad, acordes, partes e instrumentos).
 */
public final class Processor {

    public static final int SAMPLE_RATE = 44100;
    public static final double MAX_DURATION_S = 20 * 60;
    public static final String MODEL = "htdemucs_6s";
    /** Techo de las pistas y de su suma (audio_io.peak_normalize_set). */
    private static final float CEILING = 0.98f;
    /** Margen sobre el pico de la mezcla: una pista sola casi nunca supera a la mezcla por más que esto. */
    private static final float HEADROOM = 1.12f;

    private final Store store;
    private final Platform platform;

    public Processor(Store store, Platform platform) {
        this.store = store;
        this.platform = platform;
    }

    /** Reparte el avance total (0-1) entre las etapas de un trabajo (como worker.Stage). */
    static final class Stage {
        private final Map<String, double[]> spans = new LinkedHashMap<>();

        Stage(Object... plan) {
            double total = 0;
            for (int i = 1; i < plan.length; i += 2) {
                total += ((Number) plan[i]).doubleValue();
            }
            double acc = 0;
            for (int i = 0; i < plan.length; i += 2) {
                double weight = ((Number) plan[i + 1]).doubleValue();
                spans.put((String) plan[i], new double[] {acc / total, weight / total});
                acc += weight;
            }
        }

        double at(String name, double fraction) {
            double[] span = spans.get(name);
            return span[0] + span[1] * Math.min(1.0, Math.max(0.0, fraction));
        }
    }

    public JSONObject process(JSONObject job, Jobs.Reporter jobReport) throws Exception {
        final String songId = job.optString("song_id");
        JSONObject song = store.getSong(songId);
        if (song == null) {
            throw new IllegalStateException("La canción ya no existe");
        }
        Store.SongFiles paths = store.files(songId);
        paths.create();
        Stems.Preset preset = Stems.PRESETS.get(song.optString("preset"));
        if (preset == null) {
            preset = Stems.PRESETS.get(Stems.DEFAULT_PRESET);
        }
        final String title = song.optString("title", "Canción");
        final Stage stage = new Stage("decode", 4, "separate", 90, "save", 2, "analyze", 4);
        store.updateSong(songId, "status", "queued", "error", null, "stage", "Preparando…");

        File source = paths.source();
        if (source == null) {
            throw new IOException("No se encontró el archivo de audio original");
        }
        File tmpDir = new File(platform.cacheDir(), "proceso-" + songId);
        Store.removeTree(tmpDir);
        if (!tmpDir.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta temporal");
        }
        final Reporter report = new Reporter(jobReport, songId, title, stage);
        Pcm.Cancel cancel = jobReport::cancelled;
        StemsSink sink = null;
        try {
            // 1) Decodificar y pasar a 44,1 kHz estéreo.
            report.at("decode", 0.0, "Leyendo el audio…", "separating");
            File raw = new File(tmpDir, "original.f32");
            Platform.Decoded decoded = platform.decode(source, raw, cancel,
                    (f) -> report.at("decode", 0.8 * f, "Leyendo el audio…", "separating"));
            File audioFile = raw;
            if (decoded.sampleRate != SAMPLE_RATE || decoded.channels != 2) {
                File converted = new File(tmpDir, "estereo.f32");
                try (Pcm.Reader in = new Pcm.Reader(raw, decoded.channels, decoded.sampleRate)) {
                    Pcm.toStereo(in, SAMPLE_RATE, converted, cancel);
                }
                raw.delete();
                audioFile = converted;
            }
            List<String> names;
            try (Pcm.Reader audio = new Pcm.Reader(audioFile, 2, SAMPLE_RATE)) {
                double duration = audio.frames / (double) SAMPLE_RATE;
                if (duration > MAX_DURATION_S) {
                    throw new IOException(String.format(java.util.Locale.US,
                            "La canción dura %.1f min; el máximo es %.0f min.", duration / 60, MAX_DURATION_S / 60));
                }
                if (duration < 1.0) {
                    throw new IOException("El audio es demasiado corto (menos de 1 segundo).");
                }
                store.updateSong(songId, "duration", Json.round(duration, 3), "sample_rate", SAMPLE_RATE);
                report.at("decode", 1.0, "Audio listo", "separating");

                // 2) Separar (las pistas se van guardando a medida que salen).
                report.at("separate", 0.0, "Preparando la IA…", "separating");
                float inputPeak = audio.peak();
                float gain = inputPeak * HEADROOM > CEILING ? CEILING / (inputPeak * HEADROOM) : 1f;
                List<String> stems = new ArrayList<>(preset.stems);
                File stemsDir = paths.stemsDir();
                if (!stemsDir.isDirectory() && !stemsDir.mkdirs()) {
                    throw new IOException("No se pudo crear la carpeta de las pistas");
                }
                try (DemucsSeparator separator = platform.openSeparator()) {
                    DemucsSeparator.ModelInfo info = separator.info();
                    sink = new StemsSink(stems, Stems.mapping(preset), info.sources.length, stemsDir, SAMPLE_RATE,
                            gain, audio.frames, info.segmentSamples);
                    report.at("separate", 0.0, "Separando pistas con IA…", "separating");
                    separator.separate(audio, sink, new DemucsSeparator.Listener() {
                        private long workStart = System.currentTimeMillis();

                        @Override
                        public void progress(double fraction) {
                            int level = platform.thermalLevel();
                            report.at("separate", fraction, level >= 2
                                    ? "Separando despacio para que el celular no se caliente…"
                                    : "Separando pistas con IA…", "separating");
                            rest(level);
                        }

                        @Override
                        public boolean cancelled() {
                            return jobReport.cancelled();
                        }

                        /** Descanso entre trozos: el celular se enfría (más largo si ya está caliente). */
                        private void rest(int level) {
                            long now = System.currentTimeMillis();
                            long end = now + coolDownMillis(level, now - workStart);
                            while (!jobReport.cancelled()) {
                                long left = end - System.currentTimeMillis();
                                if (left <= 0) {
                                    break;
                                }
                                try {
                                    Thread.sleep(Math.min(200, left));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            workStart = System.currentTimeMillis();
                        }
                    });
                }

                // 3) Guardar.
                report.at("save", 0.0, "Guardando pistas…", "separating");
                sink.finish();
                JSONObject peaks = sink.peaksJson();
                sink = null;
                Json.write(paths.peaks(), peaks);
                File[] old = stemsDir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        String name = f.getName();
                        String stem = name.endsWith(".wav") ? name.substring(0, name.length() - 4) : null;
                        if (stem == null || !stems.contains(stem)) {
                            f.delete();
                        }
                    }
                }
                names = Stems.ordered(stems);
                store.updateSong(songId, "stems", Json.array(names), "model", MODEL);
                report.at("save", 1.0, "Pistas listas", "separating");
            }

            // 4) Analizar. Si no se puede (p. ej. no alcanza la memoria), la canción igual queda
            //    lista con sus pistas y se puede volver a analizar desde el reproductor.
            Store.removeTree(tmpDir);
            String problem = analyze(songId, paths, names, (f, m) -> report.at("analyze", f, m, "analyzing"));
            if (problem != null) {
                System.err.println("MoiMoi: " + problem);
            }
            store.updateSong(songId, "status", "ready", "progress", 1.0, "stage", "Lista", "error", null,
                    "analysis_error", problem);
            JSONObject result = new JSONObject();
            result.put("stems", new JSONArray(names));
            result.put("model", MODEL);
            return result;
        } finally {
            if (sink != null) {
                sink.abort();
            }
            Store.removeTree(tmpDir);
        }
    }

    /** Vuelve a analizar una canción ya separada (trabajo "reanalyze"). */
    public JSONObject reanalyze(JSONObject job, Jobs.Reporter jobReport) throws Exception {
        String songId = job.optString("song_id");
        JSONObject song = store.getSong(songId);
        if (song == null) {
            throw new IllegalStateException("La canción ya no existe");
        }
        List<String> names = new ArrayList<>();
        JSONArray list = song.optJSONArray("stems");
        for (int i = 0; list != null && i < list.length(); i++) {
            names.add(list.getString(i));
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("La canción todavía no tiene pistas separadas");
        }
        Reporter report = new Reporter(jobReport, songId, song.optString("title", "Canción"), new Stage("analyze", 1));
        String problem = analyze(songId, store.files(songId), names, (f, m) -> report.at("analyze", f, m, "analyzing"));
        if (problem != null) {
            throw new IOException(problem);
        }
        store.updateSong(songId, "status", "ready", "progress", 1.0, "stage", "Lista", "analysis_error", null);
        return new JSONObject().put("analysis", true);
    }

    /**
     * Analiza las pistas guardadas y guarda el resultado (analisis.json y el resumen en la canción).
     * Devuelve null si salió bien, o por qué no se pudo; cancelar sigue lanzando Jobs.Cancelled.
     */
    String analyze(String songId, Store.SongFiles paths, List<String> names, Analyzer.Progress progress) {
        progress.report(0.0, "Analizando tempo, acordes y tonalidad…");
        StemFiles source = new StemFiles(paths.stemsDir());
        try {
            int samples = 0;
            for (String name : names) {
                samples = Math.max(samples, source.length(name));
            }
            Runtime rt = Runtime.getRuntime();
            long available = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            long needed = Analyzer.memoryNeeded(names, samples);
            if (needed > available) {
                return String.format(java.util.Locale.US,
                        "La canción es muy larga para analizarla en este celular (necesita %d MB de memoria y hay %d MB).",
                        needed >> 20, available >> 20);
            }
            JSONObject analysis = Analyzer.analyze(names, source, progress);
            Json.write(paths.analysis(), analysis);
            JSONObject song = store.getSong(songId);
            JSONObject meta = song == null ? null : song.optJSONObject("meta");
            meta = meta == null ? new JSONObject() : new JSONObject(meta.toString());
            meta.put("summary", analysis.optJSONObject("summary"));
            store.updateSong(songId, "meta", meta);
            return null;
        } catch (Jobs.Cancelled e) {
            throw e;
        } catch (OutOfMemoryError e) {
            System.gc();
            return "No alcanzó la memoria del celular para analizar la canción.";
        } catch (Exception e) {
            e.printStackTrace();
            return "No se pudo analizar la canción: " + e.getMessage();
        }
    }

    /**
     * Pausa después de trabajar `workMillis` separando, según qué tan caliente está el celular
     * (Platform.thermalLevel): normal = un tercio del tiempo de trabajo (la separación tarda un poco
     * más pero el celular no se calienta tanto); caliente = más; -1 (computadora) = sin pausa.
     */
    static long coolDownMillis(int level, long workMillis) {
        if (level < 0 || workMillis <= 0) {
            return 0;
        }
        double factor = level == 0 ? 0.35 : level == 1 ? 0.8 : level == 2 ? 1.5 : level == 3 ? 3.0 : 5.0;
        return Math.min(30000, Math.round(workMillis * factor));
    }

    /** Avance del trabajo, de la canción y de la notificación a la vez. */
    private final class Reporter {
        private final Jobs.Reporter job;
        private final String songId;
        private final String title;
        private final Stage stage;
        private long lastSong;
        private String lastMessage;

        Reporter(Jobs.Reporter job, String songId, String title, Stage stage) {
            this.job = job;
            this.songId = songId;
            this.title = title;
            this.stage = stage;
        }

        void at(String name, double fraction, String message, String status) {
            double total = stage.at(name, fraction);
            job.report(total, message); // lanza Cancelled si se pidió cancelar
            long now = System.currentTimeMillis();
            // Un paso nuevo se muestra enseguida (si no, "Audio listo" quedaba hasta el primer trozo separado).
            if (now - lastSong >= 250 || fraction >= 1.0 || !message.equals(lastMessage)) {
                lastSong = now;
                lastMessage = message;
                store.updateSong(songId, "progress", Json.round(total, 4), "stage", message, "status", status);
                platform.progress(title, total, message);
            }
        }
    }

    /** Pone en cola de nuevo las canciones que quedaron a medio procesar (se cerró la app). */
    public static List<String> interrupted(Store store) throws JSONException {
        List<String> out = new ArrayList<>();
        for (JSONObject song : store.listSongs()) {
            String status = song.optString("status");
            if (status.equals("queued") || status.equals("downloading") || status.equals("separating")
                    || status.equals("analyzing")) {
                out.add(0, song.optString("id")); // en el orden en que se agregaron
            }
        }
        return out;
    }
}
