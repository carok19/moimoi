package com.moimoi.local;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * El "servidor" de MoiMoi dentro del celular: canciones, cola de trabajos (separar, exportar) y la
 * API que usa la interfaz. Hay uno solo por app (lo comparten la pantalla y el servicio que sigue
 * separando con la app minimizada).
 */
public final class LocalBackend implements Jobs.Handler {

    public static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".webm", ".mp4", ".m4v",
            ".mov", ".mkv", ".aiff", ".aif", ".wma", ".alac", ".caf", ".3gp", ".amr", ".3ga"));
    public static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024;

    private static LocalBackend instance;

    final Platform platform;
    final Store store;
    final Jobs jobs;
    final LocalApi api;
    private final Processor processor;

    public static synchronized LocalBackend get(Platform platform) {
        if (instance == null) {
            instance = new LocalBackend(platform);
            instance.start();
        }
        return instance;
    }

    /** El que ya está funcionando (o null). */
    public static synchronized LocalBackend current() {
        return instance;
    }

    /** Para las pruebas: uno nuevo con otra carpeta de datos. */
    public static LocalBackend create(Platform platform) {
        LocalBackend backend = new LocalBackend(platform);
        backend.start();
        return backend;
    }

    private LocalBackend(Platform platform) {
        this.platform = platform;
        this.store = new Store(platform.dataDir());
        this.jobs = new Jobs(this, platform);
        this.api = new LocalApi(this);
        this.processor = new Processor(store, platform);
    }

    private void start() {
        try {
            for (String id : Processor.interrupted(store)) {
                JSONObject song = store.getSong(id);
                if (song != null && song.optString("status").equals("analyzing") && hasStems(song, id)) {
                    // Ya estaba separada: solo falta el análisis.
                    store.updateSong(id, "stage", "En cola para analizar (reanudando)", "progress", 0.0);
                    jobs.enqueue("reanalyze", id, null, "En cola");
                } else {
                    store.updateSong(id, "status", "queued", "stage", "En cola (reanudando)");
                    jobs.enqueue("process", id, null, "En cola");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        cleanupExports(24);
        jobs.start();
    }

    public void stop() {
        jobs.stop();
    }

    /** ¿Están todas las pistas de la canción guardadas? */
    private boolean hasStems(JSONObject song, String id) {
        org.json.JSONArray stems = song.optJSONArray("stems");
        if (stems == null || stems.length() == 0) {
            return false;
        }
        try {
            File dir = store.files(id).stemsDir();
            for (int i = 0; i < stems.length(); i++) {
                if (!new File(dir, stems.optString(i) + ".wav").isFile()) {
                    return false;
                }
            }
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /** Vuelve a analizar tempo, acordes y partes de una canción lista. */
    public JSONObject reanalyze(String songId) throws ApiException, JSONException {
        JSONObject song = store.getSong(songId);
        if (song == null) {
            throw new ApiException(404, "La canción no existe");
        }
        if (!song.optString("status").equals("ready")) {
            throw new ApiException(409, "La canción todavía no está lista");
        }
        JSONObject job = jobs.enqueue("reanalyze", songId, null, "En cola");
        store.updateSong(songId, "status", "analyzing", "stage", "En cola para analizar", "progress", 0.0);
        return job;
    }

    public LocalApi.Response request(String method, String path, String body) {
        return api.handle(method, path, body);
    }

    public LocalApi.Download resolveDownload(String url, String name) throws ApiException, IOException {
        return api.resolveDownload(url, name);
    }

    public File exportsDir() {
        return new File(platform.cacheDir(), "exportaciones");
    }

    private void cleanupExports(double maxAgeHours) {
        File[] folders = exportsDir().listFiles();
        if (folders == null) {
            return;
        }
        long limit = System.currentTimeMillis() - (long) (maxAgeHours * 3600 * 1000);
        for (File folder : folders) {
            if (folder.lastModified() < limit) {
                Store.removeTree(folder);
            }
        }
    }

    // ---- canciones ---------------------------------------------------------------------------

    private static final Pattern TITLE_NOISE = Pattern.compile(
            "\\s*[(\\[{][^)\\]}]*(official|oficial|video|v[ií]deo|audio|lyric|letra|en vivo|live|"
                    + "visualizer|hd|4k|remaster|karaoke|cover|ac[uú]stico|acoustic)[^)\\]}]*[)\\]}]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TRAILING_NOISE = Pattern.compile(
            "\\s*[|•·]\\s*(official|oficial|video|audio|letra|lyrics?|en vivo|live)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static String cleanTitle(String title) {
        String cleaned = TITLE_NOISE.matcher(title).replaceAll("");
        cleaned = TRAILING_NOISE.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\s{2,}", " ");
        cleaned = cleaned.replaceAll("^[ \\-–—|]+|[ \\-–—|]+$", "");
        return cleaned.isEmpty() ? title.trim() : cleaned;
    }

    /** "01 - Artista - Canción.mp3" -> {"Canción", "Artista"}. */
    static String[] titleFromFilename(String filename) {
        String name = new File(filename).getName();
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').trim();
        stem = stem.replaceFirst("^\\d{1,3}[\\s.\\-]+", "");
        for (String sep : new String[] {" - ", " – "}) {
            int i = stem.indexOf(sep);
            if (i >= 0) {
                String left = stem.substring(0, i).trim();
                String right = stem.substring(i + sep.length()).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    return new String[] {cleanTitle(right), left};
                }
            }
        }
        String title = cleanTitle(stem);
        return new String[] {title.isEmpty() ? "Canción sin título" : title, null};
    }

    public static String extensionOf(String filename, String mime) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        if (AUDIO_EXTENSIONS.contains(ext)) {
            return ext;
        }
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("mpeg") || m.contains("mp3")) {
                return ".mp3";
            }
            if (m.contains("wav")) {
                return ".wav";
            }
            if (m.contains("flac")) {
                return ".flac";
            }
            if (m.contains("ogg") || m.contains("opus")) {
                return ".ogg";
            }
            if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) {
                return ".m4a";
            }
            if (m.contains("amr")) {
                return ".amr";
            }
            if (m.contains("webm")) {
                return ".webm";
            }
            if (m.contains("3gpp")) {
                return ".3gp";
            }
            if (m.startsWith("audio/") || m.startsWith("video/")) {
                return ".bin"; // lo decide el decodificador de Android
            }
        }
        return null;
    }

    /**
     * Agrega una canción desde un archivo elegido o compartido y la pone en cola para separar.
     * Devuelve la canción como la ve la interfaz.
     */
    public JSONObject importFile(InputStream in, String filename, String mime, String preset, String quality)
            throws IOException, ApiException, JSONException {
        if (preset == null || !Stems.PRESETS.containsKey(preset)) {
            preset = api.settings().optString("defaultPreset", Stems.DEFAULT_PRESET);
            if (!Stems.PRESETS.containsKey(preset)) {
                preset = Stems.DEFAULT_PRESET;
            }
        }
        String ext = extensionOf(filename, mime);
        if (ext == null) {
            throw new ApiException(400,
                    "Formato no reconocido. Elige un archivo de audio o video (mp3, wav, flac, m4a, ogg, mp4...).");
        }
        String songId = Json.newId();
        Store.SongFiles paths = store.files(songId);
        paths.create();
        File dest = new File(paths.root, "original" + ext);
        long size = 0;
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                size += n;
                if (size > MAX_UPLOAD_BYTES) {
                    throw new ApiException(413, "El archivo es demasiado grande (máximo 1 GB)");
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException | ApiException e) {
            paths.remove();
            throw e;
        }
        if (size == 0) {
            paths.remove();
            throw new ApiException(400, "El archivo está vacío");
        }
        Map<String, String> tags = platform.readTags(dest);
        String[] guessed = titleFromFilename(filename == null ? "cancion" : filename);
        String title = tags.get("title") != null && !tags.get("title").trim().isEmpty() ? tags.get("title").trim() : guessed[0];
        String artist = tags.get("artist") != null && !tags.get("artist").trim().isEmpty() ? tags.get("artist").trim() : guessed[1];
        JSONObject song = new JSONObject();
        song.put("id", songId);
        song.put("title", title);
        song.put("artist", artist == null ? JSONObject.NULL : artist);
        song.put("source_type", "upload");
        song.put("source_url", JSONObject.NULL);
        song.put("original_filename", filename == null ? JSONObject.NULL : filename);
        song.put("duration", JSONObject.NULL);
        song.put("preset", preset);
        song.put("quality", "normal");
        song.put("model", JSONObject.NULL);
        song.put("status", "queued");
        song.put("stage", "En cola");
        song.put("progress", 0.0);
        song.put("error", JSONObject.NULL);
        song.put("stems", new org.json.JSONArray());
        song.put("lyrics_status", JSONObject.NULL);
        song.put("meta", new JSONObject().put("auto_title", false));
        song.put("settings", new JSONObject());
        store.insertSong(song);
        jobs.enqueue("process", songId, null, "En cola");
        return api.songToApi(store.getSong(songId));
    }

    void cancelActive(String songId) {
        for (JSONObject job : jobs.list(songId, true)) {
            jobs.cancel(job.optString("id"));
            if (job.optString("status").equals("queued")) {
                jobs.update(job.optString("id"), "status", "cancelled", "message", "Cancelado");
            }
        }
    }

    JSONObject retry(String songId, JSONObject body) throws ApiException, InterruptedException {
        JSONObject active = jobs.activeFor(songId, "process");
        // Recién cancelado: el trabajo tarda un momento en detenerse; se espera en vez de rechazar.
        if (active != null && jobs.isCancelling(active.optString("id"))) {
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                JSONObject job = jobs.get(active.optString("id"));
                if (job == null || !(job.optString("status").equals("queued") || job.optString("status").equals("running"))) {
                    active = null;
                    break;
                }
                Thread.sleep(100);
            }
        }
        if (active != null) {
            throw new ApiException(409, "La canción ya se está procesando");
        }
        JSONObject song = store.getSong(songId);
        if (song == null) {
            throw new ApiException(404, "Canción no encontrada");
        }
        String preset = Json.optString(body, "preset");
        if (preset == null) {
            preset = song.optString("preset");
        }
        String quality = Json.optString(body, "quality");
        if (!Stems.PRESETS.containsKey(preset)) {
            throw new ApiException(400, "Tipo de separación desconocido: " + preset);
        }
        if (quality != null && !quality.equals("normal") && !quality.equals("alta")) {
            throw new ApiException(400, "Calidad desconocida: " + quality);
        }
        store.updateSong(songId, "preset", preset, "quality", "normal", "status", "queued", "stage", "En cola",
                "progress", 0.0, "error", null);
        jobs.enqueue("process", songId, null, "En cola");
        return store.getSong(songId);
    }

    // ---- trabajos (Jobs.Handler) ---------------------------------------------------------------

    @Override
    public JSONObject run(JSONObject job, Jobs.Reporter report) throws Exception {
        String kind = job.optString("kind");
        if (kind.equals("process")) {
            return processor.process(job, report);
        }
        if (kind.equals("reanalyze")) {
            return processor.reanalyze(job, report);
        }
        if (kind.equals("export")) {
            String songId = job.optString("song_id");
            JSONObject song = store.getSong(songId);
            if (song == null) {
                throw new IllegalStateException("La canción ya no existe");
            }
            File outDir = new File(exportsDir(), job.optString("id"));
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                throw new IOException("No se pudo crear la carpeta de exportación");
            }
            JSONObject params = job.optJSONObject("params");
            return Exporter.run(song, store.files(songId), params == null ? new JSONObject() : params, outDir, report,
                    platform.version());
        }
        throw new IllegalStateException("Trabajo desconocido: " + kind);
    }

    @Override
    public void failed(JSONObject job, String message, boolean cancelled) {
        String songId = Json.optString(job, "song_id");
        if (songId == null || store.getSong(songId) == null) {
            return;
        }
        String kind = job.optString("kind");
        if (kind.equals("process")) {
            store.updateSong(songId, "status", cancelled ? "cancelled" : "error", "stage",
                    cancelled ? "Cancelado" : "Error", "error", message);
        } else if (kind.equals("reanalyze")) {
            store.updateSong(songId, "status", "ready", "stage", "Lista");
        }
    }

    @Override
    public void finished(JSONObject job) {
        String songId = Json.optString(job, "song_id");
        // Si borraron la canción mientras se procesaba, no dejar archivos huérfanos.
        if (songId != null && Jobs.HEAVY.contains(job.optString("kind")) && store.getSong(songId) == null) {
            try {
                store.files(songId).remove();
            } catch (ApiException ignored) {
                // identificador inválido: no hay carpeta
            }
        }
    }
}
