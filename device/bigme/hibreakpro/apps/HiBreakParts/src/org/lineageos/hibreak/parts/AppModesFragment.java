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

/** One refresh-mode choice per launchable app; "Use global mode" removes the override. */
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
                ContentResolver cr = appContext.getContentResolver();
                Map<String, Integer> modes = Eink.getAppModes(cr);
                for (AppList.App app : apps) {
                    ListPreference pref = new ListPreference(prefContext);
                    pref.setPersistent(false);
                    pref.setKey("app_mode_" + app.packageName);
                    pref.setTitle(app.label);
                    pref.setIcon(app.icon);
                    pref.setDialogTitle(app.label);
                    pref.setEntries(R.array.app_mode_entries);
                    pref.setEntryValues(R.array.app_mode_values);
                    Integer mode = modes.get(app.packageName);
                    pref.setValue(mode != null ? Integer.toString(mode) : "-1");
                    pref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                    pref.setOnPreferenceChangeListener((p, value) -> {
                        if (!isAdded()) return false;
                        Map<String, Integer> current = Eink.getAppModes(cr);
                        int v = Integer.parseInt((String) value);
                        if (v < 0) {
                            current.remove(app.packageName);
                        } else {
                            current.put(app.packageName, v);
                        }
                        Eink.putAppModes(cr, current);
                        return true;
                    });
                    screen.addPreference(pref);
                }
            });
        }, "HiBreakAppModes").start();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.eink_app_modes);
    }
}
