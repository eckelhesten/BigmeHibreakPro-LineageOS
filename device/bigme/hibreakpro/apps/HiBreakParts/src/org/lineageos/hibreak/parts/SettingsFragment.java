/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String[] KEY_PREFS = {"key_f1", "key_f2"};
    private static final long WARMTH_WRITE_INTERVAL_MS = 80;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SystemSettingsStore mStore;
    private int mPendingWarmth = -1;
    private long mLastWarmthWrite;
    private final Runnable mWriteWarmth = this::flushWarmth;
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            if (isAdded()) sync();
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mStore = new SystemSettingsStore(requireContext().getContentResolver());
        getPreferenceManager().setPreferenceDataStore(mStore);
        setPreferencesFromResource(R.xml.settings, rootKey);

        SeekBarPreference warmth = findPreference("frontlight_warmth");
        if (warmth != null) {
            warmth.setOnPreferenceChangeListener((p, value) -> {
                scheduleWarmth((Integer) value);
                return true;
            });
        }

        Preference refresh = findPreference("eink_refresh_now");
        if (refresh != null) {
            refresh.setOnPreferenceClickListener(p -> {
                EinkPolicy.onManualRefresh();
                Eink.fullRefresh();
                return true;
            });
        }
        setupTextSwitches();
        for (String key : KEY_PREFS) {
            ListPreference pref = findPreference(key);
            if (pref == null) continue;
            pref.setSummaryProvider(p -> describeAction(((ListPreference) p).getValue()));
            pref.setOnPreferenceChangeListener((p, value) -> {
                if ("app".equals(value)) {
                    Bundle args = new Bundle();
                    args.putString(AppPickerFragment.ARG_KEY, key);
                    AppPickerFragment picker = new AppPickerFragment();
                    picker.setArguments(args);
                    ((SettingsActivity) requireActivity()).open(picker,
                            getString(R.string.key_pick_app));
                    return false;
                }
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ContentResolver cr = requireContext().getContentResolver();
        for (String key : new String[] {Keys.EINK_PROFILE, Keys.FRONTLIGHT_ENABLED,
                Keys.FRONTLIGHT_WARMTH, Keys.KEY_F1, Keys.KEY_F2}) {
            cr.registerContentObserver(Settings.System.getUriFor(key), false, mObserver);
        }
        sync();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.settings_title);
        sync();
    }

    @Override
    public void onPause() {
        flushWarmthNow();
        super.onPause();
    }

    @Override
    public void onStop() {
        flushWarmthNow();
        requireContext().getContentResolver().unregisterContentObserver(mObserver);
        super.onStop();
    }

    private void scheduleWarmth(int value) {
        mPendingWarmth = Math.max(0, Math.min(100, value));
        long wait = mLastWarmthWrite + WARMTH_WRITE_INTERVAL_MS - SystemClock.uptimeMillis();
        mHandler.removeCallbacks(mWriteWarmth);
        if (wait <= 0) {
            flushWarmth();
        } else {
            mHandler.postDelayed(mWriteWarmth, wait);
        }
    }

    private void flushWarmthNow() {
        mHandler.removeCallbacks(mWriteWarmth);
        flushWarmth();
    }

    private void flushWarmth() {
        if (mPendingWarmth < 0 || !isAdded()) return;
        mStore.putInt("frontlight_warmth", mPendingWarmth);
        mPendingWarmth = -1;
        mLastWarmthWrite = SystemClock.uptimeMillis();
    }

    private void sync() {
        // Sync preferences that may have been changed by QS tiles, side keys, or Android Settings.
        for (String key : KEY_PREFS) {
            ListPreference pref = findPreference(key);
            if (pref != null) pref.setValue(mStore.getString(key, pref.getValue()));
        }
        ListPreference profile = findPreference("eink_profile");
        if (profile != null) {
            profile.setValue(mStore.getString("eink_profile", profile.getValue()));
        }
        SwitchPreferenceCompat fl = findPreference("frontlight_enabled");
        if (fl != null) fl.setChecked(mStore.getBoolean("frontlight_enabled", true));
        SeekBarPreference warmth = findPreference("frontlight_warmth");
        if (warmth != null && mPendingWarmth < 0) {
            warmth.setValue(mStore.getInt("frontlight_warmth", Keys.DEFAULT_WARMTH));
        }
        ContentResolver cr = requireContext().getContentResolver();
        SwitchPreferenceCompat bold = findPreference("text_bold");
        if (bold != null) {
            bold.setChecked(Settings.Secure.getInt(cr,
                    Settings.Secure.FONT_WEIGHT_ADJUSTMENT, 0) > 0);
        }
        SwitchPreferenceCompat anim = findPreference("no_animations");
        if (anim != null) {
            anim.setChecked(Settings.Global.getFloat(cr,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f);
        }
    }

    /** Switches backed by the standard Android settings (Accessibility / developer options). */
    private void setupTextSwitches() {
        ContentResolver cr = requireContext().getContentResolver();
        SwitchPreferenceCompat bold = findPreference("text_bold");
        if (bold != null) {
            bold.setChecked(Settings.Secure.getInt(cr,
                    Settings.Secure.FONT_WEIGHT_ADJUSTMENT, 0) > 0);
            bold.setOnPreferenceChangeListener((p, v) -> Settings.Secure.putInt(cr,
                    Settings.Secure.FONT_WEIGHT_ADJUSTMENT,
                    (Boolean) v ? Defaults.BOLD_WEIGHT_ADJUSTMENT : 0));
        }
        SwitchPreferenceCompat anim = findPreference("no_animations");
        if (anim != null) {
            anim.setChecked(Settings.Global.getFloat(cr,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f);
            anim.setOnPreferenceChangeListener((p, v) -> {
                float scale = (Boolean) v ? 0f : 1f;
                return Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
                        && Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE,
                                scale)
                        && Settings.Global.putFloat(cr,
                                Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
            });
        }
    }

    private CharSequence describeAction(String value) {
        if (value == null) return null;
        if (value.startsWith("app:")) {
            return getString(R.string.action_app_named,
                    AppList.label(requireContext(), value.substring(4)));
        }
        String[] values = getResources().getStringArray(R.array.key_action_values);
        String[] entries = getResources().getStringArray(R.array.key_action_entries);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return entries[i];
        }
        return value;
    }
}
