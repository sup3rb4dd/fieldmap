package app.fieldmap;

import android.app.Application;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import app.fieldmap.core.Aprs;
import app.fieldmap.core.Ax25;
import app.fieldmap.core.Kiss;

/** App-wide state: settings, saved data, the radio link and APRS handling. */
public class FieldMapApp extends Application implements RadioLink.Callback {

    public interface Listener {
        void onDataChanged();
        void onRadioChanged();
    }

    private static FieldMapApp instance;
    public static FieldMapApp get() { return instance; }

    public static final String TOCALL = "APZFMP"; // experimental APRS "tocall"

    public SharedPreferences prefs;
    public Store store;
    public RadioLink radio;
    public Location lastLoc;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> log = new ArrayList<>();
    private int msgCounter = 1;
    private long lastBeacon = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        store = new Store(this);
        radio = new RadioLink(this);
        msgCounter = prefs.getInt("msgCounter", 1);
        store.pruneStations(24 * 3600 * 1000L);
    }

    // ---------- listeners ----------

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public void notifyData() { for (Listener l : listeners) l.onDataChanged(); }
    private void notifyRadio() { for (Listener l : listeners) l.onRadioChanged(); }

    // ---------- settings ----------

    public String callsign() { return prefs.getString("call", "").trim().toUpperCase(Locale.US); }
    public int ssid() { return prefs.getInt("ssid", 7); }
    public String group() {
        String g = prefs.getString("group", "FIELDMAP").trim().toUpperCase(Locale.US);
        return g.isEmpty() ? "FIELDMAP" : g;
    }
    public int beaconSeconds() { return prefs.getInt("beacon", 120); }
    public boolean imperial() { return prefs.getBoolean("imperial", true); }
    public String path() { return prefs.getString("path", "WIDE1-1,WIDE2-1"); }
    public char symTable() { return prefs.getString("symTable", "/").charAt(0); }
    public char symCode() { return prefs.getString("symCode", "[").charAt(0); }
    public String statusComment() { return prefs.getString("comment", "FieldMap"); }

    public String myCall() {
        String c = callsign();
        if (c.isEmpty()) return "";
        int s = ssid();
        return s > 0 ? c + "-" + s : c;
    }

    public boolean hasCallsign() { return !callsign().isEmpty(); }

    public boolean canTransmit() { return hasCallsign() && radio.isConnected(); }

    /** Stations allowed on the map; empty = everyone. */
    public boolean passesFilter(String call) {
        String f = prefs.getString("filter", "").trim().toUpperCase(Locale.US);
        if (f.isEmpty()) return true;
        String base = baseCall(call);
        for (String part : f.split("[,\\s]+")) {
            if (part.isEmpty()) continue;
            if (part.contains("-") ? part.equalsIgnoreCase(call) : part.equalsIgnoreCase(base)) return true;
        }
        return false;
    }

    public static String baseCall(String call) {
        if (call == null) return "";
        String c = call.replace("*", "");
        int d = c.indexOf('-');
        return d >= 0 ? c.substring(0, d) : c;
    }

    private boolean isMe(String call) {
        String me = myCall();
        return !me.isEmpty() && me.equalsIgnoreCase(call.replace("*", ""));
    }

    // ---------- log ----------

    public synchronized void log(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.add(ts + " " + line);
        while (log.size() > 500) log.remove(0);
    }

    public synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String l : log) sb.append(l).append('\n');
        return sb.toString();
    }

    // ---------- transmit ----------

    private List<String> pathList() {
        List<String> out = new ArrayList<>();
        for (String p : path().toUpperCase(Locale.US).split("[,\\s]+")) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    /** Sends one APRS info field. Returns false (and logs why) if it could not be sent. */
    public boolean sendInfo(String info) {
        if (!hasCallsign()) { log("TX blocked: no callsign set"); return false; }
        if (!radio.isConnected()) { log("TX blocked: radio not connected"); return false; }
        Ax25 f = Ax25.ui(myCall(), TOCALL, pathList(), info);
        boolean ok = radio.send(Kiss.encode(f.encode()));
        log((ok ? "TX " : "TX FAILED ") + f);
        notifyRadio();
        return ok;
    }

    public boolean beaconNow() {
        Location l = lastLoc;
        if (l == null) { log("Beacon skipped: no GPS fix yet"); return false; }
        boolean ok = sendInfo(Aprs.position(l.getLatitude(), l.getLongitude(), symTable(), symCode(), statusComment()));
        if (ok) lastBeacon = System.currentTimeMillis();
        return ok;
    }

    public boolean sharePin(Store.Pin p, boolean live) {
        boolean ok = sendInfo(Aprs.object(p.name, p.lat, p.lon, '\\', 'A', p.comment, live));
        if (ok && live) { p.shared = true; store.save(); }
        return ok;
    }

    /** Group messages go out without an id (no acks); direct messages get an id. */
    public boolean sendMessage(String to, String text) {
        String dest = to.trim().toUpperCase(Locale.US);
        boolean direct = !dest.equals(group());
        String id = null;
        if (direct) {
            id = String.valueOf(msgCounter);
            msgCounter = msgCounter % 99999 + 1;
            prefs.edit().putInt("msgCounter", msgCounter).apply();
        }
        boolean ok = sendInfo(Aprs.message(dest, text, id));
        if (ok) {
            store.addMessage(myCall(), dest, text, id, true);
            notifyData();
        }
        return ok;
    }

    private final Runnable beaconTask = new Runnable() {
        @Override public void run() {
            int secs = beaconSeconds();
            if (secs > 0 && canTransmit()) {
                long now = System.currentTimeMillis();
                if (now - lastBeacon >= secs * 1000L - 2000) beaconNow();
            }
            main.postDelayed(this, 15000);
        }
    };

    // ---------- radio callbacks ----------

    @Override
    public void onRadioState(RadioLink.State state, String detail) {
        log("Radio " + state + (detail.isEmpty() ? "" : " (" + detail + ")"));
        main.removeCallbacks(beaconTask);
        if (state == RadioLink.State.CONNECTED) {
            main.postDelayed(() -> { if (canTransmit() && beaconSeconds() > 0) beaconNow(); }, 2000);
            main.postDelayed(beaconTask, 15000);
        }
        notifyRadio();
    }

    @Override
    public void onFrame(byte[] ax25) {
        Ax25 f = Ax25.decode(ax25);
        if (f == null) { log("RX (not a UI frame, " + ax25.length + " bytes)"); return; }
        log("RX " + f);
        if (isMe(f.source)) return; // my own packet coming back from a digipeater
        Aprs.Packet p = Aprs.decode(f);
        handle(p);
        notifyRadio();
    }

    private void handle(Aprs.Packet p) {
        switch (p.type) {
            case POSITION:
                if (p.hasPosition() && passesFilter(p.source)) {
                    store.upsertStation(p.source, p.lat, p.lon, p.symTable, p.symCode, p.comment);
                    notifyData();
                }
                break;
            case OBJECT:
                if (!passesFilter(p.source)) break;
                if (p.killed) {
                    store.removePin("obj:" + p.name);
                    notifyData();
                } else if (p.hasPosition()) {
                    store.upsertRemotePin(p.source, p.name, p.lat, p.lon, p.comment);
                    notifyData();
                }
                break;
            case MESSAGE:
                handleMessage(p);
                break;
            default:
                break;
        }
    }

    private void handleMessage(Aprs.Packet p) {
        String to = p.msgTo == null ? "" : p.msgTo;
        boolean toMe = isMe(to);
        boolean toGroup = to.equalsIgnoreCase(group());
        if (p.isAck || p.isRej) {
            if (toMe && p.isAck && p.msgId != null) {
                store.markAcked(p.source, p.msgId);
                notifyData();
            }
            return;
        }
        if (!toMe && !toGroup) return;
        if (toMe && p.msgId != null && !p.msgId.isEmpty()) {
            sendInfo(Aprs.ack(p.source, p.msgId));
        }
        if (store.isDuplicate(p.source, p.msgText, p.msgId)) return;
        store.addMessage(p.source, to.toUpperCase(Locale.US), p.msgText, p.msgId, false);
        notifyData();
    }

    public static final List<String> SYMBOL_NAMES = Arrays.asList(
            "Person", "Car", "Jeep", "Truck", "House", "Camp / tent", "Bicycle");
    public static final List<String> SYMBOL_CODES = Arrays.asList(
            "/[", "/>", "/j", "/k", "/-", "/;", "/b");
}
