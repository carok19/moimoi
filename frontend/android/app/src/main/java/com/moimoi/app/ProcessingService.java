package com.moimoi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Servicio "en primer plano" mientras se separan canciones: Android no cierra la app aunque la
 * minimices o apagues la pantalla, y la notificación muestra el avance.
 */
public class ProcessingService extends Service {

    private static final String CHANNEL = "moimoi-separacion";
    private static final int NOTIFICATION = 4747;
    private static volatile boolean running;
    private static long lastUpdate;
    private PowerManager.WakeLock wakeLock;

    static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ProcessingService.class));
        } catch (RuntimeException e) {
            // Android no deja iniciarlo desde segundo plano: la separación sigue igual mientras la app viva.
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ProcessingService.class));
    }

    static void update(Context context, String title, double fraction, String message) {
        long now = System.currentTimeMillis();
        if (!running || now - lastUpdate < 1000) {
            return;
        }
        lastUpdate = now;
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION, build(context, title, fraction, message));
        } catch (SecurityException ignored) {
            // sin permiso para notificaciones: no se muestra el avance
        }
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Separación de pistas",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Avance mientras MoiMoi separa canciones");
            channel.setShowBadge(false);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private static Notification build(Context context, String title, double fraction, String message) {
        Intent open = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent content = PendingIntent.getActivity(context, 0, open, flags);
        int percent = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 100);
        return new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_moimoi)
                .setContentTitle(title == null ? "MoiMoi" : title)
                .setContentText(message == null ? "Separando pistas…" : message + " · " + percent + " %")
                .setProgress(100, percent, fraction <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
        int type = 0;
        if (Build.VERSION.SDK_INT >= 35) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, build(this, "MoiMoi", 0, "Preparando…"), type);
        } catch (RuntimeException e) {
            stopSelf();
            return;
        }
        running = true;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoiMoi:separacion");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15: tiempo máximo de un servicio de procesamiento (6 horas por día).
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
