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
    private static final String[] OVERDUE_NAMES = {"10 minutes", "15 minutes", "30 minutes", "60 minutes"};
    private static final int[] OVERDUE_MIN = {10, 15, 30, 60};
    private static final String[] CH_NAMES = {"0 · Primary", "1 · Secondary", "2", "3", "4", "5", "6", "7"};

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
        Spinner meshCh = findViewById(R.id.setMeshCh);
        CheckBox teamOnly = findViewById(R.id.setTeamOnly);
        CheckBox aprsTeam = findViewById(R.id.setAprsTeam);
        CheckBox aprsPos = findViewById(R.id.setAprsPos);
        CheckBox nogate = findViewById(R.id.setNogate);
        CheckBox background = findViewById(R.id.setBackground);
        CheckBox alertMsg = findViewById(R.id.setAlertMsg);
        CheckBox alertWpt = findViewById(R.id.setAlertWpt);
        CheckBox alertOverdue = findViewById(R.id.setAlertOverdue);
        Spinner overdueMin = findViewById(R.id.setOverdueMin);

        meshCh.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CH_NAMES));
        meshCh.setSelection(Math.max(0, Math.min(7, app.meshChannel())));
        overdueMin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, OVERDUE_NAMES));
        int om = app.prefs.getInt("overdueMin", 15);
        for (int i = 0; i < OVERDUE_MIN.length; i++) if (OVERDUE_MIN[i] == om) overdueMin.setSelection(i);
        teamOnly.setChecked(app.meshTeamOnly());
        aprsTeam.setChecked(app.aprsTeam());
        aprsPos.setChecked(app.aprsPosition());
        nogate.setChecked(app.noGate());
        background.setChecked(app.background());
        alertMsg.setChecked(app.prefs.getBoolean("alertMsg", true));
        alertWpt.setChecked(app.prefs.getBoolean("alertWpt", true));
        alertOverdue.setChecked(app.prefs.getBoolean("alertOverdue", true));

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
                    .putInt("meshChannel", Math.max(0, meshCh.getSelectedItemPosition()))
                    .putBoolean("meshTeamOnly", teamOnly.isChecked())
                    .putBoolean("aprsTeam", aprsTeam.isChecked())
                    .putBoolean("aprsPos", aprsPos.isChecked())
                    .putBoolean("nogate", nogate.isChecked())
                    .putBoolean("background", background.isChecked())
                    .putBoolean("alertMsg", alertMsg.isChecked())
                    .putBoolean("alertWpt", alertWpt.isChecked())
                    .putBoolean("alertOverdue", alertOverdue.isChecked())
                    .putInt("overdueMin", OVERDUE_MIN[Math.max(0, overdueMin.getSelectedItemPosition())])
                    .apply();
            app.updateService();
            Toast.makeText(this, "CONFIG COMMITTED", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
