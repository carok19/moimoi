import com.moimoi.engine.DemucsSeparator;
import com.moimoi.local.ApiException;
import com.moimoi.local.LocalApi;
import com.moimoi.local.LocalBackend;
import com.moimoi.local.Pcm;
import com.moimoi.local.Platform;
import com.moimoi.local.Wav;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Prueba del "servidor" del celular (com.moimoi.local) en la computadora, con el modelo ONNX:
 *
 *   java PruebaLocal carpeta_modelo [hilos]
 *
 * Agrega canciones desde WAV (mono a 48 kHz, para probar también la conversión), las separa en
 * 2, 4 y 6 pistas, revisa las pistas, las formas de onda, las exportaciones (.zip y mezcla), los
 * ajustes, el análisis (tempo, tonalidad, acordes, partes; volver a analizar), cancelar/reintentar,
 * borrar y que se retome lo que quedó a medias al reabrir la app.
 */
public class PruebaLocal {

    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FALLÓ: " + what);
        }
    }

    /** Plataforma de prueba: decodifica solo WAV y usa el modelo de la carpeta. */
    static final class DesktopPlatform implements Platform {
        final File data;
        final File cache;
        final File model;
        final int threads;
        volatile int workingChanges = 0;
        /** Carpeta con lo que la app trae adentro (recursos/ del repositorio); null = nada. */
        File bundled;

        DesktopPlatform(File root, File model, int threads) {
            data = new File(root, "datos");
            cache = new File(root, "cache");
            data.mkdirs();
            cache.mkdirs();
            this.model = model;
            this.threads = threads;
        }

        public File dataDir() {
            return data;
        }

        public File cacheDir() {
            return cache;
        }

        public Decoded decode(File source, File out, Pcm.Cancel cancel, Progress progress) throws IOException {
            try (Wav.Reader in = new Wav.Reader(source); Pcm.Writer w = new Pcm.Writer(out, in.channels)) {
                int block = 1 << 14;
                float[][] ch = new float[in.channels][block];
                float[] inter = new float[block * in.channels];
                for (long start = 0; start < in.frames; start += block) {
                    int n = (int) Math.min(block, in.frames - start);
                    for (int c = 0; c < in.channels; c++) {
                        in.read(c, start, ch[c], 0, n);
                    }
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < in.channels; c++) {
                            inter[i * in.channels + c] = ch[c][i];
                        }
                    }
                    w.writeInterleaved(inter, n);
                    progress.report((start + n) / (double) in.frames);
                }
                return new Decoded(in.sampleRate, in.channels);
            }
        }

        public DemucsSeparator openSeparator() throws Exception {
            JSONObject meta = new JSONObject(new String(Files.readAllBytes(new File(model, "htdemucs_6s.json").toPath()), "UTF-8"));
            JSONArray src = meta.getJSONArray("sources");
            String[] sources = new String[src.length()];
            for (int i = 0; i < sources.length; i++) {
                sources[i] = src.getString(i);
            }
            DemucsSeparator.ModelInfo info = new DemucsSeparator.ModelInfo(sources, meta.getInt("samplerate"),
                    meta.getInt("segmentSamples"));
            return new DemucsSeparator(new File(model, "htdemucs_6s.onnx").getAbsolutePath(), info, threads);
        }

        public Map<String, String> readTags(File source) {
            return Collections.emptyMap();
        }

        public String fileUrl(File file) {
            return "/_capacitor_file_" + file.getAbsolutePath();
        }

        public void working(boolean active) {
            workingChanges++;
        }

        public void progress(String title, double fraction, String message) {
        }

        public String version() {
            return "prueba";
        }

        public String engineDetail() {
            return "Demucs 6 pistas (prueba)";
        }

        public InputStream openBundled(String path) throws IOException {
            File f = bundled == null ? null : new File(bundled, path);
            return f != null && f.isFile() ? new FileInputStream(f) : null;
        }
    }

    static byte[] testWav(double seconds, int rate, int channels, long seed) throws IOException {
        int n = (int) (seconds * rate);
        java.util.Random rng = new java.util.Random(seed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Wav.Writer w = new Wav.Writer(out, channels, rate, n);
        float[] l = new float[n];
        float[] r = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double hit = Math.sin(2 * Math.PI * 2 * t) > 0.95 ? rng.nextGaussian() * 0.3 : 0;
            l[i] = (float) (0.25 * Math.sin(2 * Math.PI * 220 * t) + 0.12 * Math.sin(2 * Math.PI * 330 * t) + hit);
            r[i] = (float) (0.25 * Math.sin(2 * Math.PI * 110 * t + 0.3) + 0.05 * rng.nextGaussian() + hit);
        }
        w.write(l, r, n);
        w.finish();
        return out.toByteArray();
    }

    static JSONObject call(LocalBackend b, String method, String path, String body, int expect) throws Exception {
        LocalApi.Response r = b.request(method, path, body);
        check(r.status == expect, method + " " + path + " -> " + r.status + " (esperaba " + expect + "): " + r.body);
        String text = r.body.trim();
        return text.startsWith("[") ? new JSONObject().put("list", new JSONArray(text)) : new JSONObject(text);
    }

    static JSONObject waitSong(LocalBackend b, String id, String status, double timeoutS) throws Exception {
        long end = System.currentTimeMillis() + (long) (timeoutS * 1000);
        JSONObject song = null;
        while (System.currentTimeMillis() < end) {
            song = call(b, "GET", "/api/songs/" + id, null, 200);
            if (song.getString("status").equals(status)) {
                return song;
            }
            if (song.getString("status").equals("error")) {
                throw new AssertionError("Error al procesar: " + song.optString("error"));
            }
            Thread.sleep(200);
        }
        throw new AssertionError("La canción no llegó a " + status + ": " + song);
    }

    static JSONObject waitJob(LocalBackend b, String id, double timeoutS) throws Exception {
        long end = System.currentTimeMillis() + (long) (timeoutS * 1000);
        while (System.currentTimeMillis() < end) {
            JSONObject job = call(b, "GET", "/api/jobs/" + id, null, 200);
            String status = job.getString("status");
            if (status.equals("done") || status.equals("error") || status.equals("cancelled")) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("El trabajo no terminó: " + id);
    }

    static float[][] readStem(File file) throws IOException {
        try (Wav.Reader r = new Wav.Reader(file)) {
            check(r.channels == 2 && r.sampleRate == 44100 && r.bits == 16, "pista WAV estéreo 16 bits 44,1 kHz");
            float[][] out = new float[2][(int) r.frames];
            r.read(0, 0, out[0], 0, out[0].length);
            r.read(1, 0, out[1], 0, out[1].length);
            return out;
        }
    }

    static File stemFile(JSONObject song, String stem) throws Exception {
        for (int i = 0; i < song.getJSONArray("stems").length(); i++) {
            JSONObject s = song.getJSONArray("stems").getJSONObject(i);
            if (s.getString("id").equals(stem)) {
                String url = s.getString("url");
                return new File(url.substring("/_capacitor_file_".length(), url.indexOf('?')));
            }
        }
        throw new AssertionError("No está la pista " + stem);
    }

    public static void main(String[] args) throws Exception {
        File model = new File(args[0]);
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        File root = Files.createTempDirectory("moimoi-local").toFile();
        DesktopPlatform platform = new DesktopPlatform(root, model, threads);
        LocalBackend b = LocalBackend.create(platform);
        long t0 = System.currentTimeMillis();

        // ---- estado general y ajustes
        JSONObject health = call(b, "GET", "/api/health", null, 200);
        check(health.getJSONObject("engine").getBoolean("available"), "motor disponible");
        check(!health.getJSONObject("features").getBoolean("youtube"), "sin YouTube todavía");
        check(health.getJSONObject("features").getBoolean("analysis"), "análisis en el celular");
        JSONObject presets = call(b, "GET", "/api/presets", null, 200);
        check(presets.getJSONArray("presets").length() == 3 && presets.getString("default").equals("6stems"), "presets");
        JSONObject settings = call(b, "GET", "/api/settings", null, 200);
        check(settings.getString("defaultPreset").equals("6stems") && settings.getInt("exportPreRollBars") == 1, "ajustes por defecto");
        call(b, "PUT", "/api/settings", "{\"defaultPreset\":\"nada\"}", 400);
        call(b, "PUT", "/api/settings", "{\"band\":[\"vocals\",\"xx\"]}", 400);
        settings = call(b, "PUT", "/api/settings", "{\"band\":[\"drums\",\"bass\"],\"notation\":\"latin\",\"otra\":1}", 200);
        check(settings.getJSONArray("band").length() == 2 && settings.getString("notation").equals("latin")
                && !settings.has("otra"), "guardar ajustes");
        call(b, "GET", "/api/songs/zzz", null, 404);
        call(b, "GET", "/api/songs/../x", null, 404);
        call(b, "POST", "/api/songs/url", "{\"url\":\"https://youtu.be/x\"}", 501);
        check(call(b, "GET", "/api/guia", null, 200).getInt("count") == 0, "kit de voces vacío");

        // ---- agregar y separar (mono a 48 kHz: se convierte a 44,1 kHz estéreo)
        byte[] wav = testWav(9.0, 48000, 1, 1);
        JSONObject six = b.importFile(new ByteArrayInputStream(wav), "01 - Artista Prueba - Mi Canción (Official Video).wav",
                "audio/wav", "6stems", "normal");
        check(six.getString("title").equals("Mi Canción") && six.getString("artist").equals("Artista Prueba"),
                "título y artista desde el nombre: " + six);
        // "CANCIÓN - VIDEO OFICIAL - Artista" (como en YouTube): el título va antes de "VIDEO OFICIAL".
        String[] coritos = com.moimoi.local.LocalBackend.titleFromFilename(
                "CORITOS - VIDEO OFICIAL -Miel San Marcos Ft Marcos Witt, Daniel Calveti e Ingrid Rosario.mp3");
        check(coritos[0].equals("CORITOS") && "Miel San Marcos Ft Marcos Witt, Daniel Calveti e Ingrid Rosario".equals(coritos[1]),
                "título y artista con VIDEO OFICIAL en el medio: " + java.util.Arrays.toString(coritos));
        String[] plain = com.moimoi.local.LocalBackend.titleFromFilename("Re-encuentro.mp3");
        check(plain[0].equals("Re-encuentro") && plain[1] == null, "guion dentro de una palabra: " + java.util.Arrays.toString(plain));
        check(six.getString("status").equals("queued") && six.getString("preset").equals("6stems"), "en cola");
        JSONObject two = b.importFile(new ByteArrayInputStream(wav), "otra.wav", "audio/wav", "2stems", "alta");
        check(two.getString("quality").equals("normal"), "en el celular hay una sola calidad");
        JSONObject list = call(b, "GET", "/api/songs", null, 200);
        check(list.getJSONArray("list").length() == 2
                && list.getJSONArray("list").getJSONObject(0).getString("id").equals(two.getString("id")), "más nuevas primero");
        check(call(b, "GET", "/api/jobs?active=true", null, 200).getJSONArray("list").length() == 2, "dos trabajos activos");

        six = waitSong(b, six.getString("id"), "ready", 300);
        two = waitSong(b, two.getString("id"), "ready", 300);
        long expected = (long) Math.ceil(9.0 * 48000 * 147 / 160.0);
        check(Math.abs(six.getDouble("duration") - expected / 44100.0) < 0.001, "duración " + six.getDouble("duration"));
        check(six.getJSONArray("stems").length() == 6 && six.getString("model").equals("htdemucs_6s"), "6 pistas");
        check(six.getJSONArray("stems").getJSONObject(0).getString("id").equals("vocals"), "orden de pistas");
        check(two.getJSONArray("stems").length() == 2, "2 pistas");
        check(six.getDouble("progress") == 1.0 && six.getString("stage").equals("Lista"), "lista");

        // Las pistas: largo exacto, y "Acompañamiento" = suma de todo menos la voz.
        float[][] v6 = readStem(stemFile(six, "vocals"));
        float[][] v2 = readStem(stemFile(two, "vocals"));
        float[][] inst = readStem(stemFile(two, "instrumental"));
        check(v6[0].length == expected && inst[0].length == expected, "largo de las pistas " + v6[0].length);
        double maxDiff = 0, maxSum = 0, peak = 0;
        String[] others = {"drums", "bass", "other", "guitar", "piano"};
        float[][][] parts = new float[others.length][][];
        for (int k = 0; k < others.length; k++) {
            parts[k] = readStem(stemFile(six, others[k]));
        }
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < expected; i++) {
                maxDiff = Math.max(maxDiff, Math.abs(v6[c][i] - v2[c][i]));
                double sum = 0;
                for (float[][] p : parts) {
                    sum += p[c][i];
                }
                maxSum = Math.max(maxSum, Math.abs(sum - inst[c][i]));
                peak = Math.max(peak, Math.max(Math.abs(v6[c][i]), Math.abs(sum)));
            }
        }
        check(maxDiff < 1e-4, "la voz es la misma con 2 y 6 pistas (" + maxDiff + ")");
        check(maxSum < 5e-4, "acompañamiento = suma de las otras 5 (" + maxSum + ")");
        check(peak > 0.001, "las pistas no están vacías");

        // Formas de onda.
        JSONObject peaks = call(b, "GET", "/api/songs/" + six.getString("id") + "/peaks", null, 200);
        int buckets = (int) Math.ceil(expected / 1764.0);
        check(peaks.getInt("perSecond") == 25, "picos por segundo");
        for (String key : new String[] {"vocals", "drums", "bass", "guitar", "piano", "other", "mix"}) {
            String b64 = peaks.getJSONObject("peaks").getString(key);
            check(java.util.Base64.getDecoder().decode(b64).length == buckets, "picos de " + key);
        }

        // ---- análisis (tempo, compás, tonalidad, acordes, partes, instrumentos)
        JSONObject analysis = call(b, "GET", "/api/songs/" + six.getString("id") + "/analysis", null, 200);
        check(analysis.getInt("version") == com.moimoi.analysis.Analyzer.VERSION && Math.abs(analysis.getDouble("duration") - six.getDouble("duration")) < 0.01,
                "análisis: versión y duración " + analysis.optDouble("duration"));
        check(analysis.getJSONObject("instruments").length() == 6 && analysis.getJSONObject("key").has("label")
                && analysis.getJSONArray("sections").length() >= 1 && analysis.getJSONArray("chords").length() >= 1
                && analysis.getJSONObject("tempo").has("beatsPerBar") && analysis.getJSONObject("tuning").has("a4"),
                "análisis completo: " + analysis.getJSONObject("summary"));
        check(!six.isNull("summary") && six.getJSONObject("summary").has("key")
                && six.getJSONObject("summary").getJSONArray("instruments").length() <= 6, "resumen en la canción");
        JSONArray sectionsFound = analysis.getJSONArray("sections");
        check(sectionsFound.getJSONObject(0).getDouble("start") == 0.0
                && Math.abs(sectionsFound.getJSONObject(sectionsFound.length() - 1).getDouble("end") - analysis.getDouble("duration")) < 0.01,
                "las partes cubren toda la canción");
        JSONObject analysisTwo = call(b, "GET", "/api/songs/" + two.getString("id") + "/analysis", null, 200);
        check(analysisTwo.getJSONObject("instruments").length() == 2
                && analysisTwo.getJSONObject("instruments").has("instrumental"), "análisis con 2 pistas");
        boolean hasBeats = analysis.getJSONArray("beats").length() >= 4;
        System.out.println("análisis: " + analysis.getJSONObject("summary") + " (" + analysis.getJSONArray("beats").length() + " pulsos)");

        // Volver a analizar.
        JSONObject again = call(b, "POST", "/api/songs/" + two.getString("id") + "/reanalyze", null, 200);
        check(again.getString("kind").equals("reanalyze"), "volver a analizar: " + again);
        check(waitJob(b, again.getString("id"), 120).getString("status").equals("done"), "reanálisis terminado");
        two = waitSong(b, two.getString("id"), "ready", 60);
        check(two.getString("stage").equals("Lista") && !two.isNull("summary"), "lista después de reanalizar");
        call(b, "POST", "/api/songs/zzz/reanalyze", null, 404);

        // ---- editar
        JSONObject edited = call(b, "PATCH", "/api/songs/" + six.getString("id"),
                "{\"title\":\" Nuevo \",\"settings\":{\"tempo\":0.9,\"loop\":null}}", 200);
        check(edited.getString("title").equals("Nuevo") && edited.getJSONObject("settings").getDouble("tempo") == 0.9
                && !edited.getJSONObject("settings").has("loop"), "editar");

        // ---- exportar: pistas sueltas (.zip)
        JSONObject job = call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"stems\",\"stems\":[\"bass\",\"vocals\"],\"format\":\"wav\"}", 200);
        job = waitJob(b, job.getString("id"), 60);
        check(job.getString("status").equals("done"), "exportación de pistas: " + job);
        LocalApi.Download zip = b.resolveDownload(job.getString("downloadUrl"), null);
        check(zip.name.equals("Artista Prueba - Nuevo (pistas).zip") && zip.mime.equals("application/zip"), "nombre del zip " + zip.name);
        Map<String, byte[]> entries = unzip(zip.file);
        check(entries.keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("Voz.wav", "Bajo.wav", "moimoi.json"))),
                "contenido del zip " + entries.keySet());
        check(java.util.Arrays.equals(entries.get("Voz.wav"), Files.readAllBytes(stemFile(six, "vocals").toPath())), "Voz.wav igual a la pista");
        JSONObject manifest = new JSONObject(new String(entries.get("moimoi.json"), "UTF-8"));
        check(manifest.getString("formato").equals("moimoi-multitrack") && manifest.getJSONArray("pistas").length() == 2
                && manifest.getJSONArray("pistas").getJSONObject(0).getString("archivo").equals("Voz.wav"), "moimoi.json");
        check(manifest.getJSONObject("cancion").getLong("duracionMs") == Math.round(six.getDouble("duration") * 1000), "duración en moimoi.json");

        // Paquete para Multitrack con click y un compás de cuenta (si se detectó el pulso).
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"click\":true,\"preRollBars\":1}", 200).getString("id"), 60);
        if (hasBeats) {
            check(job.getString("status").equals("done"), "paquete con click: " + job);
            Map<String, byte[]> withClick = unzip(b.resolveDownload(job.getString("downloadUrl"), null).file);
            check(withClick.containsKey("Click.wav") && withClick.containsKey("moimoi.json"), "Click.wav en el paquete " + withClick.keySet());
            JSONObject m = new JSONObject(new String(withClick.get("moimoi.json"), "UTF-8"));
            check(m.toString().contains("Click.wav"), "el click en moimoi.json");
        } else {
            check(job.getString("status").equals("error") && job.getString("error").contains("pulso"), "click sin pulso: " + job);
        }
        // Tempo corregido en el reproductor (la grilla queda guardada en la canción): el click del
        // paquete va en esos pulsos, no en los del análisis.
        double songLength = six.getDouble("duration");
        JSONArray customBeats = new JSONArray(), customDowns = new JSONArray();
        java.util.List<Double> gridBeats = new java.util.ArrayList<>();
        for (double t = 0.5; t < songLength - 0.3; t += 0.6) {
            gridBeats.add(t);
            customBeats.put(t);
            if (gridBeats.size() % 4 == 1) {
                customDowns.put(t);
            }
        }
        call(b, "PATCH", "/api/songs/" + six.getString("id"), new JSONObject().put("settings", new JSONObject().put("grid",
                new JSONObject().put("beats", customBeats).put("downbeats", customDowns).put("bpm", 100))).toString(), 200);
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"click\":true}", 200).getString("id"), 60);
        check(job.getString("status").equals("done"), "paquete con el tempo corregido: " + job);
        float[] clickSamples = readWav(unzip(b.resolveDownload(job.getString("downloadUrl"), null).file).get("Click.wav"))[0];
        java.util.List<Double> onsets = new java.util.ArrayList<>();
        int quiet = 0;
        for (int i = 0; i < clickSamples.length; i++) {
            if (Math.abs(clickSamples[i]) > 0.05) {
                if (quiet > 2205) {
                    onsets.add(i / 44100.0);
                }
                quiet = 0;
            } else {
                quiet++;
            }
        }
        if (!onsets.isEmpty() && onsets.get(0) < 0.05 && gridBeats.get(0) > 0.1) {
            onsets.remove(0);
        }
        boolean aligned = onsets.size() == gridBeats.size();
        for (int i = 0; aligned && i < onsets.size(); i++) {
            aligned = Math.abs(onsets.get(i) - gridBeats.get(i)) < 0.01;
        }
        check(aligned, "el click del paquete sigue el tempo corregido: " + onsets.size() + " golpes, se esperaban " + gridBeats.size());
        call(b, "PATCH", "/api/songs/" + six.getString("id"), "{\"settings\":{\"grid\":null}}", 200);
        // ---- voz guía: un paquete .zip (español, números, sonidos de click y cosas que no son voces)
        File packDir = new File(root, "paquete");
        packDir.mkdirs();
        File pack = new File(packDir, "Voces Guia Spanish.zip");
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(pack))) {
            String[] entriesInPack = {"Guias/Spanish - Intro.wav", "Guias/Spanish - Verso 1.wav", "Guias/Spanish - Coro.wav",
                    "Guias/Spanish - Puente.wav", "Guias/Spanish - Final.wav", "Numeros/1.wav", "Numeros/2.wav", "Numeros/3.wav",
                    "Numeros/4.wav", "Click/New Click - Classic-accents.wav", "Click/New Click - Classic-quarters.wav",
                    "__MACOSX/._Coro.wav", "leeme.txt", "Guias/Silencio.wav"};
            for (String entry : entriesInPack) {
                zout.putNextEntry(new ZipEntry(entry));
                if (entry.endsWith(".txt")) {
                    zout.write("hola".getBytes("UTF-8"));
                } else if (entry.contains("Silencio")) {
                    zout.write(silentWav(0.5));
                } else {
                    zout.write(testWav(entry.contains("Click") ? 0.05 : 0.7, 44100, 1, entry.hashCode()));
                }
                zout.closeEntry();
            }
        }
        JSONObject kit = b.importGuide(java.util.Arrays.asList(new com.moimoi.local.Guide.Upload(pack.getName(), pack)), null, null);
        JSONObject summary = kit.getJSONObject("summary");
        System.out.println("voz guía: " + summary);
        check(summary.getInt("added") == 9 && summary.getInt("recognized") == 9 && summary.getInt("clicks") == 1,
                "paquete de voces: 9 voces reconocidas y 1 sonido de click: " + summary);
        check(summary.getJSONArray("sets").length() == 1 && summary.getJSONArray("sets").getString(0).equals("es"), "idioma español");
        check(summary.getJSONArray("skipped").length() == 1, "el audio sin sonido se ignora");
        check(kit.getString("active").equals("es") && kit.getInt("count") == 9, "voces en uso: " + kit.getInt("count"));
        check(kit.getJSONArray("clicks").length() == 1 && kit.getJSONArray("clicks").getJSONObject(0).getString("name").equals("Classic")
                && kit.getJSONArray("clicks").getJSONObject(0).getJSONObject("sounds").length() == 2, "sonidos de click: " + kit.getJSONArray("clicks"));
        JSONObject listed = call(b, "GET", "/api/guia", null, 200);
        check(listed.getJSONArray("files").length() == 9 && listed.getJSONArray("missing").length() > 0, "lista de voces");
        String someFile = listed.getJSONArray("files").getJSONObject(0).getString("id");
        String url = listed.getJSONArray("files").getJSONObject(0).getString("url");
        check(url.startsWith("/_capacitor_file_/") && new File(url.substring("/_capacitor_file_".length())).isFile(), "audio de la voz: " + url);
        // Reasignar, grabar una voz (llega en base64) y borrar.
        JSONObject reassigned = call(b, "PUT", "/api/guia/" + someFile, "{\"cue\":\"tag\"}", 200);
        boolean tagged = false;
        for (int i = 0; i < reassigned.getJSONArray("cues").length(); i++) {
            JSONObject c = reassigned.getJSONArray("cues").getJSONObject(i);
            tagged |= c.getString("id").equals("tag") && someFile.equals(c.optString("file"));
        }
        check(tagged, "reasignar una voz");
        call(b, "PUT", "/api/guia/" + someFile, "{\"cue\":\"nada\"}", 400);
        call(b, "PUT", "/api/guia/zzzzzzzzzzzz", "{\"cue\":\"coro\"}", 404);
        byte[] recorded = testWav(0.8, 48000, 1, 7);
        JSONObject rec = call(b, "POST", "/api/guia", new JSONObject().put("files", new JSONArray().put(new JSONObject()
                .put("name", "grabacion-precoro.wav").put("data", com.moimoi.local.Json.base64(recorded, recorded.length))))
                .put("cue", "precoro").put("set", "es").toString(), 200);
        check(rec.getJSONObject("summary").getInt("added") == 1 && rec.getInt("count") == 10, "grabación en la app: " + rec.getJSONObject("summary"));
        call(b, "PUT", "/api/guia/activo", "{\"set\":\"xx\"}", 404);
        check(call(b, "PUT", "/api/guia/activo", "{\"set\":\"es\"}", 200).getString("active").equals("es"), "elegir idioma");

        // Paquete para Multitrack con la Guía (y el click del paquete si hay pulso).
        String guideRequest = hasBeats
                ? "{\"type\":\"multitrack\",\"guide\":true,\"click\":true,\"clickSound\":\"classic\",\"preRollBars\":1}"
                : "{\"type\":\"multitrack\",\"guide\":true}";
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports", guideRequest, 200).getString("id"), 60);
        check(job.getString("status").equals("done"), "paquete con voz guía: " + job);
        Map<String, byte[]> withGuide = unzip(b.resolveDownload(job.getString("downloadUrl"), null).file);
        check(withGuide.containsKey("Guia.wav"), "Guia.wav en el paquete " + withGuide.keySet());
        JSONObject guideManifest = new JSONObject(new String(withGuide.get("moimoi.json"), "UTF-8"));
        System.out.println("guía en moimoi.json: " + guideManifest.getJSONArray("guia"));
        if (hasBeats) {
            check(guideManifest.getJSONArray("guia").length() >= 4
                    && guideManifest.getJSONArray("guia").getJSONObject(0).getString("voz").equals("n1"), "la Guía cuenta 1, 2, 3, 4");
            float[][] guideTrack = readWav(withGuide.get("Guia.wav"));
            float[][] clickTrack = readWav(withGuide.get("Click.wav"));
            double guidePeak = 0;
            for (float v : guideTrack[0]) {
                guidePeak = Math.max(guidePeak, Math.abs(v));
            }
            check(guidePeak > 0.3 && guideTrack[0].length == clickTrack[0].length, "la Guía suena y dura lo mismo que el click");
        }
        call(b, "DELETE", "/api/guia/clicks/classic", null, 200);
        check(call(b, "GET", "/api/guia", null, 200).getJSONArray("clicks").length() == 0, "borrar el sonido de click");
        check(call(b, "DELETE", "/api/guia/" + someFile, null, 200).getJSONArray("files").length() == 9, "borrar una voz");
        check(call(b, "DELETE", "/api/guia?set=es", null, 200).getJSONArray("sets").length() == 0, "borrar el idioma");
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"guide\":true}", 200).getString("id"), 30);
        check(job.getString("status").equals("error") && job.getString("error").contains("voces"), "guía sin voces: " + job);

        // ---- otra velocidad y tono: el paquete (80 %, 2 semitonos arriba) y la mezcla
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"stems\":[\"vocals\",\"drums\"],\"tempo\":0.8,\"semitones\":2}", 200)
                .getString("id"), 180);
        check(job.getString("status").equals("done"), "paquete con otra velocidad y tono: " + job);
        String keyName = analysis.getJSONObject("key").getString("name");
        String expectedKey = com.moimoi.analysis.Music.keyName(Math.floorMod(analysis.getJSONObject("key").getInt("tonic") + 2, 12),
                analysis.getJSONObject("key").getString("mode").equals("major"));
        LocalApi.Download varied = b.resolveDownload(job.getString("downloadUrl"), null);
        check(varied.name.equals("Artista Prueba - Nuevo (en " + expectedKey + ", 80%).zip"), "nombre con tonalidad y velocidad: " + varied.name);
        Map<String, byte[]> variedFiles = unzip(varied.file);
        JSONObject variedManifest = new JSONObject(new String(variedFiles.get("moimoi.json"), "UTF-8"));
        JSONObject cancion = variedManifest.getJSONObject("cancion");
        check(cancion.getDouble("velocidad") == 0.8 && cancion.getInt("transposicion") == 2
                && cancion.getString("tonalidad").equals(expectedKey) && cancion.getString("tonalidadOriginal").equals(keyName),
                "moimoi.json con la velocidad y la tonalidad nuevas: " + cancion);
        float[][] slowVoice = readWav(variedFiles.get("Voz.wav"));
        long expectedSlow = Math.round(six.getDouble("duration") / 0.8 * 44100);
        check(Math.abs(slowVoice[0].length - expectedSlow) <= 2, "pista al 80 %: " + slowVoice[0].length + " muestras (esperadas " + expectedSlow + ")");
        double slowPeak = 0;
        for (float v : slowVoice[0]) {
            slowPeak = Math.max(slowPeak, Math.abs(v));
        }
        check(slowPeak > 0.001 && slowPeak <= 0.9901, "la pista estirada suena y no satura (" + slowPeak + ")");
        JSONArray variedChords = variedManifest.getJSONArray("acordes");
        JSONArray originalChords = analysis.getJSONArray("chords");
        JSONObject firstReal = null;
        for (int i = 0; i < originalChords.length(); i++) {
            if (!originalChords.getJSONObject(i).getString("quality").equals("N")) {
                firstReal = originalChords.getJSONObject(i);
                break;
            }
        }
        if (firstReal != null && variedChords.length() > 0) {
            check(Math.abs(variedChords.getJSONObject(0).getDouble("inicio") - firstReal.getDouble("start") / 0.8) < 0.002,
                    "acordes en el tiempo nuevo");
        }
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"mix\",\"format\":\"wav\",\"tempo\":1.25,\"semitones\":-3}", 200).getString("id"), 180);
        check(job.getString("status").equals("done"), "mezcla con otra velocidad y tono: " + job);
        LocalApi.Download fastMix = b.resolveDownload(job.getString("downloadUrl"), null);
        float[][] fast = readStem(fastMix.file);
        long expectedFast = Math.round(six.getDouble("duration") / 1.25 * 44100);
        check(Math.abs(fast[0].length - expectedFast) <= 2 && fastMix.name.endsWith("125%) (mezcla).wav"),
                "mezcla al 125 %: " + fast[0].length + " muestras, " + fastMix.name);
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\"}", 200).getString("id"), 60);
        check(job.getString("status").equals("done") && job.getJSONObject("result").getString("name").endsWith(".zip"), "paquete multitrack");
        call(b, "POST", "/api/songs/" + six.getString("id") + "/exports", "{\"type\":\"stems\",\"stems\":[\"xx\"]}", 400);

        // Mezcla: sin la batería, sin saturar.
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"mix\",\"format\":\"wav\",\"mixer\":{\"drums\":{\"mute\":true},\"vocals\":{\"volume\":2,\"pan\":-1}}}", 200)
                .getString("id"), 60);
        check(job.getString("status").equals("done"), "mezcla: " + job);
        LocalApi.Download mix = b.resolveDownload(job.getString("downloadUrl"), null);
        float[][] mixed = readStem(mix.file);
        double mixPeak = 0;
        for (int c = 0; c < 2; c++) {
            for (float v : mixed[c]) {
                mixPeak = Math.max(mixPeak, Math.abs(v));
            }
        }
        check(mixed[0].length == expected && mixPeak <= 0.9701 && mix.name.endsWith("(mezcla).wav"), "mezcla " + mixPeak);
        LocalApi.Download single = b.resolveDownload("/api/songs/" + six.getString("id") + "/download/bass.wav", null);
        check(single.file.equals(stemFile(six, "bass")) && single.name.equals("Artista Prueba - Nuevo - Bajo.wav"), "una pista sola");
        try {
            b.resolveDownload("/_capacitor_file_/etc/passwd", null);
            check(false, "no se pueden pedir archivos de afuera");
        } catch (ApiException expectedError) {
            check(expectedError.status == 403, "archivo de afuera: 403");
        }

        // ---- cancelar, reintentar con otro tipo y borrar
        JSONObject third = b.importFile(new ByteArrayInputStream(testWav(30.0, 44100, 2, 2)), "larga.wav", "audio/wav", "4stems", null);
        String id3 = third.getString("id");
        long end = System.currentTimeMillis() + 60000;
        while (!call(b, "GET", "/api/songs/" + id3, null, 200).getString("status").equals("separating")) {
            check(System.currentTimeMillis() < end, "empezó a separar");
            Thread.sleep(50);
        }
        call(b, "POST", "/api/songs/" + id3 + "/reanalyze", null, 409);
        JSONObject cancelled = call(b, "POST", "/api/songs/" + id3 + "/cancel", null, 200);
        check(cancelled.getString("status").equals("cancelled"), "cancelada");
        JSONObject retried = call(b, "POST", "/api/songs/" + id3 + "/retry", "{\"preset\":\"2stems\"}", 200);
        check(retried.getString("status").equals("queued") && retried.getString("preset").equals("2stems"), "reintentar");
        call(b, "POST", "/api/songs/" + id3 + "/retry", null, 409);
        call(b, "DELETE", "/api/songs/" + id3, null, 200);
        call(b, "GET", "/api/songs/" + id3, null, 404);
        Thread.sleep(3000);
        check(!new File(platform.dataDir(), "songs/" + id3).exists(), "carpeta borrada");

        // ---- se retoma al reabrir la app
        JSONObject fourth = b.importFile(new ByteArrayInputStream(wav), "retomar.wav", "audio/wav", "2stems", null);
        b.stop();
        Thread.sleep(500);
        LocalBackend reopened = LocalBackend.create(platform);
        JSONObject resumed = waitSong(reopened, fourth.getString("id"), "ready", 300);
        check(resumed.getJSONArray("stems").length() == 2, "retomada al reabrir");
        check(call(reopened, "GET", "/api/songs", null, 200).getJSONArray("list").length() == 3, "siguen las canciones");
        reopened.stop();

        // Formato no reconocido.
        try {
            reopened.importFile(new ByteArrayInputStream(new byte[10]), "doc.pdf", "application/pdf", null, null);
            check(false, "pdf rechazado");
        } catch (ApiException e) {
            check(e.status == 400, "pdf rechazado con 400");
        }
        // ---- voces guía y sonidos de click que trae la app (recursos/voz-guia)
        File recursos = new File("recursos");
        if (new File(recursos, "voz-guia/kit.json").isFile()) {
            probarIncluidas(model, threads, recursos, wav);
        } else {
            System.out.println("(no está recursos/voz-guia: no se prueban las voces incluidas)");
        }
        // ---- popurrí con cambios de tempo (el click sigue cada tempo)
        probarMapaDeTempo();
        System.out.printf("OK: %d comprobaciones en %.1f s%n", checks, (System.currentTimeMillis() - t0) / 1000.0);
        deleteTree(root);
        System.exit(0);
    }

    /**
     * Golpes de batería como los de las canciones sintéticas de las pruebas de la computadora
     * (tests/synth.py): bombo con barrido de tono, redoblante (ruido + 190 Hz) y platillo.
     */
    static void drumHit(float[] track, double t, String kind, java.util.Random rng) {
        int sr = 22050;
        int start = (int) Math.round(t * sr);
        int length = (int) ((kind.equals("hat") ? 0.05 : kind.equals("snare") ? 0.18 : 0.25) * sr);
        double prev = 0;
        for (int i = 0; i < length && start + i < track.length; i++) {
            double x = i / (double) sr;
            double v;
            if (kind.equals("kick")) {
                double body = (Math.sin(2 * Math.PI * 55 * x) + 0.5 * Math.sin(2 * Math.PI * 110 * x)) * Math.exp(-x / 0.08);
                double sweep = 0.6 * Math.sin(2 * Math.PI * (150 * Math.exp(-x * 25) + 45) * x) * Math.exp(-x / 0.07);
                v = 0.9 * (body + sweep);
            } else if (kind.equals("snare")) {
                v = 0.35 * rng.nextGaussian() * Math.exp(-x / 0.05) + 0.2 * Math.sin(2 * Math.PI * 190 * x) * Math.exp(-x / 0.04);
            } else {
                double noise = rng.nextGaussian() * Math.exp(-x / 0.012);
                v = 0.12 * (noise - prev); // "pasa-altos" barato
                prev = noise;
            }
            track[start + i] += (float) (v * Math.min(1, x / 0.001));
        }
    }

    /**
     * Dos canciones seguidas a 100 y 140 BPM (batería, bajo y acordes sintéticos): el análisis del
     * celular tiene que encontrar los dos tramos y poner los pulsos en los dos tempos.
     */
    static void probarMapaDeTempo() throws Exception {
        java.util.List<Double> truth = new java.util.ArrayList<>();
        Map<String, float[]> stems = popurri(truth);
        double part = 60.0;
        com.moimoi.analysis.Analyzer.Source source = new com.moimoi.analysis.Analyzer.Source() {
            public int length(String stem) {
                return stems.get(stem).length;
            }

            public float[] load(String stem, int max) {
                float[] v = stems.get(stem);
                return max >= 0 && max < v.length ? java.util.Arrays.copyOf(v, max) : v.clone();
            }
        };
        JSONObject a = com.moimoi.analysis.Analyzer.analyze(java.util.Arrays.asList("drums", "bass", "other"), source, (f, m) -> {});
        JSONArray segments = a.getJSONObject("tempo").getJSONArray("segments");
        System.out.println("mapa de tempo: " + segments);
        check(segments.length() == 2 && Math.abs(segments.getJSONObject(0).getDouble("bpm") - 100) < 1.5
                && Math.abs(segments.getJSONObject(1).getDouble("bpm") - 140) < 1.5, "dos tramos: 100 y 140 BPM");
        check(segments.length() == 2 && Math.abs(segments.getJSONObject(1).getDouble("start") - part) < 4.0,
                "el cambio de tempo en su lugar: " + (segments.length() > 1 ? segments.getJSONObject(1).getDouble("start") : -1));
        JSONArray beats = a.getJSONArray("beats");
        int near = 0;
        for (double b : truth) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < beats.length(); i++) {
                best = Math.min(best, Math.abs(beats.getDouble(i) - b));
            }
            if (best < 0.03) {
                near++;
            }
        }
        check(near > 0.9 * truth.size(), "los pulsos siguen los dos tempos: " + near + " de " + truth.size());
    }

    static Map<String, float[]> popurri() {
        return popurri(new java.util.ArrayList<>());
    }

    /** Pistas (22,05 kHz) de dos canciones seguidas a 100 y 140 BPM; truth recibe los pulsos reales. */
    static Map<String, float[]> popurri(java.util.List<Double> truth) {
        int sr = 22050;
        double[] tempos = {100.0, 140.0};
        double part = 60.0;
        int n = (int) (sr * part * tempos.length);
        float[] drums = new float[n], bass = new float[n], other = new float[n];
        java.util.Random rng = new java.util.Random(7);
        int[] roots = {0, 7, 9, 5};
        double t = 0.5;
        int beat = 0;
        for (int p = 0; p < tempos.length; p++) {
            double period = 60.0 / tempos[p];
            double end = part * (p + 1) - 0.05;
            int first = beat;
            while (t < end) {
                truth.add(t);
                int inBar = (beat - first) % 4;
                drumHit(drums, t, inBar == 0 || inBar == 2 ? "kick" : "snare", rng);
                drumHit(drums, t, "hat", rng);
                drumHit(drums, t + period / 2, "hat", rng);
                double root = 55.0 * Math.pow(2, (roots[((beat - first) / 4) % 4] + 2 * p) / 12.0);
                int start = (int) Math.round(t * sr);
                int length = (int) (period * sr);
                for (int i = 0; i < length && start + i < n; i++) {
                    double env = Math.exp(-i / (0.25 * sr));
                    bass[start + i] += (float) (0.35 * env * Math.sin(2 * Math.PI * root * i / sr));
                    double chord = 0;
                    for (int iv : new int[] {0, 4, 7}) {
                        chord += Math.sin(2 * Math.PI * root * 4 * Math.pow(2, iv / 12.0) * i / sr);
                    }
                    other[start + i] += (float) (0.08 * Math.exp(-i / (0.4 * sr)) * chord);
                }
                t += period;
                beat++;
            }
        }
        Map<String, float[]> stems = new HashMap<>();
        stems.put("drums", drums);
        stems.put("bass", bass);
        stems.put("other", other);
        return stems;
    }

    static java.util.Set<String> ids(JSONArray list, String field) throws Exception {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (int i = 0; i < list.length(); i++) {
            Object item = list.get(i);
            out.add(item instanceof JSONObject ? ((JSONObject) item).getString(field) : String.valueOf(item));
        }
        return out;
    }

    static int filesIn(LocalBackend b, String set) throws Exception {
        return call(b, "GET", "/api/guia?set=" + set, null, 200).getJSONArray("files").length();
    }

    static void probarIncluidas(File model, int threads, File recursos, byte[] wav) throws Exception {
        java.util.Set<String> todos = new java.util.TreeSet<>(java.util.Arrays.asList("en-f", "es", "fr", "pt"));
        // 1) Alguien que ya tenía su propio paquete en español (app anterior, sin voces incluidas).
        File root = Files.createTempDirectory("moimoi-incluidas").toFile();
        LocalBackend before = LocalBackend.create(new DesktopPlatform(root, model, threads));
        File pack = new File(root, "Mis voces.zip");
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(pack))) {
            for (String entry : new String[] {"Spanish - Intro.wav", "Spanish - Coro.wav"}) {
                zout.putNextEntry(new ZipEntry(entry));
                zout.write(testWav(0.7, 44100, 1, entry.hashCode()));
                zout.closeEntry();
            }
        }
        before.importGuide(java.util.Arrays.asList(new com.moimoi.local.Guide.Upload(pack.getName(), pack)), null, null);
        before.stop();

        // 2) Se actualiza la app: se agregan los idiomas incluidos, pero su español queda como estaba.
        DesktopPlatform platform = new DesktopPlatform(root, model, threads);
        platform.bundled = recursos;
        LocalBackend b = LocalBackend.create(platform);
        JSONObject kit = call(b, "GET", "/api/guia", null, 200);
        check(ids(kit.getJSONArray("sets"), "id").equals(todos), "idiomas incluidos agregados: " + ids(kit.getJSONArray("sets"), "id"));
        check(ids(kit.getJSONArray("included"), "").equals(new java.util.TreeSet<>(java.util.Arrays.asList("en-f", "fr", "pt"))),
                "el español propio no cuenta como incluido: " + kit.getJSONArray("included"));
        check(kit.getString("active").equals("es") && filesIn(b, "es") == 2, "su español sigue igual (" + filesIn(b, "es") + " voces)");
        check(kit.getJSONArray("clicks").length() == 8, "8 sonidos de click incluidos: " + ids(kit.getJSONArray("clicks"), "id"));

        // 3) Al reabrir no se duplica nada.
        int fr = filesIn(b, "fr"), en = filesIn(b, "en-f");
        b.stop();
        b = LocalBackend.create(platform);
        check(filesIn(b, "fr") == fr && filesIn(b, "en-f") == en && fr > 40, "reabrir no duplica las voces (" + fr + ", " + en + ")");

        // 4) Si borra un idioma incluido no vuelve solo; "Restaurar voces incluidas" lo trae de nuevo.
        call(b, "DELETE", "/api/guia?set=fr", null, 200);
        b.stop();
        b = LocalBackend.create(platform);
        check(!ids(call(b, "GET", "/api/guia", null, 200).getJSONArray("sets"), "id").contains("fr"), "lo borrado no vuelve solo");
        JSONObject restored = call(b, "POST", "/api/guia/incluidas", null, 200);
        check(ids(restored.getJSONArray("sets"), "id").contains("fr") && filesIn(b, "fr") == fr, "restaurar las voces incluidas");
        check(filesIn(b, "es") == 2, "restaurar no toca su español");
        b.stop();
        deleteTree(root);

        // 5) Un celular nuevo: todo incluido, español en uso, click "Classic" y la Guía en el paquete.
        File root2 = Files.createTempDirectory("moimoi-incluidas").toFile();
        DesktopPlatform fresh = new DesktopPlatform(root2, model, threads);
        fresh.bundled = recursos;
        LocalBackend nuevo = LocalBackend.create(fresh);
        JSONObject kit2 = call(nuevo, "GET", "/api/guia", null, 200);
        check(ids(kit2.getJSONArray("included"), "").equals(todos) && kit2.getString("active").equals("es") && kit2.getInt("count") >= 30,
                "celular nuevo con las voces incluidas: " + kit2.getInt("count") + " en español");
        JSONObject settings = call(nuevo, "GET", "/api/settings", null, 200);
        check(settings.getString("exportClickSound").equals("classic") && settings.getBoolean("exportGuide"), "click Classic y Guía por defecto");
        JSONObject song = nuevo.importFile(new ByteArrayInputStream(wav), "incluidas.wav", "audio/wav", "2stems", null);
        waitSong(nuevo, song.getString("id"), "ready", 300);
        JSONObject job = waitJob(nuevo, call(nuevo, "POST", "/api/songs/" + song.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"guide\":true,\"click\":true,\"clickSound\":\"classic\"}", 200).getString("id"), 120);
        JSONObject analysis = call(nuevo, "GET", "/api/songs/" + song.getString("id") + "/analysis", null, 200);
        // Click y Guía para el reproductor.
        JSONObject playerGuide = call(nuevo, "GET", "/api/songs/" + song.getString("id") + "/guia", null, 200);
        System.out.println("guía del reproductor: " + playerGuide.getJSONArray("placements"));
        check(playerGuide.getJSONObject("voices").has("n1") && playerGuide.getJSONObject("voices").has("n4"),
                "voces de la cuenta para el reproductor");
        check(playerGuide.getJSONObject("click").has("accent") && playerGuide.getJSONObject("click").has("beat")
                && playerGuide.getString("clickName").equals("Classic") && playerGuide.getString("voiceSet").equals("Español"),
                "click Classic y voces en español para el reproductor: " + playerGuide.getJSONObject("click"));
        for (int i = 0; i < playerGuide.getJSONArray("placements").length(); i++) {
            String cue = playerGuide.getJSONArray("placements").getJSONObject(i).getString("cue");
            check(playerGuide.getJSONObject("voices").has(cue), "cada voz de la guía tiene su audio: " + cue);
        }
        if (analysis.getJSONArray("beats").length() > 0) {
            check(job.getString("status").equals("done"), "paquete con las voces incluidas: " + job);
            Map<String, byte[]> files = unzip(nuevo.resolveDownload(job.getString("downloadUrl"), null).file);
            check(files.containsKey("Guia.wav") && files.containsKey("Click.wav"), "Guía y Click con lo incluido: " + files.keySet());
            JSONObject manifest = new JSONObject(new String(files.get("moimoi.json"), "UTF-8"));
            System.out.println("guía con las voces incluidas: " + manifest.getJSONArray("guia"));
            check(manifest.getJSONArray("guia").length() > 0, "la Guía anuncia algo");
        } else {
            check(job.getString("status").equals("error"), "sin pulso no hay click: " + job);
        }
        nuevo.stop();
        deleteTree(root2);

        // 6) Se actualiza desde una versión que traía menos voces (sin "Puente" ni "Pre-coro" en
        //    español): se agregan solas, sin duplicar nada ni volver a poner lo que el usuario borró.
        File root3 = Files.createTempDirectory("moimoi-incluidas").toFile();
        File anterior = new File(root3, "anterior");
        new File(anterior, "voz-guia").mkdirs();
        JSONObject menos = new JSONObject(new String(Files.readAllBytes(new File(recursos, "voz-guia/kit.json").toPath()), "UTF-8"));
        java.util.Set<String> nuevas = new java.util.TreeSet<>(java.util.Arrays.asList("precoro", "puente"));
        java.util.Set<String> nombres = new java.util.TreeSet<>(java.util.Arrays.asList("Pre-coro", "Puente"));
        JSONObject archivos = menos.getJSONObject("files");
        for (String id : ids(archivos.names(), "")) {
            JSONObject info = archivos.getJSONObject(id);
            if (info.optString("set").equals("es") && nuevas.contains(info.optString("cue"))) {
                archivos.remove(id);
            }
        }
        Files.write(new File(anterior, "voz-guia/kit.json").toPath(), menos.toString().getBytes("UTF-8"));
        File audios = new File(anterior, "voz-guia/audio");
        audios.mkdirs();
        for (File f : new File(recursos, "voz-guia/audio").listFiles()) {
            Files.copy(f.toPath(), new File(audios, f.getName()).toPath());
        }
        DesktopPlatform celular = new DesktopPlatform(root3, model, threads);
        celular.bundled = anterior;
        LocalBackend viejo = LocalBackend.create(celular);
        int es = filesIn(viejo, "es"), total = call(viejo, "GET", "/api/guia", null, 200).getInt("count");
        java.util.Set<String> faltaban = ids(call(viejo, "GET", "/api/guia?set=es", null, 200).getJSONArray("missing"), "");
        check(faltaban.containsAll(nombres), "la versión anterior no tenía Puente ni Pre-coro: " + faltaban);
        call(viejo, "DELETE", "/api/guia?set=pt", null, 200);
        viejo.stop();
        celular.bundled = recursos;
        LocalBackend actualizado = LocalBackend.create(celular);
        JSONObject kit3 = call(actualizado, "GET", "/api/guia", null, 200);
        java.util.Set<String> faltan = ids(call(actualizado, "GET", "/api/guia?set=es", null, 200).getJSONArray("missing"), "");
        check(filesIn(actualizado, "es") == es + nuevas.size() && kit3.getInt("count") == total + nuevas.size()
                && java.util.Collections.disjoint(faltan, nombres), "al actualizar se agregan Puente y Pre-coro (faltan: " + faltan + ")");
        check(!ids(kit3.getJSONArray("sets"), "id").contains("pt"), "al actualizar no vuelve el idioma borrado");
        actualizado.stop();
        deleteTree(root3);
    }

    static byte[] silentWav(double seconds) throws IOException {
        int n = (int) (seconds * 44100);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Wav.Writer w = new Wav.Writer(out, 1, 44100, n);
        w.write(new float[n], null, n);
        w.close();
        return out.toByteArray();
    }

    static float[][] readWav(byte[] bytes) throws IOException {
        File tmp = File.createTempFile("moimoi", ".wav");
        try {
            Files.write(tmp.toPath(), bytes);
            return readStem(tmp);
        } finally {
            tmp.delete();
        }
    }

    static Map<String, byte[]> unzip(File file) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    bytes.write(buf, 0, n);
                }
                entries.put(e.getName(), bytes.toByteArray());
            }
        }
        return entries;
    }

    static void deleteTree(File f) {
        // Un enlace se borra sin entrar: lo de adentro no es de la prueba.
        File[] children = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }
}
