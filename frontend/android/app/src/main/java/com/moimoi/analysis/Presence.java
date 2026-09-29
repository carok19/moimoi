package com.moimoi.analysis;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** ¿Qué instrumentos suenan en la canción y en qué partes? (analysis/presence.py) */
final class Presence {

    private Presence() {}

    static final double WINDOW_S = 0.5;
    static final double HOP_S = 0.25;
    static final int FRAME = (int) (WINDOW_S * Dsp.SR);
    static final int HOP = (int) (HOP_S * Dsp.SR);

    /** Nivel por ventana de una pista (o de la mezcla). */
    static double[] level(float[] y) {
        return Dsp.rmsDb(y, FRAME, HOP);
    }

    /** _runs: máscara de actividad -> tramos [inicio, fin] (fusiona huecos cortos). */
    static JSONArray runs(boolean[] active, double hop) throws JSONException {
        double minLen = 0.75, maxGap = 1.5;
        List<double[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < active.length; i++) {
            if (active[i] && start < 0) {
                start = i;
            } else if (!active[i] && start >= 0) {
                runs.add(new double[] {start * hop, i * hop});
                start = -1;
            }
        }
        if (start >= 0) {
            runs.add(new double[] {start * hop, active.length * hop});
        }
        List<double[]> merged = new ArrayList<>();
        for (double[] run : runs) {
            if (!merged.isEmpty() && run[0] - merged.get(merged.size() - 1)[1] <= maxGap) {
                merged.get(merged.size() - 1)[1] = run[1];
            } else {
                merged.add(run);
            }
        }
        JSONArray out = new JSONArray();
        for (double[] run : merged) {
            if (run[1] - run[0] >= minLen) {
                out.put(new JSONArray().put(Dsp.round(run[0], 2)).put(Dsp.round(run[1] + WINDOW_S - HOP_S, 2)));
            }
        }
        return out;
    }

    /** analyze_presence: levels = nivel de cada pista (en el orden de names), mix = nivel de la mezcla. */
    static JSONObject analyze(List<String> names, List<double[]> levels, double[] mix) throws JSONException {
        double top = Dsp.max(mix);
        double floor = Math.max(-55.0, top - 50.0);
        boolean[] music = new boolean[mix.length];
        int musicCount = 0;
        for (int i = 0; i < mix.length; i++) {
            music[i] = mix[i] > floor;
            if (music[i]) {
                musicCount++;
            }
        }
        int musicWindows = Math.max(1, musicCount);
        JSONObject result = new JSONObject();
        for (int s = 0; s < names.size(); s++) {
            double[] level = levels.get(s);
            boolean[] active = new boolean[mix.length];
            List<Double> diffs = new ArrayList<>();
            for (int i = 0; i < mix.length; i++) {
                // Suena si supera un piso absoluto y no queda enterrado bajo la mezcla.
                active[i] = level[i] > -48.0 && level[i] > mix[i] - 24.0 && music[i];
                if (active[i]) {
                    diffs.add(level[i] - mix[i]);
                }
            }
            double ratio = diffs.size() / (double) musicWindows;
            double rel = -60.0;
            if (!diffs.isEmpty()) {
                double[] d = new double[diffs.size()];
                for (int i = 0; i < d.length; i++) {
                    d[i] = diffs.get(i);
                }
                rel = Dsp.median(d);
            }
            String label;
            if (ratio < 0.03 || rel < -30) {
                label = "ausente";
            } else if (ratio > 0.45 && rel > -14) {
                label = "alta";
            } else if (ratio > 0.2 && rel > -20) {
                label = "media";
            } else {
                label = "baja";
            }
            JSONObject info = new JSONObject();
            info.put("presence", Dsp.round(ratio, 3));
            info.put("relativeDb", Dsp.round(rel, 1));
            info.put("level", label);
            info.put("active", runs(active, HOP_S));
            result.put(names.get(s), info);
        }
        return result;
    }
}
