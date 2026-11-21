package app.fieldmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.fieldmap.core.Ask;
import app.fieldmap.core.Mesh;

/**
 * Ask Lantern: questions go to a Lantern Relay gateway node as Meshtastic direct
 * messages, and the answers come back here instead of into team chat. Every
 * article received is saved on the phone so it can be read again with no signal.
 */
public class Asker {

    public static final int MODE_RESULTS = 0, MODE_READ = 1, MODE_SECTIONS = 2;
    private static final int MAX_SAVED = 60;
    private static final java.util.regex.Pattern HEADING = java.util.regex.Pattern.compile("^(.+) \\[([^\\]]+)\\]$");

    private final FieldMapApp app;
    private final File savedFile;

    public String query = "";
    public final List<Ask.Result> results = new ArrayList<>();
    public boolean noMatches;
    public Ask.Article article;     // what's being read (live or saved)
    public boolean offline;         // reading a saved copy, not asking the gateway
    public int mode = MODE_RESULTS;
    public String notice = "";

    // the last question sent
    public String lastCmd = "";
    public long lastSent, lastReply;
    public int lastPid;
    public boolean heard;           // the mesh confirmed the gateway got it
    public int repliesSinceSent;
    private int expectSection = -1;

    public final List<Ask.Article> saved = new ArrayList<>();

    Asker(FieldMapApp app) {
        this.app = app;
        savedFile = new File(app.getFilesDir(), "ask_saved.json");
        load();
    }

    // ---------- the gateway node ----------

    /**
     * The gateway node the user picked ("!a1b2c3d4"), or "" until they pick one.
     * Never guessed: a stranger's node named "... Relay" nearby would otherwise
     * get the questions.
     */
    public String gateway() {
        return app.prefs.getString("askGateway", "");
    }

    public void setGateway(String id) {
        app.prefs.edit().putString("askGateway", id == null ? "" : id.toLowerCase(Locale.US)).apply();
    }

    public boolean isGateway(String nodeId) {
        String g = gateway();
        return !g.isEmpty() && g.equalsIgnoreCase(nodeId);
    }

    // ---------- asking ----------

    /** Sends a command to the gateway. Returns an error to show, or null if it went out. */
    private String send(String cmd) {
        String g = gateway();
        if (g.isEmpty()) return "Pick the Lantern gateway node first";
        if (!app.mesh.isConnected()) return "Meshtastic radio not connected";
        int num;
        try { num = (int) Long.parseLong(g.substring(1), 16); } catch (RuntimeException e) { return "Bad gateway node id " + g; }
        int pid = app.newPacketId();
        if (!app.mesh.send(Mesh.text(num, app.meshChannel(), cmd, pid))) return "Send failed. See the packet log";
        app.log("ASK TX " + cmd + " to " + g);
        lastCmd = cmd;
        lastPid = pid;
        lastSent = System.currentTimeMillis();
        heard = false;
        repliesSinceSent = 0;
        notice = "";
        offline = false;
        app.notifyData();
        return null;
    }

    public String search(String words) {
        String err = send(Ask.search(words));
        if (err != null) return err;
        query = words.trim();
        results.clear();
        noMatches = false;
        mode = MODE_RESULTS;
        return null;
    }

    public String moreResults() {
        String err = send(Ask.more());
        if (err == null) mode = MODE_RESULTS;
        return err;
    }

    public String open(Ask.Result r) {
        String err = send(Ask.open(r.num));
        if (err != null) return err;
        Ask.Article a = findSaved(r.source, r.title);
        article = a != null ? a : new Ask.Article(r.title, r.source);
        article.time = System.currentTimeMillis();
        expectSection = 0;
        mode = MODE_READ;
        return null;
    }

    public String more() {
        String err = send(Ask.more());
        if (err == null) { expectSection = -1; mode = MODE_READ; }
        return err;
    }

    /** Shows the section list; asks the gateway only if we don't have it yet. */
    public String sections() {
        if (article == null) return "Open a result first";
        mode = MODE_SECTIONS;
        if (!article.toc.isEmpty() || offline) { app.notifyData(); return null; }
        return send(Ask.toc());
    }

    public String go(int section) {
        String err = send(Ask.go(section));
        if (err == null) { expectSection = section; mode = MODE_READ; }
        return err;
    }

    public void openSaved(Ask.Article a) {
        article = a;
        offline = true;
        mode = MODE_READ;
        app.notifyData();
    }

    // ---------- answers ----------

    /** A direct message from the gateway node. */
    void onReply(String text) {
        lastReply = System.currentTimeMillis();
        repliesSinceSent++;
        heard = true;
        Ask.Reply r = Ask.parse(text);
        app.log("ASK RX " + r.kind + (r.kind == Ask.Kind.PART ? " " + r.part + "/" + r.total : ""));
        switch (r.kind) {
            case RESULTS:
                if (!r.query.isEmpty()) query = r.query;
                for (Ask.Result x : r.results) {
                    boolean dup = false;
                    for (Ask.Result y : results) if (y.num == x.num) { dup = true; break; }
                    if (!dup) results.add(x);
                }
                results.sort((a, b) -> Integer.compare(a.num, b.num));
                noMatches = r.noMatches && results.isEmpty();
                break;
            case PART:
                if (article == null || offline) {
                    // an answer for something not opened on this screen (or asked from chat)
                    article = new Ask.Article("Lantern answer", "");
                    java.util.regex.Matcher m = r.heading == null ? null : HEADING.matcher(r.heading);
                    if (m != null && m.matches()) { article.title = m.group(1); article.source = m.group(2); }
                    offline = false;
                }
                article.add(r, r.part == 1 ? expectSection : -1);
                if (r.part == 1) expectSection = -1;
                article.time = System.currentTimeMillis();
                keep(article);
                break;
            case SECTIONS:
                if (article != null) { article.addSections(r); keep(article); }
                break;
            case NOTICE:
            case OTHER:
                notice = r.text;
                break;
        }
        if (!Alerts.askVisible) Alerts.ask(app, r.kind == Ask.Kind.PART && article != null ? article.title : "Lantern answered", shortText(r));
        app.notifyData();
    }

    void onAck(int requestId, int error) {
        if (requestId == lastPid && error == 0 && !heard) { heard = true; app.notifyData(); }
    }

    private static String shortText(Ask.Reply r) {
        switch (r.kind) {
            case RESULTS: return r.noMatches ? "No matches for " + r.query : r.results.size() + " results for " + r.query;
            case PART: return r.text;
            case SECTIONS: return r.sections.size() + " sections";
            default: return r.text;
        }
    }

    /** One line for the status bar. */
    public String status() {
        if (offline) return "SAVED COPY · READING OFFLINE";
        if (lastSent == 0) return "READY";
        long now = System.currentTimeMillis();
        long since = (now - lastSent) / 1000;
        if (repliesSinceSent > 0) {
            String part = "";
            if (mode == MODE_READ && article != null) {
                Ask.Article.Sec s = article.secs.get(article.current);
                if (s != null) part = " · PART " + s.received() + " OF " + s.parts.length;
            }
            return "RECEIVED " + repliesSinceSent + part + ((now - lastReply) < 40_000 ? " · MORE MAY BE COMING" : "");
        }
        if (heard) return "GATEWAY GOT IT · WAITING FOR ANSWER (" + since + " S)";
        if (since > 90) return "NO RECEIPT AFTER " + since + " S · GATEWAY OFF OR OUT OF RANGE?";
        return "SENT " + lastCmd.toUpperCase(Locale.US) + " · WAITING (" + since + " S)";
    }

    public boolean waiting() {
        return !offline && lastSent > 0 && System.currentTimeMillis() - Math.max(lastSent, lastReply) < 120_000;
    }

    // ---------- saved answers ----------

    private Ask.Article findSaved(String source, String title) {
        for (Ask.Article a : saved) if (a.source.equalsIgnoreCase(source) && a.title.equalsIgnoreCase(title)) return a;
        return null;
    }

    private void keep(Ask.Article a) {
        saved.remove(a);
        Ask.Article same = findSaved(a.source, a.title);
        if (same != null) saved.remove(same);
        saved.add(0, a);
        while (saved.size() > MAX_SAVED) saved.remove(saved.size() - 1);
        save();
    }

    public void delete(Ask.Article a) {
        saved.remove(a);
        save();
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Ask.Article a : saved) {
                JSONObject o = new JSONObject().put("title", a.title).put("source", a.source).put("time", a.time);
                JSONArray secs = new JSONArray();
                for (Map.Entry<Integer, Ask.Article.Sec> e : a.secs.entrySet()) {
                    JSONArray parts = new JSONArray();
                    for (String p : e.getValue().parts) parts.put(p == null ? JSONObject.NULL : p);
                    secs.put(new JSONObject().put("n", e.getKey()).put("title", e.getValue().title).put("parts", parts));
                }
                JSONArray toc = new JSONArray();
                for (Ask.Section s : a.toc) toc.put(new JSONObject().put("n", s.num).put("title", s.title));
                arr.put(o.put("secs", secs).put("toc", toc));
            }
            java.nio.file.Files.write(savedFile.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            app.log("Ask: couldn't save answers (" + e.getMessage() + ")");
        }
    }

    private void load() {
        if (!savedFile.exists()) return;
        try {
            JSONArray arr = new JSONArray(new String(java.nio.file.Files.readAllBytes(savedFile.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Ask.Article a = new Ask.Article(o.optString("title"), o.optString("source"));
                a.time = o.optLong("time");
                JSONArray secs = o.optJSONArray("secs");
                for (int j = 0; secs != null && j < secs.length(); j++) {
                    JSONObject so = secs.getJSONObject(j);
                    Ask.Article.Sec s = new Ask.Article.Sec();
                    s.title = so.optString("title");
                    JSONArray parts = so.getJSONArray("parts");
                    s.parts = new String[parts.length()];
                    for (int k = 0; k < parts.length(); k++) s.parts[k] = parts.isNull(k) ? null : parts.getString(k);
                    a.secs.put(so.getInt("n"), s);
                }
                JSONArray toc = o.optJSONArray("toc");
                for (int j = 0; toc != null && j < toc.length(); j++) {
                    JSONObject t = toc.getJSONObject(j);
                    a.toc.add(new Ask.Section(t.getInt("n"), t.optString("title")));
                }
                saved.add(a);
            }
        } catch (Exception e) {
            app.log("Ask: saved answers unreadable (" + e.getMessage() + ")");
        }
    }
}
