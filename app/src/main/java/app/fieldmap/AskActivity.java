package app.fieldmap;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.fieldmap.core.Ask;

/**
 * Ask Lantern: search the offline library on a Lantern drive over Meshtastic.
 * Type a question, tap a result, read the answer as it arrives a few lines at a time.
 */
public class AskActivity extends AppCompatActivity implements FieldMapApp.Listener {

    private FieldMapApp app;
    private Asker ask;
    private TextView header;
    private EditText input;
    private LinearLayout body;
    private ScrollView scroll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int lastMode = -1;
    private String lastArticleKey = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshHeader();
            main.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = FieldMapApp.get();
        ask = app.ask;
        setContentView(R.layout.activity_ask);
        header = findViewById(R.id.askHeader);
        input = findViewById(R.id.askInput);
        body = findViewById(R.id.askBody);
        scroll = findViewById(R.id.askScroll);

        findViewById(R.id.askGo).setOnClickListener(v -> doSearch());
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); return true; }
            return false;
        });
        findViewById(R.id.askMore).setOnClickListener(v -> {
            if (ask.mode == Asker.MODE_RESULTS) {
                if (ask.results.isEmpty()) { toast("Search first"); return; }
                show(ask.moreResults());
            } else {
                if (ask.article == null) { toast("Open a result first"); return; }
                if (ask.offline) { toast("This is a saved copy. Search again to ask the gateway for more."); return; }
                show(ask.more());
            }
        });
        findViewById(R.id.askSections).setOnClickListener(v -> show(ask.sections()));
        findViewById(R.id.askResults).setOnClickListener(v -> { ask.mode = Asker.MODE_RESULTS; render(); });
        findViewById(R.id.askSaved).setOnClickListener(v -> savedDialog());
        findViewById(R.id.askGateway).setOnClickListener(v -> gatewayDialog());
        header.setOnClickListener(v -> gatewayDialog());
    }

    private void doSearch() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        String err = ask.search(q);
        if (err == null) {
            input.setText("");
            hideKeyboard();
        }
        show(err);
    }

    private void show(String err) {
        if (err != null) {
            toast(err);
            if (err.startsWith("Pick the Lantern")) gatewayDialog();
        }
        render();
    }

    // ---------- drawing ----------

    private void render() {
        refreshHeader();
        body.removeAllViews();
        String key = ask.article == null ? "" : ask.article.key();
        boolean jumpTop = ask.mode != lastMode || !key.equals(lastArticleKey);
        lastMode = ask.mode;
        lastArticleKey = key;

        if (!ask.notice.isEmpty()) addLine("▲ " + ask.notice, R.color.warn, 14, false);

        if (ask.mode == Asker.MODE_RESULTS) renderResults();
        else if (ask.mode == Asker.MODE_SECTIONS) renderSections();
        else renderArticle();

        if (jumpTop) scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void refreshHeader() {
        String g = ask.gateway();
        String gw = g.isEmpty() ? "NOT SET (TAP TO PICK)" : app.displayName(g).toUpperCase(Locale.US) + (app.displayName(g).equals(g) ? "" : " " + g);
        String link = app.mesh.isConnected() ? "MESH▲" : "MESH· NOT CONNECTED";
        header.setText("◆ ASK LANTERN ▸ " + gw + "\n■ " + link + " · " + ask.status());
        boolean bad = g.isEmpty() || !app.mesh.isConnected() || ask.status().startsWith("NO RECEIPT");
        header.setTextColor(ContextCompat.getColor(this, bad ? R.color.warn : R.color.accent));
    }

    private void renderResults() {
        if (ask.query.isEmpty() && ask.results.isEmpty()) {
            addLine("Search the offline library on a Lantern drive: Wikipedia, medical and repair guides, books.", R.color.text, 15, false);
            addLine("Questions go over Meshtastic as a direct message to the Lantern gateway node. Answers arrive a few lines at a time, so give it a minute. Everything you read is saved under SAVED.", R.color.dim, 13, false);
            return;
        }
        addLine("// RESULTS ▸ " + ask.query.toUpperCase(Locale.US), R.color.dim, 12, true);
        if (ask.noMatches) addLine("No matches. Try fewer or different words.", R.color.warn, 15, false);
        else if (ask.results.isEmpty()) addLine(ask.waiting() ? "Waiting for results…" : "No results received yet. Search again?", R.color.dim, 15, false);
        for (Ask.Result r : ask.results) {
            addButton(r.num + " ▸ " + r.title + "   [" + r.source.toUpperCase(Locale.US) + "]", v -> show(ask.open(r)));
        }
        if (!ask.results.isEmpty()) addLine("Tap a result to read it. MORE gets the next 3 results.", R.color.dim, 12, false);
    }

    private void renderArticle() {
        Ask.Article a = ask.article;
        if (a == null) { ask.mode = Asker.MODE_RESULTS; renderResults(); return; }
        addLine(a.title.toUpperCase(Locale.US) + (a.source.isEmpty() ? "" : "   [" + a.source.toUpperCase(Locale.US) + "]"), R.color.accent, 17, true);
        if (a.secs.isEmpty()) {
            addLine(ask.waiting() ? "Waiting for the first part…" : "Nothing received yet.", R.color.dim, 15, false);
            return;
        }
        for (Map.Entry<Integer, Ask.Article.Sec> e : a.secs.entrySet()) {
            Ask.Article.Sec s = e.getValue();
            String head = e.getKey() == 0 ? "§ SUMMARY" : "§ " + s.title.toUpperCase(Locale.US);
            head += "   " + s.received() + "/" + s.parts.length;
            addLine(head, R.color.dim, 12, true);
            String body = Ask.Article.sectionText(s);
            if (!body.isEmpty()) addLine(body, R.color.text, 16, false);
            int left = Ask.Article.notYetSent(s);
            if (left > 0) addLine("▸ " + left + " more part" + (left == 1 ? "" : "s") + " in this section"
                    + (ask.offline ? "" : ": tap MORE"), R.color.dim, 12, false);
        }
        if (!ask.offline && !a.currentIncomplete()) addLine("Tap MORE for the next section, or SECTIONS to jump.", R.color.dim, 12, false);
    }

    private void renderSections() {
        Ask.Article a = ask.article;
        if (a == null) { ask.mode = Asker.MODE_RESULTS; renderResults(); return; }
        addLine("// SECTIONS ▸ " + a.title.toUpperCase(Locale.US), R.color.dim, 12, true);
        List<Ask.Section> list = new ArrayList<>(a.toc);
        if (list.isEmpty()) {
            // a saved copy without a section list: offer what we have
            for (Map.Entry<Integer, Ask.Article.Sec> e : a.secs.entrySet()) list.add(new Ask.Section(e.getKey(), a.sectionTitle(e.getKey())));
            if (!ask.offline) addLine(ask.waiting() ? "Waiting for the section list…" : "No section list yet.", R.color.dim, 15, false);
        }
        for (Ask.Section s : list) {
            Ask.Article.Sec have = a.secs.get(s.num);
            String mark = have == null ? "" : have.complete() ? "   ✓ SAVED" : "   ◐ " + have.received() + "/" + have.parts.length;
            addButton(s.num + " ▸ " + s.title + mark, v -> {
                if (ask.offline) { ask.mode = Asker.MODE_READ; render(); return; }
                show(ask.go(s.num));
            });
        }
    }

    private void addLine(String text, int color, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, color));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextIsSelectable(!bold);
        tv.setPadding(dp(6), dp(8), dp(6), dp(8));
        body.addView(tv);
    }

    private void addButton(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, R.color.accent));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setBackgroundResource(R.drawable.hud_button_flat);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setMinHeight(dp(52));
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        body.addView(tv, lp);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------- dialogs ----------

    private void gatewayDialog() {
        List<String[]> nodes = app.meshNodes();
        // likely gateways first
        nodes.sort((x, y) -> Boolean.compare(Ask.looksLikeGateway(y[1]), Ask.looksLikeGateway(x[1])));
        String cur = ask.gateway();
        List<String> labels = new ArrayList<>();
        for (String[] n : nodes) labels.add((Ask.looksLikeGateway(n[1]) ? "◆ " : "  ") + n[1] + "  " + n[0] + (n[0].equalsIgnoreCase(cur) ? "  ✓" : ""));
        labels.add("Type a node ID…");
        new AlertDialog.Builder(this)
                .setTitle("// LANTERN GATEWAY NODE")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < nodes.size()) { ask.setGateway(nodes.get(which)[0]); render(); }
                    else typeGateway();
                })
                .setNegativeButton("Close", null)
                .show();
        if (nodes.isEmpty() && !app.mesh.isConnected()) toast("Connect the Meshtastic radio to see nodes");
    }

    private void typeGateway() {
        EditText et = new EditText(this);
        et.setHint("!a1b2c3d4");
        et.setText(ask.gateway());
        et.setSingleLine();
        new AlertDialog.Builder(this)
                .setTitle("// GATEWAY NODE ID")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String v = et.getText().toString().trim().toLowerCase(Locale.US);
                    if (!v.startsWith("!")) v = "!" + v;
                    if (!v.matches("![0-9a-f]{8}")) { toast("A node ID looks like !a1b2c3d4"); return; }
                    ask.setGateway(v);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void savedDialog() {
        if (ask.saved.isEmpty()) { toast("Nothing saved yet. Answers are saved as they arrive."); return; }
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d HH:mm", Locale.US);
        String[] labels = new String[ask.saved.size()];
        for (int i = 0; i < labels.length; i++) {
            Ask.Article a = ask.saved.get(i);
            labels[i] = a.title + (a.source.isEmpty() ? "" : " [" + a.source + "]") + "\n   " + fmt.format(new Date(a.time));
        }
        new AlertDialog.Builder(this)
                .setTitle("// SAVED ANSWERS")
                .setItems(labels, (d, which) -> {
                    Ask.Article a = ask.saved.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(a.title)
                            .setItems(new String[]{"Read", "Delete"}, (d2, w2) -> {
                                if (w2 == 0) { ask.openSaved(a); render(); }
                                else {
                                    if (ask.article == a) { ask.article = null; ask.mode = Asker.MODE_RESULTS; }
                                    ask.delete(a);
                                    render();
                                }
                            })
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------- lifecycle ----------

    @Override protected void onResume() {
        super.onResume();
        Alerts.askVisible = true;
        androidx.core.app.NotificationManagerCompat.from(this).cancel(12);
        app.addListener(this);
        render();
        main.postDelayed(tick, 3000);
    }

    @Override protected void onPause() {
        super.onPause();
        Alerts.askVisible = false;
        app.removeListener(this);
        main.removeCallbacks(tick);
    }

    @Override public void onDataChanged() { render(); }
    @Override public void onRadioChanged() { refreshHeader(); }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
