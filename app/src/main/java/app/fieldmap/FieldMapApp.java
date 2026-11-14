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
import app.fieldmap.core.Cot;
import app.fieldmap.core.Mesh;
import app.fieldmap.core.Shapes;
import app.fieldmap.core.Tac;
import app.fieldmap.core.Trail;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** App-wide state: settings, saved data, the radio link and APRS handling. */
public class FieldMapApp extends Application implements RadioLink.Callback, MeshLink.Callback, TakLink.Callback {

    public interface Listener {
        void onDataChanged();
        void onRadioChanged();
        default void onLocation(Location loc) { }
    }

    private static FieldMapApp instance;
    public static FieldMapApp get() { return instance; }

    public static final String TOCALL = "APZFMP"; // experimental APRS "tocall"

    public SharedPreferences prefs;
    public Store store;
    public RadioLink radio;
    public MeshLink mesh;
    public Location lastLoc;

    /** Meshtastic: my node number and the names of nodes we know. */
    public int myNodeNum = 0;
    private final Map<Integer, String> nodeNames = new HashMap<>();
    private final Random rnd = new Random();
    private long lastMeshBeacon = 0;
    public volatile boolean serviceRunning = false;
    public Trail trail;
    public Weather weather;
    public Elevation elev;
    public TakLink tak;
    private final Map<String, String> takNames = new HashMap<>();
    private long lastTakBeacon = 0;
    /** True while the map screen is on top. */
    public boolean mapVisible = false;
    private boolean autoTried = false;

    // SOS: mine, and those heard from teammates (station key -> last time heard)
    public boolean sosActive = false;
    private long sosStarted = 0;
    private boolean sosLogged = false;
    public final Map<String, Long> sosFrom = new HashMap<>();
    private static final long SOS_REPEAT_MS = 2 * 60_000L, SOS_MAX_MS = 60 * 60_000L;
    private final long sessionStart = System.currentTimeMillis();

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
        mesh = new MeshLink(this, this);
        msgCounter = prefs.getInt("msgCounter", 1);
        store.pruneStations(24 * 3600 * 1000L);
        Alerts.createChannels(this);
        trail = new Trail(new java.io.File(getFilesDir(), "track.csv"));
        trail.load();
        weather = new Weather(this);
        elev = new Elevation(this);
        tak = new TakLink(this, this);
        main.postDelayed(overdueTask, 60_000);
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

    // privacy: APRS is public by law (no encryption on ham bands), so it is opt-in for team traffic
    public boolean aprsTeam() { return prefs.getBoolean("aprsTeam", false); }
    public boolean aprsPosition() { return prefs.getBoolean("aprsPos", false); }
    public boolean noGate() { return prefs.getBoolean("nogate", true); }
    public int meshChannel() { return prefs.getInt("meshChannel", 0); }
    public boolean meshTeamOnly() { return prefs.getBoolean("meshTeamOnly", true); }
    public boolean background() { return prefs.getBoolean("background", true); }

    private final Runnable overdueTask = new Runnable() {
        @Override public void run() {
            Alerts.checkOverdue(FieldMapApp.this, sessionStart);
            pollWeather(false);
            main.postDelayed(this, 60_000);
        }
    };

    // ---------- location ----------

    /** Every GPS fix, from the map screen or the background service, comes through here. */
    public void onLocation(Location l) {
        Location prev = lastLoc;
        boolean better = prev == null
                || l.getTime() - prev.getTime() > 10000
                || l.getAccuracy() <= prev.getAccuracy()
                || android.location.LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (!better) return;
        lastLoc = l;
        if (prefs.getBoolean("trails", true) && l.hasAccuracy()
                && android.location.LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            trail.add(l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : Double.NaN,
                    l.getTime(), l.getAccuracy());
        }
        if (weather.lastTry == 0) pollWeather(false);
        for (Listener x : listeners) x.onLocation(l);
    }

    // ---------- weather ----------

    /** Checks NWS alerts at most every 5 minutes (sooner when forced) while the app is in use. */
    public void pollWeather(boolean force) {
        if (!prefs.getBoolean("weather", true)) return;
        Location l = lastLoc;
        if (l == null || !(mapVisible || serviceRunning)) return;
        long since = System.currentTimeMillis() - weather.lastTry;
        if (since < (force ? 30_000L : 5 * 60_000L)) return;
        weather.refresh(l.getLatitude(), l.getLongitude(), this::notifyData);
    }

    // ---------- auto-reconnect ----------

    /** Reconnects the radios used last time. Gives up after a minute if they are not around. */
    @android.annotation.SuppressLint("MissingPermission")
    public void autoConnect() {
        if (autoTried) return;
        autoTried = true;
        applyTakSettings();
        if (!prefs.getBoolean("autoConnect", true)) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a == null || !a.isEnabled()) return;
            String ra = prefs.getString("lastRadio", ""), ma = prefs.getString("lastMesh", "");
            if (prefs.getBoolean("wantAprs", false) && android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ra)
                    && radio.getState() == RadioLink.State.OFF) {
                log("Auto-connect UV-PRO " + ra);
                radio.connect(a.getRemoteDevice(ra), 60_000L);
            }
            if (prefs.getBoolean("wantMesh", false) && android.bluetooth.BluetoothAdapter.checkBluetoothAddress(ma)
                    && mesh.getState() == RadioLink.State.OFF) {
                log("Auto-connect Meshtastic " + ma);
                mesh.connect(a.getRemoteDevice(ma), 60_000L);
            }
        } catch (Exception e) {
            log("Auto-connect failed: " + e.getMessage());
        }
    }

    /** Starts/stops the background service to match settings and radio state. */
    public void updateService() {
        boolean want = background() && (radio.getState() != RadioLink.State.OFF || mesh.getState() != RadioLink.State.OFF
                || tak.getState() != RadioLink.State.OFF);
        if (want && !serviceRunning) FieldMapService.start(this);
        else if (!want && serviceRunning) FieldMapService.stop(this);
        else if (serviceRunning) Alerts.updateStatus(this);
    }

    public String myCall() {
        String c = callsign();
        if (c.isEmpty()) return "";
        int s = ssid();
        return s > 0 ? c + "-" + s : c;
    }

    public boolean hasCallsign() { return !callsign().isEmpty(); }

    /** APRS transmit needs a callsign and the UV-PRO connected. */
    public boolean canTransmit() { return hasCallsign() && radio.isConnected(); }

    /** Anything that can send right now: APRS (with callsign) or Meshtastic. */
    public boolean canShare() { return mesh.isConnected() || tak.isConnected() || (canTransmit() && aprsTeam()); }

    /** Friendly name for a callsign or Meshtastic node id. */
    public String displayName(String key) {
        if (key == null) return "";
        Store.Station s = store.stations.get(key);
        if (s != null && s.name != null && !s.name.isEmpty()) return s.name;
        if (key.startsWith("!")) {
            try {
                String n = nodeNames.get((int) Long.parseLong(key.substring(1), 16));
                if (n != null) return n;
            } catch (NumberFormatException ignored) { }
        }
        if (key.startsWith("tak:")) {
            String n = takNames.get(key.substring(4));
            return n != null && !n.isEmpty() ? n : "TAK user";
        }
        return key;
    }

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
            if (!p.trim().isEmpty() && !p.trim().equals("NOGATE")) out.add(p.trim());
        }
        // iGates must not copy packets marked NOGATE to the internet (aprs.fi etc.)
        if (noGate()) out.add("NOGATE");
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
        boolean ok = false;
        if (canTransmit() && aprsPosition()) {
            ok = sendInfo(Aprs.position(l.getLatitude(), l.getLongitude(), symTable(), symCode(), statusComment()));
            if (ok) lastBeacon = System.currentTimeMillis();
        }
        if (mesh.isConnected()) ok |= meshBeacon();
        if (tak.isConnected()) ok |= takBeacon();
        return ok;
    }

    // ---------- TAK (ATAK / WinTAK / iTAK) ----------

    /** My id on TAK networks: random, made once, kept. */
    public String takUid() {
        String u = prefs.getString("takUid", "");
        if (u.isEmpty()) {
            u = "FIELDMAP-" + Long.toHexString(rnd.nextLong() & 0xFFFFFFFFFFL);
            prefs.edit().putString("takUid", u).apply();
        }
        return u;
    }

    public String takCallsign() {
        String c = prefs.getString("takCall", "").trim();
        return c.isEmpty() ? myName() : c;
    }

    /** Starts or stops the TAK link to match Settings. */
    public void applyTakSettings() {
        boolean lan = prefs.getBoolean("takLan", false);
        String server = prefs.getString("takServer", "").trim();
        String host = server;
        int port = 8087;
        int colon = server.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(server.substring(colon + 1).trim());
                host = server.substring(0, colon).trim();
            } catch (NumberFormatException ignored) { }
        }
        String sig = lan + "|" + host + "|" + port;
        if (sig.equals(takSig)) return;
        takSig = sig;
        tak.configure(lan, host, port, takUid());
    }
    private String takSig = "false||8087";
    private final java.util.LinkedHashSet<String> takSeen = new java.util.LinkedHashSet<>();

    private boolean takBeacon() {
        Location l = lastLoc;
        if (l == null || !tak.isConnected()) return false;
        boolean ok = tak.send(Cot.pli(takUid(), takCallsign(), l.getLatitude(), l.getLongitude(),
                l.hasAltitude() ? l.getAltitude() : Double.NaN, l.hasSpeed() ? l.getSpeed() : 0,
                l.hasBearing() ? l.getBearing() : 0, "0.3"), false);
        if (ok) lastTakBeacon = System.currentTimeMillis();
        return ok;
    }

    @Override
    public void onTakState(RadioLink.State state, String detail) {
        log("TAK " + state + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (state == RadioLink.State.CONNECTED) lastTakBeacon = 0;
        restartBeaconTask();
        updateService();
        notifyRadio();
    }

    @Override
    public void onTakEvent(Cot.Event e) {
        String mine = takUid();
        if (mine.equals(e.uid) || mine.equals(e.fromUid) || (e.uid != null && e.uid.startsWith("FM-" + mine))) return;
        long now = System.currentTimeMillis();
        switch (e.kind) {
            case PERSON: {
                if (e.stale > 0 && e.stale < now - 5 * 60_000L) break; // an old position replayed by a server
                String key = "tak:" + e.uid;
                if (e.callsign != null && !e.callsign.isEmpty()) takNames.put(e.uid, e.callsign);
                store.upsertStation(key, e.lat, e.lon, '/', '[', "", "TAK", e.callsign, now);
                log("TAK RX position " + displayName(key));
                notifyData();
                break;
            }
            case MARKER: {
                String id = "tak:" + e.uid;
                boolean isNew = !store.pins.containsKey(id);
                String name = e.callsign == null || e.callsign.isEmpty() ? "TAK point" : e.callsign;
                String owner = e.fromUid == null || e.fromUid.isEmpty() ? "TAK" : displayName("tak:" + e.fromUid);
                store.upsertRemotePin(id, owner, name, e.lat, e.lon, "");
                log("TAK RX marker " + name);
                if (isNew) Alerts.waypoint(this, owner, name);
                notifyData();
                break;
            }
            case SHAPE: {
                String name = e.callsign == null || e.callsign.isEmpty() ? (e.closed ? "TAK area" : "TAK route") : e.callsign;
                store.upsertRemoteShape("tak:" + e.uid, "TAK", name, e.closed, Shapes.limit(e.shape, 1500));
                log("TAK RX shape " + name);
                notifyData();
                break;
            }
            case DELETE:
                store.removePin("tak:" + e.deleteUid);
                store.removeShape("tak:" + e.deleteUid);
                notifyData();
                break;
            case CHAT: {
                boolean all = Cot.ALL_CHAT.equals(e.chatRoom) || Cot.ALL_CHAT.equals(e.chatToUid);
                boolean toMe = mine.equals(e.chatToUid);
                if (!all && !toMe) break;
                String key = "tak:" + (e.fromUid == null ? "" : e.fromUid);
                if (e.fromCallsign != null && !e.fromCallsign.isEmpty() && e.fromUid != null) takNames.put(e.fromUid, e.fromCallsign);
                // the same message can arrive twice (Wi-Fi and server): go by its id, not its text
                if (e.uid != null && !e.uid.isEmpty()) {
                    if (takSeen.contains(e.uid)) break;
                    takSeen.add(e.uid);
                    if (takSeen.size() > 300) takSeen.remove(takSeen.iterator().next());
                }
                log("TAK RX chat " + displayName(key) + ": " + e.chatText);
                boolean sosRepeat = Tac.isSos(e.chatText) && isSos(key);
                if (!sosRepeat) store.addMessage(key, all ? group() : myLabel(), e.chatText, null, false, "TAK");
                if (!inspectText(key, "TAK", e.chatText)) Alerts.message(this, displayName(key), e.chatText, toMe);
                notifyData();
                break;
            }
            default:
                break;
        }
        notifyRadio();
    }

    // ---------- routes and areas ----------

    /** Sends a drawn route or area to the team: compact text on the mesh, a drawing on TAK. */
    public boolean shareShape(Store.Shape s) {
        boolean ok = false;
        if (mesh.isConnected()) {
            String text = Shapes.share(s.name, s.closed, s.pts);
            if (text != null && mesh.send(Mesh.text(Mesh.BROADCAST, meshChannel(), text, newPacketId()))) {
                log("MESH TX " + (s.closed ? "area " : "route ") + s.name);
                ok = true;
            }
        }
        if (tak.isConnected()) ok |= tak.send(Cot.shape("FM-" + takUid() + "-" + s.id, s.name, s.pts, s.closed), false);
        if (ok) { s.shared = true; store.save(); }
        return ok;
    }

    public void unshareShape(Store.Shape s) {
        if (s.shared && tak.isConnected()) tak.send(Cot.delete("FM-" + takUid() + "-" + s.id, "u-d-f"), false);
    }

    private boolean meshBeacon() {
        Location l = lastLoc;
        if (l == null || !mesh.isConnected()) return false;
        byte[] pos = Mesh.positionPayload(l.getLatitude(), l.getLongitude(),
                l.hasAltitude() ? (int) Math.round(l.getAltitude()) : 0, (int) (System.currentTimeMillis() / 1000));
        boolean ok = mesh.send(Mesh.packet(Mesh.BROADCAST, meshChannel(), Mesh.PORT_POSITION, pos, newPacketId(), false));
        if (ok) { lastMeshBeacon = System.currentTimeMillis(); log("MESH TX position"); }
        return ok;
    }

    private int newPacketId() { return rnd.nextInt(Integer.MAX_VALUE - 1) + 1; }

    public boolean sharePin(Store.Pin p, boolean live) {
        if (p.place && live) { log("Not sent: " + p.name + " is a private saved place"); return false; }
        boolean ok = false;
        if (canTransmit() && aprsTeam()) ok = sendInfo(Aprs.object(p.name, p.lat, p.lon, '\\', 'A', p.comment, live));
        if (mesh.isConnected()) {
            if (p.meshId == 0) p.meshId = newPacketId();
            byte[] wp = Mesh.waypointPayload(p.meshId, p.lat, p.lon, live ? 0 : 1, p.name, p.comment);
            boolean m = mesh.send(Mesh.packet(Mesh.BROADCAST, meshChannel(), Mesh.PORT_WAYPOINT, wp, newPacketId(), true));
            if (m) log("MESH TX waypoint " + p.name + (live ? "" : " (delete)"));
            ok |= m;
        }
        if (tak.isConnected()) {
            String uid = "FM-" + takUid() + "-" + p.id;
            ok |= tak.send(live ? Cot.marker(uid, p.name, p.lat, p.lon, takUid(), takCallsign()) : Cot.delete(uid, "a-u-G"), false);
        }
        if (ok && live) { p.shared = true; store.save(); }
        return ok;
    }

    /**
     * Team messages go out on every connected link. "!xxxxxxxx" goes to one Meshtastic node,
     * anything else to one APRS callsign.
     */
    public boolean sendMessage(String to, String text) {
        String dest = to.trim();
        if (dest.startsWith("!")) return sendMeshDirect(dest.toLowerCase(Locale.US), text);
        if (dest.startsWith("tak:")) { log("Direct messages to TAK users are not supported; send to the team"); return false; }
        dest = dest.toUpperCase(Locale.US);
        if (dest.equals(group())) {
            boolean ok = false;
            String via = "";
            if (canTransmit() && aprsTeam() && sendInfo(Aprs.message(dest, text, null))) { ok = true; via = "APRS"; }
            if (mesh.isConnected() && mesh.send(Mesh.text(Mesh.BROADCAST, meshChannel(), text, newPacketId()))) {
                ok = true; via = via.isEmpty() ? "MESH" : via + "+MESH";
                log("MESH TX text to channel");
            }
            if (takChat(text)) { ok = true; via = via.isEmpty() ? "TAK" : via + "+TAK"; }
            if (ok) { store.addMessage(myLabel(), dest, text, null, true, via); notifyData(); }
            return ok;
        }
        return sendAprsMessage(dest, text);
    }

    private boolean takChat(String text) {
        if (!tak.isConnected()) return false;
        Location l = lastLoc;
        return tak.send(Cot.chat(takUid(), takCallsign(), text,
                l == null ? Double.NaN : l.getLatitude(), l == null ? Double.NaN : l.getLongitude()), true);
    }

    private String myLabel() {
        if (hasCallsign()) return myCall();
        return myNodeNum != 0 ? Mesh.nodeId(myNodeNum) : "me";
    }

    /** Name other people see in SOS and check-in texts. */
    public String myName() {
        String n = myNodeNum != 0 ? nodeNames.get(myNodeNum) : null;
        if (n != null && !n.isEmpty()) return n;
        return hasCallsign() ? callsign() : "FIELDMAP";
    }

    // ---------- check-in and SOS ----------

    public boolean checkIn() {
        Location l = lastLoc;
        return sendMessage(group(), Tac.checkIn("OK",
                l == null ? Double.NaN : l.getLatitude(), l == null ? Double.NaN : l.getLongitude()));
    }

    /** Which links an SOS would go out on right now, for the confirmation screen. Empty = none. */
    public String sosLinks() {
        String s = "";
        if (mesh.isConnected()) s = "MESHTASTIC (team channel " + meshChannel() + ", encrypted)";
        if (canTransmit()) s += (s.isEmpty() ? "" : "\n") + "APRS (public — anyone listening can read it)";
        if (tak.isConnected()) s += (s.isEmpty() ? "" : "\n") + "TAK network (" + tak.describe().trim() + ")";
        return s;
    }

    public boolean startSos() {
        if (!mesh.isConnected() && !canTransmit() && !tak.isConnected()) return false;
        sosActive = true;
        sosStarted = System.currentTimeMillis();
        sosLogged = false;
        main.removeCallbacks(sosTask);
        main.post(sosTask);
        return true;
    }

    public void cancelSos() {
        if (!sosActive) return;
        sosActive = false;
        main.removeCallbacks(sosTask);
        String text = Tac.sosCancel(myName());
        String via = sendEmergency(text);
        if (!via.isEmpty()) store.addMessage(myLabel(), group(), text, null, true, via);
        log("SOS cancelled");
        notifyData();
    }

    /** An emergency goes out on everything that can transmit, including APRS. */
    private String sendEmergency(String text) {
        String via = "";
        if (mesh.isConnected() && mesh.send(Mesh.text(Mesh.BROADCAST, meshChannel(), text, newPacketId()))) via = "MESH";
        if (canTransmit() && sendInfo(Aprs.message(group(), text, null))) via = via.isEmpty() ? "APRS" : "APRS+" + via;
        if (takChat(text)) via = via.isEmpty() ? "TAK" : via + "+TAK";
        return via;
    }

    private final Runnable sosTask = new Runnable() {
        @Override public void run() {
            if (!sosActive) return;
            if (System.currentTimeMillis() - sosStarted > SOS_MAX_MS) {
                sosActive = false;
                log("SOS stopped repeating after 60 minutes");
                notifyData();
                return;
            }
            Location l = lastLoc;
            String text = Tac.sos(myName(), l == null ? Double.NaN : l.getLatitude(), l == null ? Double.NaN : l.getLongitude());
            String via = sendEmergency(text);
            log("SOS sent via " + (via.isEmpty() ? "NOTHING (no radio connected)" : via));
            if (!via.isEmpty() && !sosLogged) {
                sosLogged = true;
                store.addMessage(myLabel(), group(), text, null, true, via);
            }
            if (l != null && mesh.isConnected()) meshBeacon();
            notifyData();
            main.postDelayed(this, SOS_REPEAT_MS);
        }
    };

    public boolean isSos(String key) {
        Long t = sosFrom.get(key);
        return t != null && System.currentTimeMillis() - t < SOS_MAX_MS;
    }

    /** A teammate's SOS that is still live (heard in the last hour), or null. Returns the station key. */
    public String activeSosFrom() {
        long now = System.currentTimeMillis();
        String best = null;
        for (Map.Entry<String, Long> e : sosFrom.entrySet()) {
            if (now - e.getValue() > SOS_MAX_MS) continue;
            if (best == null || e.getValue() > sosFrom.get(best)) best = e.getKey();
        }
        return best;
    }

    /**
     * Looks at team text for SOS / check-in / SOS-cancel. Returns true if it raised its own
     * alert, so the ordinary message alert can be skipped.
     */
    private boolean inspectText(String fromKey, String via, String text) {
        boolean sos = Tac.isSos(text), cancel = Tac.isSosCancel(text), chk = Tac.isCheckIn(text);
        if (!sos && !cancel && !chk) return false;
        long now = System.currentTimeMillis();
        double[] g = Tac.findGrid(text);
        if (g != null && (sos || chk)) {
            Store.Station old = store.stations.get(fromKey);
            store.upsertStation(fromKey, g[0], g[1], old == null ? '/' : old.table, old == null ? '[' : old.code,
                    old == null ? "" : old.comment, via, old == null ? nodeNameFor(fromKey) : old.name, now);
        }
        Store.Station s = store.stations.get(fromKey);
        if (chk && s != null) { s.checkTime = now; store.save(); }
        if (cancel) {
            if (sosFrom.remove(fromKey) != null) Alerts.sosCleared(this, displayName(fromKey));
            return true;
        }
        if (sos) {
            Long last = sosFrom.put(fromKey, now);
            // repeats every 2 minutes refresh the position quietly; only the first one sounds the alarm
            if (last == null || now - last > 10 * 60_000L) Alerts.sos(this, displayName(fromKey), text);
            return true;
        }
        return false;
    }

    private String nodeNameFor(String key) {
        if (key != null && key.startsWith("!")) {
            try { return nodeNames.get((int) Long.parseLong(key.substring(1), 16)); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private boolean sendMeshDirect(String nodeId, String text) {
        if (!mesh.isConnected()) { log("TX blocked: Meshtastic not connected"); return false; }
        int num;
        try { num = (int) Long.parseLong(nodeId.substring(1), 16); } catch (NumberFormatException e) { return false; }
        boolean ok = mesh.send(Mesh.text(num, meshChannel(), text, newPacketId()));
        if (ok) {
            log("MESH TX text to " + nodeId);
            store.addMessage(myLabel(), nodeId, text, null, true, "MESH");
            notifyData();
        }
        return ok;
    }

    private boolean sendAprsMessage(String dest, String text) {
        boolean direct = true;
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
            long now = System.currentTimeMillis();
            if (secs > 0 && canTransmit() && aprsPosition() && now - lastBeacon >= secs * 1000L - 2000) {
                Location l = lastLoc;
                if (l != null && sendInfo(Aprs.position(l.getLatitude(), l.getLongitude(), symTable(), symCode(), statusComment())))
                    lastBeacon = now;
            }
            // LoRa airtime is precious: never more often than every 5 minutes on the mesh
            int meshSecs = Math.max(secs, 300);
            if (secs > 0 && mesh.isConnected() && now - lastMeshBeacon >= meshSecs * 1000L - 2000) meshBeacon();
            // TAK devices expect a position about once a minute, or they grey you out
            if (tak.isConnected() && now - lastTakBeacon >= 58_000L) takBeacon();
            main.postDelayed(this, 15000);
        }
    };

    // ---------- radio callbacks ----------

    @Override
    public void onRadioState(RadioLink.State state, String detail) {
        log("Radio " + state + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (state == RadioLink.State.OFF && "LOST".equals(detail) && !mapVisible) Alerts.commsLost(this, "UV-PRO");
        if (state == RadioLink.State.CONNECTED) {
            main.postDelayed(() -> {
                if (canTransmit() && aprsPosition() && beaconSeconds() > 0) {
                    Location l = lastLoc;
                    if (l != null && sendInfo(Aprs.position(l.getLatitude(), l.getLongitude(), symTable(), symCode(), statusComment())))
                        lastBeacon = System.currentTimeMillis();
                }
            }, 2000);
        }
        restartBeaconTask();
        updateService();
        notifyRadio();
    }

    private void restartBeaconTask() {
        main.removeCallbacks(beaconTask);
        if (radio.isConnected() || mesh.isConnected() || tak.isConnected()) main.postDelayed(beaconTask, tak.isConnected() ? 3000 : 15000);
    }

    // ---------- Meshtastic ----------

    @Override
    public void onMeshState(RadioLink.State state, String detail) {
        log("Mesh " + state + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (state == RadioLink.State.OFF && "LOST".equals(detail) && !mapVisible) Alerts.commsLost(this, "MESHTASTIC");
        if (state == RadioLink.State.CONNECTED) lastMeshBeacon = 0;
        restartBeaconTask();
        updateService();
        notifyRadio();
    }

    @Override
    public void onFromRadio(byte[] data) {
        Mesh.Event e;
        try {
            e = Mesh.parseFromRadio(data);
        } catch (RuntimeException ex) {
            log("MESH RX unreadable (" + data.length + " bytes)");
            return;
        }
        long now = System.currentTimeMillis();
        String fromId = Mesh.nodeId(e.from);
        switch (e.kind) {
            case MY_INFO:
                myNodeNum = e.myNode;
                log("MESH my node " + Mesh.nodeId(myNodeNum));
                break;
            case NODE_INFO: {
                Mesh.Node n = e.node;
                nodeNames.put(n.num, n.label());
                boolean onTeam = !meshTeamOnly() || n.channel == meshChannel();
                if (n.num != myNodeNum && n.hasPosition() && onTeam && passesFilter(n.id)) {
                    long heard = n.lastHeard > 0 ? n.lastHeard * 1000 : now - 3600_000L;
                    Store.Station old = store.stations.get(n.id);
                    if (old == null || old.time < heard)
                        store.upsertStation(n.id, n.lat, n.lon, '/', '[', "", "MESH", n.label(), heard);
                } else {
                    store.renameStation(n.id, n.label());
                }
                break;
            }
            case CONFIG_COMPLETE:
                log("MESH node database loaded (" + nodeNames.size() + " nodes)");
                notifyData();
                break;
            case USER:
                nodeNames.put(e.from, e.node.label());
                store.renameStation(fromId, e.node.label());
                log("MESH RX nodeinfo " + fromId + " " + e.node.label());
                notifyData();
                break;
            case POSITION:
                log("MESH RX position " + displayName(fromId));
                if (e.from != myNodeNum && passesFilter(fromId) && (!meshTeamOnly() || e.channel == meshChannel())) {
                    store.upsertStation(fromId, e.lat, e.lon, '/', '[', "", "MESH", nodeNames.get(e.from), now);
                    notifyData();
                }
                break;
            case TEXT: {
                if (e.from == myNodeNum) break;
                boolean broadcast = e.to == Mesh.BROADCAST;
                if (!broadcast && e.to != myNodeNum) break;
                if (broadcast && e.channel != meshChannel()) {
                    log("MESH RX text on other channel " + e.channel + " (ignored)");
                    break;
                }
                log("MESH RX text " + displayName(fromId) + ": " + e.text);
                // an SOS repeats every 2 minutes: keep one line in chat, not thirty
                Shapes.Shared shp = Shapes.parseShared(e.text);
                if (shp != null) {
                    // a route or area drawn by a teammate: put it on the map, and a readable line in chat
                    store.upsertRemoteShape("ms:" + fromId + ":" + shp.name, displayName(fromId), shp.name, shp.closed, shp.pts);
                    String note = (shp.closed ? "[AREA] " : "[ROUTE] ") + shp.name + " (" + shp.pts.size() + " pts, on your map)";
                    log("MESH RX " + note);
                    store.addMessage(fromId, broadcast ? group() : myLabel(), note, null, false, "MESH");
                    Alerts.waypoint(this, displayName(fromId), shp.name);
                    notifyData();
                    break;
                }
                boolean sosRepeat = Tac.isSos(e.text) && isSos(fromId);
                if (!sosRepeat) store.addMessage(fromId, broadcast ? group() : myLabel(), e.text, null, false, "MESH");
                if (!inspectText(fromId, "MESH", e.text)) Alerts.message(this, displayName(fromId), e.text, !broadcast);
                notifyData();
                break;
            }
            case WAYPOINT: {
                if (e.from == myNodeNum) break;
                Mesh.Waypoint w = e.waypoint;
                String id = "wp:" + Integer.toHexString(w.id);
                boolean expired = w.expire == 1 || (w.expire > 1 && (w.expire & 0xFFFFFFFFL) < now / 1000);
                log("MESH RX waypoint " + w.name + (expired || !w.hasPosition() ? " (deleted)" : ""));
                if (meshTeamOnly() && e.channel != meshChannel()) break;
                if (expired || !w.hasPosition()) store.removePin(id);
                else if (passesFilter(fromId)) {
                    boolean isNew = !store.pins.containsKey(id);
                    String nm = w.name.isEmpty() ? "Waypoint" : w.name;
                    store.upsertRemotePin(id, displayName(fromId), nm, w.lat, w.lon, w.desc);
                    if (isNew) Alerts.waypoint(this, displayName(fromId), nm);
                }
                notifyData();
                break;
            }
            default:
                break;
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
                    boolean isNew = !store.pins.containsKey("obj:" + p.name);
                    store.upsertRemotePin(p.source, p.name, p.lat, p.lon, p.comment);
                    // APRS is a public network: only alert for stations on your track filter list
                    if (isNew && !prefs.getString("filter", "").trim().isEmpty()) Alerts.waypoint(this, p.source, p.name);
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
        boolean sosRepeat = Tac.isSos(p.msgText) && isSos(p.source);
        if (!sosRepeat) store.addMessage(p.source, to.toUpperCase(Locale.US), p.msgText, p.msgId, false);
        if (!inspectText(p.source, "APRS", p.msgText)) Alerts.message(this, p.source, p.msgText, toMe);
        notifyData();
    }

    public static final List<String> SYMBOL_NAMES = Arrays.asList(
            "Person", "Car", "Jeep", "Truck", "House", "Camp / tent", "Bicycle");
    public static final List<String> SYMBOL_CODES = Arrays.asList(
            "/[", "/>", "/j", "/k", "/-", "/;", "/b");
}
