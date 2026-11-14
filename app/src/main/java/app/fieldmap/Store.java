package app.fieldmap;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pins, heard stations and chat messages, saved on the phone. */
public class Store {

    public static class Pin {
        public String id;          // local id, or "obj:NAME" for pins received over radio
        public String name;
        public double lat, lon;
        public String owner = "";  // "" = mine, otherwise the callsign that sent it
        public String comment = "";
        public long time;
        public boolean shared;     // I have sent it over the radio
        public int meshId;         // Meshtastic waypoint id for my pins (0 = not assigned yet)
        public boolean place;      // saved place (home, family): local only, never transmitted
        public String src = "";    // file this was imported from, "" otherwise

        public boolean isMine() { return owner == null || owner.isEmpty(); }
    }

    /** A drawn, received or imported route (open line) or area (closed). */
    public static class Shape {
        public String id, name = "";
        public boolean closed;
        public List<double[]> pts = new ArrayList<>();
        public String owner = "";  // "" = mine
        public String src = "";    // import file name, "" otherwise
        public boolean shared;
        public long time;
        public int color;          // ARGB chosen by the user, 0 = default

        public boolean isMine() { return owner == null || owner.isEmpty(); }
    }

    public static class Station {
        public String call;        // APRS callsign, or Meshtastic node id like !a1b2c3d4
        public String name = "";   // friendly name (Meshtastic long name)
        public String via = "APRS";
        public double lat, lon;
        public long time;
        public char table = '/', code = '[';
        public String comment = "";
        public long checkTime;     // last "CHK" check-in heard from this station
        /** Recent positions {lat, lon}, oldest first. In memory only. */
        public final List<double[]> trail = new ArrayList<>();

        public String label() { return name == null || name.isEmpty() ? call : name; }
    }

    public static class Msg {
        public String from, to, text, id;
        public long time;
        public boolean out, acked;
        public String via = "APRS";
    }

    public final Map<String, Pin> pins = new LinkedHashMap<>();
    public final Map<String, Station> stations = new LinkedHashMap<>();
    public final List<Msg> messages = new ArrayList<>();
    public final Map<String, Shape> shapes = new LinkedHashMap<>();
    /** Bumped whenever shapes change, so the map only redraws them when needed. */
    public int shapesRev = 0;
    public int unread = 0;

    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getSharedPreferences("store", Context.MODE_PRIVATE);
        load();
    }

    public Pin addMyPin(String name, double lat, double lon) {
        Pin p = new Pin();
        p.id = UUID.randomUUID().toString();
        p.name = name;
        p.lat = lat;
        p.lon = lon;
        p.time = System.currentTimeMillis();
        pins.put(p.id, p);
        save();
        return p;
    }

    public void upsertRemotePin(String owner, String name, double lat, double lon, String comment) {
        upsertRemotePin("obj:" + name, owner, name, lat, lon, comment);
    }

    public void upsertRemotePin(String id, String owner, String name, double lat, double lon, String comment) {
        Pin p = pins.get(id);
        if (p == null) {
            p = new Pin();
            p.id = id;
            pins.put(id, p);
        }
        p.name = name;
        p.owner = owner;
        p.lat = lat;
        p.lon = lon;
        p.comment = comment == null ? "" : comment;
        p.time = System.currentTimeMillis();
        save();
    }

    public Shape addMyShape(String name, boolean closed, List<double[]> pts, String src) {
        Shape s = new Shape();
        s.id = UUID.randomUUID().toString();
        s.name = name;
        s.closed = closed;
        s.pts = new ArrayList<>(pts);
        s.src = src == null ? "" : src;
        s.time = System.currentTimeMillis();
        shapes.put(s.id, s);
        shapesRev++;
        save();
        return s;
    }

    public void upsertRemoteShape(String id, String owner, String name, boolean closed, List<double[]> pts) {
        Shape s = shapes.get(id);
        if (s == null) {
            s = new Shape();
            s.id = id;
            shapes.put(id, s);
        }
        s.owner = owner == null || owner.isEmpty() ? "?" : owner;
        s.name = name;
        s.closed = closed;
        s.pts = new ArrayList<>(pts);
        s.time = System.currentTimeMillis();
        shapesRev++;
        save();
    }

    public void removeShape(String id) {
        if (shapes.remove(id) != null) { shapesRev++; save(); }
    }

    public void touchShapes() { shapesRev++; save(); }

    /** Names of the files things were imported from. */
    public java.util.Set<String> importSources() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Pin p : pins.values()) if (p.src != null && !p.src.isEmpty()) out.add(p.src);
        for (Shape s : shapes.values()) if (s.src != null && !s.src.isEmpty()) out.add(s.src);
        return out;
    }

    /** Removes everything that came from one imported file. Returns how many items went. */
    public int removeImport(String src) {
        int n = 0;
        for (Iterator<Pin> it = pins.values().iterator(); it.hasNext(); ) if (src.equals(it.next().src)) { it.remove(); n++; }
        for (Iterator<Shape> it = shapes.values().iterator(); it.hasNext(); ) if (src.equals(it.next().src)) { it.remove(); n++; }
        shapesRev++;
        save();
        return n;
    }

    public Pin addImportedPin(String name, double lat, double lon, String src) {
        Pin p = new Pin();
        p.id = UUID.randomUUID().toString();
        p.name = name;
        p.lat = lat;
        p.lon = lon;
        p.src = src;
        p.time = System.currentTimeMillis();
        pins.put(p.id, p);
        save();
        return p;
    }

    private static JSONObject shapeJson(Shape s) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", s.id); o.put("name", s.name); o.put("closed", s.closed);
        o.put("poly", app.fieldmap.core.Shapes.encode(s.pts));
        o.put("owner", s.owner); o.put("src", s.src); o.put("shared", s.shared); o.put("time", s.time);
        o.put("color", s.color);
        return o;
    }

    private static Shape shapeFrom(JSONObject o) throws org.json.JSONException {
        Shape s = new Shape();
        s.id = o.getString("id"); s.name = o.optString("name"); s.closed = o.optBoolean("closed");
        List<double[]> pts = app.fieldmap.core.Shapes.decode(o.optString("poly"));
        if (pts == null || pts.size() < 2) throw new org.json.JSONException("bad shape");
        s.pts = pts;
        s.owner = o.optString("owner"); s.src = o.optString("src"); s.shared = o.optBoolean("shared"); s.time = o.optLong("time");
        s.color = o.optInt("color");
        return s;
    }

    public JSONArray exportMyShapes() throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (Shape s : shapes.values()) if (s.isMine()) out.put(shapeJson(s));
        return out;
    }

    public int importShapes(JSONArray arr) {
        int n = 0;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            try {
                Shape s = shapeFrom(arr.getJSONObject(i));
                s.owner = "";
                s.shared = false;
                shapes.put(s.id, s);
                n++;
            } catch (Exception ignored) {
            }
        }
        if (n > 0) { shapesRev++; save(); }
        return n;
    }

    public void removePin(String id) {
        pins.remove(id);
        save();
    }

    public void upsertStation(String call, double lat, double lon, char table, char code, String comment) {
        upsertStation(call, lat, lon, table, code, comment, "APRS", null, System.currentTimeMillis());
    }

    public void upsertStation(String call, double lat, double lon, char table, char code, String comment,
                              String via, String name, long time) {
        Station s = stations.get(call);
        if (s == null) {
            s = new Station();
            s.call = call;
            stations.put(call, s);
        }
        s.via = via;
        if (name != null && !name.isEmpty()) s.name = name;
        if (s.trail.isEmpty()
                || app.fieldmap.core.Geo.distance(s.trail.get(s.trail.size() - 1)[0], s.trail.get(s.trail.size() - 1)[1], lat, lon) > 20) {
            s.trail.add(new double[]{lat, lon});
            if (s.trail.size() > 200) s.trail.remove(0);
        }
        s.lat = lat;
        s.lon = lon;
        s.table = table;
        s.code = code;
        s.comment = comment == null ? "" : comment;
        s.time = time;
        save();
    }

    public void renameStation(String call, String name) {
        Station s = stations.get(call);
        if (s != null && name != null && !name.isEmpty() && !name.equals(s.name)) { s.name = name; save(); }
    }

    public void removeStation(String call) {
        stations.remove(call);
        save();
    }

    public Msg addMessage(String from, String to, String text, String id, boolean out) {
        return addMessage(from, to, text, id, out, "APRS");
    }

    public Msg addMessage(String from, String to, String text, String id, boolean out, String via) {
        Msg m = new Msg();
        m.via = via;
        m.from = from;
        m.to = to;
        m.text = text;
        m.id = id;
        m.out = out;
        m.time = System.currentTimeMillis();
        messages.add(m);
        while (messages.size() > 300) messages.remove(0);
        if (!out) unread++;
        save();
        return m;
    }

    /** True if an identical incoming message was already received recently (APRS retries). */
    public boolean isDuplicate(String from, String text, String id) {
        long now = System.currentTimeMillis();
        for (int i = messages.size() - 1; i >= 0 && i >= messages.size() - 30; i--) {
            Msg m = messages.get(i);
            if (m.out || now - m.time > 30 * 60 * 1000L) continue;
            if (m.from.equalsIgnoreCase(from) && m.text.equals(text)
                    && ((m.id == null && id == null) || (m.id != null && m.id.equals(id)))) return true;
        }
        return false;
    }

    public void markAcked(String to, String id) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.out && id.equals(m.id) && m.to.equalsIgnoreCase(to)) {
                m.acked = true;
                save();
                return;
            }
        }
    }

    public void clearStations() {
        stations.clear();
        save();
    }

    // ---------- persistence ----------

    private final android.os.Handler saver = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean savePending = false;

    /** Schedules a write. Bursts (like a 200-node mesh database arriving) become one write. */
    public void save() {
        if (savePending) return;
        savePending = true;
        saver.postDelayed(this::flush, 400);
    }

    private static JSONObject pinJson(Pin p) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", p.id); o.put("name", p.name); o.put("lat", p.lat); o.put("lon", p.lon);
        o.put("owner", p.owner); o.put("comment", p.comment); o.put("time", p.time); o.put("shared", p.shared);
        o.put("meshId", p.meshId); o.put("place", p.place); o.put("src", p.src);
        return o;
    }

    private static Pin pinFrom(JSONObject o) throws org.json.JSONException {
        Pin p = new Pin();
        p.id = o.getString("id"); p.name = o.optString("name"); p.lat = o.getDouble("lat"); p.lon = o.getDouble("lon");
        p.owner = o.optString("owner"); p.comment = o.optString("comment"); p.time = o.optLong("time");
        p.shared = o.optBoolean("shared");
        p.meshId = o.optInt("meshId");
        p.place = o.optBoolean("place");
        p.src = o.optString("src");
        return p;
    }

    /** My own waypoints and saved places, for a backup file. */
    public JSONArray exportMyPins() throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (Pin p : pins.values()) if (p.isMine()) out.put(pinJson(p));
        return out;
    }

    /** Merges pins from a backup file (same id = replaced). Returns how many were read. */
    public int importPins(JSONArray arr) {
        int n = 0;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            try {
                Pin p = pinFrom(arr.getJSONObject(i));
                if (Double.isNaN(p.lat) || Double.isNaN(p.lon) || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180) continue;
                p.owner = "";
                p.shared = false;   // not shared from this phone yet
                p.meshId = 0;
                pins.put(p.id, p);
                n++;
            } catch (Exception ignored) {
            }
        }
        if (n > 0) save();
        return n;
    }

    /** Writes to storage right now. */
    public void flush() {
        saver.removeCallbacksAndMessages(null);
        savePending = false;
        try {
            JSONArray jp = new JSONArray();
            for (Pin p : pins.values()) jp.put(pinJson(p));
            JSONArray js = new JSONArray();
            for (Station s : stations.values()) {
                JSONObject o = new JSONObject();
                o.put("call", s.call); o.put("lat", s.lat); o.put("lon", s.lon); o.put("time", s.time);
                o.put("table", String.valueOf(s.table)); o.put("code", String.valueOf(s.code)); o.put("comment", s.comment);
                o.put("name", s.name); o.put("via", s.via); o.put("checkTime", s.checkTime);
                js.put(o);
            }
            JSONArray jm = new JSONArray();
            for (Msg m : messages) {
                JSONObject o = new JSONObject();
                o.put("from", m.from); o.put("to", m.to); o.put("text", m.text); o.put("id", m.id == null ? "" : m.id);
                o.put("time", m.time); o.put("out", m.out); o.put("acked", m.acked); o.put("via", m.via);
                jm.put(o);
            }
            JSONArray jsh = new JSONArray();
            for (Shape s : shapes.values()) jsh.put(shapeJson(s));
            sp.edit().putString("pins", jp.toString()).putString("stations", js.toString()).putString("shapes", jsh.toString())
                    .putString("messages", jm.toString()).putInt("unread", unread).apply();
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            JSONArray jp = new JSONArray(sp.getString("pins", "[]"));
            for (int i = 0; i < jp.length(); i++) {
                Pin p = pinFrom(jp.getJSONObject(i));
                pins.put(p.id, p);
            }
            JSONArray js = new JSONArray(sp.getString("stations", "[]"));
            for (int i = 0; i < js.length(); i++) {
                JSONObject o = js.getJSONObject(i);
                Station s = new Station();
                s.call = o.getString("call"); s.lat = o.getDouble("lat"); s.lon = o.getDouble("lon"); s.time = o.optLong("time");
                String t = o.optString("table", "/"), c = o.optString("code", "[");
                s.table = t.isEmpty() ? '/' : t.charAt(0);
                s.code = c.isEmpty() ? '[' : c.charAt(0);
                s.comment = o.optString("comment");
                s.name = o.optString("name");
                s.via = o.optString("via", "APRS");
                s.checkTime = o.optLong("checkTime");
                stations.put(s.call, s);
            }
            JSONArray jm = new JSONArray(sp.getString("messages", "[]"));
            for (int i = 0; i < jm.length(); i++) {
                JSONObject o = jm.getJSONObject(i);
                Msg m = new Msg();
                m.from = o.optString("from"); m.to = o.optString("to"); m.text = o.optString("text");
                String id = o.optString("id");
                m.id = id.isEmpty() ? null : id;
                m.time = o.optLong("time"); m.out = o.optBoolean("out"); m.acked = o.optBoolean("acked");
                m.via = o.optString("via", "APRS");
                messages.add(m);
            }
            unread = sp.getInt("unread", 0);
            JSONArray jsh = new JSONArray(sp.getString("shapes", "[]"));
            for (int i = 0; i < jsh.length(); i++) {
                try {
                    Shape s = shapeFrom(jsh.getJSONObject(i));
                    shapes.put(s.id, s);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Drops stations not heard for the given time. */
    public void pruneStations(long maxAgeMs) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Station> it = stations.values().iterator(); it.hasNext(); ) {
            if (now - it.next().time > maxAgeMs) { it.remove(); changed = true; }
        }
        if (changed) save();
    }
}
