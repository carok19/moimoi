package com.moimoi.local;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pistas y tipos de separación: lo mismo que backend/moimoi/separation/base.py. */
public final class Stems {

    private Stems() {}

    public static final class Info {
        public final String id;
        public final String name;
        public final String color;
        public final String fileName;

        Info(String id, String name, String color, String fileName) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.fileName = fileName;
        }
    }

    public static final class Preset {
        public final String id;
        public final String name;
        public final String description;
        public final List<String> stems;

        Preset(String id, String name, String description, String... stems) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.stems = Collections.unmodifiableList(Arrays.asList(stems));
        }
    }

    public static final Map<String, Info> INFO = new LinkedHashMap<>();
    public static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    public static final List<String> ORDER = Arrays.asList(
            "vocals", "drums", "bass", "guitar", "piano", "other", "instrumental");
    public static final String DEFAULT_PRESET = "6stems";
    /** Fuentes del modelo htdemucs_6s, en el orden en que las devuelve. */
    public static final String[] MODEL_SOURCES = {"drums", "bass", "other", "vocals", "guitar", "piano"};

    static {
        add(new Info("vocals", "Voz", "#ff5d8f", "Voz"));
        add(new Info("drums", "Batería", "#ffa24c", "Bateria"));
        add(new Info("bass", "Bajo", "#a77bff", "Bajo"));
        add(new Info("guitar", "Guitarra", "#ffd75e", "Guitarra"));
        add(new Info("piano", "Piano", "#57a8ff", "Piano"));
        add(new Info("other", "Otros", "#3fd9b0", "Otros"));
        add(new Info("instrumental", "Acompañamiento", "#9fb3c8", "Acompanamiento"));
        addPreset(new Preset("2stems", "Voz y acompañamiento",
                "2 pistas: la voz sola y todo lo demás (ideal para karaoke o para cantar encima).",
                "vocals", "instrumental"));
        addPreset(new Preset("4stems", "Voz, batería, bajo y otros",
                "4 pistas: la separación clásica.", "vocals", "drums", "bass", "other"));
        addPreset(new Preset("6stems", "Voz, batería, bajo, guitarra, piano y otros",
                "6 pistas: separa también guitarra y piano/teclados.",
                "vocals", "drums", "bass", "guitar", "piano", "other"));
    }

    private static void add(Info info) {
        INFO.put(info.id, info);
    }

    private static void addPreset(Preset preset) {
        PRESETS.put(preset.id, preset);
    }

    public static List<String> ordered(List<String> stems) {
        List<String> copy = new ArrayList<>(stems);
        Collections.sort(copy, (a, b) -> Integer.compare(rank(a), rank(b)));
        return copy;
    }

    private static int rank(String stem) {
        int i = ORDER.indexOf(stem);
        return i < 0 ? ORDER.size() : i;
    }

    /**
     * Qué fuentes del modelo suman en cada pista del tipo de separación (como assemble_preset_stems):
     * "instrumental" = todo menos la voz; lo que el tipo no tiene (guitarra y piano con 4 pistas) va
     * a "otros". Devuelve, para cada pista del tipo, los índices de MODEL_SOURCES que la forman.
     */
    public static int[][] mapping(Preset preset) {
        int[][] result = new int[preset.stems.size()][];
        for (int i = 0; i < preset.stems.size(); i++) {
            String stem = preset.stems.get(i);
            List<Integer> parts = new ArrayList<>();
            for (int s = 0; s < MODEL_SOURCES.length; s++) {
                String source = MODEL_SOURCES[s];
                boolean belongs;
                if (stem.equals("instrumental")) {
                    belongs = !source.equals("vocals");
                } else if (stem.equals("other")) {
                    belongs = source.equals("other") || !preset.stems.contains(source);
                } else {
                    belongs = source.equals(stem);
                }
                if (belongs) {
                    parts.add(s);
                }
            }
            int[] idx = new int[parts.size()];
            for (int k = 0; k < idx.length; k++) {
                idx[k] = parts.get(k);
            }
            result[i] = idx;
        }
        return result;
    }

    public static JSONObject presetsJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("default", DEFAULT_PRESET);
        JSONArray presets = new JSONArray();
        for (Preset p : PRESETS.values()) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("description", p.description);
            o.put("stems", new JSONArray(p.stems));
            presets.put(o);
        }
        out.put("presets", presets);
        JSONArray stems = new JSONArray();
        for (Info info : INFO.values()) {
            JSONObject o = new JSONObject();
            o.put("id", info.id);
            o.put("name", info.name);
            o.put("color", info.color);
            stems.put(o);
        }
        out.put("stems", stems);
        JSONArray qualities = new JSONArray();
        JSONObject normal = new JSONObject();
        normal.put("id", "normal");
        normal.put("name", "Normal");
        normal.put("description", "La separación de 6 pistas de Demucs, hecha en este celular.");
        qualities.put(normal);
        out.put("qualities", qualities);
        return out;
    }
}
