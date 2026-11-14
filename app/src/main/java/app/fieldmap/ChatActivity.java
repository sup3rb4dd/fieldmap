package app.fieldmap;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import app.fieldmap.core.Mgrs;
import app.fieldmap.core.Tac;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity implements FieldMapApp.Listener {

    private FieldMapApp app;
    private ArrayAdapter<Store.Msg> adapter;
    private EditText to, input;
    private TextView header;
    private SimpleDateFormat fmt;

    /** The box only accepts what the links for this destination can carry. */
    private void applyLimit() {
        int max = app.maxText(to.getText().toString());
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        if (input.getText().length() > max) input.setText(input.getText().subSequence(0, max));
        input.setHint(app.lbl("ENTER TRAFFIC_", "Type a message") + " (" + max + " max)");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = FieldMapApp.get();
        setContentView(R.layout.activity_chat);
        if (app.prefs.getBoolean("localTime", false)) {
            fmt = new SimpleDateFormat("HH:mm", Locale.US);
        } else {
            fmt = new SimpleDateFormat("ddHHmm'Z'", Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        }
        header = findViewById(R.id.chatHeader);
        to = findViewById(R.id.chatTo);
        input = findViewById(R.id.chatInput);
        ListView list = findViewById(R.id.chatList);

        String extraTo = getIntent().getStringExtra("to");
        to.setText(extraTo != null ? extraTo : app.group());
        to.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { applyLimit(); }
        });

        adapter = new ArrayAdapter<Store.Msg>(this, android.R.layout.simple_list_item_1, app.store.messages) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                Store.Msg m = getItem(position);
                String time = fmt.format(new Date(m.time));
                boolean team = m.to.equalsIgnoreCase(app.group());
                String via = m.via == null ? "APRS" : m.via;
                String net = app.lbl("NET", "TEAM"), self = app.lbl("SELF", "ME");
                String who = m.out ? self + " ▸ " + (team ? net : app.displayName(m.to))
                        : app.displayName(m.from) + " ▸ " + (team ? net : self);
                boolean meshOnly = m.id != null && m.id.startsWith("m");
                String status = !m.out || m.id == null ? ""
                        : m.acked ? (meshOnly && team ? "  ✓ HEARD ON THE MESH" : "  ✓ DELIVERED")
                        : "  … SENT, NOT CONFIRMED YET";
                boolean hasGrid = Tac.findGrid(m.text) != null;
                tv.setText("[" + time + "] " + who.toUpperCase(Locale.US) + "  <" + via + ">\n> " + m.text + status
                        + (hasGrid ? "\n  ⌖ TAP FOR MAP" : ""));
                boolean sos = Tac.isSos(m.text);
                int color = sos ? Color.parseColor("#FFFF3B30") : m.out ? Color.parseColor("#FF39FF14")
                        : via.contains("GMRS") ? Color.parseColor("#FFFFB000")
                        : via.contains("MESH") ? Color.parseColor("#FF00E5FF")
                        : via.contains("TAK") ? Color.parseColor("#FF7FB2FF") : Color.parseColor("#FFA8FFA0");
                tv.setTextColor(color);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextSize(14);
                tv.setPadding(8, 14, 8, 14);
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Store.Msg m = adapter.getItem(position);
            if (m == null) return;
            final String replyTo = m.out ? m.to : (m.to.equalsIgnoreCase(app.group()) ? app.group() : m.from);
            final double[] grid = Tac.findGrid(m.text);
            if (grid == null) { to.setText(replyTo); return; }
            // the message carries a grid: offer to jump there
            final String who = m.out ? "My grid" : app.displayName(m.from);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("// " + Mgrs.format(grid[0], grid[1]))
                    .setItems(new String[]{"Show on map and track", "Reply"}, (d, which) -> {
                        if (which == 1) { to.setText(replyTo); return; }
                        startActivity(new android.content.Intent(this, MainActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra("gotoLat", grid[0]).putExtra("gotoLon", grid[1]).putExtra("gotoName", who));
                    })
                    .show();
        });

        // quick messages fill the box so nothing is sent by a stray tap
        int[] quick = {R.id.q1, R.id.q2, R.id.q3, R.id.q4, R.id.q5};
        for (int qid : quick) {
            android.widget.Button b = findViewById(qid);
            b.setOnClickListener(v -> {
                input.setText(b.getText());
                input.setSelection(input.getText().length());
            });
        }
        findViewById(R.id.qGrid).setOnClickListener(v -> {
            android.location.Location l = app.lastLoc;
            if (l == null) { toast("No GPS fix yet"); return; }
            String cur = input.getText().toString().trim();
            String withGrid = Tac.withGrid(cur.isEmpty() ? "AT" : cur, l.getLatitude(), l.getLongitude());
            if (withGrid.length() > app.maxText(to.getText().toString())) { toast("Message too long to add a grid"); return; }
            input.setText(withGrid);
            input.setSelection(input.getText().length());
        });

        findViewById(R.id.chatSend).setOnClickListener(v -> send());
    }

    private void send() {
        String text = input.getText().toString().trim();
        String dest = to.getText().toString().trim();
        if (text.isEmpty()) return;
        if (dest.isEmpty()) dest = app.group();
        if (dest.startsWith("tak:")) {
            toast("Direct messages to TAK users aren't supported yet. Send to the team instead.");
            to.setText(app.group());
            return;
        }
        if (dest.startsWith("!")) {
            if (!app.mesh.isConnected()) { toast("Meshtastic radio not connected"); return; }
        } else if (!dest.equalsIgnoreCase(app.group())) {
            if (!app.radio.isConnected()) { toast("UV-PRO not connected"); return; }
            if (!app.hasCallsign()) { toast(app.gmrs() ? "Finish GMRS setup in Settings (callsign and unit name)" : "Set your callsign in Settings to send over APRS"); return; }
        } else if (!app.canShare()) {
            if (app.mesh.isConnected() && !app.meshTeamOk())
                toast("Team traffic is held: your mesh team channel is 0, which may be public. Open the map to confirm, or pick your channel in Settings ▸ Privacy");
            else if (app.canTransmit() && !app.aprsTeam())
                toast("Team chat over APRS is off (APRS is public). Connect Meshtastic or allow it in Config ▸ Privacy");
            else
                toast(!app.radio.isConnected() ? "Connect a radio first" : app.gmrs() ? "Finish GMRS setup in Settings (callsign and unit name)"
                        : "Set your callsign in Settings to send over APRS");
            return;
        }
        if (app.sendMessage(dest, text)) {
            input.setText("");
            if (app.gmrs() && app.radio.isConnected()) {
                int w = app.gmrsWaitSeconds(), n = app.gmrsQueue.size();
                if (n > 0) toast("GMRS: goes out in about " + (w + (n - 1) * 30) + " s (one burst every 30 s)");
            }
        }
        else toast("Send failed — check the packet log");
    }

    private void refresh() {
        if (app.store.unread != 0) { app.store.unread = 0; app.store.save(Store.MSGS); }
        adapter.notifyDataSetChanged();
        String rl = app.radioLabel();
        String links = (app.radio.isConnected() ? rl + "▲ " : rl + "· ") + (app.mesh.isConnected() ? (app.meshTeamOk() ? "MESH▲" : "MESH▲(HELD)") : "MESH·")
                + (app.tak.getState() == RadioLink.State.OFF ? "" : app.tak.isConnected() ? " TAK▲" : " TAK·");
        String gm = !app.gmrs() ? "" : "\n▲ GMRS MODE ON · " + (app.gmrsReady() ? app.gmrsCall() + " · NOT ENCRYPTED · 1 BURST / 30 S" : "SETUP NOT FINISHED");
        header.setText("◆ " + app.lbl("NET", "TEAM") + ": " + app.group() + "    " + app.lbl("OP", "ME") + ": " + (app.hasCallsign() ? app.myCall() : "NO CALLSIGN")
                + "\n■ LINKS: " + links + gm);
        header.setTextColor(androidx.core.content.ContextCompat.getColor(this, app.gmrs() ? R.color.warn : R.color.accent));
        applyLimit();
    }

    @Override protected void onResume() {
        super.onResume();
        Alerts.chatVisible = true;
        androidx.core.app.NotificationManagerCompat.from(this).cancel(10);
        app.addListener(this);
        refresh();
    }
    @Override protected void onPause() {
        super.onPause();
        Alerts.chatVisible = false;
        app.removeListener(this);
        app.store.flush();
    }
    @Override public void onDataChanged() { refresh(); }
    @Override public void onRadioChanged() { refresh(); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
