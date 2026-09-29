package com.moimoi.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Separa una canción con Demucs v4 (núcleo en ONNX, ver scripts/android/modelo_demucs.py) igual que
 * demucs.apply.apply_model con shifts=0: segmentos del largo de entrenamiento con 25 % de
 * superposición y pesos triangulares, normalización por la media y el desvío de la mezcla.
 *
 * El resultado se entrega por bloques a medida que queda listo (no se guarda la canción entera en
 * memoria): cada fuente recibe sus muestras en orden, de 0 a length.
 */
public final class DemucsSeparator implements AutoCloseable {

    /** Audio de entrada: estéreo, muestras en [-1, 1] a la frecuencia del modelo. */
    public interface AudioInput {
        int length();

        /** Copia las muestras [start, start+count) del canal en dst (ceros fuera del audio). */
        void read(int channel, int start, float[] dst, int count) throws IOException;
    }

    /** Recibe el resultado: muestras [offset, offset+count) de la fuente, ya listas. */
    public interface Sink {
        void write(int source, float[] left, float[] right, int count) throws IOException;
    }

    public interface Listener {
        void progress(double fraction);

        boolean cancelled();
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Cancelado");
        }
    }

    public static final class ModelInfo {
        public final String[] sources;
        public final int samplerate;
        public final int segmentSamples;

        public ModelInfo(String[] sources, int samplerate, int segmentSamples) {
            this.sources = sources;
            this.samplerate = samplerate;
            this.segmentSamples = segmentSamples;
        }
    }

    private static final double OVERLAP = 0.25;

    private final OrtEnvironment env;
    private final OrtSession session;
    /** Tiempos de la última separación, en segundos: espectrograma, modelo, inverso, resto. */
    public final double[] timings = new double[4];
    private final ModelInfo info;
    private final int threads;

    public DemucsSeparator(String modelPath, ModelInfo info, int threads) throws OrtException {
        this(modelPath, null, info, threads);
    }

    /** Con el modelo en memoria (por ejemplo mapeado directo desde el APK, sin copiarlo). */
    public DemucsSeparator(ByteBuffer model, ModelInfo info, int threads) throws OrtException {
        this(null, model, info, threads);
    }

    private DemucsSeparator(String modelPath, ByteBuffer model, ModelInfo info, int threads) throws OrtException {
        this.info = info;
        this.threads = Math.max(1, threads);
        env = OrtEnvironment.getEnvironment();
        try {
            env.setTelemetry(false);
        } catch (OrtException | UnsupportedOperationException ignored) {
            // solo existe en algunas plataformas
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(this.threads);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // Los números "desnormalizados" (menores a 1e-38) hacen muy lenta la CPU y no se escuchan:
        // con audio real el modelo tarda la mitad al tratarlos como cero.
        options.addConfigEntry("session.set_denormal_as_zero", "1");
        // Sin el "arena" de ONNX Runtime la memoria se devuelve después de cada segmento: el pico
        // baja de ~3 GB a ~1 GB (clave en el celular) y hasta es un poco más rápido.
        options.setCPUArenaAllocator(false);
        options.setMemoryPatternOptimization(false);
        session = model != null ? env.createSession(model, options) : env.createSession(modelPath, options);
    }

    public ModelInfo info() {
        return info;
    }

    private static FloatBuffer direct(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public void separate(AudioInput input, Sink sink, Listener listener) throws OrtException, IOException {
        final int length = input.length();
        java.util.Arrays.fill(timings, 0);
        final int segLen = info.segmentSamples;
        final int nSources = info.sources.length;
        final DemucsSpec spec = new DemucsSpec(segLen);
        final int frames = spec.frames();
        final int plane = DemucsSpec.BINS * frames;

        // Normalización de la mezcla (como demucs.separate): media y desvío del promedio de canales.
        double[] stats = meanStd(input, length);
        final float mean = (float) stats[0];
        final float std = (float) stats[1];

        final int stride = (int) ((1 - OVERLAP) * segLen);
        final float[] weight = new float[segLen];
        int half = segLen / 2;
        for (int i = 0; i < segLen; i++) {
            weight[i] = (i < half ? i + 1 : segLen - i) / (float) half;
        }
        List<Integer> offsets = new ArrayList<>();
        for (int o = 0; o < length; o += stride) {
            offsets.add(o);
        }

        FloatBuffer inMix = direct(2 * segLen);
        FloatBuffer inSpec = direct(4 * plane);
        FloatBuffer outSpec = direct(nSources * 4 * plane);
        FloatBuffer outWave = direct(nSources * 2 * segLen);
        long[] mixShape = {1, 2, segLen};
        long[] specShape = {1, 4, DemucsSpec.BINS, frames};
        long[] outSpecShape = {1, nSources, 4, DemucsSpec.BINS, frames};
        long[] outWaveShape = {1, nSources, 2, segLen};

        float[][] seg = new float[2][segLen];
        float[][][] acc = new float[nSources][2][segLen]; // suma ponderada desde el segmento actual
        float[] sumWeight = new float[segLen];
        final float[][][] segOut = new float[nSources][2][segLen];
        float[] outLeft = new float[segLen];
        float[] outRight = new float[segLen];

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // (ThreadLocal.withInitial no existe en Android 7)
        final ThreadLocal<Workspace> workspace = new ThreadLocal<Workspace>() {
            @Override
            protected Workspace initialValue() {
                return new Workspace(plane);
            }
        };
        try (OnnxTensor tMix = OnnxTensor.createTensor(env, inMix, mixShape);
             OnnxTensor tSpec = OnnxTensor.createTensor(env, inSpec, specShape);
             OnnxTensor tOutSpec = OnnxTensor.createTensor(env, outSpec, outSpecShape);
             OnnxTensor tOutWave = OnnxTensor.createTensor(env, outWave, outWaveShape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("mix", tMix);
            inputs.put("spec", tSpec);
            Map<String, OnnxTensor> outputs = new HashMap<>();
            outputs.put("spec_out", tOutSpec);
            outputs.put("wave_out", tOutWave);
            List<Future<?>> jobs = new ArrayList<>();

            for (int k = 0; k < offsets.size(); k++) {
                if (listener != null && listener.cancelled()) {
                    throw new CancelledException();
                }
                final int offset = offsets.get(k);
                final int chunk = Math.min(segLen, length - offset);
                final int delta = segLen - chunk;
                final int trim = delta / 2;

                // Segmento con contexto (TensorChunk.padded): se centra y se rellena con ceros.
                int start = offset - trim;
                for (int c = 0; c < 2; c++) {
                    input.read(c, start, seg[c], segLen);
                    float[] x = seg[c];
                    for (int i = 0; i < segLen; i++) {
                        int global = start + i;
                        x[i] = global < 0 || global >= length ? 0f : (x[i] - mean) / std;
                    }
                    inMix.position(c * segLen);
                    inMix.put(x, 0, segLen);
                }
                inMix.rewind();

                long tA = System.nanoTime();
                // Espectrograma de los dos canales, en paralelo.
                for (int c = 0; c < 2; c++) {
                    final int ch = c;
                    jobs.add(pool.submit(() -> {
                        Workspace w = workspace.get();
                        spec.forward(w.fft, seg[ch], w.realT, w.imagT, w.frame, w.re, w.im);
                        FloatBuffer dst = inSpec.duplicate();
                        spec.toModel(w.realT, dst, 2 * ch * plane);
                        spec.toModel(w.imagT, dst, (2 * ch + 1) * plane);
                    }));
                }
                waitAll(jobs);
                long tB = System.nanoTime();
                session.run(inputs, outputs).close();
                long tC = System.nanoTime();

                // Espectrograma inverso de cada fuente y canal + la rama de tiempo.
                for (int s = 0; s < nSources; s++) {
                    for (int c = 0; c < 2; c++) {
                        final int src = s;
                        final int ch = c;
                        jobs.add(pool.submit(() -> {
                            Workspace w = workspace.get();
                            FloatBuffer view = outSpec.duplicate();
                            spec.fromModel(view, (src * 4 + 2 * ch) * plane, w.realT);
                            spec.fromModel(view, (src * 4 + 2 * ch + 1) * plane, w.imagT);
                            float[] y = segOut[src][ch];
                            spec.inverse(w.fft, w.realT, w.imagT, y, w.re, w.im, w.frame);
                            FloatBuffer wave = outWave.duplicate();
                            wave.position((src * 2 + ch) * segLen);
                            for (int i = 0; i < segLen; i++) {
                                y[i] += wave.get();
                            }
                        }));
                    }
                }
                waitAll(jobs);
                long tD = System.nanoTime();

                for (int s = 0; s < nSources; s++) {
                    for (int c = 0; c < 2; c++) {
                        float[] a = acc[s][c];
                        float[] y = segOut[s][c];
                        for (int i = 0; i < chunk; i++) {
                            a[i] += weight[i] * y[trim + i];
                        }
                    }
                }
                for (int i = 0; i < chunk; i++) {
                    sumWeight[i] += weight[i];
                }

                // Lo que va hasta el próximo segmento ya no recibe más aportes: se entrega.
                int next = k + 1 < offsets.size() ? offsets.get(k + 1) : length;
                int ready = next - offset;
                for (int s = 0; s < nSources; s++) {
                    float[] l = acc[s][0];
                    float[] r = acc[s][1];
                    for (int i = 0; i < ready; i++) {
                        float w = sumWeight[i];
                        outLeft[i] = l[i] / w * std + mean;
                        outRight[i] = r[i] / w * std + mean;
                    }
                    sink.write(s, outLeft, outRight, ready);
                    shift(l, ready);
                    shift(r, ready);
                }
                shift(sumWeight, ready);
                long tE = System.nanoTime();
                timings[0] += (tB - tA) / 1e9;
                timings[1] += (tC - tB) / 1e9;
                timings[2] += (tD - tC) / 1e9;
                timings[3] += (tE - tD) / 1e9;
                if (listener != null) {
                    listener.progress((k + 1) / (double) offsets.size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Memoria de trabajo de cada hilo (FFT, espectrogramas por cuadro y arreglos auxiliares). */
    private static final class Workspace {
        final Fft fft = new Fft(DemucsSpec.NFFT);
        final float[] realT;
        final float[] imagT;
        final float[] frame = new float[DemucsSpec.NFFT];
        final float[] re = new float[DemucsSpec.BINS + 1];
        final float[] im = new float[DemucsSpec.BINS + 1];

        Workspace(int plane) {
            realT = new float[plane];
            imagT = new float[plane];
        }
    }

    private static void shift(float[] a, int n) {
        System.arraycopy(a, n, a, 0, a.length - n);
        java.util.Arrays.fill(a, a.length - n, a.length, 0f);
    }

    private static void waitAll(List<Future<?>> jobs) throws IOException {
        try {
            for (Future<?> job : jobs) {
                job.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException(cause);
        } finally {
            jobs.clear();
        }
    }

    /** Media y desvío (con n-1, como torch.std) del promedio de los dos canales. */
    static double[] meanStd(AudioInput input, int length) throws IOException {
        int block = 1 << 16;
        float[] l = new float[block];
        float[] r = new float[block];
        double sum = 0;
        for (int start = 0; start < length; start += block) {
            int n = Math.min(block, length - start);
            input.read(0, start, l, n);
            input.read(1, start, r, n);
            for (int i = 0; i < n; i++) {
                sum += 0.5 * (l[i] + r[i]);
            }
        }
        double mean = sum / Math.max(1, length);
        double sq = 0;
        for (int start = 0; start < length; start += block) {
            int n = Math.min(block, length - start);
            input.read(0, start, l, n);
            input.read(1, start, r, n);
            for (int i = 0; i < n; i++) {
                double d = 0.5 * (l[i] + r[i]) - mean;
                sq += d * d;
            }
        }
        double std = Math.sqrt(sq / Math.max(1, length - 1)) + 1e-8;
        return new double[] {mean, std};
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
