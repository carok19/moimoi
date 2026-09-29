package com.moimoi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.moimoi.local.LocalApi;
import com.moimoi.local.LocalBackend;
import com.moimoi.local.Wav;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.FileInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * La separación completa en un Android de verdad (emulador en GitHub Actions): decodificar con
 * MediaCodec (M4A a 48 kHz estéreo y MP3 mono a 22 kHz), cargar el modelo desde el APK, separar con
 * ONNX Runtime, guardar las pistas y exportar el .zip. Los audios de prueba los genera la
 * compilación con ffmpeg (src/androidTest/assets).
 */
@RunWith(AndroidJUnit4.class)
public class SeparacionEnAndroidTest {

    private static LocalBackend backend;

    @BeforeClass
    public static void start() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        backend = LocalBackend.create(new AndroidPlatform(app));
    }

    @AfterClass
    public static void stop() {
        backend.stop();
    }

    private static JSONObject call(String method, String path, String body) throws Exception {
        LocalApi.Response r = backend.request(method, path, body);
        assertTrue(method + " " + path + ": " + r.body, r.status < 400);
        return new JSONObject(r.body);
    }

    private static JSONObject separate(String asset, String mime, String preset, double seconds) throws Exception {
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        JSONObject song;
        try (InputStream in = test.getAssets().open(asset)) {
            song = backend.importFile(in, asset, mime, preset, null);
        }
        String id = song.getString("id");
        long start = System.currentTimeMillis();
        long end = start + 15 * 60 * 1000L;
        while (System.currentTimeMillis() < end) {
            song = call("GET", "/api/songs/" + id, null);
            String status = song.getString("status");
            if (status.equals("ready")) {
                break;
            }
            assertTrue("Falló la separación de " + asset + ": " + song.optString("error"),
                    !status.equals("error") && !status.equals("cancelled"));
            Thread.sleep(500);
        }
        assertEquals("ready", song.getString("status"));
        System.out.println("MoiMoi: " + asset + " separado en "
                + (System.currentTimeMillis() - start) / 1000.0 + " s");
        assertEquals(seconds, song.getDouble("duration"), 0.2);
        JSONArray stems = song.getJSONArray("stems");
        for (int i = 0; i < stems.length(); i++) {
            String url = stems.getJSONObject(i).getString("url");
            File file = new File(url.substring("/_capacitor_file_".length(), url.indexOf('?')));
            try (Wav.Reader wav = new Wav.Reader(file)) {
                assertEquals(2, wav.channels);
                assertEquals(44100, wav.sampleRate);
                assertEquals(song.getDouble("duration"), wav.duration(), 0.01);
            }
        }
        JSONObject peaks = call("GET", "/api/songs/" + id + "/peaks", null);
        assertEquals(25, peaks.getInt("perSecond"));

        // El análisis (tempo, tonalidad, acordes, partes, instrumentos) también se hace en Android.
        JSONObject analysis = call("GET", "/api/songs/" + id + "/analysis", null);
        assertEquals(1, analysis.getInt("version"));
        assertEquals(song.getDouble("duration"), analysis.getDouble("duration"), 0.01);
        assertEquals(stems.length(), analysis.getJSONObject("instruments").length());
        assertTrue(analysis.getJSONArray("sections").length() >= 1);
        assertTrue(analysis.getJSONObject("key").has("name"));
        assertTrue(!song.isNull("summary"));
        System.out.println("MoiMoi: análisis de " + asset + ": " + analysis.getJSONObject("summary"));
        return song;
    }

    @Test
    public void separaUnM4aEstereoEnSeisPistas() throws Exception {
        JSONObject song = separate("prueba.m4a", "audio/mp4", "6stems", 10.0);
        assertEquals(6, song.getJSONArray("stems").length());

        // Exportar las pistas sueltas (.zip) y ver que se pueda compartir.
        JSONObject job = call("POST", "/api/songs/" + song.getString("id") + "/exports",
                "{\"type\":\"stems\",\"stems\":[\"vocals\",\"drums\"]}");
        long end = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < end) {
            job = call("GET", "/api/jobs/" + job.getString("id"), null);
            if (!job.getString("status").equals("queued") && !job.getString("status").equals("running")) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals(job.toString(), "done", job.getString("status"));
        LocalApi.Download zip = backend.resolveDownload(job.getString("downloadUrl"), null);
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip.file))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries++;
            }
        }
        assertEquals(3, entries); // Voz.wav, Bateria.wav y moimoi.json
        call("DELETE", "/api/songs/" + song.getString("id"), null);
    }

    @Test
    public void separaUnMp3MonoEnDosPistas() throws Exception {
        JSONObject song = separate("prueba.mp3", "audio/mpeg", "2stems", 6.0);
        assertEquals(2, song.getJSONArray("stems").length());
        call("DELETE", "/api/songs/" + song.getString("id"), null);
    }
}
