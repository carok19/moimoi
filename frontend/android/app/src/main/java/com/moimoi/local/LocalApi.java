package com.moimoi.local;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * La API del programa de la computadora (backend/moimoi/api.py), pero dentro del celular: la
 * interfaz le hace los mismos pedidos (método + ruta + JSON) y recibe las mismas respuestas.
 */
public final class LocalApi {

    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    static final List<String> PROCESSING = Arrays.asList("queued", "downloading", "separating", "analyzing");
    private static final int MAX_SETTINGS_BYTES = 512 * 1024;
    private static final String SOON_LINKS =
            "Los links de YouTube en el celular llegan en la próxima versión. Mientras tanto, sube el archivo.";

    private final LocalBackend backend;
    private final Store store;
    private final Jobs jobs;
    private final Platform platform;

    LocalApi(LocalBackend backend) {
        this.backend = backend;
        this.store = backend.store;
        this.jobs = backend.jobs;
        this.platform = backend.platform;
    }

    // ---- entrada -------------------------------------------------------------------------

    public Response handle(String method, String url, String body) {
        try {
            Object result = route(method.toUpperCase(java.util.Locale.ROOT), url, body);
            return new Response(200, result.toString());
        } catch (ApiException e) {
            return error(e.status, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return error(500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Response error(int status, String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("detail", message);
        } catch (JSONException ignored) {
            // no pasa
        }
        return new Response(status, out.toString());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        int q = url.indexOf('?');
        if (q < 0) {
            return out;
        }
        for (String part : url.substring(q + 1).split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // parámetro mal formado: se ignora
            }
        }
        return out;
    }

    private static JSONObject bodyObject(String body) throws ApiException {
        if (body == null || body.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            Object value = new JSONTokener(body).nextValue();
            if (!(value instanceof JSONObject)) {
                throw new ApiException(422, "Se esperaba un objeto JSON");
            }
            return (JSONObject) value;
        } catch (JSONException e) {
            throw new ApiException(422, "JSON inválido");
        }
    }

    private Object route(String method, String url, String body) throws Exception {
        Map<String, String> params = query(url);
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (!path.startsWith("/api/") && !path.equals("/api")) {
            throw new ApiException(404, "No encontrado");
        }
        List<String> parts = new ArrayList<>();
        for (String p : path.substring(4).split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        int n = parts.size();
        String first = n > 0 ? parts.get(0) : "";
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        if (n == 0 && get) {
            return new JSONObject().put("app", "MoiMoi").put("version", platform.version());
        }
        if (first.equals("health") && n == 1 && get) {
            return health();
        }
        if (first.equals("presets") && n == 1 && get) {
            return Stems.presetsJson();
        }
        if (first.equals("settings") && n == 1) {
            if (get) {
                return settings();
            }
            if (method.equals("PUT")) {
                return putSettings(bodyObject(body));
            }
        }
        if (first.equals("songs")) {
            if (n == 1 && get) {
                JSONArray out = new JSONArray();
                for (JSONObject song : store.listSongs()) {
                    out.put(songToApi(song));
                }
                return out;
            }
            if (n == 2 && parts.get(1).equals("url") && post) {
                throw new ApiException(501, SOON_LINKS);
            }
            if (n == 2 && parts.get(1).equals("upload") && post) {
                throw new ApiException(400, "En el celular elige los archivos con el botón \"Subir archivo\".");
            }
            if (n >= 2) {
                return songRoute(method, parts, params, body);
            }
        }
        if (first.equals("jobs")) {
            if (n == 1 && get) {
                JSONArray out = new JSONArray();
                String song = params.get("song");
                for (JSONObject job : jobs.list(song, "true".equals(params.get("active")))) {
                    out.put(jobToApi(job));
                }
                return out;
            }
            if (n >= 2) {
                JSONObject job = jobs.get(parts.get(1));
                if (job == null) {
                    throw new ApiException(404, "Trabajo no encontrado");
                }
                if (n == 2 && get) {
                    return jobToApi(job);
                }
                if (n == 3 && parts.get(2).equals("cancel") && post) {
                    String status = job.optString("status");
                    if (status.equals("queued") || status.equals("running")) {
                        jobs.cancel(job.optString("id"));
                        if (status.equals("queued")) {
                            jobs.update(job.optString("id"), "status", "cancelled", "message", "Cancelado");
                        }
                    }
                    return jobToApi(jobs.get(job.optString("id")));
                }
                if (n == 3 && parts.get(2).equals("enviar") && post) {
                    throw new ApiException(501, "Enviar directo a Multitrack Alabanza desde el celular llega en la "
                            + "próxima versión: usa \"Compartir\" y ábrelo en Multitrack Alabanza.");
                }
                if (n == 3 && parts.get(2).equals("download") && get) {
                    throw new ApiException(400, "Usa Compartir o Guardar en el celular");
                }
            }
        }
        if (first.equals("url-info") || first.equals("search")) {
            throw new ApiException(501, SOON_LINKS);
        }
        if (first.equals("guia")) {
            return guideRoute(method, parts, params, body);
        }
        if (first.equals("multitrack") && n == 1 && get) {
            String target = params.containsKey("url") ? params.get("url") : settings().optString("multitrackUrl");
            return new JSONObject().put("ok", false).put("url", target)
                    .put("error", "Desde el celular, comparte el .zip y ábrelo en Multitrack Alabanza");
        }
        if (first.equals("red") && n == 1 && get) {
            return new JSONObject().put("lanAccess", false).put("listening", false).put("port", 0)
                    .put("httpsPort", JSONObject.NULL).put("http", new JSONArray()).put("https", new JSONArray())
                    .put("local", true);
        }
        throw new ApiException(404, "No encontrado");
    }

    // ---- voz guía -------------------------------------------------------------------------------

    private Object guideRoute(String method, List<String> parts, Map<String, String> params, String body) throws Exception {
        Guide kit = backend.guide;
        int n = parts.size();
        String set = params.get("set");
        if (set != null && set.isEmpty()) {
            set = null;
        }
        try {
            if (n == 1) {
                if (method.equals("GET")) {
                    return kit.describe(set);
                }
                if (method.equals("DELETE")) {
                    // Borra un idioma (?set=es) o todo el paquete de voces.
                    if (set != null) {
                        kit.removeSet(set);
                    } else {
                        kit.clear();
                    }
                    return kit.describe(null);
                }
                if (method.equals("POST")) {
                    return importGuideJson(bodyObject(body));
                }
            }
            if (n == 2 && parts.get(1).equals("incluidas") && method.equals("POST")) {
                // Vuelve a poner las voces y los clicks que trae la app (también los que se borraron).
                kit.installBundled(true);
                return kit.describe(null);
            }
            if (n == 2 && parts.get(1).equals("activo") && method.equals("PUT")) {
                kit.setActive(bodyObject(body).optString("set", null));
                return kit.describe(null);
            }
            if (n == 3 && parts.get(1).equals("clicks") && method.equals("DELETE")) {
                kit.removeClicks(parts.get(2));
                return kit.describe(null);
            }
            if (n == 2 && method.equals("PUT")) {
                JSONObject b = bodyObject(body);
                String cue = b.isNull("cue") || b.optString("cue", "").isEmpty() ? null : b.optString("cue");
                kit.assign(parts.get(1), cue);
                return kit.describe(set);
            }
            if (n == 2 && method.equals("DELETE")) {
                kit.remove(parts.get(1));
                return kit.describe(set);
            }
        } catch (Guide.GuideException e) {
            throw new ApiException(e.status, e.getMessage());
        }
        throw new ApiException(404, "No encontrado");
    }

    /**
     * Voces enviadas por la interfaz como JSON (una grabación de pocos segundos):
     * {"files": [{"name", "data" (base64)}], "cue", "set"}. Los paquetes grandes se eligen con el
     * selector de archivos del celular (MoiMoiLocal.pickGuide), sin pasar por acá.
     */
    private JSONObject importGuideJson(JSONObject request) throws Exception {
        JSONArray files = request.optJSONArray("files");
        if (files == null || files.length() == 0) {
            throw new ApiException(400, "No llegó ningún archivo");
        }
        File dir = new File(platform.cacheDir(), "voz-guia-subida-" + Json.newId());
        if (!dir.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta temporal");
        }
        try {
            List<Guide.Upload> uploads = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < files.length() && i < Guide.MAX_FILES; i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                String name = Guide.fileName(f.optString("name", "voz-" + i + ".wav"));
                byte[] data = Json.unbase64(f.optString("data", ""));
                total += data.length;
                if (total > 50L * 1024 * 1024) {
                    throw new ApiException(413, "Demasiado grande: elige el paquete con \"Cargar paquete\"");
                }
                File target = new File(dir, String.format(java.util.Locale.US, "%04d%s", i, Guide.suffix(name).isEmpty() ? ".bin" : Guide.suffix(name)));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                    out.write(data);
                }
                uploads.add(new Guide.Upload(name, target));
            }
            String cue = request.isNull("cue") || request.optString("cue", "").isEmpty() ? null : request.optString("cue");
            String set = request.isNull("set") || request.optString("set", "").isEmpty() ? null : request.optString("set");
            return backend.importGuide(uploads, cue, set);
        } finally {
            Store.removeTree(dir);
        }
    }

    // ---- estado general ---------------------------------------------------------------------

    private JSONObject health() throws JSONException {
        JSONObject engine = new JSONObject();
        engine.put("available", true);
        engine.put("device", "phone");
        engine.put("gpu", JSONObject.NULL);
        engine.put("detail", platform.engineDetail());
        JSONObject features = new JSONObject();
        features.put("youtube", false);
        features.put("lyrics", false);
        features.put("stretchExport", true);
        features.put("ffmpeg", false);
        features.put("analysis", true);
        features.put("guide", true);
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("app", "MoiMoi");
        out.put("version", platform.version());
        out.put("standalone", true);
        out.put("engine", engine);
        out.put("features", features);
        out.put("dataDir", platform.dataDir().getAbsolutePath());
        return out;
    }

    static JSONObject defaultSettings() throws JSONException {
        JSONObject d = new JSONObject();
        d.put("defaultPreset", Stems.DEFAULT_PRESET);
        d.put("defaultQuality", "normal");
        d.put("band", new JSONArray());
        d.put("notation", "american");
        d.put("countInBars", 1);
        d.put("metronomeVolume", 0.7);
        d.put("metronomeSound", "click");
        d.put("multitrackUrl", "http://127.0.0.1:4848");
        d.put("exportClick", true);
        d.put("exportGuide", true);
        d.put("exportPreRollBars", 1);
        d.put("exportClickSound", "classic"); // viene incluido (si no está, el de MoiMoi)
        d.put("guideNumbering", "verses");
        d.put("guideKeyChanges", true);
        d.put("lanAccess", false);
        return d;
    }

    JSONObject settings() throws JSONException {
        JSONObject out = Json.merge(defaultSettings(), store.settings());
        out.put("defaultQuality", "normal"); // en el celular hay una sola calidad
        return out;
    }

    private JSONObject putSettings(JSONObject body) throws Exception {
        JSONObject defaults = defaultSettings();
        JSONObject allowed = new JSONObject();
        for (String key : Store.keys(body)) {
            if (defaults.has(key) && !key.equals("lanAccess")) {
                allowed.put(key, body.get(key));
            }
        }
        if (allowed.has("defaultPreset") && !Stems.PRESETS.containsKey(allowed.optString("defaultPreset"))) {
            throw new ApiException(400, "Tipo de separación desconocido");
        }
        if (allowed.has("band")) {
            JSONArray band = allowed.optJSONArray("band");
            if (band == null) {
                throw new ApiException(400, "Lista de instrumentos inválida");
            }
            for (int i = 0; i < band.length(); i++) {
                if (!Stems.INFO.containsKey(band.optString(i, ""))) {
                    throw new ApiException(400, "Lista de instrumentos inválida");
                }
            }
        }
        if (allowed.has("exportPreRollBars")) {
            int bars = allowed.optInt("exportPreRollBars", -1);
            if (bars < 0 || bars > 2) {
                throw new ApiException(400, "La cuenta inicial es de 0, 1 o 2 compases");
            }
        }
        if (allowed.has("guideNumbering")
                && !Arrays.asList("verses", "all", "none").contains(allowed.optString("guideNumbering"))) {
            throw new ApiException(400, "Opción de numeración inválida");
        }
        if (allowed.has("exportClickSound")) {
            Object sound = allowed.get("exportClickSound");
            if (!(sound instanceof String) || ((String) sound).isEmpty() || ((String) sound).length() > 40) {
                throw new ApiException(400, "Sonido de click inválido");
            }
        }
        store.setSettings(allowed);
        return settings();
    }

    // ---- canciones ----------------------------------------------------------------------------

    JSONObject songOr404(String id) throws ApiException {
        store.files(id);
        JSONObject song = store.getSong(id);
        if (song == null) {
            throw new ApiException(404, "Canción no encontrada");
        }
        return song;
    }

    JSONObject songToApi(JSONObject song) throws JSONException, ApiException {
        String id = song.optString("id");
        Store.SongFiles paths = store.files(id);
        Stems.Preset preset = Stems.PRESETS.get(song.optString("preset"));
        String version = song.optString("updated_at", "").replace(":", "").replace("-", "").replace("+", "");
        JSONArray stems = new JSONArray();
        List<String> names = new ArrayList<>();
        JSONArray have = song.optJSONArray("stems");
        for (int i = 0; have != null && i < have.length(); i++) {
            names.add(have.optString(i));
        }
        for (String stem : Stems.ordered(names)) {
            Stems.Info info = Stems.INFO.get(stem);
            JSONObject s = new JSONObject();
            s.put("id", stem);
            s.put("name", info != null ? info.name : stem);
            s.put("color", info != null ? info.color : "#9fb3c8");
            s.put("url", platform.fileUrl(paths.stem(stem)) + "?v=" + version);
            stems.put(s);
        }
        JSONObject meta = song.optJSONObject("meta");
        File thumb = paths.thumbnail();
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("title", song.optString("title"));
        out.put("artist", song.has("artist") ? song.opt("artist") : JSONObject.NULL);
        out.put("sourceType", song.optString("source_type", "upload"));
        out.put("sourceUrl", song.has("source_url") ? song.opt("source_url") : JSONObject.NULL);
        out.put("originalFilename", song.has("original_filename") ? song.opt("original_filename") : JSONObject.NULL);
        out.put("duration", song.has("duration") ? song.opt("duration") : JSONObject.NULL);
        out.put("preset", song.optString("preset"));
        out.put("presetName", preset != null ? preset.name : song.optString("preset"));
        out.put("quality", song.optString("quality", "normal"));
        out.put("model", song.has("model") ? song.opt("model") : JSONObject.NULL);
        out.put("status", song.optString("status"));
        out.put("progress", song.optDouble("progress", 0.0));
        out.put("stage", song.has("stage") ? song.opt("stage") : JSONObject.NULL);
        out.put("error", song.has("error") ? song.opt("error") : JSONObject.NULL);
        out.put("stems", stems);
        out.put("summary", meta != null && meta.has("summary") ? meta.opt("summary") : JSONObject.NULL);
        out.put("lyricsStatus", song.has("lyrics_status") ? song.opt("lyrics_status") : JSONObject.NULL);
        JSONObject settings = song.optJSONObject("settings");
        out.put("settings", settings == null ? new JSONObject() : settings);
        out.put("thumbnailUrl", thumb != null ? platform.fileUrl(thumb) + "?v=" + version : JSONObject.NULL);
        out.put("createdAt", song.optString("created_at"));
        out.put("updatedAt", song.optString("updated_at"));
        return out;
    }

    static JSONObject jobToApi(JSONObject job) throws JSONException {
        Object result = job.opt("result");
        boolean done = job.optString("status").equals("done");
        String download = done && job.optString("kind").equals("export") && result instanceof JSONObject
                ? "/api/jobs/" + job.optString("id") + "/download" : null;
        JSONObject out = new JSONObject();
        out.put("id", job.optString("id"));
        out.put("songId", job.opt("song_id"));
        out.put("kind", job.optString("kind"));
        out.put("status", job.optString("status"));
        out.put("progress", job.optDouble("progress", 0.0));
        out.put("message", job.opt("message"));
        out.put("error", job.opt("error"));
        out.put("result", result == null ? JSONObject.NULL : result);
        out.put("downloadUrl", download == null ? JSONObject.NULL : download);
        out.put("createdAt", job.optString("created_at"));
        out.put("startedAt", job.opt("started_at"));
        out.put("finishedAt", job.opt("finished_at"));
        return out;
    }

    private Object songRoute(String method, List<String> parts, Map<String, String> params, String body)
            throws Exception {
        String id = parts.get(1);
        int n = parts.size();
        JSONObject song = songOr404(id);
        Store.SongFiles paths = store.files(id);
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        if (n == 2) {
            if (get) {
                return songToApi(song);
            }
            if (method.equals("PATCH")) {
                return updateSong(song, bodyObject(body));
            }
            if (method.equals("DELETE")) {
                backend.cancelActive(id);
                store.deleteSong(id);
                paths.remove();
                return new JSONObject().put("ok", true);
            }
        }
        String action = parts.get(2);
        if (n == 3 && action.equals("cancel") && post) {
            backend.cancelActive(id);
            if (PROCESSING.contains(song.optString("status"))) {
                store.updateSong(id, "status", "cancelled", "stage", "Cancelado");
            }
            return songToApi(store.getSong(id));
        }
        if (n == 3 && action.equals("retry") && post) {
            return songToApi(backend.retry(id, bodyObject(body)));
        }
        if (n == 3 && action.equals("reanalyze") && post) {
            return jobToApi(backend.reanalyze(id));
        }
        if (n == 3 && action.equals("analysis") && get) {
            JSONObject data = Json.readObject(paths.analysis());
            if (data == null) {
                throw new ApiException(404, "Todavía no hay análisis");
            }
            return data;
        }
        if (n == 3 && action.equals("guia") && get) {
            // Click y Guía para el reproductor (con las voces y el sonido de click elegidos).
            JSONObject data = Json.readObject(paths.analysis());
            if (data == null) {
                throw new ApiException(404, "Todavía no hay análisis");
            }
            return Exporter.playerGuide(song, data, settings(), backend.guide);
        }
        if (n == 3 && action.equals("peaks") && get) {
            JSONObject data = Json.readObject(paths.peaks());
            if (data == null) {
                throw new ApiException(404, "Todavía no hay formas de onda");
            }
            return data;
        }
        if (n == 3 && action.equals("lyrics")) {
            if (get) {
                JSONObject data = Json.readObject(paths.lyrics());
                if (data == null) {
                    throw new ApiException(404, "Todavía no hay letra");
                }
                return data;
            }
            if (post) {
                throw new ApiException(501, "La letra automática todavía no está disponible en el celular.");
            }
            if (method.equals("PUT")) {
                JSONObject request = bodyObject(body);
                JSONArray lines = request.optJSONArray("lines");
                if (lines == null || lines.length() > 2000) {
                    throw new ApiException(422, "Letra inválida");
                }
                JSONObject previous = Json.readObject(paths.lyrics());
                JSONObject data = Json.merge(previous == null ? new JSONObject() : previous,
                        new JSONObject().put("lines", lines).put("edited", true));
                if (Json.optString(request, "language") != null) {
                    data.put("language", request.optString("language"));
                }
                Json.write(paths.lyrics(), data);
                store.updateSong(id, "lyrics_status", "ready");
                return data;
            }
        }
        if (n == 3 && action.equals("exports") && post) {
            JSONObject request = bodyObject(body);
            JSONArray have = song.optJSONArray("stems");
            if (!song.optString("status").equals("ready") && (have == null || have.length() == 0)) {
                throw new ApiException(409, "La canción todavía no está lista");
            }
            JSONArray asked = request.optJSONArray("stems");
            List<String> available = new ArrayList<>();
            for (int i = 0; have != null && i < have.length(); i++) {
                available.add(have.optString(i));
            }
            List<String> unknown = new ArrayList<>();
            for (int i = 0; asked != null && i < asked.length(); i++) {
                if (!available.contains(asked.optString(i))) {
                    unknown.add(asked.optString(i));
                }
            }
            if (!unknown.isEmpty()) {
                java.util.Collections.sort(unknown);
                throw new ApiException(400, "Pistas desconocidas: " + Json.join(", ", unknown));
            }
            return jobToApi(jobs.enqueue("export", id, request, "Preparando exportación…"));
        }
        if (n == 3 && action.equals("thumbnail") && get) {
            throw new ApiException(404, "Sin portada");
        }
        throw new ApiException(404, "No encontrado");
    }

    private JSONObject updateSong(JSONObject song, JSONObject body) throws Exception {
        String id = song.optString("id");
        List<Object> updates = new ArrayList<>();
        String title = Json.optString(body, "title");
        if (title != null && title.length() > 300 || Json.optString(body, "artist") != null
                && Json.optString(body, "artist").length() > 300) {
            throw new ApiException(422, "Texto demasiado largo");
        }
        if (title != null && !title.trim().isEmpty()) {
            updates.add("title");
            updates.add(title.trim());
        }
        if (body.has("artist") && !body.isNull("artist")) {
            String artist = body.optString("artist").trim();
            updates.add("artist");
            updates.add(artist.isEmpty() ? null : artist);
        }
        JSONObject settings = body.optJSONObject("settings");
        if (settings != null) {
            JSONObject current = song.optJSONObject("settings");
            JSONObject merged = Json.merge(current == null ? new JSONObject() : current, settings);
            Iterator<String> keys = Store.keys(merged).iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                if (merged.isNull(key)) {
                    merged.remove(key);
                }
            }
            if (merged.toString().length() > MAX_SETTINGS_BYTES) {
                throw new ApiException(413, "Ajustes demasiado grandes");
            }
            updates.add("settings");
            updates.add(merged);
        }
        if (!updates.isEmpty()) {
            store.updateSong(id, updates.toArray());
        }
        return songToApi(store.getSong(id));
    }

    /** Archivo para compartir o guardar a partir de su URL en la interfaz. */
    public static final class Download {
        public final File file;
        public final String name;
        public final String mime;

        Download(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    public Download resolveDownload(String url, String name) throws ApiException, IOException {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int slash = path.indexOf('/', path.indexOf("//") + 2);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        String fileUrlPrefix = platform.fileUrl(new File("/")).replaceAll("/+$", "");
        if (path.startsWith(fileUrlPrefix + "/")) {
            File file = new File(path.substring(fileUrlPrefix.length()));
            String canonical = file.getCanonicalPath();
            if (!canonical.startsWith(platform.dataDir().getCanonicalPath())
                    && !canonical.startsWith(platform.cacheDir().getCanonicalPath())) {
                throw new ApiException(403, "Archivo no permitido");
            }
            if (!file.isFile()) {
                throw new ApiException(404, "El archivo ya no existe");
            }
            return new Download(file, name != null ? name : file.getName(), mimeOf(file.getName()));
        }
        String[] parts = path.split("/");
        // /api/jobs/<id>/download
        if (parts.length == 5 && parts[2].equals("jobs") && parts[4].equals("download")) {
            JSONObject job = jobs.get(parts[3]);
            JSONObject result = job == null ? null : job.optJSONObject("result");
            if (job == null || !job.optString("status").equals("done") || result == null) {
                throw new ApiException(404, "La exportación no está lista");
            }
            File file = new File(new File(backend.exportsDir(), parts[3]), new File(result.optString("file")).getName());
            if (!file.isFile()) {
                throw new ApiException(410, "La exportación ya se borró; vuelve a exportar");
            }
            return new Download(file, result.optString("name", file.getName()), result.optString("mime", mimeOf(file.getName())));
        }
        // /api/songs/<id>/download/<pista>.<formato>
        if (parts.length == 6 && parts[2].equals("songs") && parts[4].equals("download")) {
            JSONObject song = songOr404(parts[3]);
            String file = parts[5];
            int dot = file.lastIndexOf('.');
            String stem = dot < 0 ? file : file.substring(0, dot);
            String fmt = dot < 0 ? "wav" : file.substring(dot + 1);
            JSONArray have = song.optJSONArray("stems");
            boolean exists = false;
            for (int i = 0; have != null && i < have.length(); i++) {
                exists |= have.optString(i).equals(stem);
            }
            if (!exists || !store.files(parts[3]).stem(stem).isFile()) {
                throw new ApiException(404, "Esa pista no existe");
            }
            if (!fmt.equals("wav")) {
                throw new ApiException(400, "En el celular las pistas se guardan en WAV (elige WAV).");
            }
            Stems.Info info = Stems.INFO.get(stem);
            String display = Exporter.safeFilename(Exporter.displayName(song)) + " - " + (info != null ? info.name : stem) + ".wav";
            return new Download(store.files(parts[3]).stem(stem), display, Exporter.MIME_WAV);
        }
        throw new ApiException(404, "No encontrado");
    }

    static String mimeOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".zip")) {
            return Exporter.MIME_ZIP;
        }
        if (lower.endsWith(".wav")) {
            return Exporter.MIME_WAV;
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }
}
