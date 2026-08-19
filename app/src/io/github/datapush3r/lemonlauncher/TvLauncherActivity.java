package io.github.datapush3r.lemonlauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TvLauncherActivity extends Activity
        implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private static final String PREFS = "launcher_prefs";
    private static final String KEY_SHOWN = "shown_packages";
    private static final String PREFIX_APP = "app:";
    private static final String PREFIX_INPUT = "input:";
    private static final String PREFIX_ACTION = "action:";

    private static final int COLOR_SELECTED = 0xFF8BC34A;
    private static final int COLOR_UNSELECTED = 0xFFFFFFFF;

    private final List<Entry> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private boolean pickerMode = false;
    private Set<String> shownSnapshot = Collections.emptySet();

    private static final class Entry implements Comparable<Entry> {
        final String label;
        final String id;
        Entry(String label, String id) {
            this.label = label;
            this.id = id;
        }
        @Override
        public int compareTo(Entry other) {
            return label.compareToIgnoreCase(other.label);
        }
    }

    private static final Entry ACTION_SETTINGS = new Entry("Settings", PREFIX_ACTION + "settings");
    private static final Entry ACTION_FREE_MEMORY = new Entry("Free Up Memory", PREFIX_ACTION + "free_memory");
    private static final List<Entry> ALL_ACTIONS = Arrays.asList(ACTION_SETTINGS, ACTION_FREE_MEMORY);
    private static final Entry PLACEHOLDER_ADD = new Entry("Long press to select items", "placeholder:add");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ListView listView = new ListView(this);
        listView.setSelector(android.R.color.darker_gray);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        setContentView(listView);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                Entry entry = rows.get(position);
                boolean selected = pickerMode && shownSnapshot.contains(entry.id);
                row.setTextColor(selected ? COLOR_SELECTED : COLOR_UNSELECTED);
                return row;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this);
        listView.setOnItemLongClickListener(this);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Entry entry = rows.get(position);
        if (entry.id.equals(PLACEHOLDER_ADD.id)) {
            return;
        }
        if (pickerMode) {
            toggleShown(entry.id);
        } else {
            launchEntry(entry);
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        togglePickerMode();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void togglePickerMode() {
        pickerMode = !pickerMode;
        refreshList();
    }

    private void launchEntry(Entry entry) {
        if (entry.id.equals(ACTION_FREE_MEMORY.id)) {
            freeMemory();
            return;
        }
        Intent launchIntent;
        if (entry.id.startsWith(PREFIX_INPUT)) {
            String inputId = entry.id.substring(PREFIX_INPUT.length());
            launchIntent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
        } else if (entry.id.startsWith(PREFIX_ACTION)) {
            launchIntent = new Intent(Settings.ACTION_SETTINGS);
        } else {
            String packageName = entry.id.substring(PREFIX_APP.length());
            PackageManager pm = getPackageManager();
            launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                launchIntent = pm.getLaunchIntentForPackage(packageName);
            }
        }
        if (launchIntent != null) {
            startActivity(launchIntent);
        }
    }

    private void freeMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            String selfPackage = getPackageName();
            for (ApplicationInfo appInfo : getPackageManager().getInstalledApplications(0)) {
                if (!appInfo.packageName.equals(selfPackage)) {
                    am.killBackgroundProcesses(appInfo.packageName);
                }
            }
        }
        Toast.makeText(this, "Freed background apps", Toast.LENGTH_SHORT).show();
    }

    private Set<String> loadShown(SharedPreferences prefs) {
        Set<String> raw = prefs.getStringSet(KEY_SHOWN, Collections.<String>emptySet());
        Set<String> normalized = new HashSet<>();
        boolean migrated = false;
        for (String id : raw) {
            if (id.startsWith(PREFIX_APP) || id.startsWith(PREFIX_INPUT) || id.startsWith(PREFIX_ACTION)) {
                normalized.add(id);
            } else {
                normalized.add(PREFIX_APP + id);
                migrated = true;
            }
        }
        if (migrated) {
            prefs.edit().putStringSet(KEY_SHOWN, normalized).apply();
        }
        return normalized;
    }

    private void toggleShown(String id) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> shown = new HashSet<>(loadShown(prefs));
        if (!shown.remove(id)) {
            shown.add(id);
        }
        prefs.edit().putStringSet(KEY_SHOWN, shown).apply();
        refreshList();
    }

    private void refreshList() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> shown = loadShown(prefs);
        shownSnapshot = shown;

        List<Entry> apps = new ArrayList<>();
        List<Entry> inputs = new ArrayList<>();
        List<Entry> actions = new ArrayList<>();

        if (pickerMode) {
            PackageManager pm = getPackageManager();
            Map<String, Entry> appsById = new LinkedHashMap<>();
            for (ApplicationInfo appInfo : pm.getInstalledApplications(0)) {
                addAppEntry(appsById, pm, appInfo);
            }
            apps.addAll(appsById.values());

            Map<String, Entry> inputsById = new LinkedHashMap<>();
            addInputEntries(inputsById);
            inputs.addAll(inputsById.values());

            actions.addAll(ALL_ACTIONS);
        } else {
            resolveShown(shown, apps, inputs);
            for (Entry action : ALL_ACTIONS) {
                if (shown.contains(action.id)) {
                    actions.add(action);
                }
            }
        }

        Collections.sort(apps);
        Collections.sort(inputs);

        rows.clear();
        adapter.clear();
        addSection(apps);
        addSection(inputs);
        addSection(actions);
        if (!pickerMode && rows.isEmpty()) {
            rows.add(PLACEHOLDER_ADD);
            adapter.add(PLACEHOLDER_ADD.label);
        }
        adapter.notifyDataSetChanged();
    }

    private void resolveShown(Set<String> shown, List<Entry> apps, List<Entry> inputs) {
        PackageManager pm = getPackageManager();
        TvInputManager tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        for (String id : shown) {
            if (id.startsWith(PREFIX_APP)) {
                String packageName = id.substring(PREFIX_APP.length());
                try {
                    String label = pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString();
                    apps.add(new Entry(label, id));
                } catch (PackageManager.NameNotFoundException e) {
                }
            } else if (id.startsWith(PREFIX_INPUT) && tvInputManager != null) {
                String inputId = id.substring(PREFIX_INPUT.length());
                TvInputInfo info = tvInputManager.getTvInputInfo(inputId);
                if (info != null) {
                    inputs.add(new Entry(info.loadLabel(this).toString(), id));
                }
            }
        }
    }

    private void addSection(List<Entry> sectionEntries) {
        for (Entry entry : sectionEntries) {
            rows.add(entry);
            adapter.add(entry.label);
        }
    }

    private void addAppEntry(Map<String, Entry> byId, PackageManager pm, ApplicationInfo appInfo) {
        String packageName = appInfo.packageName;
        if (packageName.equals(getPackageName())) {
            return;
        }
        if (pm.getLeanbackLaunchIntentForPackage(packageName) == null
                && pm.getLaunchIntentForPackage(packageName) == null) {
            return;
        }
        String label = appInfo.loadLabel(pm).toString();
        byId.put(PREFIX_APP + packageName, new Entry(label, PREFIX_APP + packageName));
    }

    private void addInputEntries(Map<String, Entry> byId) {
        TvInputManager tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputManager == null) {
            return;
        }
        for (TvInputInfo info : tvInputManager.getTvInputList()) {
            String inputId = info.getId();
            String label = info.loadLabel(this).toString();
            byId.put(PREFIX_INPUT + inputId, new Entry(label, PREFIX_INPUT + inputId));
        }
    }
}
