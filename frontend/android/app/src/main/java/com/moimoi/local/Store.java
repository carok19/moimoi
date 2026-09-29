package com.moimoi.local;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Canciones y ajustes guardados en el celular: cada canción en songs/&lt;id&gt;/cancion.json (con los
 * mismos campos que la tabla "songs" del programa de la computadora) junto a sus pistas.
 *
 * El avance de un trabajo (progress, stage) cambia varias veces por segundo: se guarda en memoria y
 * se escribe al disco solo cuando cambia algo más (o cada tanto).
 */
public final class Store {

    public static final String SONG_FILE = "cancion.json";

    private final File songsDir;
    private final File settingsFile;
    private final Map<String, JSONObject> songs = new LinkedHashMap<>();
    private final Map<String, Long> lastWrite = new LinkedHashMap<>();
    private JSONObject settings;

    public Store(File dataDir) {
        songsDir = new File(dataDir, "songs");
        settingsFile = new File(dataDir, "ajustes.json");
        load();
    }

    private void load() {
        List<JSONObject> found = new ArrayList<>();
        File[] dirs = songsDir.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                JSONObject song = Json.readObject(new File(dir, SONG_FILE));
                if (song != null && dir.getName().equals(song.optString("id"))) {
                    found.add(song);
                }
            }
        }
        Collections.sort(found, (a, b) -> a.optString("created_at").compareTo(b.optString("created_at")));
        for (JSONObject song : found) {
            songs.put(song.optString("id"), song);
        }
        settings = Json.readObject(settingsFile);
        if (settings == null) {
            settings = new JSONObject();
        }
    }

    public File songsDir() {
        return songsDir;
    }

    public SongFiles files(String songId) throws ApiException {
        return new SongFiles(songsDir, songId);
    }

    // ---- canciones ------------------------------------------------------------------

    /** Más nuevas primero. */
    public synchronized List<JSONObject> listSongs() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject song : songs.values()) {
            out.add(Json.copy(song));
        }
        Collections.reverse(out);
        return out;
    }

    public synchronized JSONObject getSong(String id) {
        return Json.copy(songs.get(id));
    }

    public synchronized void insertSong(JSONObject song) throws IOException {
        String stamp = Json.nowIso();
        JSONObject copy = Json.copy(song);
        try {
            if (!copy.has("created_at")) {
                copy.put("created_at", stamp);
            }
            copy.put("updated_at", stamp);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        songs.put(copy.optString("id"), copy);
        persist(copy.optString("id"));
    }

    /** Como db.update_song: cambia los campos dados y la fecha de actualización. */
    public synchronized void updateSong(String id, Object... keyValues) {
        JSONObject song = songs.get(id);
        if (song == null) {
            return;
        }
        boolean onlyProgress = true;
        try {
            for (int i = 0; i < keyValues.length; i += 2) {
                String key = (String) keyValues[i];
                Object value = keyValues[i + 1];
                song.put(key, value == null ? JSONObject.NULL : value);
                if (!key.equals("progress") && !key.equals("stage")) {
                    onlyProgress = false;
                }
            }
            song.put("updated_at", Json.nowIso());
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        Long last = lastWrite.get(id);
        long now = System.currentTimeMillis();
        if (!onlyProgress || last == null || now - last > 5000) {
            try {
                persist(id);
            } catch (IOException ignored) {
                // se vuelve a intentar en el próximo cambio
            }
        }
    }

    public synchronized void deleteSong(String id) {
        songs.remove(id);
        lastWrite.remove(id);
    }

    private void persist(String id) throws IOException {
        JSONObject song = songs.get(id);
        if (song == null) {
            return;
        }
        Json.write(new File(new File(songsDir, id), SONG_FILE), song);
        lastWrite.put(id, System.currentTimeMillis());
    }

    // ---- ajustes --------------------------------------------------------------------

    public synchronized JSONObject settings() {
        return Json.copy(settings);
    }

    public synchronized void setSettings(JSONObject values) throws IOException {
        try {
            settings = Json.merge(settings, values);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Json.write(settingsFile, settings);
    }

    /** Borra una carpeta con todo su contenido. */
    public static void removeTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                removeTree(child);
            }
        }
        file.delete();
    }

    /** Ubicación de los archivos de una canción (como storage.SongPaths). */
    public static final class SongFiles {
        public final File root;

        SongFiles(File songsDir, String songId) throws ApiException {
            if (songId == null || songId.isEmpty() || songId.length() > 64
                    || songId.contains("/") || songId.contains("\\") || songId.contains(".")) {
                throw new ApiException(404, "Canción no encontrada");
            }
            root = new File(songsDir, songId);
        }

        public File stemsDir() {
            return new File(root, "pistas");
        }

        public File stem(String stem) {
            return new File(stemsDir(), stem + ".wav");
        }

        public File analysis() {
            return new File(root, "analisis.json");
        }

        public File peaks() {
            return new File(root, "picos.json");
        }

        public File lyrics() {
            return new File(root, "letra.json");
        }

        public File source() {
            File[] files = root.listFiles();
            if (files == null) {
                return null;
            }
            List<File> list = new ArrayList<>();
            Collections.addAll(list, files);
            Collections.sort(list);
            for (File f : list) {
                String name = f.getName();
                if (name.startsWith("original.") && !name.endsWith(".part") && !name.endsWith(".tmp")) {
                    return f;
                }
            }
            return null;
        }

        public File thumbnail() {
            for (String ext : new String[] {".jpg", ".jpeg", ".png", ".webp"}) {
                File f = new File(root, "portada" + ext);
                if (f.isFile()) {
                    return f;
                }
            }
            return null;
        }

        public void create() throws IOException {
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("No se pudo crear la carpeta de la canción");
            }
        }

        public void remove() {
            removeTree(root);
        }
    }

    /** Claves de un objeto como lista (Android no tiene JSONObject.keySet()). */
    public static List<String> keys(JSONObject object) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = object.keys();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }
}
