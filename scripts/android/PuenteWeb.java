import com.moimoi.local.ApiException;
import com.moimoi.local.LocalApi;
import com.moimoi.local.LocalBackend;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Para probar la interfaz del "modo celular" en la computadora, sin Android:
 *
 *   java PuenteWeb carpeta_modelo frontend/dist puerto archivo_de_audio...
 *
 * Con MOIMOI_RAIZ se usa esa carpeta de datos (si no, una temporal nueva) y con MOIMOI_GUIA
 * (rutas separadas por ":") lo que "elige" el selector de voces guía.
 *
 * Sirve la interfaz compilada y hace de "puente de Android": los pedidos que la app haría a sus
 * plugins (MoiMoiLocal, SharedLink, App) llegan acá por POST /bridge y los responde el mismo
 * com.moimoi.local que usa la app. "Elegir canciones" agrega los archivos de la línea de comandos.
 * En el navegador hay que cargar antes el native-bridge.js de Capacitor con un androidBridge falso
 * que reenvía cada mensaje a /bridge (ver la prueba de la interfaz).
 */
public class PuenteWeb {

    static LocalBackend backend;
    static File dist;
    static final List<File> toPick = new ArrayList<>();
    static final JSONArray shared = new JSONArray();

    public static void main(String[] args) throws Exception {
        File model = new File(args[0]);
        dist = new File(args[1]);
        int port = Integer.parseInt(args[2]);
        for (int i = 3; i < args.length; i++) {
            toPick.add(new File(args[i]));
        }
        String fixed = System.getenv("MOIMOI_RAIZ");
        File root = fixed != null && !fixed.isEmpty() ? new File(fixed) : Files.createTempDirectory("moimoi-puente").toFile();
        backend = LocalBackend.create(new PruebaLocal.DesktopPlatform(root, model, 4));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", PuenteWeb::handle);
        server.start();
        System.out.println("Puente listo en http://127.0.0.1:" + port + " (datos en " + root + ")");
    }

    static void send(HttpExchange ex, int status, byte[] body, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    static void sendFile(HttpExchange ex, File file) throws IOException {
        String name = file.getName();
        String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript"
                : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".wav") ? "audio/wav" : name.endsWith(".json") ? "application/json"
                : name.endsWith(".wasm") ? "application/wasm" : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, file.length());
        try (OutputStream out = ex.getResponseBody(); InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), "UTF-8");
            if (path.equals("/bridge") && ex.getRequestMethod().equals("POST")) {
                byte[] raw = ex.getRequestBody().readAllBytes();
                JSONObject result = bridge(new JSONObject(new String(raw, "UTF-8")));
                if (result == null) {
                    send(ex, 204, new byte[0], "application/json");
                } else {
                    send(ex, 200, result.toString().getBytes("UTF-8"), "application/json");
                }
                return;
            }
            if (path.equals("/bridge/compartidos")) {
                send(ex, 200, shared.toString().getBytes("UTF-8"), "application/json");
                return;
            }
            if (path.startsWith("/_capacitor_file_/")) {
                File file = new File(path.substring("/_capacitor_file_".length()));
                if (file.isFile()) {
                    sendFile(ex, file);
                } else {
                    send(ex, 404, new byte[0], "text/plain");
                }
                return;
            }
            File file = new File(dist, path.equals("/") ? "index.html" : path.substring(1));
            if (!file.getCanonicalPath().startsWith(dist.getCanonicalPath()) || !file.isFile()) {
                file = new File(dist, "index.html");
            }
            sendFile(ex, file);
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, String.valueOf(e.getMessage()).getBytes("UTF-8"), "text/plain");
        }
    }

    /** Un mensaje del puente de Capacitor: {callbackId, pluginId, methodName, options}. */
    static JSONObject bridge(JSONObject call) throws Exception {
        String plugin = call.optString("pluginId");
        String method = call.optString("methodName");
        JSONObject options = call.optJSONObject("options");
        if (options == null) {
            options = new JSONObject();
        }
        if (method.equals("addListener") || method.equals("removeListener") || method.equals("removeAllListeners")) {
            return null;
        }
        JSONObject data = new JSONObject();
        String error = null;
        if (plugin.equals("MoiMoiLocal")) {
            switch (method) {
                case "request": {
                    LocalApi.Response r = backend.request(options.optString("method", "GET"), options.optString("path"),
                            options.has("body") && !options.isNull("body") ? options.optString("body") : null);
                    data.put("status", r.status);
                    data.put("body", r.body);
                    break;
                }
                case "pickAudio": {
                    JSONArray songs = new JSONArray();
                    JSONArray errors = new JSONArray();
                    for (File f : toPick) {
                        try (InputStream in = new FileInputStream(f)) {
                            songs.put(backend.importFile(in, f.getName(), "audio/wav", options.optString("preset", null), null));
                        } catch (ApiException e) {
                            errors.put(new JSONObject().put("name", f.getName()).put("error", e.getMessage()));
                        }
                    }
                    data.put("songs", songs.toString());
                    data.put("errors", errors.toString());
                    break;
                }
                case "shareFile":
                case "saveFile": {
                    try {
                        LocalApi.Download d = backend.resolveDownload(options.optString("url"), options.optString("name", null));
                        shared.put(new JSONObject().put("method", method).put("file", d.file.getAbsolutePath())
                                .put("name", d.name).put("mime", d.mime).put("size", d.file.length()));
                        if (method.equals("shareFile")) {
                            data.put("outcome", "shared");
                        } else {
                            data.put("where", "Descargas/MoiMoi/" + d.name);
                        }
                    } catch (ApiException e) {
                        error = e.getMessage();
                    }
                    break;
                }
                case "requestNotifications":
                    data.put("granted", true);
                    break;
                case "pickGuide": {
                    String list = System.getenv("MOIMOI_GUIA");
                    if (list == null || list.isEmpty()) {
                        data.put("cancelled", true);
                        break;
                    }
                    List<com.moimoi.local.Guide.Upload> uploads = new ArrayList<>();
                    for (String path : list.split(":")) {
                        File f = new File(path);
                        File copy = File.createTempFile("voz", "");
                        Files.copy(f.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        uploads.add(new com.moimoi.local.Guide.Upload(f.getName(), copy));
                    }
                    try {
                        data.put("kit", backend.importGuide(uploads, null,
                                options.isNull("set") ? null : options.optString("set", null)).toString());
                    } catch (ApiException e) {
                        error = e.getMessage();
                    }
                    break;
                }
                default:
                    error = "Método desconocido: " + method;
            }
        } else if (plugin.equals("SharedLink") && method.equals("getPending")) {
            data.put("text", JSONObject.NULL);
        }
        JSONObject out = new JSONObject();
        out.put("callbackId", call.optString("callbackId"));
        out.put("pluginId", plugin);
        out.put("methodName", method);
        out.put("success", error == null);
        if (error == null) {
            out.put("data", data);
        } else {
            out.put("error", new JSONObject().put("message", error));
        }
        return out;
    }
}
