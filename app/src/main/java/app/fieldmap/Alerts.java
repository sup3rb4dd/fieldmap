package app.fieldmap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Phone notifications: messages, new waypoints, overdue teammates, and the background status line. */
public final class Alerts {
    private Alerts() {}

    public static final String CH_STATUS = "status", CH_ALERTS = "alerts", CH_SOS = "sos";
    public static final int ID_SOS = 12, ID_WX = 13, ID_COMMS = 14;
    private static int nextId = 100;
    /** True while the chat screen is open (no message popups then). */
    public static boolean chatVisible = false;
    private static final Set<String> overdueAlerted = new HashSet<>();

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Background status", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("Shown while FieldMap keeps radios connected in the background");
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Messages, shared waypoints and overdue teammates");
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[]{0, 250, 150, 250});
        // SOS: plays the alarm sound on the alarm volume, so it is heard with the ringer silenced
        NotificationChannel sos = new NotificationChannel(CH_SOS, "SOS alarm", NotificationManager.IMPORTANCE_HIGH);
        sos.setDescription("A teammate sent an SOS");
        sos.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        sos.enableVibration(true);
        sos.setVibrationPattern(new long[]{0, 700, 250, 700, 250, 700});
        nm.createNotificationChannel(sos);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(alerts);
    }

    private static boolean allowed(Context ctx) {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private static void post(Context ctx, String title, String text, Class<?> open, int id) {
        if (!allowed(ctx)) return;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, new Intent(ctx, open).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationManagerCompat.from(ctx).notify(id, new NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pi)
                .build());
    }

    /** Loud, repeating alarm until the notification is opened or swiped away. */
    @SuppressLint("MissingPermission")
    public static void sos(Context ctx, String from, String text) {
        FieldMapApp app = FieldMapApp.get();
        if (!allowed(ctx)) return;
        boolean loud = app.prefs.getBoolean("alertSos", true);
        PendingIntent pi = PendingIntent.getActivity(ctx, ID_SOS,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new NotificationCompat.Builder(ctx, loud ? CH_SOS : CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("SOS · " + from.toUpperCase(Locale.US))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setColor(0xFFFF3B30)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pi)
                .build();
        // keep sounding until someone looks, unless the map (with its flashing banner) is already open
        if (loud && !app.mapVisible) n.flags |= android.app.Notification.FLAG_INSISTENT;
        NotificationManagerCompat.from(ctx).notify(ID_SOS, n);
    }

    public static void sosCleared(Context ctx, String from) {
        NotificationManagerCompat.from(ctx).cancel(ID_SOS);
        post(ctx, "SOS CANCELLED · " + from.toUpperCase(Locale.US), from + " reports OK", MainActivity.class, ID_SOS);
    }

    public static void weather(Context ctx, Weather.Alert a) {
        if (!FieldMapApp.get().prefs.getBoolean("weather", true)) return;
        String until = a.expires == 0 ? "" : " until "
                + new java.text.SimpleDateFormat("h:mm a", Locale.US).format(new java.util.Date(a.expires));
        post(ctx, a.event.toUpperCase(Locale.US) + until, a.headline.isEmpty() ? a.area : a.headline, MainActivity.class, ID_WX);
    }

    public static void commsLost(Context ctx, String which) {
        post(ctx, "COMMS LOST · " + which, "Could not reach the radio. Open FieldMap to reconnect.", MainActivity.class, ID_COMMS);
    }

    public static void message(Context ctx, String from, String text, boolean direct) {
        FieldMapApp app = FieldMapApp.get();
        if (!app.prefs.getBoolean("alertMsg", true) || chatVisible) return;
        post(ctx, (direct ? "MSG ▸ YOU · " : "MSG ▸ NET · ") + from.toUpperCase(Locale.US), text, ChatActivity.class, 10);
    }

    public static void waypoint(Context ctx, String from, String name) {
        FieldMapApp app = FieldMapApp.get();
        if (!app.prefs.getBoolean("alertWpt", true)) return;
        post(ctx, "NEW WAYPOINT · " + name.toUpperCase(Locale.US), "Shared by " + from, MainActivity.class, 11);
    }

    /** Checks every station heard this session; alerts once when one goes quiet too long. */
    public static void checkOverdue(Context ctx, long sessionStart) {
        FieldMapApp app = FieldMapApp.get();
        int minutes = app.prefs.getInt("overdueMin", 15);
        if (!app.prefs.getBoolean("alertOverdue", true) || minutes <= 0) return;
        long now = System.currentTimeMillis();
        long limit = minutes * 60_000L;
        for (Store.Station s : app.store.stations.values()) {
            if (s.time < sessionStart) continue;      // only teammates heard since the app started
            boolean late = now - s.time > limit;
            if (late && overdueAlerted.add(s.call)) {
                post(ctx, "OVERDUE · " + s.label().toUpperCase(Locale.US),
                        "No position report for " + ((now - s.time) / 60_000) + " min (" + s.via + ")",
                        MainActivity.class, nextId++);
            } else if (!late) {
                overdueAlerted.remove(s.call);
            }
        }
    }

    @SuppressLint("MissingPermission")
    public static void updateStatus(Context ctx) {
        if (!allowed(ctx) || !FieldMapApp.get().serviceRunning) return;
        try {
            NotificationManagerCompat.from(ctx).notify(FieldMapService.NOTIF_ID, FieldMapService.buildNotification(ctx));
        } catch (Exception ignored) {
        }
    }
}
