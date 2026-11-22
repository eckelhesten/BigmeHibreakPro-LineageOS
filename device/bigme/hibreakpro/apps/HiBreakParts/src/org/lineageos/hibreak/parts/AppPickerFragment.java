/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.List;

/** Picks the app a side key launches; stores "app:<package>". */
public class AppPickerFragment extends PreferenceFragmentCompat {
    static final String ARG_KEY = "key";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = requireContext();
        String key = getArguments() != null ? getArguments().getString(ARG_KEY) : null;
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        if (key == null) return;

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
                SystemSettingsStore store = new SystemSettingsStore(appContext.getContentResolver());
                for (AppList.App app : apps) {
                    Preference pref = new Preference(prefContext);
                    pref.setTitle(app.label);
                    pref.setIcon(app.icon);
                    pref.setPersistent(false);
                    pref.setOnPreferenceClickListener(p -> {
                        store.putString(key, "app:" + app.packageName);
                        if (isAdded()) getParentFragmentManager().popBackStack();
                        return true;
                    });
                    screen.addPreference(pref);
                }
            });
        }, "HiBreakAppPicker").start();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.key_pick_app);
    }
}
