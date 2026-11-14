package app.fieldmap.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Minimal GPX 1.1 writer (one track + waypoints) that other mapping apps can open. */
public final class Gpx {
    private Gpx() {}

    /** track points: {lat, lon, timeMs, elevationM or NaN}; waypoints: name + {lat, lon}. */
    public static String build(String name, List<double[]> track, List<String> wptNames, List<double[]> wpts) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder(64 + track.size() * 90);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"FieldMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (int i = 0; i < wpts.size(); i++) {
            double[] w = wpts.get(i);
            sb.append(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name></wpt>\n",
                    w[0], w[1], esc(wptNames.get(i))));
        }
        if (!track.isEmpty()) {
            sb.append("  <trk><name>").append(esc(name)).append("</name><trkseg>\n");
            for (double[] p : track) {
                sb.append(String.format(Locale.US, "    <trkpt lat=\"%.6f\" lon=\"%.6f\">", p[0], p[1]));
                if (p.length > 3 && !Double.isNaN(p[3])) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p[3]));
                if (p.length > 2 && p[2] > 0) sb.append("<time>").append(iso.format(new Date((long) p[2]))).append("</time>");
                sb.append("</trkpt>\n");
            }
            sb.append("  </trkseg></trk>\n");
        }
        return sb.append("</gpx>\n").toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
