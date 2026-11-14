package app.fieldmap.core;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;

/**
 * GMRS data mode. FCC rules (47 CFR 95.1787) allow short digital bursts on GMRS: location and
 * brief text, at most one second long, at most one every thirty seconds. Everything here exists
 * to keep the app inside those limits.
 */
public final class Gmrs {
    private Gmrs() {}

    /** Longest APRS text in one burst: with the 18-byte frame around it this is about 0.65 s at 1200 baud. */
    public static final int MAX_INFO = 72;
    /** Longest chat text: a message carries an 11-character address in front. */
    public static final int MAX_TEXT = MAX_INFO - 11;
    /** Minimum time between two bursts. */
    public static final long GAP_MS = 30_000L;
    public static final int MAX_QUEUE = 8;

    public static String cleanCall(String s) {
        String c = s == null ? "" : s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        return c.length() > 7 ? c.substring(0, 7) : c;
    }

    /** GMRS callsigns look like WRXX123 (newer) or KAA1234 (older). */
    public static boolean validCall(String c) {
        return c != null && (c.matches("[A-Z]{4}[0-9]{3}") || c.matches("[A-Z]{3}[0-9]{4}"));
    }

    /** Short name for this radio (one licence often covers a whole family), six characters at most. */
    public static String cleanUnit(String s) {
        String c = s == null ? "" : s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        return c.length() > 6 ? c.substring(0, 6) : c;
    }

    /**
     * A packet address only has room for six characters, and a GMRS callsign has seven. The last
     * one is always a digit, so it rides in the address's number field (digit + 1).
     */
    public static String destFor(String call) {
        String c = cleanCall(call);
        if (c.length() == 7 && Character.isDigit(c.charAt(6))) return c.substring(0, 6) + "-" + (c.charAt(6) - '0' + 1);
        return c.length() > 6 ? c.substring(0, 6) : c;
    }

    /** Reverses {@link #destFor}. */
    public static String callFrom(String dest) {
        if (dest == null) return "";
        String d = dest.replace("*", "").trim().toUpperCase(Locale.US);
        int dash = d.indexOf('-');
        if (dash < 0) return d;
        String base = d.substring(0, dash);
        try {
            int n = Integer.parseInt(d.substring(dash + 1));
            if (base.length() == 6 && n >= 1 && n <= 10) return base + (n - 1);
        } catch (NumberFormatException ignored) { }
        return base;
    }

    /** Rough seconds on the air for one burst, including the radio's key-up delay. */
    public static double airtime(int infoBytes, int txDelayMs) {
        return (18 + infoBytes + 3) * 8 * 1.04 / 1200.0 + txDelayMs / 1000.0;
    }

    /** Holds outgoing bursts and lets one go every thirty seconds. */
    public static final class Queue {
        private final ArrayDeque<String[]> q = new ArrayDeque<>();
        private long lastTx = -GAP_MS;

        /**
         * kind "pos" replaces any position still waiting (only the newest matters).
         * urgent (SOS) goes to the front and is never refused for a full queue.
         */
        public synchronized boolean offer(String kind, String info, boolean urgent) {
            if (info == null || info.length() > MAX_INFO) return false;
            if ("pos".equals(kind)) {
                for (Iterator<String[]> it = q.iterator(); it.hasNext(); ) if ("pos".equals(it.next()[0])) it.remove();
            }
            if (urgent) {
                // an SOS already waiting is replaced by the newer one (it carries a newer position)
                for (Iterator<String[]> it = q.iterator(); it.hasNext(); ) if ("sos".equals(it.next()[0])) it.remove();
                q.addFirst(new String[]{kind, info});
                return true;
            }
            if (q.size() >= MAX_QUEUE) return false;
            q.addLast(new String[]{kind, info});
            return true;
        }

        /** The next burst if thirty seconds have passed since the last one, otherwise null. */
        public synchronized String poll(long now) {
            if (q.isEmpty() || now - lastTx < GAP_MS) return null;
            lastTx = now;
            return q.pollFirst()[1];
        }

        public synchronized long waitMs(long now) { return Math.max(0, GAP_MS - (now - lastTx)); }
        public synchronized int size() { return q.size(); }
        public synchronized void clear() { q.clear(); }
        /** The last burst never went on the air (link test): the next one need not wait. */
        public synchronized void forgetLast() { lastTx = -GAP_MS; }
        /** A burst was sent outside the queue (link test): it counts toward the thirty seconds. */
        public synchronized void markSent(long now) { lastTx = now; }
    }
}
