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
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);

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
                String who = m.out ? "me → " + m.to : m.from + (m.to.equalsIgnoreCase(app.group()) ? " → team" : " → me");
                String status = m.out && m.id != null ? (m.acked ? "  ✓" : "  …") : "";
                tv.setText(time + "  " + who + "\n" + m.text + status);
                tv.setTextColor(m.out ? Color.parseColor("#FF7CFC00") : Color.parseColor("#FFE8F0E8"));
                tv.setTextSize(15);
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Store.Msg m = adapter.getItem(position);
            if (m != null && !m.out) to.setText(m.to.equalsIgnoreCase(app.group()) ? app.group() : m.from);
        });

        findViewById(R.id.chatSend).setOnClickListener(v -> send());
    }

    private void send() {
        String text = input.getText().toString().trim();
        String dest = to.getText().toString().trim().toUpperCase(Locale.US);
        if (text.isEmpty()) return;
        if (dest.isEmpty()) dest = app.group();
        if (!app.hasCallsign()) { toast("Set your callsign in Settings to send"); return; }
        if (!app.radio.isConnected()) { toast("Radio not connected"); return; }
        if (app.sendMessage(dest, text)) input.setText("");
        else toast("Send failed — check the packet log");
    }

    private void refresh() {
        app.store.unread = 0;
        app.store.save();
        adapter.notifyDataSetChanged();
        header.setText("Team: " + app.group() + "   Me: " + (app.hasCallsign() ? app.myCall() : "(no callsign)")
                + (app.radio.isConnected() ? "" : "   · radio off"));
    }

    @Override protected void onResume() { super.onResume(); app.addListener(this); refresh(); }
    @Override protected void onPause() { super.onPause(); app.removeListener(this); }
    @Override public void onDataChanged() { refresh(); }
    @Override public void onRadioChanged() { refresh(); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
