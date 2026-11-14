package app.fieldmap.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Breadcrumb track of where this phone has been, kept in a small CSV file. */
public final class Trail {
    public static final int MAX_POINTS = 20000;
    private static final double MIN_MOVE_M = 15, MAX_ACCURACY_M = 50;

    /** {lat, lon, timeMs, elevationM or NaN} */
    private final List<double[]> pts = new ArrayList<>();
    private final File file;

    public Trail(File file) { this.file = file; }

    public synchronized int size() { return pts.size(); }
    public synchronized double[] get(int i) { return pts.get(i); }
    public synchronized List<double[]> copy() { return new ArrayList<>(pts); }

    /** Adds a fix if it is accurate enough and far enough from the last one. Returns true if added. */
    public synchronized boolean add(double lat, double lon, double ele, long timeMs, float accuracyM) {
        if (accuracyM > MAX_ACCURACY_M || Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (!pts.isEmpty()) {
            double[] last = pts.get(pts.size() - 1);
            // a point must clear both the minimum step and the GPS error, or a parked phone draws a scribble
            if (Geo.distance(last[0], last[1], lat, lon) < Math.max(MIN_MOVE_M, accuracyM)) return false;
        }
        double[] p = {lat, lon, timeMs, ele};
        pts.add(p);
        if (pts.size() > MAX_POINTS) {
            pts.subList(0, MAX_POINTS / 4).clear();
            rewrite();
        } else {
            append(p);
        }
        return true;
    }

    public synchronized void clear() {
        pts.clear();
        if (file != null) file.delete();
    }

    public synchronized void load() {
        pts.clear();
        if (file == null || !file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length < 3) continue;
                try {
                    pts.add(new double[]{Double.parseDouble(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                            f.length > 3 && !f[3].isEmpty() ? Double.parseDouble(f[3]) : Double.NaN});
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        if (pts.size() > MAX_POINTS) {
            pts.subList(0, pts.size() - MAX_POINTS).clear();
            rewrite();
        }
    }

    /** Total length in meters. */
    public synchronized double length() {
        double d = 0;
        for (int i = 1; i < pts.size(); i++)
            d += Geo.distance(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
        return d;
    }

    private static String line(double[] p) {
        return String.format(Locale.US, "%.6f,%.6f,%d,%s\n", p[0], p[1], (long) p[2],
                Double.isNaN(p[3]) ? "" : String.format(Locale.US, "%.1f", p[3]));
    }

    private void append(double[] p) {
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, true)) { w.write(line(p)); } catch (Exception ignored) { }
    }

    private void rewrite() {
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, false)) {
            for (double[] p : pts) w.write(line(p));
        } catch (Exception ignored) {
        }
    }
}
