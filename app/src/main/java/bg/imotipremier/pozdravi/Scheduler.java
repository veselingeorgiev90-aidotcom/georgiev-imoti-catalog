package bg.imotipremier.pozdravi;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class Scheduler {
    static final String CHANNEL = "pozdravi";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("p", Context.MODE_PRIVATE);
    }

    static long nextTime(Context c) {
        SharedPreferences p = prefs(c);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, p.getInt("h", 9));
        cal.set(Calendar.MINUTE, p.getInt("m", 0));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DATE, 1);
        return cal.getTimeInMillis();
    }

    static void scheduleNext(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime(c), pi);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static void fireIfDue(Context c) {
        SharedPreferences p = prefs(c);
        String t = today();
        if (t.equals(p.getString("last", ""))) return;
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (nowMin < p.getInt("h", 9) * 60 + p.getInt("m", 0)) return;
        p.edit().putString("last", t).apply();
        notifyDay(c, t);
    }

    static void notifyDay(Context c, String day) {
        String s = Bridge.readFile(c, "schedule.json");
        if (s.isEmpty()) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Reminder — напомняния",
                NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            JSONArray a = new JSONArray(s);
            int id = 100;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!day.equals(o.optString("d"))) continue;
                Notification n = new Notification.Builder(c, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notify)
                        .setColor(0xFF13294B)
                        .setContentTitle(o.optString("t"))
                        .setContentText(o.optString("b"))
                        .setStyle(new Notification.BigTextStyle().bigText(o.optString("b")))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(id++, n);
            }
        } catch (Exception ignored) {
        }
    }
}
