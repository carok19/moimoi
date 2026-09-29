package com.moimoi.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.moimoi.local.ApiException;
import com.moimoi.local.LocalApi;
import com.moimoi.local.LocalBackend;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Puente entre la interfaz y el "servidor" de MoiMoi dentro del celular (com.moimoi.local):
 * los pedidos de la API, elegir canciones del celular, audios compartidos desde otras apps
 * (WhatsApp, Archivos…) y compartir o guardar exportaciones.
 */
@CapacitorPlugin(
        name = "MoiMoiLocal",
        permissions = {@Permission(strings = {Manifest.permission.POST_NOTIFICATIONS}, alias = "notifications")})
public class LocalPlugin extends Plugin {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private AndroidPlatform platform;

    private synchronized LocalBackend backend() {
        if (platform == null) {
            platform = new AndroidPlatform(getContext());
        }
        return LocalBackend.get(platform);
    }

    @Override
    public void load() {
        // Arranca la cola (retoma lo que quedó a medias si Android cerró la app).
        executor.execute(this::backend);
    }

    // ---- API ------------------------------------------------------------------------------------

    @PluginMethod
    public void request(PluginCall call) {
        String method = call.getString("method", "GET");
        String path = call.getString("path", "/api/");
        String body = call.getString("body");
        executor.execute(() -> {
            LocalApi.Response response = backend().request(method, path, body);
            JSObject out = new JSObject();
            out.put("status", response.status);
            out.put("body", response.body);
            call.resolve(out);
        });
    }

    // ---- agregar canciones ------------------------------------------------------------------------

    @PluginMethod
    public void pickAudio(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/*", "video/*", "application/ogg"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(call, intent, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        Intent data = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        String preset = call.getString("preset");
        executor.execute(() -> call.resolve(importAll(uris, preset)));
    }

    private JSObject importAll(List<Uri> uris, String preset) {
        JSONArray songs = new JSONArray();
        JSONArray errors = new JSONArray();
        ContentResolver resolver = getContext().getContentResolver();
        for (Uri uri : uris) {
            String name = displayName(uri);
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("No se pudo abrir el archivo");
                }
                songs.put(backend().importFile(in, name, resolver.getType(uri), preset, null));
            } catch (ApiException e) {
                errors.put(errorJson(name, e.getMessage()));
            } catch (Exception e) {
                errors.put(errorJson(name, e.getMessage() == null ? "No se pudo agregar" : e.getMessage()));
            }
        }
        JSObject out = new JSObject();
        out.put("songs", songs.toString());
        out.put("errors", errors.toString());
        return out;
    }

    private static JSONObject errorJson(String name, String message) {
        JSONObject e = new JSONObject();
        try {
            e.put("name", name == null ? "archivo" : name);
            e.put("error", message);
        } catch (org.json.JSONException ignored) {
            // no pasa
        }
        return e;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContext().getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // sin nombre
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    /** Audios compartidos con MoiMoi (o abiertos con "Abrir con MoiMoi"). */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        List<Uri> uris = sharedAudio(intent);
        if (uris.isEmpty()) {
            return;
        }
        // Que al recrear la pantalla no se vuelva a agregar lo mismo.
        getActivity().setIntent(new Intent(Intent.ACTION_MAIN));
        // Si la página todavía no escucha, Capacitor guarda el aviso hasta que lo haga.
        executor.execute(() -> notifyListeners("imported", importAll(uris, null), true));
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> sharedAudio(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null || intent.getAction() == null) {
            return uris;
        }
        String type = intent.getType() == null ? "" : intent.getType();
        boolean media = type.startsWith("audio/") || type.startsWith("video/") || type.equals("application/ogg")
                || type.equals("*/*") || type.startsWith("application/octet-stream");
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && media) {
            Parcelable stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) {
                uris.add((Uri) stream);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && media) {
            ArrayList<Parcelable> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Parcelable p : streams) {
                    if (p instanceof Uri) {
                        uris.add((Uri) p);
                    }
                }
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        return uris;
    }

    // ---- notificaciones -------------------------------------------------------------------------

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            call.resolve(new JSObject().put("granted", true));
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationsResult");
    }

    @PermissionCallback
    private void notificationsResult(PluginCall call) {
        call.resolve(new JSObject().put("granted", getPermissionState("notifications") == PermissionState.GRANTED));
    }

    // ---- compartir y guardar ------------------------------------------------------------------------

    /** Copia con el nombre que verá quien lo reciba (si hace falta) en la caché de compartir. */
    private File named(File file, String name) throws IOException {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]+", " ").trim();
        if (safe.isEmpty() || safe.equals(file.getName())) {
            return file;
        }
        File dir = new File(getContext().getCacheDir(), "moimoi/compartir");
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No se pudo preparar el archivo");
        }
        File target = new File(dir, safe);
        copy(new FileInputStream(file), new FileOutputStream(target));
        return target;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = i.read(buffer)) > 0) {
                o.write(buffer, 0, n);
            }
        }
    }

    @PluginMethod
    public void shareFile(PluginCall call) {
        String url = call.getString("url");
        String name = call.getString("name");
        executor.execute(() -> {
            try {
                LocalApi.Download download = backend().resolveDownload(url, name);
                File file = named(download.file, download.name);
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(download.mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, download.name);
                send.setClipData(ClipData.newRawUri(download.name, uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Compartir con…");
                getActivity().runOnUiThread(() -> {
                    try {
                        getActivity().startActivity(chooser);
                        call.resolve(new JSObject().put("outcome", "shared"));
                    } catch (RuntimeException e) {
                        call.reject("No hay apps para compartir este archivo");
                    }
                });
            } catch (ApiException | IOException | RuntimeException e) {
                call.reject(e.getMessage() == null ? "No se pudo compartir" : e.getMessage());
            }
        });
    }

    /** Guarda en Descargas/MoiMoi (Android 10 o más nuevo) o donde el usuario elija. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String url = call.getString("url");
        String name = call.getString("name");
        executor.execute(() -> {
            try {
                LocalApi.Download download = backend().resolveDownload(url, name);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, download.name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, download.mime);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MoiMoi");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    ContentResolver resolver = getContext().getContentResolver();
                    Uri target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (target == null) {
                        throw new IOException("No se pudo guardar en Descargas");
                    }
                    try {
                        copy(new FileInputStream(download.file), resolver.openOutputStream(target));
                    } catch (IOException e) {
                        resolver.delete(target, null, null);
                        throw e;
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(target, values, null, null);
                    call.resolve(new JSObject().put("where", "Descargas/MoiMoi/" + download.name));
                } else {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(download.mime);
                    intent.putExtra(Intent.EXTRA_TITLE, download.name);
                    call.getData().put("file", download.file.getAbsolutePath());
                    getActivity().runOnUiThread(() -> startActivityForResult(call, intent, "saveResult"));
                }
            } catch (ApiException | IOException | RuntimeException e) {
                call.reject(e.getMessage() == null ? "No se pudo guardar" : e.getMessage());
            }
        });
    }

    @ActivityCallback
    private void saveResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.resolve(new JSObject().put("where", (String) null));
            return;
        }
        String file = call.getString("file");
        executor.execute(() -> {
            try {
                copy(new FileInputStream(file), getContext().getContentResolver().openOutputStream(data.getData()));
                call.resolve(new JSObject().put("where", "la carpeta elegida"));
            } catch (IOException | RuntimeException e) {
                call.reject(e.getMessage() == null ? "No se pudo guardar" : e.getMessage());
            }
        });
    }
}
