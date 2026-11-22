/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Settings > Display > E-ink & front light (also the tiles' long-press target). */
public class SettingsActivity extends CollapsingToolbarBaseActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    static final int CONTENT = com.android.settingslib.collapsingtoolbar.R.id.content_frame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(CONTENT, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        Fragment fragment = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), pref.getFragment());
        fragment.setArguments(pref.getExtras());
        open(fragment, pref.getTitle());
        return true;
    }

    void open(Fragment fragment, CharSequence title) {
        getSupportFragmentManager().beginTransaction()
                .replace(CONTENT, fragment)
                .addToBackStack(null)
                .commit();
        if (title != null) setTitle(title);
    }
}
