package app.fieldmap.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text conventions for team traffic. Everything stays human-readable so people on
 * the stock Meshtastic app or any APRS client can still read it.
 */
public final class Tac {
    private Tac() {}

    private static final Pattern GRID = Pattern.compile(
            "(?<![A-Za-z0-9])(\\d{1,2}[C-HJ-NP-Xc-hj-np-x]\\s?[A-HJ-NP-Za-hj-np-z][A-HJ-NP-Va-hj-np-v]"
                    + "\\s?(?:\\d{5}\\s?\\d{5}|\\d{4}\\s?\\d{4}|\\d{3}\\s?\\d{3}|\\d{2}\\s?\\d{2}))(?!\\d)");

    /** Finds the first MGRS grid in free text. Returns {lat, lon} or null. */
    public static double[] findGrid(String text) {
        if (text == null) return null;
        Matcher m = GRID.matcher(text);
        while (m.find()) {
            double[] ll = Mgrs.parse(m.group(1));
            if (ll != null) return ll;
        }
        return null;
    }

    private static String name(String n) {
        String s = n == null ? "" : n.replaceAll("[^\\p{L}\\p{N} _\\-!]", "").trim();
        if (s.length() > 16) s = s.substring(0, 16);
        return s.toUpperCase(Locale.US);
    }

    /** "*SOS* ELIJAH @ 14S PE 21688 36396" (grid left out when there is no fix). */
    public static String sos(String name, double lat, double lon) {
        String g = Double.isNaN(lat) ? "" : Mgrs.format(lat, lon);
        return ("*SOS* " + name(name) + (g.isEmpty() ? " NO GPS FIX" : " @ " + g)).trim();
    }

    public static String sosCancel(String name) {
        return ("*SOS CANCEL* " + name(name) + " OK").trim();
    }

    public static boolean isSosCancel(String text) {
        return text != null && text.trim().toUpperCase(Locale.US).startsWith("*SOS CANCEL*");
    }

    public static boolean isSos(String text) {
        return text != null && !isSosCancel(text) && text.trim().toUpperCase(Locale.US).startsWith("*SOS*");
    }

    /** "CHK OK @ 14S PE 21688 36396" */
    public static String checkIn(String status, double lat, double lon) {
        return withGrid("CHK " + status.toUpperCase(Locale.US), lat, lon);
    }

    public static boolean isCheckIn(String text) {
        return text != null && text.trim().toUpperCase(Locale.US).startsWith("CHK ");
    }

    /** Appends " @ grid" unless there is no fix. */
    public static String withGrid(String text, double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return text;
        String g = Mgrs.format(lat, lon);
        return g.isEmpty() ? text : text + " @ " + g;
    }
}
