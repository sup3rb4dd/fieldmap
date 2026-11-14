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
