package app.fieldmap.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ask Lantern: reads the replies Lantern Relay sends over Meshtastic and puts
 * articles back together from their parts. The reply formats are defined in
 * Lantern's RELAY.md (github.com/sup3rb4dd/lantern).
 */
public final class Ask {
    private Ask() {}

    // ---------- commands (sent as direct messages to the gateway node) ----------

    public static String search(String words) { return "?s " + words.trim(); }
    public static String open(int n) { return "?" + n; }
    public static String more() { return "?more"; }
    public static String toc() { return "?toc"; }
    public static String go(int section) { return "?go " + section; }

    /** A Meshtastic node name that looks like a Lantern gateway. */
    public static boolean looksLikeGateway(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return name.contains("🤖") || n.contains("lantern") || n.contains("relay");
    }

    // ---------- replies ----------

    public enum Kind { RESULTS, PART, SECTIONS, NOTICE, OTHER }

    public static final class Result {
        public int num;
        public String title = "", source = "";
        public Result() { }
        public Result(int num, String title, String source) { this.num = num; this.title = title; this.source = source; }
    }

    public static final class Section {
        public int num;
        public String title = "";
        public Section() { }
        public Section(int num, String title) { this.num = num; this.title = title; }
    }

    public static final class Reply {
        public Kind kind = Kind.OTHER;
        public String query = "";
        public final List<Result> results = new ArrayList<>();
        public boolean noMatches;
        /** PART and SECTIONS: this message is part p of P. */
        public int part, total;
        /** PART: the heading line on part 1 of a section, otherwise null. */
        public String heading;
        public String text = "";
        public final List<Section> sections = new ArrayList<>();
    }

    private static final Pattern RESULT = Pattern.compile("^(\\d+) (.+) \\[([^\\]]+)\\]$");
    private static final Pattern PART = Pattern.compile("^\\[(\\d+)/(\\d+)\\] ?(.*)$", Pattern.DOTALL);
    private static final Pattern TOC = Pattern.compile("^☰ (\\d+)/(\\d+)\\n?(.*)$", Pattern.DOTALL);
    private static final Pattern SECTION_HEAD = Pattern.compile("^\u00a7(\\d+)? ?(.*)$", Pattern.DOTALL);
    private static final Pattern LIST_ITEM = Pattern.compile("^(\u2022 |\\(?[0-9]{1,2}[.)] |\\(?[a-zA-Z][.)] )");
    private static final Pattern TOC_LINE = Pattern.compile("^(\\d+) (.+)$");
    private static final String SEARCH_MARK = "🔎 "; // 🔎

    public static Reply parse(String msg) {
        Reply r = new Reply();
        if (msg == null) return r;
        if (msg.startsWith(SEARCH_MARK)) {
            r.kind = Kind.RESULTS;
            String[] lines = msg.substring(SEARCH_MARK.length()).split("\n");
            r.query = lines[0].trim();
            for (int i = 1; i < lines.length; i++) {
                Matcher m = RESULT.matcher(lines[i].trim());
                if (m.matches()) r.results.add(new Result(Integer.parseInt(m.group(1)), m.group(2), m.group(3)));
                else if (lines[i].startsWith("No matches")) r.noMatches = true;
            }
            return r;
        }
        Matcher m = PART.matcher(msg);
        if (m.matches()) {
            r.kind = Kind.PART;
            r.part = Integer.parseInt(m.group(1));
            r.total = Integer.parseInt(m.group(2));
            String body = m.group(3);
            if (r.part == 1) {
                int nl = body.indexOf('\n');
                r.heading = (nl < 0 ? body : body.substring(0, nl)).trim();
                body = nl < 0 ? "" : body.substring(nl + 1);
            }
            r.text = body.trim();
            return r;
        }
        m = TOC.matcher(msg);
        if (m.matches()) {
            r.kind = Kind.SECTIONS;
            r.part = Integer.parseInt(m.group(1));
            r.total = Integer.parseInt(m.group(2));
            for (String line : m.group(3).split("\n")) {
                Matcher t = TOC_LINE.matcher(line.trim());
                if (t.matches()) r.sections.add(new Section(Integer.parseInt(t.group(1)), t.group(2)));
            }
            return r;
        }
        if (msg.startsWith("Lantern")) {
            r.kind = Kind.NOTICE;
            r.text = msg.startsWith("Lantern: ") ? msg.substring(9) : msg;
            return r;
        }
        r.text = msg;
        return r;
    }

    // ---------- an article, put back together ----------

    public static final class Article {
        public String title = "", source = "";
        public long time;
        /** Section number -> section. */
        public final TreeMap<Integer, Sec> secs = new TreeMap<>();
        /** Section list from ?toc, empty until asked. */
        public final List<Section> toc = new ArrayList<>();
        /** The section the most recent part belonged to. */
        public int current = 0;

        public static final class Sec {
            public String title = "";
            public String[] parts = new String[0];

            public int received() {
                int n = 0;
                for (String p : parts) if (p != null) n++;
                return n;
            }
            public boolean complete() { return parts.length > 0 && received() == parts.length; }
        }

        public Article() { }
        public Article(String title, String source) { this.title = title; this.source = source; }

        public String key() { return source + "|" + title; }

        /**
         * Adds one PART reply. expectSection is the section asked for with ?1 or ?go
         * (or -1 after ?more, when the gateway may have moved on to the next section).
         */
        public void add(Reply r, int expectSection) {
            if (r.kind != Kind.PART || r.part < 1 || r.total < 1 || r.part > r.total) return;
            int idx = current;
            String secTitle = null;
            if (r.part == 1 && r.heading != null) {
                Matcher h = SECTION_HEAD.matcher(r.heading);
                if (h.matches()) { // "§3 Name" (or "§ Name" from older Lantern versions)
                    secTitle = h.group(2).trim();
                    int known = sectionNumber(secTitle);
                    idx = h.group(1) != null ? Integer.parseInt(h.group(1))
                            : expectSection >= 0 ? expectSection : known >= 0 ? known : current + 1;
                } else {
                    // section 0 starts with "Title [Source]"
                    idx = 0;
                    secTitle = "Summary";
                    String suffix = " [" + source + "]";
                    if (!source.isEmpty() && r.heading.endsWith(suffix)) title = r.heading.substring(0, r.heading.length() - suffix.length());
                    else if (title.isEmpty()) title = r.heading;
                }
            }
            Sec s = secs.get(idx);
            if (s == null) { s = new Sec(); secs.put(idx, s); }
            if (secTitle != null) s.title = secTitle;
            if (s.parts.length != r.total) {
                String[] p = new String[r.total];
                // a different length means the gateway resent this section: keep what still fits
                if (r.part != 1) System.arraycopy(s.parts, 0, p, 0, Math.min(s.parts.length, p.length));
                s.parts = p;
            }
            s.parts[r.part - 1] = r.text;
            current = idx;
        }

        public void addSections(Reply r) {
            for (Section s : r.sections) {
                boolean have = false;
                for (Section t : toc) if (t.num == s.num) { t.title = s.title; have = true; }
                if (!have) toc.add(s);
            }
            toc.sort((a, b) -> Integer.compare(a.num, b.num));
        }

        public int sectionNumber(String name) {
            for (Section s : toc) if (s.title.equalsIgnoreCase(name)) return s.num;
            for (Map.Entry<Integer, Sec> e : secs.entrySet()) if (e.getValue().title.equalsIgnoreCase(name)) return e.getKey();
            return -1;
        }

        public String sectionTitle(int n) {
            Sec s = secs.get(n);
            if (s != null && !s.title.isEmpty()) return s.title;
            for (Section t : toc) if (t.num == n) return t.title;
            return n == 0 ? "Summary" : "Section " + n;
        }

        /** Readable text of everything received, sections in order, with gaps marked. */
        public String text() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Integer, Sec> e : secs.entrySet()) {
                Sec s = e.getValue();
                if (b.length() > 0) b.append("\n\n");
                if (e.getKey() != 0) b.append("§ ").append(s.title.toUpperCase(Locale.US)).append("\n");
                b.append(sectionText(s));
            }
            return b.toString();
        }

        /**
         * The text of a section as received so far. A part that never arrived between
         * two that did is marked as missing; parts not sent yet are left off (see notYetSent).
         */
        public static String sectionText(Sec s) {
            int last = -1;
            for (int i = 0; i < s.parts.length; i++) if (s.parts[i] != null) last = i;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i <= last; i++) {
                String p = s.parts[i] == null ? "[\u2026 missing \u2026]" : s.parts[i];
                // a part that starts a list item starts a new line, as it did in the book
                if (b.length() > 0) b.append(LIST_ITEM.matcher(p).find() ? '\n' : ' ');
                b.append(p);
            }
            return b.toString();
        }

        /** Parts after the last one received: what MORE will bring. */
        public static int notYetSent(Sec s) {
            int last = -1;
            for (int i = 0; i < s.parts.length; i++) if (s.parts[i] != null) last = i;
            return s.parts.length - 1 - last;
        }

        /** True if the current section still has parts the gateway hasn't sent. */
        public boolean currentIncomplete() {
            Sec s = secs.get(current);
            return s != null && !s.complete();
        }
    }
}
