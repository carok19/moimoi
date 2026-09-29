package com.moimoi.analysis;

/**
 * Filtros de Butterworth en secciones de 2º orden y filtrado de ida y vuelta (sin desfase), como
 * scipy.signal.butter(..., output='sos') + sosfiltfilt (relleno "odd" y condiciones iniciales de
 * estado estacionario).
 */
public final class Butter {

    private Butter() {}

    /** Coeficientes [sección][b0, b1, b2, a0=1, a1, a2]. highpass = false: pasabajos. */
    public static double[][] design(int order, double cutoffHz, double fs, boolean highpass) {
        double wn = 2 * cutoffHz / fs;
        double warped = 4 * Math.tan(Math.PI * wn / 2); // fs = 2 interno de scipy
        // Polos del prototipo analógico: exp(i π (2k + N + 1) / (2N)).
        double[] pr = new double[order];
        double[] pi = new double[order];
        for (int k = 0; k < order; k++) {
            double theta = Math.PI * (2 * k + order + 1) / (2.0 * order);
            pr[k] = Math.cos(theta);
            pi[k] = Math.sin(theta);
        }
        double gain = 1.0;
        // Transformación a pasabajos/pasaaltos con frecuencia "warped".
        for (int k = 0; k < order; k++) {
            if (highpass) {
                // p' = wo / p
                double d = pr[k] * pr[k] + pi[k] * pi[k];
                double r = warped * pr[k] / d;
                double i = -warped * pi[k] / d;
                pr[k] = r;
                pi[k] = i;
            } else {
                pr[k] *= warped;
                pi[k] *= warped;
            }
        }
        // Pasaaltos: k' = k * prod(-z) / prod(-p) del prototipo, que vale 1 en Butterworth.
        // Pasabajos: k' = k * wo^N.
        gain = highpass ? 1.0 : Math.pow(warped, order);
        // Bilineal (fs2 = 4): p_d = (4 + p) / (4 - p); ceros: -1 (pasabajos) o +1 (pasaaltos).
        double[] dr = new double[order];
        double[] di = new double[order];
        double[] num = {1, 0};
        double[] den = {1, 0};
        for (int k = 0; k < order; k++) {
            double[] a = {4 + pr[k], pi[k]};
            double[] b = {4 - pr[k], -pi[k]};
            double[] q = div(a, b);
            dr[k] = q[0];
            di[k] = q[1];
            den = mul(den, b);
        }
        if (highpass) {
            // ceros analógicos en 0: prod(fs2 - z) = 4^N
            num = new double[] {Math.pow(4, order), 0};
        }
        double kd = gain * div(num, den)[0];
        // Agrupar polos complejos conjugados de a pares (orden par).
        int sections = order / 2;
        double[][] sos = new double[sections][6];
        java.util.List<double[]> upper = new java.util.ArrayList<>();
        for (int k = 0; k < order; k++) {
            if (di[k] > 0) {
                upper.add(new double[] {dr[k], di[k]});
            }
        }
        // zpk2sos (pairing "nearest"): los polos más cerca del círculo unidad van en la última sección.
        upper.sort((x, y) -> Double.compare(Math.hypot(x[0], x[1]), Math.hypot(y[0], y[1])));
        for (int s = 0; s < sections; s++) {
            double[] p = upper.get(s);
            double zero = highpass ? 1.0 : -1.0;
            double b0 = 1, b1 = -2 * zero, b2 = zero * zero;
            double a1 = -2 * p[0];
            double a2 = p[0] * p[0] + p[1] * p[1];
            double g = s == 0 ? kd : 1.0;
            sos[s] = new double[] {g * b0, g * b1, g * b2, 1, a1, a2};
        }
        return sos;
    }

    private static double[] mul(double[] a, double[] b) {
        return new double[] {a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]};
    }

    private static double[] div(double[] a, double[] b) {
        double d = b[0] * b[0] + b[1] * b[1];
        return new double[] {(a[0] * b[0] + a[1] * b[1]) / d, (a[1] * b[0] - a[0] * b[1]) / d};
    }

    /** Condiciones iniciales de estado estacionario de cada sección (sosfilt_zi). */
    static double[][] zi(double[][] sos) {
        double[][] out = new double[sos.length][2];
        double scale = 1.0;
        for (int s = 0; s < sos.length; s++) {
            double b0 = sos[s][0], b1 = sos[s][1], b2 = sos[s][2];
            double a1 = sos[s][4], a2 = sos[s][5];
            // (I - A^T) zi = B, con A la matriz compañera de a
            double m00 = 1 + a1, m01 = -1, m10 = a2, m11 = 1;
            double r0 = b1 - a1 * b0, r1 = b2 - a2 * b0;
            double det = m00 * m11 - m01 * m10;
            double z0 = (r0 * m11 - m01 * r1) / det;
            double z1 = (m00 * r1 - m10 * r0) / det;
            out[s][0] = scale * z0;
            out[s][1] = scale * z1;
            scale *= (b0 + b1 + b2) / (1 + a1 + a2);
        }
        return out;
    }

    private static void sosfilt(double[][] sos, float[] x, double[][] z) {
        for (int n = 0; n < x.length; n++) {
            double v = x[n];
            for (int s = 0; s < sos.length; s++) {
                double[] c = sos[s];
                double yv = c[0] * v + z[s][0];
                z[s][0] = c[1] * v - c[4] * yv + z[s][1];
                z[s][1] = c[2] * v - c[5] * yv;
                v = yv;
            }
            x[n] = (float) v;
        }
    }

    /** scipy.signal.sosfiltfilt(sos, x) con padtype = "odd" y padlen por defecto. */
    public static float[] filtfilt(double[][] sos, float[] x) {
        return filtfilt(sos, x, false);
    }

    /**
     * Igual, y con inPlace = true el resultado queda en x (para no tener otra copia de la señal).
     * El paso intermedio se guarda en float (diferencias de 1e-7, la mitad de memoria).
     */
    public static float[] filtfilt(double[][] sos, float[] x, boolean inPlace) {
        int ntaps = 2 * sos.length + 1;
        int zerosB2 = 0, zerosA2 = 0;
        for (double[] s : sos) {
            if (s[2] == 0) {
                zerosB2++;
            }
            if (s[5] == 0) {
                zerosA2++;
            }
        }
        ntaps -= Math.min(zerosB2, zerosA2);
        int edge = 3 * ntaps;
        int n = x.length;
        if (n <= edge) {
            return inPlace ? x : x.clone();
        }
        float[] ext = new float[n + 2 * edge];
        for (int i = 0; i < edge; i++) {
            ext[i] = 2 * x[0] - x[edge - i];
            ext[n + edge + i] = 2 * x[n - 1] - x[n - 2 - i];
        }
        System.arraycopy(x, 0, ext, edge, n);
        double[][] zi = zi(sos);
        double[][] z = new double[sos.length][2];
        double x0 = ext[0];
        for (int s = 0; s < sos.length; s++) {
            z[s][0] = zi[s][0] * x0;
            z[s][1] = zi[s][1] * x0;
        }
        sosfilt(sos, ext, z);
        // hacia atrás
        for (int i = 0, j = ext.length - 1; i < j; i++, j--) {
            float t = ext[i];
            ext[i] = ext[j];
            ext[j] = t;
        }
        double y0 = ext[0];
        for (int s = 0; s < sos.length; s++) {
            z[s][0] = zi[s][0] * y0;
            z[s][1] = zi[s][1] * y0;
        }
        sosfilt(sos, ext, z);
        float[] out = inPlace ? x : new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = ext[ext.length - 1 - edge - i];
        }
        return out;
    }
}
