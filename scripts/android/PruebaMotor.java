import com.moimoi.engine.DemucsSeparator;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Prueba del motor de separación de la app en la computadora (fuera de Android):
 *   java PruebaMotor modelo.onnx fuentes entrada.f32 salida_prefijo hilos
 * entrada.f32: float32 little-endian, canal izquierdo completo y después el derecho.
 * Escribe salida_prefijo<n>.f32 con el mismo formato por cada fuente.
 */
public class PruebaMotor {
    public static void main(String[] args) throws Exception {
        String model = args[0];
        String[] sources = args[1].split(",");
        byte[] raw = Files.readAllBytes(Paths.get(args[2]));
        String prefix = args[3];
        int threads = Integer.parseInt(args[4]);
        int segment = args.length > 5 ? Integer.parseInt(args[5]) : 343980;
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 8;
        float[][] audio = new float[2][n];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < n; i++) {
                audio[c][i] = bb.getFloat();
            }
        }
        float[][][] out = new float[sources.length][2][n];
        int[] written = new int[sources.length];
        DemucsSeparator.ModelInfo info = new DemucsSeparator.ModelInfo(sources, 44100, segment);
        long t0 = System.nanoTime();
        try (DemucsSeparator sep = new DemucsSeparator(model, info, threads)) {
            long t1 = System.nanoTime();
            sep.separate(new DemucsSeparator.AudioInput() {
                public int length() {
                    return n;
                }

                public void read(int channel, int start, float[] dst, int count) {
                    for (int i = 0; i < count; i++) {
                        int j = start + i;
                        dst[i] = j >= 0 && j < n ? audio[channel][j] : 0f;
                    }
                }
            }, (source, left, right, count) -> {
                System.arraycopy(left, 0, out[source][0], written[source], count);
                System.arraycopy(right, 0, out[source][1], written[source], count);
                written[source] += count;
            }, new DemucsSeparator.Listener() {
                public void progress(double fraction) {
                    System.out.printf("progreso %.0f%%%n", fraction * 100);
                }

                public boolean cancelled() {
                    return false;
                }
            });
            long t2 = System.nanoTime();
            System.out.printf("carga %.1f s, separación %.1f s (espectrograma %.1f, modelo %.1f, inverso %.1f, resto %.1f)%n",
                    (t1 - t0) / 1e9, (t2 - t1) / 1e9, sep.timings[0], sep.timings[1], sep.timings[2], sep.timings[3]);
        }
        for (int s = 0; s < sources.length; s++) {
            if (written[s] != n) {
                throw new IllegalStateException("fuente " + s + ": " + written[s] + " de " + n + " muestras");
            }
            ByteBuffer ob = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < n; i++) {
                    ob.putFloat(out[s][c][i]);
                }
            }
            try (FileOutputStream f = new FileOutputStream(prefix + s + ".f32")) {
                f.write(ob.array());
            }
        }
    }
}
