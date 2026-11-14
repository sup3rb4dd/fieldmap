package app.fieldmap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.fieldmap.core.Geo;

/**
 * National Weather Service alerts (api.weather.gov, US only, needs internet).
 * One request asks "what is active where I am"; a second pulls storm warning polygons to draw.
 */
public class Weather {

    public static class Alert {
        public String id = "", event = "", headline = "", area = "", description = "", instruction = "", severity = "";
        public long expires;
        public boolean here;                                   // applies to my location
        public final List<List<double[]>> polygons = new ArrayList<>(); // rings of {lat, lon}

        public boolean active() { return expires == 0 || expires > System.currentTimeMillis(); }

        public int rank() {
            int sev = "Extreme".equals(severity) ? 0 : "Severe".equals(severity) ? 1 : "Moderate".equals(severity) ? 2 : 3;
            return (event.contains("Tornado") ? 0 : 10) + sev;
        }

        /** Worth interrupting someone for. */
        public boolean urgent() {
            return event.endsWith("Warning") || event.contains("Emergency") || "Extreme".equals(severity);
        }

        public int color() {
            if (event.contains("Tornado")) return 0xFFFF3B30;
            if (event.contains("Thunderstorm")) return 0xFFFFB000;
            if (event.contains("Flood")) return 0xFF00C853;
            return 0xFFFFEA00;
        }
    }

    private static final String BASE = "https://api.weather.gov/alerts/active";
    private static final String STORM_EVENTS = "Tornado%20Warning,Severe%20Thunderstorm%20Warning,Flash%20Flood%20Warning";
    private static final double DRAW_RADIUS_M = 400_000;

    public volatile List<Alert> alerts = Collections.emptyList();
    public volatile long lastOk = 0, lastTry = 0;
    public volatile String lastError = null;
    private volatile boolean busy = false;
    private final Set<String> notified = new HashSet<>();
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Weather(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Highest-priority alert for my location, or null. */
    public Alert top() {
        Alert best = null;
        for (Alert a : alerts) {
            if (!a.here || !a.active()) continue;
            if (best == null || a.rank() < best.rank()) best = a;
        }
        return best;
    }

    public int hereCount() {
        int n = 0;
        for (Alert a : alerts) if (a.here && a.active()) n++;
        return n;
    }

    public void refresh(final double lat, final double lon, final Runnable done) {
        if (busy) return;
        busy = true;
        lastTry = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Map<String, Alert> byId = new LinkedHashMap<>();
                String point = String.format(Locale.US, "%.4f,%.4f", lat, lon);
                parse(fetch(BASE + "?status=actual&point=" + point), true, lat, lon, byId);
                try {
                    parse(fetch(BASE + "?status=actual&event=" + STORM_EVENTS), false, lat, lon, byId);
                } catch (Exception regional) {
                    // the map polygons are a bonus; alerts for my own location already loaded
                }
                List<Alert> out = new ArrayList<>(byId.values());
                Collections.sort(out, (a, b) -> Integer.compare(a.rank(), b.rank()));
                alerts = out;
                lastOk = System.currentTimeMillis();
                lastError = null;
                for (Alert a : out) {
                    if (a.here && a.urgent() && a.active() && notified.add(a.id)) Alerts.weather(ctx, a);
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                busy = false;
                if (done != null) main.post(done);
            }
        }, "weather").start();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            // NWS asks every client to identify itself
            con.setRequestProperty("User-Agent", "FieldMap (github.com/sup3rb4dd/fieldmap)");
            con.setRequestProperty("Accept", "application/geo+json");
            int code = con.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 12_000_000) throw new java.io.IOException("response too large");
                }
            }
            return bo.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static void parse(String json, boolean here, double lat, double lon, Map<String, Alert> out) throws Exception {
        JSONArray features = new JSONObject(json).optJSONArray("features");
        if (features == null) return;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            JSONObject p = f == null ? null : f.optJSONObject("properties");
            if (p == null) continue;
            String type = p.optString("messageType");
            if ("Cancel".equalsIgnoreCase(type)) continue;
            String id = p.optString("id", f.optString("id"));
            Alert a = out.get(id);
            if (a == null) {
                a = new Alert();
                a.id = id;
                a.event = str(p, "event");
                a.headline = str(p, "headline");
                a.area = str(p, "areaDesc");
                a.description = str(p, "description");
                a.instruction = str(p, "instruction");
                a.severity = str(p, "severity");
                a.expires = time(str(p, "ends"));
                if (a.expires == 0) a.expires = time(str(p, "expires"));
                JSONObject g = f.isNull("geometry") ? null : f.optJSONObject("geometry");
                if (g != null) readGeometry(g, a);
            }
            if (here) a.here = true;
            if (!a.active()) continue;
            if (!here && !near(a, lat, lon)) continue;
            out.put(id, a);
        }
    }

    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    private static void readGeometry(JSONObject g, Alert a) {
        JSONArray c = g.optJSONArray("coordinates");
        if (c == null) return;
        String type = g.optString("type");
        if ("Polygon".equals(type)) {
            addRing(c.optJSONArray(0), a);
        } else if ("MultiPolygon".equals(type)) {
            for (int i = 0; i < c.length(); i++) {
                JSONArray poly = c.optJSONArray(i);
                if (poly != null) addRing(poly.optJSONArray(0), a);
            }
        }
    }

    private static void addRing(JSONArray ring, Alert a) {
        if (ring == null || ring.length() < 3) return;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < ring.length(); i++) {
            JSONArray xy = ring.optJSONArray(i);
            if (xy == null || xy.length() < 2) continue;
            pts.add(new double[]{xy.optDouble(1), xy.optDouble(0)}); // GeoJSON is lon, lat
        }
        if (pts.size() >= 3) a.polygons.add(pts);
    }

    private static boolean near(Alert a, double lat, double lon) {
        for (List<double[]> ring : a.polygons)
            for (double[] p : ring)
                if (Geo.distance(lat, lon, p[0], p[1]) < DRAW_RADIUS_M) return true;
        return false;
    }

    /** ISO 8601 like 2026-11-14T06:45:00-06:00 → epoch ms, 0 if missing. */
    static long time(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
