package app.fieldmap;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private static final String[] BEACON_NAMES = {"Off (manual only)", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"};
    private static final int[] BEACON_SECS = {0, 60, 120, 300, 600, 1800};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        FieldMapApp app = FieldMapApp.get();

        EditText call = findViewById(R.id.setCall);
        EditText ssid = findViewById(R.id.setSsid);
        EditText group = findViewById(R.id.setGroup);
        EditText path = findViewById(R.id.setPath);
        EditText comment = findViewById(R.id.setComment);
        EditText filter = findViewById(R.id.setFilter);
        Spinner beacon = findViewById(R.id.setBeacon);
        Spinner symbol = findViewById(R.id.setSymbol);
        Spinner detail = findViewById(R.id.setDetail);
        detail.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Sharpest (smallest text)", "Balanced", "Large text (blurrier)"}));
        detail.setSelection(app.prefs.getInt("detail", 1));
        CheckBox imperial = findViewById(R.id.setImperial);
        CheckBox keepOn = findViewById(R.id.setKeepOn);

        call.setText(app.callsign());
        ssid.setText(String.valueOf(app.ssid()));
        group.setText(app.group());
        path.setText(app.path());
        comment.setText(app.statusComment());
        filter.setText(app.prefs.getString("filter", ""));
        imperial.setChecked(app.imperial());
        keepOn.setChecked(app.prefs.getBoolean("keepOn", false));

        ArrayAdapter<String> ba = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, BEACON_NAMES);
        beacon.setAdapter(ba);
        int cur = app.beaconSeconds();
        for (int i = 0; i < BEACON_SECS.length; i++) if (BEACON_SECS[i] == cur) beacon.setSelection(i);

        ArrayAdapter<String> sa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FieldMapApp.SYMBOL_NAMES);
        symbol.setAdapter(sa);
        String curSym = "" + app.symTable() + app.symCode();
        int si = FieldMapApp.SYMBOL_CODES.indexOf(curSym);
        if (si >= 0) symbol.setSelection(si);

        findViewById(R.id.setSave).setOnClickListener(v -> {
            String c = call.getText().toString().trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
            if (!c.isEmpty() && !c.matches(".*\\d.*")) {
                Toast.makeText(this, "That doesn't look like a callsign", Toast.LENGTH_LONG).show();
                return;
            }
            int s;
            try { s = Integer.parseInt(ssid.getText().toString().trim()); } catch (NumberFormatException e) { s = 0; }
            if (s < 0 || s > 15) s = 0;
            String g = group.getText().toString().trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9-]", "");
            String sym = FieldMapApp.SYMBOL_CODES.get(Math.max(0, symbol.getSelectedItemPosition()));
            app.prefs.edit()
                    .putString("call", c)
                    .putInt("ssid", s)
                    .putString("group", g.isEmpty() ? "FIELDMAP" : g)
                    .putString("path", path.getText().toString().trim().toUpperCase(Locale.US))
                    .putString("comment", comment.getText().toString().trim())
                    .putString("filter", filter.getText().toString().trim().toUpperCase(Locale.US))
                    .putInt("beacon", BEACON_SECS[Math.max(0, beacon.getSelectedItemPosition())])
                    .putString("symTable", sym.substring(0, 1))
                    .putString("symCode", sym.substring(1, 2))
                    .putBoolean("imperial", imperial.isChecked())
                    .putBoolean("keepOn", keepOn.isChecked())
                    .putInt("detail", Math.max(0, detail.getSelectedItemPosition()))
                    .apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
