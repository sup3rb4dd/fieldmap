package app.fieldmap.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Routes and areas: compact text encoding for sharing over the mesh, length, area, simplification. */
public final class Shapes {
    private Shapes() {}

    /** Longest text FieldMap will put in one mesh message. */
    public static final int MAX_SHARE_CHARS = 200;

    // ---------- polyline encoding (precision 1e-5, about 1 m) ----------

    public static String encode(List<double[]> pts) {
        StringBuilder sb = new StringBuilder();
        long pLat = 0, pLon = 0;
        for (double[] p : pts) {
            long lat = Math.round(p[0] * 1e5), lon = Math.round(p[1] * 1e5);
            enc(lat - pLat, sb);
            enc(lon - pLon, sb);
            pLat = lat;
            pLon = lon;
        }
        return sb.toString();
    }

    private static void enc(long v, StringBuilder sb) {
        v = v < 0 ? ~(v << 1) : (v << 1);
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }

    /** Returns null if the text is not a valid encoding. */
    public static List<double[]> decode(String s) {
        List<double[]> out = new ArrayList<>();
        int i = 0;
        long lat = 0, lon = 0;
        try {
            while (i < s.length()) {
                long[] r = new long[2];
                for (int k = 0; k < 2; k++) {
                    long result = 0;
                    int shift = 0, b;
                    do {
                        b = s.charAt(i++) - 63;
                        if (b < 0 || b > 63 || shift > 40) return null;
                        result |= (long) (b & 0x1f) << shift;
                        shift += 5;
                    } while (b >= 0x20);
                    r[k] = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
                }
                lat += r[0];
                lon += r[1];
                if (Math.abs(lat) > 9000000 || Math.abs(lon) > 18000000) return null;
                out.add(new double[]{lat / 1e5, lon / 1e5});
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        return out;
    }

    // ---------- measurements ----------

    public static double length(List<double[]> pts, boolean closed) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++)
            d += Geo.distance(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
        if (closed && pts.size() > 2)
            d += Geo.distance(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1], pts.get(0)[0], pts.get(0)[1]);
        return d;
    }

    /** Enclosed area in square meters (flat-earth around the shape's center; fine up to county size). */
    public static double area(List<double[]> pts) {
        if (pts.size() < 3) return 0;
        double lat0 = 0;
        for (double[] p : pts) lat0 += p[0];
        lat0 /= pts.size();
        double mLat = 111320.0, mLon = 111320.0 * Math.cos(Math.toRadians(lat0));
        double sum = 0;
        for (int i = 0; i < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
            sum += (a[1] * mLon) * (b[0] * mLat) - (b[1] * mLon) * (a[0] * mLat);
        }
        return Math.abs(sum) / 2;
    }

    public static String formatArea(double m2, boolean imperial) {
        if (imperial) {
            double acres = m2 / 4046.8564;
            if (acres < 640) return String.format(Locale.US, acres < 10 ? "%.2f ac" : "%.0f ac", acres);
            return String.format(Locale.US, "%.1f sq mi", acres / 640);
        }
        double ha = m2 / 10000;
        if (ha < 100) return String.format(Locale.US, ha < 10 ? "%.2f ha" : "%.0f ha", ha);
        return String.format(Locale.US, "%.1f km²", ha / 100);
    }

    public static double[] center(List<double[]> pts) {
        double la = 0, lo = 0;
        for (double[] p : pts) { la += p[0]; lo += p[1]; }
        return new double[]{la / pts.size(), lo / pts.size()};
    }

    // ---------- simplification (Douglas-Peucker) ----------

    public static List<double[]> simplify(List<double[]> pts, double tolM) {
        if (pts.size() < 3) return new ArrayList<>(pts);
        boolean[] keep = new boolean[pts.size()];
        keep[0] = keep[pts.size() - 1] = true;
        dp(pts, 0, pts.size() - 1, tolM, keep);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    private static void dp(List<double[]> pts, int a, int b, double tol, boolean[] keep) {
        // explicit stack: imported tracks can be long
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{a, b});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            double max = 0;
            int idx = -1;
            for (int i = seg[0] + 1; i < seg[1]; i++) {
                double d = crossTrack(pts.get(i), pts.get(seg[0]), pts.get(seg[1]));
                if (d > max) { max = d; idx = i; }
            }
            if (idx >= 0 && max > tol) {
                keep[idx] = true;
                stack.push(new int[]{seg[0], idx});
                stack.push(new int[]{idx, seg[1]});
            }
        }
    }

    /** Distance in meters from p to segment a-b (flat approximation). */
    static double crossTrack(double[] p, double[] a, double[] b) {
        double mLon = Math.cos(Math.toRadians(a[0])) * 111320.0, mLat = 111320.0;
        double px = (p[1] - a[1]) * mLon, py = (p[0] - a[0]) * mLat;
        double bx = (b[1] - a[1]) * mLon, by = (b[0] - a[0]) * mLat;
        double len2 = bx * bx + by * by;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
        double dx = px - t * bx, dy = py - t * by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Thins a line until it has at most maxPoints points. */
    public static List<double[]> limit(List<double[]> pts, int maxPoints) {
        List<double[]> out = pts;
        double tol = 2;
        while (out.size() > maxPoints && tol < 1e6) {
            out = simplify(pts, tol);
            tol *= 1.6;
        }
        return out;
    }

    // ---------- sharing as text ----------

    public static final class Shared {
        public String name;
        public boolean closed;
        public List<double[]> pts;
    }

    private static String cleanName(String n) {
        String s = n == null ? "" : n.replace('%', ' ').replaceAll("\\s+", " ").trim();
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    /**
     * "%RTE Name%<encoded>" or "%AREA Name%<encoded>". The line is thinned as needed to fit
     * one mesh message; returns null if even two points will not fit.
     */
    public static String share(String name, boolean closed, List<double[]> pts) {
        return share(name, closed, pts, MAX_SHARE_CHARS);
    }

    /** Same, for links with a smaller limit (APRS, GMRS). Long names are cut to leave room for the line. */
    public static String share(String name, boolean closed, List<double[]> pts, int maxChars) {
        String nm = cleanName(name);
        if (maxChars < 100 && nm.length() > 8) nm = nm.substring(0, 8).trim();
        String head = (closed ? "%AREA " : "%RTE ") + nm + "%";
        List<double[]> cur = pts;
        double tol = 2;
        while (true) {
            String s = head + encode(cur);
            if (s.length() <= maxChars) return s;
            if (cur.size() <= (closed ? 3 : 2) || tol > 1e6) return null;
            cur = simplify(pts, tol);
            tol *= 1.6;
        }
    }

    public static Shared parseShared(String text) {
        if (text == null) return null;
        boolean area = text.startsWith("%AREA "), rte = text.startsWith("%RTE ");
        if (!area && !rte) return null;
        int start = area ? 6 : 5;
        int end = text.indexOf('%', start);
        if (end < 0) return null;
        List<double[]> pts = decode(text.substring(end + 1).trim());
        if (pts == null || pts.size() < (area ? 3 : 2)) return null;
        Shared s = new Shared();
        s.name = text.substring(start, end).trim();
        if (s.name.isEmpty()) s.name = area ? "AREA" : "ROUTE";
        s.closed = area;
        s.pts = pts;
        return s;
    }
}
