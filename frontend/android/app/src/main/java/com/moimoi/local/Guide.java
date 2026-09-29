package com.moimoi.local;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Voz guía (como guia.py de la computadora): las voces que anuncian las partes de la canción
 * ("Verso 1", "Coro"…) y la cuenta. La app trae voces y sonidos de click incluidos (se instalan
 * solos, ver installBundled); además el usuario puede cargar su propio paquete (el .zip de un
 * sitio de secuencias o grabaciones propias): cada archivo se reconoce por su nombre y se puede
 * reasignar a mano. Si el paquete trae varios idiomas, cada uno queda por separado, y los sonidos
 * de click del paquete también se reconocen. Con eso se arma la pista Guía del multitrack.
 */
public final class Guide {

    static final String PARTES = "partes";
    static final String CUENTA = "cuenta";
    static final String INDICACIONES = "indicaciones";

    /** Tipos de voz: clave, nombre para mostrar y grupo, en el orden en que se muestran. */
    static final List<String[]> CUE_TYPES = new ArrayList<>();
    static final Map<String, String> CUE_NAMES = new LinkedHashMap<>();
    static final Map<String, String> CUE_GROUPS = new HashMap<>();
    static final Map<String, Integer> CUE_ORDER = new HashMap<>();

    private static void cue(String id, String name, String group) {
        CUE_TYPES.add(new String[] {id, name, group});
    }

    static {
        cue("intro", "Intro", PARTES);
        cue("verso", "Verso", PARTES);
        for (int i = 1; i <= 6; i++) {
            cue("verso" + i, "Verso " + i, PARTES);
        }
        cue("precoro", "Pre-coro", PARTES);
        for (int i = 1; i <= 4; i++) {
            cue("precoro" + i, "Pre-coro " + i, PARTES);
        }
        cue("coro", "Coro", PARTES);
        for (int i = 1; i <= 4; i++) {
            cue("coro" + i, "Coro " + i, PARTES);
        }
        cue("postcoro", "Post-coro", PARTES);
        cue("puente", "Puente", PARTES);
        for (int i = 1; i <= 4; i++) {
            cue("puente" + i, "Puente " + i, PARTES);
        }
        cue("instrumental", "Instrumental", PARTES);
        cue("interludio", "Interludio", PARTES);
        cue("solo", "Solo", PARTES);
        cue("turnaround", "Vuelta (turnaround)", PARTES);
        cue("breakdown", "Baja intensidad (breakdown)", PARTES);
        cue("vamp", "Vamp", PARTES);
        cue("refran", "Refrán", PARTES);
        cue("tag", "Tag (repetir)", PARTES);
        cue("rap", "Rap", PARTES);
        cue("exhortacion", "Exhortación", PARTES);
        cue("acapella", "A capella", PARTES);
        cue("channel", "Channel", PARTES);
        cue("final", "Final", PARTES);
        cue("outro", "Outro", PARTES);
        cue("cuenta", "Cuenta completa (1, 2, 3, 4)", CUENTA);
        String[] words = {"uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho"};
        for (int i = 1; i <= 8; i++) {
            cue("n" + i, i + " (" + words[i - 1] + ")", CUENTA);
        }
        cue("ultimavez", "Última vez", INDICACIONES);
        cue("sube", "Sube tono", INDICACIONES);
        cue("baja", "Baja tono", INDICACIONES);
        cue("todos", "Toda la banda", INDICACIONES);
        cue("break", "Pausa (break)", INDICACIONES);
        cue("hits", "Hits", INDICACIONES);
        cue("hold", "Sostener", INDICACIONES);
        cue("suave", "Suave", INDICACIONES);
        cue("build", "Sube intensidad", INDICACIONES);
        cue("slowbuild", "Sube de a poco", INDICACIONES);
        cue("swell", "Swell", INDICACIONES);
        cue("bigending", "Final grande", INDICACIONES);
        cue("libre", "Adoración libre", INDICACIONES);
        cue("adlib", "Ad lib", INDICACIONES);
        cue("drums", "Batería", INDICACIONES);
        cue("drumsin", "Entra batería", INDICACIONES);
        cue("bass", "Bajo", INDICACIONES);
        cue("guitar", "Guitarra", INDICACIONES);
        cue("keys", "Teclado", INDICACIONES);
        cue("pad", "Pad", INDICACIONES);
        cue("click", "Click", INDICACIONES);
        for (int i = 0; i < CUE_TYPES.size(); i++) {
            String[] c = CUE_TYPES.get(i);
            CUE_NAMES.put(c[0], c[1]);
            CUE_GROUPS.put(c[0], c[2]);
            CUE_ORDER.put(c[0], i);
        }
    }

    /** Las más importantes: si faltan, se avisa en Ajustes. */
    static final List<String> MAIN_CUES = Arrays.asList("intro", "verso", "verso1", "verso2", "verso3", "precoro",
            "coro", "puente", "instrumental", "final", "n1", "n2", "n3", "n4");
    static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".oga",
            ".opus", ".flac", ".aif", ".aiff", ".webm", ".wma", ".caf"));
    static final int MAX_FILES = 600;
    static final double MAX_CUE_SECONDS = 20.0;
    static final double MAX_CLICK_SECONDS = 2.0;
    /** Más largo que esto, sin recortar el silencio, ni se intenta (no es una voz guía). */
    static final double MAX_SOURCE_SECONDS = 180.0;
    static final String DEFAULT_SET = "mias";
    static final String DEFAULT_SET_NAME = "Mis voces";
    static final int SR = Processor.SAMPLE_RATE;

    static final Map<String, String> NUMBERS = new HashMap<>();

    static {
        String[][] numbers = {
            {"1", "uno", "one", "um", "un"}, {"2", "dos", "two", "dois", "deux"}, {"3", "tres", "three", "trois"},
            {"4", "cuatro", "four", "quatro", "quatre"}, {"5", "cinco", "five", "cinq"}, {"6", "seis", "six"},
            {"7", "siete", "seven", "sete", "sept"}, {"8", "ocho", "eight", "oito", "huit"},
        };
        for (int i = 0; i < numbers.length; i++) {
            for (String w : numbers[i]) {
                NUMBERS.put(w, "n" + (i + 1));
            }
        }
    }

    /** Reglas en orden: la primera que coincide gana (las compuestas antes que las simples). */
    static final List<Object[]> RULES = new ArrayList<>();

    private static void rule(String pattern, String cue) {
        RULES.add(new Object[] {Pattern.compile(pattern), cue});
    }

    static {
        rule("\\b(?:big ending|final grande|gran final)\\b", "bigending");
        rule("\\b(?:slowly build|sube de a poco|sube poco a poco|poco a poco)\\b", "slowbuild");
        rule("\\b(?:build|sube intensidad|sube la intensidad|crece|crescendo)\\b", "build");
        rule("\\b(?:breakdown|baja intensidad|baja la intensidad)\\b", "breakdown");
        rule("\\b(?:key change up|sube (?:el |de |medio )?tono|subimos (?:el )?tono|modulacion|modula)\\b", "sube");
        rule("\\b(?:key change down|baja (?:el |de |medio )?tono|bajamos (?:el )?tono)\\b", "baja");
        rule("\\b(?:key change|cambio de tono|cambio de tonalidad)\\b", "sube");
        rule("\\b(?:drums in|entra (?:la )?bateria)\\b", "drumsin");
        rule("\\b(?:last time|ultima vez)\\b", "ultimavez");
        rule("\\b(?:all in|toda la banda|todos|everybody|everyone)\\b", "todos");
        rule("\\b(?:worship freely|adoracion libre|libre)\\b", "libre");
        rule("\\bad ?lib\\b", "adlib");
        rule("\\bpost ?(?:coro|chorus)\\b|\\bpostcoro\\b|\\bpostchorus\\b", "postcoro");
        rule("\\b(?:pre ?coro|pre ?chorus|pre ?refrao)\\s*(\\d)\\b", "precoro{0}");
        rule("\\b(?:pre ?coro|pre ?chorus|pre ?refrao)\\b", "precoro");
        rule("\\b(?:verso|verse|estrofa|couplet)\\s*(?:n|no|numero)?\\s*(\\d)\\b", "verso{0}");
        rule("\\b(?:verso|verse|estrofa|couplet)\\b", "verso");
        rule("\\b(?:coro|chorus|estribillo|refrao)\\s*(\\d)\\b", "coro{0}");
        rule("\\b(?:coro|chorus|estribillo|refrao)\\b", "coro");
        rule("\\b(?:puente|bridge|ponte|pont)\\s*(\\d)\\b", "puente{0}");
        rule("\\b(?:puente|bridge|ponte|pont)\\b", "puente");
        rule("\\bintro(?:duccion)?\\b", "intro");
        rule("\\b(?:interludio|interlude)\\b", "interludio");
        rule("\\binstrumental\\b", "instrumental");
        rule("\\bsolo\\b", "solo");
        rule("\\bturn ?around\\b|\\bvuelta\\b", "turnaround");
        rule("\\bvamp\\b", "vamp");
        rule("\\b(?:refran|refrain)\\b", "refran");
        rule("\\b(?:tag|repetir|repite|repeat|otra vez)\\b", "tag");
        rule("\\brap\\b", "rap");
        rule("\\b(?:exhortacion|exhortation|exortacao)\\b", "exhortacion");
        rule("\\ba ?capp?ella\\b", "acapella");
        rule("\\bchannel\\b", "channel");
        rule("\\boutro\\b", "outro");
        rule("\\b(?:final|ending|fin|cierre|coda)\\b", "final");
        rule("\\bhits?\\b", "hits");
        rule("\\b(?:hold|sostener|sosten|sostenido)\\b", "hold");
        rule("\\b(?:softly|soft|suave|suavemente)\\b", "suave");
        rule("\\bswell\\b", "swell");
        rule("\\b(?:break|pausa|corte|parada|stop)\\b", "break");
        rule("\\b(?:drums|bateria)\\b", "drums");
        rule("\\b(?:bass|bajo)\\b", "bass");
        rule("\\b(?:guitar|guitarra|guitara|guitars)\\b", "guitar");
        rule("\\b(?:keys|teclado|teclados|piano)\\b", "keys");
        rule("\\bpads?\\b", "pad");
        rule("\\bclick\\b", "click");
        rule("\\b(?:cuenta|conteo|count ?in|count ?off|count)\\b", "cuenta");
    }

    /** Palabras de relleno comunes en los nombres de archivo de los paquetes. */
    static final Pattern NOISE = Pattern.compile(
            "\\b(?:voz guia|voces guia|guias?|guides?|cues?|vg|hombre|mujer|masculin[oa]|femenin[oa]|male|female|"
                    + "man|woman|esp(?:anol)?|spanish|castellano|english|ingles|french|frances|francais|portugese|portuguese|"
                    + "portugues|italian|italiano|german|aleman|deutsch|latam|voz|voces|voice|voices|v\\d|song sections?|"
                    + "dynamic)\\b");

    static final String[][] LANGUAGES = {
        {"es", "Español", "spanish espanol castellano latino latam"},
        {"en", "Inglés", "english ingles"},
        {"pt", "Portugués", "portugese portuguese portugues brasil brazil brasileiro"},
        {"fr", "Francés", "french frances francais"},
        {"it", "Italiano", "italian italiano"},
        {"de", "Alemán", "german aleman deutsch"},
    };
    static final String[][] GENDERS = {
        {"f", "mujer", "female mujer femenina femenino woman femme feminino"},
        {"m", "hombre", "male hombre masculina masculino man homme"},
    };

    static final Pattern CLICK_WORD = Pattern.compile("\\b(?:click|clicks|metronomo|metronome|claqueta)\\b");
    static final String[] CLICK_ROLES = {"accent", "beat", "eighth", "sixteenth"};
    static final Pattern[] CLICK_ROLE_RULES = {
        Pattern.compile("\\b(?:accents?|acentos?|acentuado|downbeat)\\b"),
        Pattern.compile("\\b(?:quarters?|negras?|beats?|pulso|normal)\\b"),
        Pattern.compile("\\b(?:eighths?|corcheas?|8ths?)\\b"),
        Pattern.compile("\\b(?:sixteenths?|semicorcheas?|16ths?)\\b"),
    };
    static final Pattern CLICK_NOISE = Pattern.compile(
            "\\b(?:new|nuevo|click|clicks|metronomo|metronome|claqueta|sound|sonido|sample)\\b");
    /** Carpetas de otros programas que vienen dentro de algunos paquetes (proyectos de Ableton…). */
    static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList("__macosx", "freeze", "ableton project info", "backup"));

    static final Map<String, List<String>> FALLBACKS = new HashMap<>();

    static {
        FALLBACKS.put("interludio", Arrays.asList("instrumental"));
        FALLBACKS.put("instrumental", Arrays.asList("interludio"));
        FALLBACKS.put("turnaround", Arrays.asList("interludio", "instrumental"));
        FALLBACKS.put("solo", Arrays.asList("instrumental"));
        FALLBACKS.put("final", Arrays.asList("outro"));
        FALLBACKS.put("outro", Arrays.asList("final"));
    }

    // ---- reconocer nombres ----------------------------------------------------------------------

    static String normalize(String text) {
        String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKD);
        t = t.replaceAll("[^\\x00-\\x7F]", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[_\\-.,()\\[\\]{}+/\\\\]+", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Último componente de una ruta ("Coro/Hombre.wav" -> "Hombre.wav"). */
    static String fileName(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** Carpeta que contiene al archivo ("" si no hay). */
    static String parentName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? "" : fileName(p.substring(0, slash));
    }

    /** Extensión en minúsculas (".wav"), o "" (como Path.suffix). */
    static String suffix(String name) {
        String base = fileName(name);
        int dot = base.lastIndexOf('.');
        return dot > 0 && dot < base.length() - 1 ? base.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    static String withoutSuffix(String name) {
        String base = fileName(name);
        int dot = base.lastIndexOf('.');
        return dot > 0 && dot < base.length() - 1 ? base.substring(0, dot) : base;
    }

    static String stem(String name) {
        String base = fileName(name);
        return AUDIO_EXTS.contains(suffix(base)) ? withoutSuffix(base) : base;
    }

    private static String applyRule(Object[] rule, Matcher m) {
        String cue = (String) rule[1];
        if (cue.contains("{0}")) {
            String numbered = cue.replace("{0}", m.group(1));
            return CUE_NAMES.containsKey(numbered) ? numbered : cue.replace("{0}", "");
        }
        return cue;
    }

    /** Tipo de voz a partir del nombre de un archivo (o de una parte de la canción). */
    static String classify(String name) {
        String base = normalize(stem(name));
        // Numeración al principio ("01 - Coro"), pero no si el nombre ES un número ("1").
        String stripped = base.replaceFirst("^\\d{1,3}\\s+(?=\\D)", "");
        String cleaned = NOISE.matcher(stripped).replaceAll(" ").replaceAll("\\s+", " ").trim();
        for (String candidate : new String[] {cleaned, stripped}) {
            for (Object[] rule : RULES) {
                Matcher m = ((Pattern) rule[0]).matcher(candidate);
                if (m.find()) {
                    return applyRule(rule, m);
                }
            }
        }
        Set<String> counts = new HashSet<>(Arrays.asList("1234", "123", "12345678", "unodostrescuatro", "onetwothreefour"));
        for (String candidate : new String[] {cleaned, stripped, base}) {
            if (counts.contains(candidate.replace(" ", ""))) {
                return "cuenta";
            }
            if (NUMBERS.containsKey(candidate)) {
                return NUMBERS.get(candidate);
            }
            if (!candidate.isEmpty() && candidate.matches("\\d+") && candidate.length() < 10) {
                int v = Integer.parseInt(candidate);
                if (v >= 1 && v <= 8) {
                    return "n" + v;
                }
            }
        }
        return null;
    }

    private static Set<String> words(String list) {
        return new HashSet<>(Arrays.asList(list.split(" ")));
    }

    /** Idioma (y voz) según el nombre o la carpeta: {"es", "Español"}, {"en-f", "Inglés (mujer)"} o null. */
    static String[] detectSet(String path) {
        Set<String> tokens = new HashSet<>(Arrays.asList(normalize(path).split(" ")));
        for (String[] lang : LANGUAGES) {
            if (!Collections.disjoint(tokens, words(lang[2]))) {
                for (String[] gender : GENDERS) {
                    if (!Collections.disjoint(tokens, words(gender[2]))) {
                        return new String[] {lang[0] + "-" + gender[0], lang[1] + " (" + gender[1] + ")"};
                    }
                }
                return new String[] {lang[0], lang[1]};
            }
        }
        return null;
    }

    /** str.title() de Python: mayúscula al principio de cada palabra (las letras que siguen a algo que no es letra). */
    static String title(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean previousCased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                out.append(previousCased ? Character.toLowerCase(c) : Character.toUpperCase(c));
                previousCased = true;
            } else {
                out.append(c);
                previousCased = false;
            }
        }
        return out.toString();
    }

    /** Sonido de click de un paquete: {estilo, nombre del estilo, rol} o null si no lo es. */
    static String[] classifyClick(String path) {
        String base = normalize(stem(path));
        String parent = normalize(parentName(path));
        if (!CLICK_WORD.matcher(parent + " " + base).find()) {
            return null;
        }
        for (int r = 0; r < CLICK_ROLE_RULES.length; r++) {
            Matcher m = CLICK_ROLE_RULES[r].matcher(base);
            if (m.find()) {
                String style = CLICK_ROLE_RULES[r].matcher(base).replaceAll(" ");
                style = CLICK_NOISE.matcher(style).replaceAll(" ");
                style = style.replaceAll("\\s+", " ").trim();
                if (style.isEmpty()) {
                    style = "click";
                }
                String id = style.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+", "").replaceAll("-+$", "");
                if (id.length() > 40) {
                    id = id.substring(0, 40);
                }
                if (id.isEmpty()) {
                    id = "click";
                }
                return new String[] {id, title(style), CLICK_ROLES[r]};
            }
        }
        return null;
    }

    /**
     * {parte, indicación} que nombra una parte de la canción: "Coro (última vez)" -> {"coro",
     * "ultimavez"}; "Puente sube tono" -> {"puente", "sube"}; "Verso 2" -> {"verso2", null}.
     */
    static String[] labelCues(String label) {
        String text = NOISE.matcher(normalize(label)).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String part = null, extra = null;
        for (Object[] rule : RULES) {
            Matcher m = ((Pattern) rule[0]).matcher(text);
            if (!m.find()) {
                continue;
            }
            String cue = applyRule(rule, m);
            String group = CUE_GROUPS.get(cue);
            if (PARTES.equals(group) && part == null) {
                part = cue;
            } else if (INDICACIONES.equals(group) && extra == null) {
                extra = cue;
            }
        }
        return new String[] {part, extra};
    }

    /**
     * Voz a usar para una parte de la canción. numbering: "verses" = número solo en los versos
     * ("Verso 2", pero "Coro" en cada coro), "all" = número en todas las que lo tengan, "none" = nunca.
     */
    static String cueForLabel(String label, Set<String> available, String numbering) {
        String cue = labelCues(label)[0];
        if (cue == null) {
            return null;
        }
        String base = cue.replaceFirst("\\d+$", "");
        List<String> candidates = new ArrayList<>();
        if (!base.equals(cue)) {
            boolean keep = "all".equals(numbering) || ("verses".equals(numbering) && base.equals("verso"));
            if (keep) {
                candidates.add(cue);
                candidates.add(base);
            } else {
                candidates.add(base);
                candidates.add(cue);
            }
        } else {
            candidates.add(cue);
        }
        List<String> more = FALLBACKS.get(base);
        if (more != null) {
            candidates.addAll(more);
        }
        for (String c : candidates) {
            if (available.contains(c)) {
                return c;
            }
        }
        return null;
    }

    /** Algunos paquetes usan la carpeta para la parte ("Coro/Hombre.wav"). */
    static String classifyWithFolder(String label) {
        String parent = parentName(label);
        return parent.isEmpty() ? null : classify(parent + " " + stem(label));
    }

    // ---- el paquete de voces --------------------------------------------------------------------

    /** Un archivo para importar: el nombre original (o la ruta dentro del .zip) y dónde está. */
    public static final class Upload {
        final String name;
        final File file;

        public Upload(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    public static final class GuideException extends Exception {
        final int status;

        GuideException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final File root;
    private final File audioDir;
    private final File index;
    private final Platform platform;

    public Guide(File root, Platform platform) {
        this.root = root;
        this.audioDir = new File(root, "audio");
        this.index = new File(root, "kit.json");
        this.platform = platform;
    }

    private JSONObject load() throws JSONException {
        JSONObject data = Json.readObject(index);
        if (data == null) {
            data = new JSONObject();
        }
        if (data.optJSONObject("files") == null) {
            data.put("files", new JSONObject());
        }
        if (data.optJSONObject("sets") == null) {
            data.put("sets", new JSONObject());
        }
        if (!data.has("active")) {
            data.put("active", JSONObject.NULL);
        }
        JSONObject files = data.getJSONObject("files");
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            JSONObject info = files.optJSONObject(it.next());
            if (info == null) {
                continue;
            }
            if (!info.has("kind")) {
                info.put("kind", "voz");
            }
            if (info.optString("kind").equals("voz") && !info.has("set")) {
                info.put("set", DEFAULT_SET);
            }
        }
        return data;
    }

    private void save(JSONObject data) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de las voces");
        }
        Json.write(index, data);
    }

    private static String active(JSONObject data) {
        return data.isNull("active") ? null : data.optString("active", null);
    }

    private static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    /** {id: info} de las voces de un idioma. */
    private static Map<String, JSONObject> voices(JSONObject data, String setId) {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        JSONObject files = data.optJSONObject("files");
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info != null && info.optString("kind").equals("voz") && setId != null && setId.equals(info.optString("set"))) {
                out.put(id, info);
            }
        }
        return out;
    }

    private static String cueOf(JSONObject info) {
        return info.isNull("cue") ? null : info.optString("cue", null);
    }

    private static String pickActive(JSONObject data) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        JSONObject files = data.optJSONObject("files");
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info != null && info.optString("kind").equals("voz") && cueOf(info) != null) {
                String set = info.optString("set");
                Integer c = counts.get(set);
                counts.put(set, c == null ? 1 : c + 1);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        String best = null;
        for (String s : counts.keySet()) {
            if ((s.equals("es") || s.startsWith("es-")) && (best == null || counts.get(s) > counts.get(best))) {
                best = s;
            }
        }
        if (best != null) {
            return best;
        }
        for (String s : counts.keySet()) {
            if (best == null || counts.get(s) > counts.get(best)) {
                best = s;
            }
        }
        return best;
    }

    File audioFile(String fileId) {
        return new File(audioDir, fileId + ".wav");
    }

    /** URL con la que la interfaz escucha un audio de las voces. */
    String urlOf(File audio) {
        return platform.fileUrl(audio);
    }

    /** {"accent": url, "beat": url} de un estilo de click (vacío si no está: se usa el de MoiMoi). */
    public synchronized JSONObject clickUrls(String style) throws JSONException {
        JSONArray styles = describeClicks(load());
        for (int i = 0; i < styles.length(); i++) {
            JSONObject s = styles.getJSONObject(i);
            if (s.optString("id").equals(style)) {
                JSONObject sounds = s.getJSONObject("sounds");
                JSONObject out = new JSONObject();
                String beat = sounds.optString("beat", sounds.optString("eighth", sounds.optString("sixteenth", sounds.optString("accent", ""))));
                String accent = sounds.optString("accent", beat);
                if (!beat.isEmpty()) {
                    out.put("accent", accent).put("beat", beat);
                }
                return out;
            }
        }
        return new JSONObject();
    }

    private String url(String fileId) {
        return platform.fileUrl(audioFile(fileId));
    }

    // -- consulta --

    public synchronized JSONObject describe(String setId) throws JSONException {
        JSONObject data = load();
        String active = active(data);
        JSONObject sets = data.getJSONObject("sets");
        String shown = setId != null && sets.has(setId) ? setId : active;
        List<JSONObject> setList = new ArrayList<>();
        for (String sid : keys(sets)) {
            Map<String, JSONObject> v = voices(data, sid);
            int assigned = 0;
            for (JSONObject info : v.values()) {
                if (cueOf(info) != null) {
                    assigned++;
                }
            }
            JSONObject info = sets.optJSONObject(sid);
            String name = info == null ? sid : info.optString("name", sid);
            setList.add(new JSONObject().put("id", sid).put("name", name.isEmpty() ? sid : name).put("files", v.size())
                    .put("assigned", assigned).put("active", sid.equals(active)));
        }
        Collections.sort(setList, (a, b) -> {
            boolean aa = a.optBoolean("active"), ba = b.optBoolean("active");
            if (aa != ba) {
                return aa ? -1 : 1;
            }
            return a.optString("name").compareTo(b.optString("name"));
        });
        List<JSONObject> files = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : voices(data, shown).entrySet()) {
            JSONObject info = e.getValue();
            files.add(new JSONObject().put("id", e.getKey())
                    .put("original", info.has("original") ? info.opt("original") : JSONObject.NULL)
                    .put("cue", info.has("cue") ? info.opt("cue") : JSONObject.NULL)
                    .put("duration", info.has("duration") ? info.opt("duration") : JSONObject.NULL)
                    .put("url", url(e.getKey())));
        }
        Collections.sort(files, (a, b) -> {
            int oa = order(a.isNull("cue") ? null : a.optString("cue")), ob = order(b.isNull("cue") ? null : b.optString("cue"));
            if (oa != ob) {
                return Integer.compare(oa, ob);
            }
            String na = a.isNull("original") ? "" : a.optString("original"), nb = b.isNull("original") ? "" : b.optString("original");
            return na.compareTo(nb);
        });
        Map<String, String> assigned = new HashMap<>();
        for (JSONObject f : files) {
            if (!f.isNull("cue") && !f.optString("cue").isEmpty()) {
                assigned.put(f.optString("cue"), f.optString("id"));
            }
        }
        JSONArray cues = new JSONArray();
        for (String[] c : CUE_TYPES) {
            cues.put(new JSONObject().put("id", c[0]).put("name", c[1]).put("group", c[2])
                    .put("file", assigned.containsKey(c[0]) ? assigned.get(c[0]) : JSONObject.NULL));
        }
        JSONArray missing = new JSONArray();
        if (shown != null) {
            for (String c : MAIN_CUES) {
                if (!assigned.containsKey(c)) {
                    missing.put(CUE_NAMES.get(c));
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("active", active == null ? JSONObject.NULL : active);
        out.put("set", shown == null ? JSONObject.NULL : shown);
        out.put("sets", new JSONArray(setList));
        out.put("cues", cues);
        out.put("files", new JSONArray(files));
        out.put("count", assigned.size());
        out.put("missing", missing);
        out.put("clicks", describeClicks(data));
        out.put("included", sorted(bundledSets()));
        return out;
    }

    private static int order(String cue) {
        Integer o = cue == null ? null : CUE_ORDER.get(cue);
        return o == null ? CUE_ORDER.size() : o;
    }

    private JSONArray describeClicks(JSONObject data) throws JSONException {
        Map<String, JSONObject> styles = new LinkedHashMap<>();
        JSONObject files = data.getJSONObject("files");
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info == null || !info.optString("kind").equals("click")) {
                continue;
            }
            String style = info.optString("style");
            JSONObject s = styles.get(style);
            if (s == null) {
                String name = info.optString("styleName", style);
                s = new JSONObject().put("id", style).put("name", name.isEmpty() ? style : name).put("sounds", new JSONObject());
                styles.put(style, s);
            }
            s.getJSONObject("sounds").put(info.optString("role"), url(id));
        }
        List<JSONObject> list = new ArrayList<>(styles.values());
        Collections.sort(list, (a, b) -> a.optString("name").compareTo(b.optString("name")));
        return new JSONArray(list);
    }

    /** {tipo de voz: archivo} del idioma elegido. */
    public synchronized Map<String, File> assignments() throws JSONException {
        JSONObject data = load();
        Map<String, File> out = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> e : voices(data, active(data)).entrySet()) {
            String cue = cueOf(e.getValue());
            File f = audioFile(e.getKey());
            if (cue != null && !cue.isEmpty() && f.isFile()) {
                out.put(cue, f);
            }
        }
        return out;
    }

    /** Las voces del idioma elegido, en mono a 44,1 kHz. */
    public Map<String, float[]> loadCues() throws JSONException, IOException {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, File> e : assignments().entrySet()) {
            out.put(e.getKey(), readMono(e.getValue()));
        }
        return out;
    }

    /** Sonidos de un estilo de click: {"accent": audio, "beat": audio, …} (mono). */
    public Map<String, float[]> clickSounds(String style) throws JSONException, IOException {
        JSONObject data;
        synchronized (this) {
            data = load();
        }
        Map<String, float[]> out = new LinkedHashMap<>();
        JSONObject files = data.getJSONObject("files");
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info != null && info.optString("kind").equals("click") && style.equals(info.optString("style"))) {
                File f = audioFile(id);
                if (f.isFile()) {
                    out.put(info.optString("role"), readMono(f));
                }
            }
        }
        return out;
    }

    static float[] readMono(File file) throws IOException {
        try (Wav.Reader r = new Wav.Reader(file)) {
            float[] out = new float[(int) r.frames];
            r.read(0, 0, out, 0, out.length);
            return out;
        }
    }

    // -- importar --

    private static JSONObject result(String original, String key, String value) throws JSONException {
        return new JSONObject().put("original", original).put(key, value);
    }

    /** Quita el silencio del principio y del final (guia.trim_silence). */
    static float[] trimSilence(float[] audio, double thresholdDb, boolean keepStart) {
        if (audio.length == 0) {
            return audio;
        }
        float peak = 0;
        for (float v : audio) {
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak <= 1e-5f) {
            return new float[0];
        }
        double threshold = peak * Math.pow(10, thresholdDb / 20);
        int first = -1, last = -1;
        for (int i = 0; i < audio.length; i++) {
            if (Math.abs(audio[i]) > threshold) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        int start = keepStart ? 0 : Math.max(0, first - (int) (0.008 * SR));
        int end = Math.min(audio.length, last + (int) (0.06 * SR));
        return Arrays.copyOfRange(audio, start, end);
    }

    /** Decodifica un audio a mono a 44,1 kHz (null si es demasiado largo para ser una voz). */
    private float[] decodeMono(File source, File tmp) throws IOException {
        File raw = new File(tmp, "voz.f32");
        File stereo = new File(tmp, "voz44.f32");
        try {
            Platform.Decoded decoded = platform.decode(source, raw, () -> false, f -> { });
            try (Pcm.Reader in = new Pcm.Reader(raw, decoded.channels, decoded.sampleRate)) {
                if (in.frames / (double) decoded.sampleRate > MAX_SOURCE_SECONDS) {
                    return null;
                }
                Pcm.toStereo(in, SR, stereo, null);
            }
            try (Pcm.Reader in = new Pcm.Reader(stereo, 2, SR)) {
                int n = (int) in.frames;
                float[] l = new float[n];
                float[] r = new float[n];
                in.read(0, 0L, l, 0, n);
                in.read(1, 0L, r, 0, n);
                for (int i = 0; i < n; i++) {
                    l[i] = (l[i] + r[i]) / 2f;
                }
                return l;
            }
        } finally {
            raw.delete();
            stereo.delete();
        }
    }

    private String write(float[] audio) throws IOException {
        if (!audioDir.isDirectory() && !audioDir.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta de las voces");
        }
        String id = Json.newId();
        File target = audioFile(id);
        try (Wav.Writer w = new Wav.Writer(target, 1, SR)) {
            w.write(audio, audio, audio.length);
        }
        return id;
    }

    private void drop(JSONObject data, String fileId) {
        data.optJSONObject("files").remove(fileId);
        audioFile(fileId).delete();
    }

    private JSONObject storeVoice(JSONObject data, File source, String original, String cue, String setId, File tmp)
            throws IOException, JSONException {
        float[] decoded = decodeMono(source, tmp);
        if (decoded == null) {
            return result(original, "skipped", "demasiado largo");
        }
        float[] audio = trimSilence(decoded, -40.0, false);
        double duration = audio.length / (double) SR;
        if (duration > MAX_CUE_SECONDS) {
            return result(original, "skipped", String.format(Locale.US, "demasiado largo (%.0f s)", duration));
        }
        if (duration < 0.05) {
            return result(original, "skipped", "no tiene sonido");
        }
        float peak = 0;
        for (float v : audio) {
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak == 0) {
            peak = 1f;
        }
        float gain = (float) (0.89 / peak); // pico en -1 dBFS
        for (int i = 0; i < audio.length; i++) {
            audio[i] *= gain;
        }
        // El mismo archivo cargado otra vez reemplaza al anterior.
        for (Map.Entry<String, JSONObject> e : voices(data, setId).entrySet()) {
            if (original.equals(e.getValue().optString("original"))) {
                drop(data, e.getKey());
            }
        }
        if (cue != null) {
            // Una sola voz por tipo en cada idioma: la nueva reemplaza a la anterior.
            for (JSONObject info : voices(data, setId).values()) {
                if (cue.equals(cueOf(info))) {
                    info.put("cue", JSONObject.NULL);
                }
            }
        }
        String id = write(audio);
        data.getJSONObject("files").put(id, new JSONObject().put("kind", "voz").put("set", setId).put("original", original)
                .put("cue", cue == null ? JSONObject.NULL : cue).put("duration", Json.round(duration, 2)));
        return new JSONObject().put("original", original).put("id", id).put("cue", cue == null ? JSONObject.NULL : cue)
                .put("set", setId);
    }

    private JSONObject storeClick(JSONObject data, File source, String original, String[] click, File tmp)
            throws IOException, JSONException {
        float[] decoded = decodeMono(source, tmp);
        if (decoded == null) {
            return result(original, "skipped", "demasiado largo");
        }
        float[] audio = trimSilence(decoded, -60.0, true);
        if (audio.length < (int) (0.003 * SR)) {
            return result(original, "skipped", "no tiene sonido");
        }
        audio = Arrays.copyOf(audio, Math.min(audio.length, (int) (MAX_CLICK_SECONDS * SR)));
        JSONObject files = data.getJSONObject("files");
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info != null && info.optString("kind").equals("click") && click[0].equals(info.optString("style"))
                    && click[2].equals(info.optString("role"))) {
                drop(data, id);
            }
        }
        String id = write(audio);
        files.put(id, new JSONObject().put("kind", "click").put("style", click[0]).put("styleName", click[1])
                .put("role", click[2]).put("original", original).put("duration", Json.round(audio.length / (double) SR, 3)));
        return new JSONObject().put("original", original).put("id", id).put("click", click[0]).put("role", click[2]);
    }

    /** Extrae los audios de un .zip; los archivos dañados se saltean (y se informan). */
    static List<Upload> extractZip(File zip, File dir, List<JSONObject> errors, String zipName) throws JSONException {
        List<Upload> items = new ArrayList<>();
        Charset names;
        try {
            names = Charset.forName("IBM437"); // como zipfile de Python con los nombres sin la marca UTF-8
        } catch (RuntimeException e) {
            names = Json.UTF8;
        }
        ZipFile archive;
        try {
            archive = new ZipFile(zip, names);
        } catch (IOException | RuntimeException e) {
            errors.add(result(zipName, "error", "el .zip está dañado o incompleto"));
            return items;
        }
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        try {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry;
                try {
                    entry = entries.nextElement();
                } catch (RuntimeException e) {
                    errors.add(result(zipName, "error", "el .zip está dañado o incompleto"));
                    break;
                }
                String name = entry.getName().replace('\\', '/');
                String base = fileName(name);
                boolean skipDir = false;
                String[] parts = name.split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (SKIP_DIRS.contains(parts[i].toLowerCase(Locale.ROOT))) {
                        skipDir = true;
                    }
                }
                if (entry.isDirectory() || base.startsWith(".") || skipDir) {
                    continue;
                }
                if (!AUDIO_EXTS.contains(suffix(base)) || entry.getSize() > 50L * 1024 * 1024) {
                    continue;
                }
                File target = new File(dir, String.format(Locale.US, "%04d%s", items.size(), suffix(base)));
                try (InputStream in = archive.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    long total = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > 50L * 1024 * 1024) {
                            throw new IOException("demasiado grande");
                        }
                        out.write(buffer, 0, n);
                    }
                } catch (IOException | RuntimeException e) {
                    target.delete();
                    errors.add(result(base, "error", "archivo dañado dentro del .zip"));
                    continue;
                }
                items.add(new Upload(name, target));
            }
        } finally {
            try {
                archive.close();
            } catch (IOException ignored) {
                // nada
            }
        }
        return items;
    }

    /**
     * Importa audios sueltos o .zip con audios (paquetes completos). forcedCue: el archivo es esa
     * voz (una grabación hecha en la app); targetSet: idioma de los que no dicen de qué idioma son.
     */
    public synchronized JSONObject importFiles(List<Upload> uploads, String forcedCue, String targetSet, File tmp)
            throws GuideException, JSONException, IOException {
        if (forcedCue != null && !CUE_NAMES.containsKey(forcedCue)) {
            throw new GuideException(400, "Tipo de voz desconocido");
        }
        List<JSONObject> results = new ArrayList<>();
        JSONObject data = load();
        JSONObject sets = data.getJSONObject("sets");
        if (targetSet != null && !sets.has(targetSet) && !targetSet.equals(DEFAULT_SET)) {
            throw new GuideException(400, "Ese idioma no existe");
        }
        String fallbackSet = targetSet != null ? targetSet : DEFAULT_SET;
        Store.removeTree(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("No se pudo crear la carpeta temporal");
        }
        try {
            List<Object[]> items = new ArrayList<>(); // {ruta dentro del paquete, archivo, idioma del zip}
            int zips = 0;
            for (Upload u : uploads) {
                String ext = suffix(u.name);
                if (ext.equals(".zip")) {
                    List<JSONObject> errors = new ArrayList<>();
                    List<Upload> extracted = extractZip(u.file, new File(tmp, "zip" + zips++), errors, fileName(u.name));
                    String[] zipSet = detectSet(withoutSuffix(u.name));
                    for (Upload e : extracted) {
                        items.add(new Object[] {e.name, e.file, zipSet == null ? null : zipSet[0]});
                    }
                    results.addAll(errors);
                    if (zipSet != null && !sets.has(zipSet[0])) {
                        sets.put(zipSet[0], new JSONObject().put("name", zipSet[1]));
                    }
                } else if (AUDIO_EXTS.contains(ext) || forcedCue != null) {
                    items.add(new Object[] {u.name, u.file, null});
                } else {
                    results.add(result(fileName(u.name), "skipped", "no es un archivo de audio"));
                }
            }
            if (items.size() > MAX_FILES) {
                results.add(result((items.size() - MAX_FILES) + " archivos más", "skipped", "máximo " + MAX_FILES + " por vez"));
            }
            File work = new File(tmp, "trabajo");
            work.mkdirs();
            for (int i = 0; i < Math.min(items.size(), MAX_FILES); i++) {
                String label = (String) items.get(i)[0];
                File file = (File) items.get(i)[1];
                String zipSet = (String) items.get(i)[2];
                String original = fileName(label);
                try {
                    String[] click = forcedCue != null ? null : classifyClick(label);
                    if (click != null) {
                        results.add(storeClick(data, file, original, click, work));
                        continue;
                    }
                    String[] detected = forcedCue != null ? null : detectSet(label);
                    if (detected != null && !sets.has(detected[0])) {
                        sets.put(detected[0], new JSONObject().put("name", detected[1]));
                    }
                    String setId = detected != null ? detected[0] : (zipSet != null ? zipSet : fallbackSet);
                    if (setId.equals(DEFAULT_SET) && !sets.has(DEFAULT_SET)) {
                        sets.put(DEFAULT_SET, new JSONObject().put("name", DEFAULT_SET_NAME));
                    }
                    String cue = forcedCue;
                    if (cue == null) {
                        cue = classify(original);
                    }
                    if (cue == null) {
                        cue = classifyWithFolder(label);
                    }
                    results.add(storeVoice(data, file, original, cue, setId, work));
                } catch (IOException | RuntimeException e) {
                    String message = "no se pudo leer (" + e.getMessage() + ")";
                    results.add(result(original, "error", message.length() > 160 ? message.substring(0, 160) : message));
                }
            }
            pruneSets(data);
            String active = active(data);
            if (active == null || !sets.has(active) || voices(data, active).isEmpty()) {
                String picked = pickActive(data);
                data.put("active", picked == null ? JSONObject.NULL : picked);
            }
            save(data);
        } finally {
            Store.removeTree(tmp);
        }
        JSONArray added = new JSONArray(), errors = new JSONArray(), skipped = new JSONArray();
        int recognized = 0;
        Set<String> clickStyles = new HashSet<>();
        java.util.TreeSet<String> addedSets = new java.util.TreeSet<>();
        for (JSONObject r : results) {
            if (r.has("id") && !r.has("click")) {
                added.put(r);
                if (!r.isNull("cue")) {
                    recognized++;
                }
                addedSets.add(r.optString("set"));
            } else if (r.has("click")) {
                clickStyles.add(r.optString("click"));
            }
            if (r.has("error")) {
                errors.put(r);
            }
            if (r.has("skipped")) {
                skipped.put(r);
            }
        }
        JSONObject summary = new JSONObject();
        summary.put("added", added.length());
        summary.put("recognized", recognized);
        summary.put("clicks", clickStyles.size());
        summary.put("sets", new JSONArray(addedSets));
        summary.put("errors", errors);
        summary.put("skipped", skipped);
        summary.put("files", added);
        return summary;
    }

    private static void pruneSets(JSONObject data) {
        JSONObject sets = data.optJSONObject("sets");
        for (String sid : keys(sets)) {
            if (voices(data, sid).isEmpty()) {
                sets.remove(sid);
            }
        }
    }

    // -- editar --

    public synchronized void setActive(String setId) throws GuideException, JSONException, IOException {
        JSONObject data = load();
        if (setId == null || !data.getJSONObject("sets").has(setId)) {
            throw new GuideException(404, "Ese idioma no existe");
        }
        data.put("active", setId);
        save(data);
    }

    public synchronized void assign(String fileId, String cue) throws GuideException, JSONException, IOException {
        if (cue != null && !CUE_NAMES.containsKey(cue)) {
            throw new GuideException(400, "Tipo de voz desconocido");
        }
        JSONObject data = load();
        JSONObject info = data.getJSONObject("files").optJSONObject(fileId == null ? "" : fileId);
        if (info == null || !info.optString("kind").equals("voz")) {
            throw new GuideException(404, "Esa voz no existe");
        }
        if (cue != null) {
            for (JSONObject other : voices(data, info.optString("set")).values()) {
                if (cue.equals(cueOf(other))) {
                    other.put("cue", JSONObject.NULL);
                }
            }
        }
        info.put("cue", cue == null ? JSONObject.NULL : cue);
        save(data);
    }

    public synchronized void remove(String fileId) throws JSONException, IOException {
        JSONObject data = load();
        drop(data, fileId);
        pruneSets(data);
        String active = active(data);
        if (active == null || !data.getJSONObject("sets").has(active)) {
            String picked = pickActive(data);
            data.put("active", picked == null ? JSONObject.NULL : picked);
        }
        save(data);
    }

    public synchronized void removeSet(String setId) throws JSONException, IOException {
        forgetBundled("sets", setId);
        JSONObject data = load();
        for (String id : new ArrayList<>(voices(data, setId).keySet())) {
            drop(data, id);
        }
        data.getJSONObject("sets").remove(setId);
        if (setId.equals(active(data))) {
            String picked = pickActive(data);
            data.put("active", picked == null ? JSONObject.NULL : picked);
        }
        save(data);
    }

    public synchronized void removeClicks(String style) throws JSONException, IOException {
        forgetBundled("clicks", style);
        JSONObject data = load();
        JSONObject files = data.getJSONObject("files");
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info != null && info.optString("kind").equals("click") && style.equals(info.optString("style"))) {
                drop(data, id);
            }
        }
        save(data);
    }

    public synchronized void clear() {
        Store.removeTree(root);
    }

    // -- voces incluidas en la app --

    /** Carpeta dentro de la app con las voces y los clicks incluidos: kit.json + audio/<id>.wav. */
    static final String BUNDLE = "voz-guia";

    private File bundleMarker() {
        return new File(root, "incluidas.json");
    }

    private static Set<String> toSet(JSONArray array) {
        Set<String> out = new HashSet<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                out.add(array.optString(i));
            }
        }
        return out;
    }

    private static JSONArray sorted(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return new JSONArray(list);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private boolean copyBundled(String path, File target) throws IOException {
        try (InputStream in = platform.openBundled(path)) {
            if (in == null) {
                return false;
            }
            if (!audioDir.isDirectory() && !audioDir.mkdirs()) {
                throw new IOException("No se pudo crear la carpeta de las voces");
            }
            File tmp = new File(target.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("No se pudo guardar una voz");
            }
            return true;
        }
    }

    /** El usuario borró un idioma o un sonido de click de los incluidos: no se vuelve a poner solo. */
    private void forgetBundled(String field, String id) throws JSONException, IOException {
        JSONObject marker = Json.readObject(bundleMarker());
        if (marker == null) {
            return;
        }
        Set<String> own = toSet(marker.optJSONArray(field));
        if (own.remove(id)) {
            Set<String> removed = toSet(marker.optJSONArray("removed_" + field));
            removed.add(id);
            marker.put(field, sorted(own)).put("removed_" + field, sorted(removed));
            Json.write(bundleMarker(), marker);
        }
    }

    /** Idiomas de voces que vinieron con la app y siguen instalados. */
    synchronized Set<String> bundledSets() {
        JSONObject marker = Json.readObject(bundleMarker());
        return marker == null ? new HashSet<>() : toSet(marker.optJSONArray("sets"));
    }

    /**
     * Instala las voces guía y los sonidos de click que vienen con la app, para que no haya que
     * cargar nada: la primera vez, todo; cuando la app trae otras (se nota porque cambia kit.json),
     * lo que falte. No toca lo que cargó el usuario (un idioma propio con el mismo nombre queda
     * como está) ni vuelve a poner lo que borró, salvo con restore (botón "Restaurar voces
     * incluidas"). Devuelve cuántos archivos agregó, o -1 si la app no trae voces.
     */
    public synchronized int installBundled(boolean restore) throws IOException, JSONException {
        byte[] raw;
        try (InputStream in = platform.openBundled(BUNDLE + "/kit.json")) {
            if (in == null) {
                return -1;
            }
            raw = readAll(in);
        }
        CRC32 crc = new CRC32();
        crc.update(raw);
        String version = Long.toHexString(crc.getValue());
        JSONObject marker = Json.readObject(bundleMarker());
        if (marker == null) {
            marker = new JSONObject();
        }
        if (!restore && version.equals(marker.optString("version"))) {
            return 0;
        }
        JSONObject bundle = new JSONObject(new String(raw, "UTF-8"));
        JSONObject bundleFiles = bundle.optJSONObject("files");
        JSONObject bundleSets = bundle.optJSONObject("sets");
        if (bundleFiles == null) {
            return 0;
        }
        Set<String> ownSets = toSet(marker.optJSONArray("sets"));
        Set<String> ownClicks = toSet(marker.optJSONArray("clicks"));
        Set<String> removedSets = toSet(marker.optJSONArray("removed_sets"));
        Set<String> removedClicks = toSet(marker.optJSONArray("removed_clicks"));

        JSONObject data = load();
        JSONObject files = data.getJSONObject("files");
        JSONObject sets = data.getJSONObject("sets");
        Map<String, Set<String>> cues = new HashMap<>(); // idioma -> voces asignadas
        Map<String, Set<String>> roles = new HashMap<>(); // estilo de click -> sonidos
        for (String id : keys(files)) {
            JSONObject info = files.optJSONObject(id);
            if (info == null) {
                continue;
            }
            if (info.optString("kind").equals("click")) {
                roles.computeIfAbsent(info.optString("style"), k -> new HashSet<>()).add(info.optString("role"));
            } else if (cueOf(info) != null) {
                cues.computeIfAbsent(info.optString("set"), k -> new HashSet<>()).add(cueOf(info));
            }
        }
        int added = 0;
        for (String id : keys(bundleFiles)) {
            JSONObject info = bundleFiles.optJSONObject(id);
            if (info == null) {
                continue;
            }
            boolean click = info.optString("kind").equals("click");
            String group = click ? info.optString("style") : info.optString("set");
            Set<String> own = click ? ownClicks : ownSets;
            if (files.has(id)) {
                own.add(group); // ya instalada antes
                continue;
            }
            boolean exists = click ? roles.containsKey(group) : sets.has(group);
            if (exists && !own.contains(group)) {
                continue; // el usuario tiene los suyos con ese nombre
            }
            if (!exists && (click ? removedClicks : removedSets).contains(group) && !restore) {
                continue; // lo borró el usuario
            }
            if (exists) {
                String cue = cueOf(info);
                boolean taken = click ? roles.get(group).contains(info.optString("role"))
                        : cue == null || cues.getOrDefault(group, Collections.emptySet()).contains(cue);
                if (taken) {
                    continue; // solo se completa lo que falta
                }
            }
            if (!copyBundled(BUNDLE + "/audio/" + id + ".wav", audioFile(id))) {
                continue;
            }
            files.put(id, new JSONObject(info.toString()));
            if (click) {
                roles.computeIfAbsent(group, k -> new HashSet<>()).add(info.optString("role"));
                removedClicks.remove(group);
            } else {
                if (!sets.has(group)) {
                    JSONObject setInfo = bundleSets == null ? null : bundleSets.optJSONObject(group);
                    sets.put(group, setInfo != null ? new JSONObject(setInfo.toString()) : new JSONObject().put("name", group));
                }
                if (cueOf(info) != null) {
                    cues.computeIfAbsent(group, k -> new HashSet<>()).add(cueOf(info));
                }
                removedSets.remove(group);
            }
            own.add(group);
            added++;
        }
        String active = active(data);
        if (active == null || !sets.has(active)) {
            String preferred = bundle.isNull("active") ? null : bundle.optString("active", null);
            String picked = preferred != null && sets.has(preferred) ? preferred : pickActive(data);
            data.put("active", picked == null ? JSONObject.NULL : picked);
        }
        save(data);
        marker.put("version", version).put("sets", sorted(ownSets)).put("clicks", sorted(ownClicks))
                .put("removed_sets", sorted(removedSets)).put("removed_clicks", sorted(removedClicks));
        Json.write(bundleMarker(), marker);
        return added;
    }

    // ---- pista guía ---------------------------------------------------------------------------

    public static final class Placement {
        final String cue;
        final double time; // segundos en la pista exportada
        final String label;

        Placement(String cue, double time, String label) {
            this.cue = cue;
            this.time = time;
            this.label = label;
        }
    }

    /** Pasa de segundos de la canción a segundos de la pista exportada. */
    public interface ToOutput {
        double at(double songTime);
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    /**
     * Dónde va cada voz (guia.plan_guide). Cada parte se anuncia en el primer pulso del compás
     * anterior; extras = {número de parte: indicación} ("Sube tono" donde cambia la tonalidad),
     * que suena un compás antes del nombre. Cada parte puede traer "guide" (voz elegida a mano;
     * "" = sin voz) y "guideExtra".
     */
    public static List<Placement> plan(JSONArray sections, double[] beats, int beatsPerBar, Set<String> available,
                                       ToOutput toOutput, double[] countTimes, String numbering,
                                       Map<Integer, String> extras, Integer leadBeats) {
        List<Placement> placements = new ArrayList<>();
        int lead = leadBeats != null ? leadBeats : beatsPerBar;
        double period = 0.5;
        if (beats.length > 1) {
            double[] d = new double[beats.length - 1];
            for (int i = 0; i + 1 < beats.length; i++) {
                d[i] = beats[i + 1] - beats[i];
            }
            period = median(d);
        }
        double bar = beatsPerBar * period;

        // Cuenta: en el último compás, un número por pulso; en los anteriores, "1 … 2 …".
        Double countEnd = null;
        if (countTimes.length > 0) {
            int perBar = beatsPerBar;
            int bars = Math.max(1, countTimes.length / perBar);
            boolean allNumbers = true;
            for (int i = 1; i <= perBar; i++) {
                allNumbers &= available.contains("n" + i);
            }
            if (allNumbers) {
                for (int index = 0; index < countTimes.length; index++) {
                    int barIndex = index / perBar, beat = index % perBar;
                    if (barIndex < bars - 1) {
                        int half = perBar % 2 == 0 ? perBar / 2 : perBar;
                        if (beat % half == 0) {
                            placements.add(new Placement("n" + (beat / half + 1), countTimes[index], "cuenta"));
                        }
                    } else {
                        placements.add(new Placement("n" + (beat + 1), countTimes[index], "cuenta"));
                    }
                }
            } else if (available.contains("cuenta")) {
                double firstOfLast = countTimes.length >= perBar ? countTimes[countTimes.length - perBar] : countTimes[0];
                placements.add(new Placement("cuenta", firstOfLast, "cuenta"));
            }
            if (!placements.isEmpty()) {
                double step = countTimes.length > 1 ? countTimes[countTimes.length - 1] - countTimes[countTimes.length - 2] : period;
                countEnd = countTimes[countTimes.length - 1] + step;
            }
        }

        Map<Integer, String> extra = extras == null ? new HashMap<>() : extras;
        for (int index = 0; index < sections.length(); index++) {
            JSONObject section = sections.optJSONObject(index);
            if (section == null) {
                continue;
            }
            double start = section.optDouble("start", 0);
            String label = section.isNull("label") ? "" : section.optString("label", "");
            String cue;
            if (section.has("guide") && !section.isNull("guide")) {
                String chosen = section.optString("guide");
                cue = available.contains(chosen) ? chosen : null;
            } else {
                cue = cueForLabel(label, available, numbering);
            }
            String extraCue = section.isNull("guideExtra") ? "" : section.optString("guideExtra", "");
            if (extraCue.isEmpty()) {
                String fromLabel = labelCues(label)[1];
                extraCue = fromLabel != null ? fromLabel : extra.get(index);
            }
            if (extraCue != null && !available.contains(extraCue)) {
                extraCue = null;
            }
            if (start < bar * 0.75) {
                continue; // la canción arranca con esta parte: la cubre la cuenta
            }
            Object[][] kinds = {{extraCue, cue != null ? 2 : 1}, {cue, 1}};
            for (Object[] k : kinds) {
                String kind = (String) k[0];
                if (kind == null) {
                    continue;
                }
                int barsBefore = (Integer) k[1];
                double time = toOutput.at(anchor(beats, start, barsBefore, lead, period, bar));
                if (countEnd != null && time < countEnd - 0.05) {
                    continue; // cae dentro de la cuenta inicial
                }
                placements.add(new Placement(kind, time, label));
            }
        }
        List<Placement> sorted = new ArrayList<>(placements);
        Collections.sort(sorted, (a, b) -> Double.compare(a.time, b.time)); // estable, como sorted()
        return sorted;
    }

    private static double anchor(double[] beats, double start, int barsBefore, int lead, double period, double bar) {
        int beatsBefore = lead * barsBefore;
        double anchor;
        if (beats.length > 0) {
            // searchsorted (izquierda): el primer pulso >= start - 0.08
            double target = start - 0.08;
            int lo = 0, hi = beats.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (beats[mid] < target) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            anchor = lo - beatsBefore >= 0 ? beats[lo - beatsBefore] : start - beatsBefore * period;
        } else {
            anchor = start - beatsBefore * period;
        }
        return Math.max(anchor, start - (barsBefore + 1) * bar);
    }

    /** Pista guía (mono). Si una voz se superpone con la siguiente, se corta con un fundido. */
    public static float[] render(List<Placement> placements, Map<String, float[]> cues, int length) {
        float[] out = new float[Math.max(0, length)];
        int fade = (int) (0.02 * SR);
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            float[] audio = cues.get(p.cue);
            if (audio == null) {
                continue;
            }
            int start = (int) Math.rint(p.time * SR);
            if (start >= length || start + audio.length <= 0) {
                continue;
            }
            float[] clip = audio.clone();
            if (i + 1 < placements.size()) {
                int limit = (int) Math.rint(placements.get(i + 1).time * SR) - start;
                if (limit <= 0) {
                    continue; // dos voces en el mismo lugar: queda la siguiente
                }
                if (limit < clip.length) {
                    clip = Arrays.copyOf(clip, limit);
                    int n = Math.min(fade, clip.length);
                    for (int k = 0; k < n; k++) {
                        float w = n == 1 ? 1f : (float) (1.0 - k / (double) (n - 1));
                        clip[clip.length - n + k] *= w;
                    }
                }
            }
            int from = 0;
            if (start < 0) {
                from = -start;
                start = 0;
            }
            int end = Math.min(length, start + clip.length - from);
            for (int k = start; k < end; k++) {
                out[k] += clip[from + k - start];
            }
        }
        return out;
    }
}
