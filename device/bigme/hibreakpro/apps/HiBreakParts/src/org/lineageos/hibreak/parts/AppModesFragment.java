/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.List;
import java.util.Map;

/** One e-ink profile per launchable app; "Automatic" removes the choice (Reading for known
 *  e-readers, otherwise the global profile). */
public class AppModesFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        if (savedInstanceState != null && screen.getPreferenceCount() == 0) {
            Fragment dialog = getParentFragmentManager()
                    .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
            if (dialog instanceof DialogFragment) {
                ((DialogFragment) dialog).dismissAllowingStateLoss();
            }
        }

        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            List<AppList.App> apps = AppList.launchable(appContext);
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded() || getContext() == null || activity.isFinishing()
                        || activity.isDestroyed()) {
                    return;
                }
                Context prefContext = getPreferenceManager().getContext();
                ContentResolver cr = SystemSettingsStore.deviceResolver(appContext);
                Map<String, Integer> profiles = Eink.getAppProfiles(cr);
                // Loaded once for all apps, not twice per app.
                CharSequence[] entries = prefContext.getResources()
                        .getTextArray(R.array.app_profile_entries);
                CharSequence[] values = prefContext.getResources()
                        .getTextArray(R.array.app_profile_values);
                String auto = prefContext.getString(R.string.app_profile_auto);
                String autoReading = prefContext.getString(R.string.app_profile_auto_reading);
                for (AppList.App app : apps) {
                    ListPreference pref = new ListPreference(prefContext);
                    pref.setPersistent(false);
                    pref.setKey("app_profile_" + app.packageName);
                    pref.setTitle(app.label);
                    pref.setIcon(app.icon);
                    pref.setDialogTitle(app.label);
                    pref.setEntries(entries);
                    pref.setEntryValues(values);
                    Integer own = profiles.get(app.packageName);
                    pref.setValue(own != null ? Integer.toString(own) : "-1");
                    // "Automatic" says what it means for this app: Reading for known e-readers.
                    boolean reader = Eink.READING_APPS.contains(app.packageName);
                    pref.setSummaryProvider(p -> {
                        ListPreference lp = (ListPreference) p;
                        if (!"-1".equals(lp.getValue())) return lp.getEntry();
                        return reader ? autoReading : auto;
                    });
                    pref.setOnPreferenceChangeListener((p, value) -> {
                        if (!isAdded()) return false;
                        Map<String, Integer> current = Eink.getAppProfiles(cr);
                        int v = Integer.parseInt((String) value);
                        if (v < 0) {
                            current.remove(app.packageName);
                        } else {
                            current.put(app.packageName, v);
                        }
                        Eink.putAppProfiles(cr, current);
                        return true;
                    });
                    screen.addPreference(pref);
                }
            });
        }, "HiBreakAppProfiles").start();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.eink_app_profiles);
    }
}
