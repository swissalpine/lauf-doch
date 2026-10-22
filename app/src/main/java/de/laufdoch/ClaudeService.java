package de.laufdoch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Vordergrunddienst, solange eine Claude-Anfrage läuft.
 * Hält Netz und CPU aktiv, wenn der Bildschirm aus ist oder die App im Hintergrund liegt
 * (sonst kappt Android im Ruhemodus die Verbindung).
 */
public class ClaudeService extends Service {
    static final int NOTIF_ID = 4713;
    private static int running = 0;
    private PowerManager.WakeLock lock;

    static synchronized void begin(Context c) {
        running++;
        try { ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(4714); } catch (Exception ignored) { }
        if (running == 1) {
            try { c.startForegroundService(new Intent(c, ClaudeService.class)); } catch (Exception ignored) { }
        }
    }

    static synchronized boolean idle() { return running == 0; }

    static synchronized void end(Context c) {
        running = Math.max(0, running - 1);
        if (running == 0) {
            try { c.stopService(new Intent(c, ClaudeService.class)); } catch (Exception ignored) { }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel("claude_work") == null) {
                NotificationChannel ch = new NotificationChannel("claude_work", "Claude arbeitet", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Hinweis, solange Claude an einer Antwort arbeitet");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "claude_work")
                    .setSmallIcon(R.drawable.ic_notif)
                    .setColor(0xFF0053FA)
                    .setContentTitle("Claude arbeitet …")
                    .setContentText("Die Antwort kommt auch bei ausgeschaltetem Bildschirm an.")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception ignored) { }
        try {
            if (lock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "laufdoch:claude");
                lock.setReferenceCounted(false);
            }
            lock.acquire(15 * 60 * 1000L);
        } catch (Exception ignored) { }
        synchronized (ClaudeService.class) { if (running == 0) stopSelf(); }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
