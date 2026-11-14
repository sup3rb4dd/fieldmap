package app.fieldmap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps FieldMap alive with the screen off: holds the radio links, keeps GPS updating
 * for position reports, and shows a small status notification.
 */
@SuppressLint("MissingPermission")
public class FieldMapService extends Service {

    public static final int NOTIF_ID = 1;
    private PowerManager.WakeLock wakeLock;
    private LocationManager lm;

    private final LocationListener loc = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location l) { FieldMapApp.get().onLocation(l); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(@NonNull String provider) { }
        @Override public void onProviderDisabled(@NonNull String provider) { }
    };

    public static void start(Context ctx) {
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, FieldMapService.class));
        } catch (Exception e) {
            FieldMapApp.get().log("Background mode could not start: " + e.getMessage());
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, FieldMapService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification(this);
        boolean hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                if (hasLoc) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                startForeground(NOTIF_ID, n, type);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            FieldMapApp.get().log("Background mode refused by Android: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FieldMap:radio");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        if (lm == null && hasLoc) {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    // screen off: a fix every 10 s is plenty for a trail, every 30 s for position reports alone
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            FieldMapApp.get().prefs.getBoolean("trails", true) ? 10000 : 30000, 0, loc);
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 0, loc);
            } catch (Exception ignored) {
            }
        }
        FieldMapApp.get().serviceRunning = true;
        // Android restarted us after closing the app to free memory: bring the radios back
        if (intent == null) FieldMapApp.get().autoConnect();
        FieldMapApp.get().log("Background mode ON");
        return START_STICKY;
    }

    static Notification buildNotification(Context ctx) {
        FieldMapApp app = FieldMapApp.get();
        String rl = app.radioLabel();
        String aprs = app.radio.isConnected() ? rl + "▲" : app.radio.getState() == RadioLink.State.OFF ? rl + "·" : rl + "…";
        String mesh = app.mesh.isConnected() ? "MESH▲" : app.mesh.getState() == RadioLink.State.OFF ? "MESH·" : "MESH…";
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(ctx, Alerts.CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(app.sosActive ? "FieldMap · YOUR SOS IS TRANSMITTING" : app.gmrs() ? "FieldMap active · GMRS MODE" : "FieldMap active")
                .setContentText(aprs + "  " + mesh + (app.tak.getState() == RadioLink.State.OFF ? "" : app.tak.isConnected() ? "  TAK▲" : "  TAK…")
                        + "  · RX " + (app.radio.rxCount + app.mesh.rxCount + app.tak.rxCount))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        FieldMapApp.get().serviceRunning = false;
        FieldMapApp.get().store.flush();
        if (lm != null) lm.removeUpdates(loc);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        FieldMapApp.get().log("Background mode OFF");
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
