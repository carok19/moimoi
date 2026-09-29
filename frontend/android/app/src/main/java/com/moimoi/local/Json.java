package com.moimoi.local;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Utilidades: JSON en disco (escritura atómica), identificadores, fechas y base64.
 * Solo se usa la parte de org.json que también existe en Android.
 */
public final class Json {

    private Json() {}

    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 12 caracteres hexadecimales, como storage.new_id() del programa de la computadora. */
    public static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(12);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** Fecha y hora UTC como datetime.isoformat(timespec="seconds"): 2026-01-31T18:05:09+00:00. */
    public static String nowIso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    public static JSONObject copy(JSONObject object) {
        if (object == null) {
            return null;
        }
        try {
            return new JSONObject(object.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Une `patch` sobre `base` (sin recursión, como {**a, **b} en Python). */
    public static JSONObject merge(JSONObject base, JSONObject patch) throws JSONException {
        JSONObject out = copy(base == null ? new JSONObject() : base);
        Iterator<String> keys = patch.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, patch.get(key));
        }
        return out;
    }

    public static JSONObject readObject(File file) {
        try {
            String text = readText(file);
            return text == null ? null : new JSONObject(text);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    public static String readText(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), UTF8);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 14];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Escritura atómica (archivo temporal + rename) para no dejar JSON a medias. */
    public static void write(File file, String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No se pudo crear " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(UTF8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            // En algunos sistemas rename no reemplaza: se borra el anterior primero.
            if (!file.delete() || !tmp.renameTo(file)) {
                tmp.delete();
                throw new IOException("No se pudo guardar " + file);
            }
        }
    }

    public static void write(File file, JSONObject object) throws IOException {
        write(file, object.toString());
    }

    /** null de JSON -> null de Java. */
    public static String optString(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optString(key, null);
    }

    public static JSONArray array(Iterable<String> values) {
        JSONArray out = new JSONArray();
        for (String value : values) {
            out.put(value);
        }
        return out;
    }

    public static String join(String separator, Iterable<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(value);
        }
        return out.toString();
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] data, int length) {
        StringBuilder out = new StringBuilder((length + 2) / 3 * 4);
        int i = 0;
        for (; i + 2 < length; i += 3) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(B64[v >> 18]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append(B64[v & 63]);
        }
        int rest = length - i;
        if (rest == 1) {
            int v = (data[i] & 0xFF) << 16;
            out.append(B64[v >> 18]).append(B64[(v >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(B64[v >> 18]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append('=');
        }
        return out.toString();
    }

    /** Base64 a bytes (ignora espacios y saltos de línea; acepta el prefijo "data:...;base64,"). */
    public static byte[] unbase64(String text) {
        String t = text == null ? "" : text;
        int comma = t.startsWith("data:") ? t.indexOf(',') : -1;
        if (comma >= 0) {
            t = t.substring(comma + 1);
        }
        int[] value = new int[128];
        java.util.Arrays.fill(value, -1);
        for (int i = 0; i < B64.length; i++) {
            value[B64[i]] = i;
        }
        value['-'] = 62; // variante para URLs
        value['_'] = 63;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(t.length() * 3 / 4);
        int acc = 0, bits = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '=') {
                break;
            }
            int v = c < 128 ? value[c] : -1;
            if (v < 0) {
                continue; // espacios, saltos de línea
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    /** Redondeo a `digits` decimales (como round() de Python para mostrar). */
    public static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
