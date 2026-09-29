package com.moimoi.local;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cola de trabajos en segundo plano (como worker.py): dos hilos, uno "pesado" (separar y analizar,
 * de a uno) y uno "liviano" (exportaciones). Los trabajos viven en memoria; si Android cierra la
 * app a mitad de una separación, al volver a abrirla la canción se vuelve a poner en cola.
 */
public final class Jobs {

    public static final List<String> HEAVY = Arrays.asList("process", "reanalyze", "lyrics");
    public static final List<String> LIGHT = Arrays.asList("export");
    private static final int KEEP_FINISHED = 200;

    /** Se pidió cancelar: el trabajo se detiene en el próximo aviso de avance. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("Cancelado");
        }
    }

    public interface Handler {
        /** Hace el trabajo y devuelve su resultado ("result" del trabajo). */
        JSONObject run(JSONObject job, Reporter report) throws Exception;

        /** Terminó con error (message != null) o cancelado: se actualiza la canción. */
        void failed(JSONObject job, String message, boolean cancelled);

        /** Terminó (bien o mal). */
        void finished(JSONObject job);
    }

    public interface Reporter {
        /** Avance 0-1 con un mensaje; lanza Cancelled si se pidió cancelar. */
        void report(double fraction, String message);

        boolean cancelled();
    }

    private final Map<String, JSONObject> jobs = new LinkedHashMap<>();
    private final Set<String> cancelled = new HashSet<>();
    private final Handler handler;
    private final Platform platform;
    private final Object heavyLock = new Object();
    private final Object lightLock = new Object();
    private boolean stopped;
    private final List<Thread> threads = new ArrayList<>();

    public Jobs(Handler handler, Platform platform) {
        this.handler = handler;
        this.platform = platform;
    }

    public void start() {
        threads.add(startLane("moimoi-pesado", HEAVY, heavyLock, true));
        threads.add(startLane("moimoi-liviano", LIGHT, lightLock, false));
    }

    public void stop() {
        synchronized (this) {
            stopped = true;
        }
        synchronized (heavyLock) {
            heavyLock.notifyAll();
        }
        synchronized (lightLock) {
            lightLock.notifyAll();
        }
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            try {
                t.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Thread startLane(String name, List<String> kinds, Object lock, boolean heavy) {
        Thread thread = new Thread(() -> loop(kinds, lock, heavy), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void loop(List<String> kinds, Object lock, boolean heavy) {
        boolean working = false;
        while (true) {
            JSONObject job;
            synchronized (this) {
                if (stopped) {
                    return;
                }
                job = claimNext(kinds);
            }
            if (job == null) {
                if (heavy && working) {
                    working = false;
                    platform.working(false);
                }
                synchronized (lock) {
                    try {
                        lock.wait(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                continue;
            }
            if (heavy && !working) {
                working = true;
                platform.working(true);
            }
            run(job);
        }
    }

    // ---- registro --------------------------------------------------------------------

    public synchronized JSONObject enqueue(String kind, String songId, JSONObject params, String message) {
        JSONObject job = new JSONObject();
        try {
            job.put("id", Json.newId());
            job.put("song_id", songId == null ? JSONObject.NULL : songId);
            job.put("kind", kind);
            job.put("params", params == null ? new JSONObject() : params);
            job.put("status", "queued");
            job.put("progress", 0.0);
            job.put("message", message);
            job.put("error", JSONObject.NULL);
            job.put("result", JSONObject.NULL);
            job.put("created_at", Json.nowIso());
            job.put("started_at", JSONObject.NULL);
            job.put("finished_at", JSONObject.NULL);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        jobs.put(job.optString("id"), job);
        prune();
        Object lock = LIGHT.contains(kind) ? lightLock : heavyLock;
        synchronized (lock) {
            lock.notifyAll();
        }
        return Json.copy(job);
    }

    private void prune() {
        if (jobs.size() <= KEEP_FINISHED) {
            return;
        }
        List<String> old = new ArrayList<>();
        for (JSONObject job : jobs.values()) {
            String status = job.optString("status");
            if (!status.equals("queued") && !status.equals("running")) {
                old.add(job.optString("id"));
            }
            if (jobs.size() - old.size() <= KEEP_FINISHED) {
                break;
            }
        }
        for (String id : old) {
            jobs.remove(id);
        }
    }

    private JSONObject claimNext(List<String> kinds) {
        for (JSONObject job : jobs.values()) {
            if (job.optString("status").equals("queued") && kinds.contains(job.optString("kind"))) {
                update(job.optString("id"), "status", "running", "started_at", Json.nowIso());
                return Json.copy(job);
            }
        }
        return null;
    }

    public synchronized JSONObject get(String id) {
        return Json.copy(jobs.get(id));
    }

    public synchronized List<JSONObject> list(String songId, boolean activeOnly) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject job : jobs.values()) {
            if (songId != null && !songId.equals(Json.optString(job, "song_id"))) {
                continue;
            }
            String status = job.optString("status");
            if (activeOnly && !status.equals("queued") && !status.equals("running")) {
                continue;
            }
            out.add(Json.copy(job));
        }
        return out;
    }

    public synchronized JSONObject activeFor(String songId, String kind) {
        for (JSONObject job : list(songId, true)) {
            if (job.optString("kind").equals(kind)) {
                return job;
            }
        }
        return null;
    }

    public synchronized void update(String id, Object... keyValues) {
        JSONObject job = jobs.get(id);
        if (job == null) {
            return;
        }
        try {
            for (int i = 0; i < keyValues.length; i += 2) {
                Object value = keyValues[i + 1];
                job.put((String) keyValues[i], value == null ? JSONObject.NULL : value);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public synchronized void cancel(String id) {
        cancelled.add(id);
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    public synchronized boolean isCancelling(String id) {
        return cancelled.contains(id);
    }

    // ---- ejecución -------------------------------------------------------------------

    private void run(JSONObject job) {
        final String id = job.optString("id");
        final long[] lastWrite = {0};
        Reporter reporter = new Reporter() {
            @Override
            public void report(double fraction, String message) {
                if (isCancelling(id)) {
                    throw new Cancelled();
                }
                long now = System.currentTimeMillis();
                // No más de ~4 avisos por segundo.
                if (now - lastWrite[0] < 250 && fraction < 1.0) {
                    return;
                }
                lastWrite[0] = now;
                update(id, "progress", Json.round(fraction, 4), "message", message);
            }

            @Override
            public boolean cancelled() {
                return isCancelling(id);
            }
        };
        try {
            if (isCancelling(id)) {
                throw new Cancelled();
            }
            JSONObject result = handler.run(job, reporter);
            update(id, "status", "done", "progress", 1.0, "message", "Listo", "finished_at", Json.nowIso(),
                    "result", result == null ? JSONObject.NULL : result);
        } catch (Throwable e) {
            if (isStopped()) {
                // Se cerró la app (o se detuvo la cola) a mitad del trabajo: la canción queda como
                // estaba y se retoma la próxima vez, igual que si Android hubiera cerrado la app.
                update(id, "status", "queued", "started_at", null, "progress", 0.0);
                return;
            }
            if (e instanceof Cancelled || e instanceof com.moimoi.engine.DemucsSeparator.CancelledException) {
                update(id, "status", "cancelled", "message", "Cancelado", "finished_at", Json.nowIso());
                handler.failed(job, null, true);
                return;
            }
            String message = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage()
                    : e.getClass().getSimpleName();
            if (e instanceof OutOfMemoryError) {
                message = "El celular se quedó sin memoria. Cierra otras apps y vuelve a intentarlo.";
            }
            System.err.println("Falló el trabajo " + id + ": " + message);
            if (!(e instanceof Exporter.ExportError) && !(e instanceof ApiException)) {
                e.printStackTrace();
            }
            update(id, "status", "error", "error", message, "message", "Error", "finished_at", Json.nowIso());
            handler.failed(job, message, false);
        } finally {
            synchronized (this) {
                cancelled.remove(id);
            }
            handler.finished(job);
        }
    }
}
