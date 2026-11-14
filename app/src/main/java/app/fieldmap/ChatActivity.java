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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity implements FieldMapApp.Listener {

    private FieldMapApp app;
    private ArrayAdapter<Store.Msg> adapter;
    private EditText to, input;
    private TextView header;
    private final SimpleDateFormat fmt = new SimpleDateFormat("ddHHmm'Z'", Locale.US);
    { fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = FieldMapApp.get();
        setContentView(R.layout.activity_chat);
        header = findViewById(R.id.chatHeader);
        to = findViewById(R.id.chatTo);
        input = findViewById(R.id.chatInput);
        ListView list = findViewById(R.id.chatList);

        String extraTo = getIntent().getStringExtra("to");
        to.setText(extraTo != null ? extraTo : app.group());

        adapter = new ArrayAdapter<Store.Msg>(this, android.R.layout.simple_list_item_1, app.store.messages) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                Store.Msg m = getItem(position);
                String time = fmt.format(new Date(m.time));
                boolean team = m.to.equalsIgnoreCase(app.group());
                String via = m.via == null ? "APRS" : m.via;
                String who = m.out ? "SELF ▸ " + (team ? "NET" : app.displayName(m.to))
                        : app.displayName(m.from) + " ▸ " + (team ? "NET" : "SELF");
                String status = m.out && m.id != null ? (m.acked ? "  [ACK]" : "  [PEND]") : "";
                tv.setText("[" + time + "] " + who.toUpperCase(Locale.US) + "  <" + via + ">\n> " + m.text + status);
                int color = m.out ? Color.parseColor("#FF39FF14")
                        : via.contains("MESH") ? Color.parseColor("#FF00E5FF") : Color.parseColor("#FFA8FFA0");
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
            if (m != null && !m.out) to.setText(m.to.equalsIgnoreCase(app.group()) ? app.group() : m.from);
            if (m != null && m.out) to.setText(m.to);
        });

        findViewById(R.id.chatSend).setOnClickListener(v -> send());
    }

    private void send() {
        String text = input.getText().toString().trim();
        String dest = to.getText().toString().trim();
        if (text.isEmpty()) return;
        if (dest.isEmpty()) dest = app.group();
        if (dest.startsWith("!")) {
            if (!app.mesh.isConnected()) { toast("Meshtastic radio not connected"); return; }
        } else if (!dest.equalsIgnoreCase(app.group())) {
            if (!app.radio.isConnected()) { toast("UV-PRO not connected"); return; }
            if (!app.hasCallsign()) { toast("Set your callsign in Settings to send over APRS"); return; }
        } else if (!app.canShare()) {
            toast(app.radio.isConnected() ? "Set your callsign in Settings to send over APRS" : "Connect a radio first");
            return;
        }
        if (app.sendMessage(dest, text)) input.setText("");
        else toast("Send failed — check the packet log");
    }

    private void refresh() {
        app.store.unread = 0;
        app.store.save();
        adapter.notifyDataSetChanged();
        String links = (app.radio.isConnected() ? "APRS▲ " : "APRS· ") + (app.mesh.isConnected() ? "MESH▲" : "MESH·");
        header.setText("◆ NET: " + app.group() + "    OP: " + (app.hasCallsign() ? app.myCall() : "NO CALLSIGN")
                + "\n■ LINKS: " + links);
    }

    @Override protected void onResume() { super.onResume(); app.addListener(this); refresh(); }
    @Override protected void onPause() { super.onPause(); app.removeListener(this); }
    @Override public void onDataChanged() { refresh(); }
    @Override public void onRadioChanged() { refresh(); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
