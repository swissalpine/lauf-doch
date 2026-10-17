package de.laufdoch;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Erinnerungen morgens (Einheit des Tages, Morgen-Check) und abends (Rückmeldung).
 * Die Texte je Tag berechnet die Web-App beim Öffnen und legt sie mit setReminders() ab;
 * dieser Empfänger zeigt zur eingestellten Zeit den Eintrag für heute an, falls es einen gibt.
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String PREFS = "laufdoch";
    static final String CHANNEL = "reminders";
    static final String[] KINDS = {"morning", "evening"};

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            schedule(c);
            return;
        }
        String kind = intent.getStringExtra("kind");
        if (kind != null) show(c, kind);
        schedule(c);
    }

    private static JSONObject cfg(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("remind_cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void show(Context c, String kind) {
        JSONObject cfg = cfg(c);
        if (!cfg.optBoolean(kind + "On", false)) return;
        JSONArray items = cfg.optJSONArray(kind);
        if (items == null) return;
        String today = LocalDate.now().toString();
        JSONObject item = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && today.equals(o.optString("date"))) { item = o; break; }
        }
        if (item == null) return;

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Einheit des Tages, Morgen-Check und Rückmeldung");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("open_tab", "heute");
        PendingIntent pi = PendingIntent.getActivity(c, kind.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = item.optString("text", "");
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF0053FA)
                .setContentTitle(item.optString("title", "Lauf doch!"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify("morning".equals(kind) ? 1 : 2, n);
        } catch (SecurityException ignored) {
            // Benachrichtigungen nicht erlaubt
        }
    }

    /** Nächste Weckzeiten setzen (oder löschen, wenn ausgeschaltet). */
    static void schedule(Context c) {
        JSONObject cfg = cfg(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (String kind : KINDS) {
            Intent i = new Intent(c, ReminderReceiver.class);
            i.setAction("de.laufdoch.REMIND_" + kind);
            i.putExtra("kind", kind);
            PendingIntent pi = PendingIntent.getBroadcast(c, kind.hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            if (!cfg.optBoolean(kind + "On", false)) continue;
            LocalTime t;
            try {
                t = LocalTime.parse(cfg.optString(kind + "Time", "morning".equals(kind) ? "07:00" : "20:00"));
            } catch (Exception e) {
                t = "morning".equals(kind) ? LocalTime.of(7, 0) : LocalTime.of(20, 0);
            }
            LocalDateTime next = LocalDate.now().atTime(t);
            if (!next.isAfter(LocalDateTime.now().plusSeconds(30))) next = next.plusDays(1);
            long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
