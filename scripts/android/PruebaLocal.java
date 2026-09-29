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
 * ajustes, cancelar/reintentar, borrar y que se retome lo que quedó a medias al reabrir la app.
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
        call(b, "GET", "/api/songs/" + six.getString("id") + "/analysis", null, 404);

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
        check(zip.name.equals("Artista Prueba - Nuevo.zip") && zip.mime.equals("application/zip"), "nombre del zip " + zip.name);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip.file))) {
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
        check(entries.keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("Voz.wav", "Bajo.wav", "moimoi.json"))),
                "contenido del zip " + entries.keySet());
        check(java.util.Arrays.equals(entries.get("Voz.wav"), Files.readAllBytes(stemFile(six, "vocals").toPath())), "Voz.wav igual a la pista");
        JSONObject manifest = new JSONObject(new String(entries.get("moimoi.json"), "UTF-8"));
        check(manifest.getString("formato").equals("moimoi-multitrack") && manifest.getJSONArray("pistas").length() == 2
                && manifest.getJSONArray("pistas").getJSONObject(0).getString("archivo").equals("Voz.wav"), "moimoi.json");
        check(manifest.getJSONObject("cancion").getLong("duracionMs") == Math.round(six.getDouble("duration") * 1000), "duración en moimoi.json");

        // Paquete para Multitrack sin pulso: sin click ni cuenta (y avisa si se pide).
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"click\":true,\"preRollBars\":1}", 200).getString("id"), 30);
        check(job.getString("status").equals("error") && job.getString("error").contains("pulso"), "click sin pulso: " + job);
        job = waitJob(b, call(b, "POST", "/api/songs/" + six.getString("id") + "/exports",
                "{\"type\":\"multitrack\",\"tempo\":0.8}", 200).getString("id"), 30);
        check(job.getString("status").equals("error"), "velocidad al exportar todavía no");
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
        System.out.printf("OK: %d comprobaciones en %.1f s%n", checks, (System.currentTimeMillis() - t0) / 1000.0);
        deleteTree(root);
        System.exit(0);
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }
}
