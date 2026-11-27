package app.fieldmap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import app.fieldmap.core.Geo;
import app.fieldmap.core.Repeaters;
import app.fieldmap.core.Repeaters.Repeater;

/**
 * Repeaters shown on the map, saved on the phone so they work with no signal:
 * states downloaded from HearHam.live, a "near me" download, imported CSV lists and
 * ones typed in by hand.
 */
public class RepeaterStore {
    public interface Done { void done(String error, int count); }

    public static final String NEARBY = "NEAR";
    public static final String MINE = "MINE";

    private final File file;
    private final List<Repeater> all = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    RepeaterStore(Context c) {
        file = new File(c.getFilesDir(), "repeaters.tsv");
        load();
    }

    public synchronized List<Repeater> all() { return new ArrayList<>(all); }

    public synchronized int size() { return all.size(); }

    public synchronized Repeater get(String id) {
        for (Repeater r : all) if (r.id.equals(id)) return r;
        return null;
    }

    public boolean busy() { return busy; }

    /** Region code -> number of repeaters, in the order they were added. */
    public synchronized Map<String, Integer> regions() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Repeater r : all) m.put(r.region, m.getOrDefault(r.region, 0) + 1);
        return m;
    }

    public static String regionName(String code) {
        if (NEARBY.equals(code)) return "Near me";
        if (MINE.equals(code)) return "Added by hand";
        Object[] s = Repeaters.state(code);
        return s != null ? (String) s[1] : code;
    }

    public synchronized void add(Repeater r) {
        if (r.id.isEmpty()) r.id = "my:" + UUID.randomUUID().toString().substring(0, 8);
        if (r.region.isEmpty()) r.region = MINE;
        all.add(r);
        save();
    }

    public synchronized void remove(String id) {
        all.removeIf(r -> r.id.equals(id));
        save();
    }

    public synchronized void removeRegion(String region) {
        all.removeIf(r -> r.region.equals(region));
        save();
    }

    /** Replaces a region's repeaters (a fresh download or a re-imported file). */
    private synchronized void replaceRegion(String region, List<Repeater> list) {
        all.removeIf(r -> r.region.equals(region));
        all.addAll(list);
        save();
    }

    public synchronized Repeaters.ImportResult importCsv(String text, String name) {
        Repeaters.ImportResult res = Repeaters.parseCsv(text, name);
        if (!res.repeaters.isEmpty()) replaceRegion(name, res.repeaters);
        return res;
    }

    /** Downloads a state's repeaters (code like "OK") from HearHam. */
    public void downloadState(String code, Done done) {
        Object[] s = Repeaters.state(code);
        if (s == null) { done.done("Unknown state " + code, 0); return; }
        download(code, (lat, lon) -> Repeaters.inState(s, lat, lon), done);
    }

    /** Downloads every repeater within radiusKm of a point. */
    public void downloadNear(double lat0, double lon0, double radiusKm, Done done) {
        download(NEARBY, (lat, lon) -> Geo.distance(lat0, lon0, lat, lon) <= radiusKm * 1000, done);
    }

    private void download(String region, Repeaters.Filter filter, Done done) {
        if (busy) { done.done("A download is already running", 0); return; }
        busy = true;
        new Thread(() -> {
            String err = null;
            int n = 0;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(Repeaters.HEARHAM_URL).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(20_000);
                con.setReadTimeout(60_000);
                con.setRequestProperty("User-Agent", "FieldMap (github.com/sup3rb4dd/fieldmap)");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Accept-Encoding", "gzip");
                int code = con.getResponseCode();
                if (code == 429) throw new Exception("HearHam is busy (too many requests). Try again in a minute.");
                if (code != 200) throw new Exception("HearHam answered " + code);
                InputStream in = con.getInputStream();
                if ("gzip".equalsIgnoreCase(con.getContentEncoding())) in = new GZIPInputStream(in);
                List<Repeater> list;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024)) {
                    list = Repeaters.parseHearHam(r, region, filter);
                }
                replaceRegion(region, list);
                n = list.size();
            } catch (java.net.UnknownHostException e) {
                err = "No internet connection";
            } catch (java.net.SocketTimeoutException e) {
                err = "HearHam didn't answer in time";
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (con != null) con.disconnect();
                busy = false;
            }
            final String fe = err;
            final int fn = n;
            main.post(() -> done.done(fe, fn));
        }, "repeaters").start();
    }

    private synchronized void load() {
        all.clear();
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Repeater x = Repeaters.fromLine(line);
                if (x != null) all.add(x);
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void save() {
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Repeater r : all) w.write(Repeaters.toLine(r) + "\n");
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }
}
