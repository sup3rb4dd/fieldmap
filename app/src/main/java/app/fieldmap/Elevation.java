package app.fieldmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.fieldmap.core.Terrain;

/**
 * Ground elevation from the public "Terrain Tiles" dataset (Mapzen/AWS open data, Terrarium PNGs).
 * Tiles are kept on the phone after the first download, so areas you have looked at work offline.
 * Zoom 12 tiles: one height about every 100 ft at this latitude.
 */
public class Elevation {

    public interface ProfileCallback { void onProfile(float[] heights); }
    public interface CountCallback { void onDone(int ok, int failed); }

    public static final int Z = 12;
    private static final String URL_FMT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png";
    private static final long RETRY_FAILED_MS = 60_000L;

    private final File dir;
    private final LruCache<Long, short[]> mem = new LruCache<>(24);
    private final Map<Long, Long> failedAt = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    public Elevation(Context ctx) {
        dir = new File(ctx.getFilesDir(), "elev/" + Z);
        dir.mkdirs();
    }

    // ---------- tile math ----------

    private static double tileX(double lon) { return (lon + 180) / 360 * (1 << Z); }

    private static double tileY(double lat) {
        double r = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << Z);
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    private static float sample(short[] t, double fx, double fy) {
        // bilinear inside the tile; edges clamp
        double px = Math.max(0, Math.min(255, fx * 256 - 0.5)), py = Math.max(0, Math.min(255, fy * 256 - 0.5));
        int x0 = (int) px, y0 = (int) py, x1 = Math.min(255, x0 + 1), y1 = Math.min(255, y0 + 1);
        double ax = px - x0, ay = py - y0;
        double top = t[y0 * 256 + x0] * (1 - ax) + t[y0 * 256 + x1] * ax;
        double bot = t[y1 * 256 + x0] * (1 - ax) + t[y1 * 256 + x1] * ax;
        return (float) (top * (1 - ay) + bot * ay);
    }

    // ---------- lookups ----------

    /** Height in meters if that tile is already in memory, else null (call request() to load it). */
    public Float cached(double lat, double lon) {
        double tx = tileX(lon), ty = tileY(lat);
        short[] t = mem.get(key((int) tx, (int) ty));
        return t == null ? null : sample(t, tx - Math.floor(tx), ty - Math.floor(ty));
    }

    /** Loads the tile under a point in the background, then runs onLoaded on the main thread. */
    public void request(double lat, double lon, final Runnable onLoaded) {
        final int x = (int) tileX(lon), y = (int) tileY(lat);
        final long k = key(x, y);
        synchronized (this) {
            if (mem.get(k) != null || loading.contains(k)) return;
            Long f = failedAt.get(k);
            if (f != null && System.currentTimeMillis() - f < RETRY_FAILED_MS) return;
            loading.add(k);
        }
        pool.execute(() -> {
            short[] t = load(x, y, true);
            synchronized (Elevation.this) { loading.remove(k); }
            if (t != null && onLoaded != null) main.post(onLoaded);
        });
    }

    /** Heights at evenly spaced points from A to B (NaN where no data). Result arrives on the main thread. */
    public void profile(final double lat1, final double lon1, final double lat2, final double lon2,
                        final int n, final ProfileCallback cb) {
        pool.execute(() -> {
            final float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                double t = n == 1 ? 0 : i / (double) (n - 1);
                double lat = lat1 + (lat2 - lat1) * t, lon = lon1 + (lon2 - lon1) * t;
                double tx = tileX(lon), ty = tileY(lat);
                short[] tile = load((int) tx, (int) ty, true);
                out[i] = tile == null ? Float.NaN : sample(tile, tx - Math.floor(tx), ty - Math.floor(ty));
            }
            main.post(() -> cb.onProfile(out));
        });
    }

    /** Downloads every tile covering a box (for offline use). Skips ones already saved. */
    public void prefetch(double north, double south, double east, double west, final CountCallback cb) {
        final int x0 = (int) tileX(west), x1 = (int) tileX(east), y0 = (int) tileY(north), y1 = (int) tileY(south);
        pool.execute(() -> {
            int ok = 0, bad = 0;
            for (int x = x0; x <= x1 && ok + bad < 400; x++) {
                for (int y = y0; y <= y1 && ok + bad < 400; y++) {
                    if (file(x, y).exists() || download(x, y)) ok++; else bad++;
                }
            }
            final int fo = ok, fb = bad;
            if (cb != null) main.post(() -> cb.onDone(fo, fb));
        });
    }

    public static int tilesInBox(double north, double south, double east, double west) {
        return ((int) tileX(east) - (int) tileX(west) + 1) * ((int) tileY(south) - (int) tileY(north) + 1);
    }

    // ---------- storage ----------

    private File file(int x, int y) { return new File(dir, x + "_" + y + ".png"); }

    private short[] load(int x, int y, boolean allowNetwork) {
        int max = 1 << Z;
        if (x < 0 || y < 0 || x >= max || y >= max) return null;
        long k = key(x, y);
        short[] t = mem.get(k);
        if (t != null) return t;
        File f = file(x, y);
        if (!f.exists()) {
            Long failed;
            synchronized (this) { failed = failedAt.get(k); }
            if (!allowNetwork || (failed != null && System.currentTimeMillis() - failed < RETRY_FAILED_MS)) return null;
            if (!download(x, y)) return null;
        }
        t = decode(f);
        if (t == null) { f.delete(); return null; }
        mem.put(k, t);
        return t;
    }

    private boolean download(int x, int y) {
        HttpURLConnection con = null;
        File f = file(x, y), tmp = new File(dir, x + "_" + y + ".tmp" + Thread.currentThread().getId());
        try {
            con = (HttpURLConnection) new URL(String.format(java.util.Locale.US, URL_FMT, Z, x, y)).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent", "FieldMap (github.com/sup3rb4dd/fieldmap)");
            if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 2_000_000) throw new java.io.IOException("tile too large");
                }
            }
            if (!tmp.renameTo(f)) { tmp.delete(); return f.exists(); }
            synchronized (this) { failedAt.remove(key(x, y)); }
            return true;
        } catch (Exception e) {
            tmp.delete();
            synchronized (this) { failedAt.put(key(x, y), System.currentTimeMillis()); }
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static short[] decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            o.inPremultiplied = false;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return null;
            if (b.getWidth() != 256 || b.getHeight() != 256) { b.recycle(); return null; }
            int[] px = new int[65536];
            b.getPixels(px, 0, 256, 0, 0, 256, 256);
            b.recycle();
            short[] out = new short[65536];
            for (int i = 0; i < 65536; i++) {
                int p = px[i];
                float h = Terrain.terrarium((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
                out[i] = (short) Math.max(-12000, Math.min(9000, Math.round(h)));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
