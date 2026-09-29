package com.moimoi.analysis;

/** Reparte un bucle entre los núcleos del celular (cada parte da el mismo resultado que sola). */
final class Parallel {

    private Parallel() {}

    interface Body {
        void run(int from, int to);
    }

    static int threads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    /** body(from, to) sobre [0, n) en partes; con poco trabajo, todo en este hilo. */
    static void range(int n, int minPerThread, final Body body) {
        int k = Math.min(threads(), Math.max(1, n / Math.max(1, minPerThread)));
        if (k <= 1) {
            body.run(0, n);
            return;
        }
        final Throwable[] error = new Throwable[1];
        Thread[] workers = new Thread[k - 1];
        for (int w = 1; w < k; w++) {
            final int from = (int) ((long) n * w / k);
            final int to = (int) ((long) n * (w + 1) / k);
            workers[w - 1] = new Thread(() -> {
                try {
                    body.run(from, to);
                } catch (Throwable t) {
                    synchronized (error) {
                        error[0] = t;
                    }
                }
            }, "moimoi-analisis");
            workers[w - 1].start();
        }
        try {
            body.run(0, (int) ((long) n / k));
        } finally {
            boolean interrupted = false;
            for (Thread t : workers) {
                while (true) {
                    try {
                        t.join();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (error) {
            if (error[0] instanceof RuntimeException) {
                throw (RuntimeException) error[0];
            }
            if (error[0] instanceof Error) {
                throw (Error) error[0];
            }
            if (error[0] != null) {
                throw new RuntimeException(error[0]);
            }
        }
    }
}
