package app.fieldmap;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Raw packet log — handy for checking that the radio link works. */
public class LogActivity extends AppCompatActivity implements FieldMapApp.Listener {

    private TextView text;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        text = findViewById(R.id.logText);
        scroll = findViewById(R.id.logScroll);
        findViewById(R.id.logCopy).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("FieldMap log", FieldMapApp.get().logText()));
            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.logShare).setOnClickListener(v -> {
            String t = FieldMapApp.get().logText();
            // very long texts can fail to hand over between apps: the newest part is what matters
            if (t.length() > 60000) t = t.substring(t.length() - 60000);
            try {
                startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, "FieldMap packet log")
                        .putExtra(android.content.Intent.EXTRA_TEXT, t), "Send the log"));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "No app to send with", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refresh() {
        String t = FieldMapApp.get().logText();
        text.setText(t.isEmpty() ? "Nothing yet. Connect the radio and packets will show up here." : t);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override protected void onResume() { super.onResume(); FieldMapApp.get().addListener(this); refresh(); }
    @Override protected void onPause() { super.onPause(); FieldMapApp.get().removeListener(this); }
    @Override public void onDataChanged() { refresh(); }
    @Override public void onRadioChanged() { refresh(); }
}
