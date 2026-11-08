package de.laufdoch;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * Krafttraining im Hintergrund: laufende Benachrichtigung mit Countdown und
 * ein Alarm mit Ton und Vibration, wenn Haltezeit oder Erholung abgelaufen ist.
 */
public class WorkoutAlarm extends BroadcastReceiver {
    static final int ID_ONGOING = 4720, ID_ALERT = 4721;
    static volatile boolean appVisible = true;
    private static long prevEnd = 0; private static String prevTitle = "", prevText = "";

    private static void channels(NotificationManager nm) {
        if (nm.getNotificationChannel("workout") == null) {
            NotificationChannel ch = new NotificationChannel("workout", "Training läuft", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Laufendes Krafttraining mit Countdown");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("workout_alert") == null) {
            NotificationChannel ch = new NotificationChannel("workout_alert", "Training: Zeit abgelaufen", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Ton und Vibration, wenn Haltezeit oder Erholung vorbei ist");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 300, 150, 300});
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent openApp(Context c, int req) {
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent alarmIntent(Context c, String title, String text) {
        Intent i = new Intent(c, WorkoutAlarm.class).putExtra("title", title).putExtra("text", text);
        return PendingIntent.getBroadcast(c, ID_ALERT, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** endAt > 0: Countdown läuft bis endAt (ms), dann Alarm mit doneTitle/doneText. endAt == 0: nur Hinweis „Training läuft“. */
    static void update(Context c, String title, String text, long endAt, String doneTitle, String doneText) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            channels(nm);
            // Ist der vorige Countdown gerade abgelaufen und die App im Hintergrund, Alarm sofort auslösen
            // (die App plant beim Ablauf schon den nächsten Abschnitt, der alte Alarm würde dabei gelöscht)
            long now = System.currentTimeMillis();
            if (!appVisible && prevEnd > 0 && now >= prevEnd - 1500 && now - prevEnd < 60000) alert(c, prevTitle, prevText);
            prevEnd = endAt > now ? endAt : 0; prevTitle = doneTitle; prevText = doneText;
            Notification.Builder b = new Notification.Builder(c, "workout")
                    .setSmallIcon(R.drawable.ic_notif).setColor(0xFF0053FA)
                    .setContentTitle(title).setContentText(text)
                    .setContentIntent(openApp(c, ID_ONGOING)).setOngoing(true).setOnlyAlertOnce(true);
            if (endAt > System.currentTimeMillis()) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(endAt).setShowWhen(true);
            nm.notify(ID_ONGOING, b.build());
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = alarmIntent(c, doneTitle, doneText);
            am.cancel(pi);
            if (endAt > System.currentTimeMillis()) {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            }
        } catch (Exception ignored) { }
    }

    static void clear(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            long now = System.currentTimeMillis();
            if (!appVisible && prevEnd > 0 && now >= prevEnd - 1500 && now - prevEnd < 60000) alert(c, prevTitle, prevText);
            prevEnd = 0;
            nm.cancel(ID_ONGOING);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(alarmIntent(c, "", ""));
        } catch (Exception ignored) { }
    }

    static void alert(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            channels(nm);
            Notification n = new Notification.Builder(c, "workout_alert")
                    .setSmallIcon(R.drawable.ic_notif).setColor(0xFF0053FA)
                    .setContentTitle(title).setContentText(text)
                    .setContentIntent(openApp(c, ID_ALERT)).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM).build();
            nm.notify(ID_ALERT, n);
        } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (appVisible) return; // im Vordergrund piept die App selbst
        prevEnd = 0; // schon gemeldet
        alert(c, intent.getStringExtra("title"), intent.getStringExtra("text"));
    }
}
