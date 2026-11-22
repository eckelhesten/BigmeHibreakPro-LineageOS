/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Settings > Display > E-ink & front light > Sleep screen: weather and next event. */
public class SleepScreenFragment extends PreferenceFragmentCompat {
    private static final String TAG = "HiBreakSleepSettings";
    private Preference mCity;
    private AlertDialog mDialog;
    private boolean mSearching;
    private final AtomicInteger mSearchSeq = new AtomicInteger();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        ContentResolver cr = requireContext().getContentResolver();
        SystemSettingsStore store = new SystemSettingsStore(cr);
        getPreferenceManager().setPreferenceDataStore(store);
        setPreferencesFromResource(R.xml.sleep_screen, rootKey);

        ListPreference unit = findPreference("sleep_weather_unit");
        if (unit != null) {
            // Keep non-persistent until the user explicitly chooses a unit, so merely opening
            // this screen does not lock in the locale default and break locale-change tracking.
            unit.setPersistent(false);
            unit.setValue(Weather.unit(cr));
            unit.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            unit.setOnPreferenceChangeListener((p, value) -> {
                store.putString("sleep_weather_unit", (String) value);
                return true;
            });
        }

        mCity = findPreference("sleep_weather_city");
        if (mCity != null) {
            updateCitySummary();
            mCity.setOnPreferenceClickListener(p -> {
                askForCity();
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.sleep_title);
        if (mCity != null && !mSearching) updateCitySummary();
    }

    @Override
    public void onDestroyView() {
        mSearchSeq.incrementAndGet();
        mSearching = false;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroyView();
    }

    private void updateCitySummary() {
        Weather.City city = Weather.City.decode(Settings.System.getString(
                requireContext().getContentResolver(), Keys.SLEEP_WEATHER_CITY));
        mCity.setSummary(city != null ? city.name : getString(R.string.sleep_weather_city_none));
    }

    private void askForCity() {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint(R.string.sleep_weather_city_hint);
        Weather.City current = Weather.City.decode(
                Settings.System.getString(context.getContentResolver(), Keys.SLEEP_WEATHER_CITY));
        if (current != null) {
            input.setText(current.name);
            input.selectAll();
        }
        FrameLayout frame = new FrameLayout(context);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.sleep_weather_city)
                .setView(frame)
                .setPositiveButton(R.string.sleep_weather_search,
                        (d, which) -> search(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.dismiss();
            search(input.getText().toString().trim());
            return true;
        });
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) mDialog = null;
        });
        if (mDialog != null) mDialog.dismiss();
        mDialog = dialog;
        dialog.show();
        input.requestFocus();
    }

    private void search(String query) {
        if (query.isEmpty()) return;
        final int seq = mSearchSeq.incrementAndGet();
        final Context app = requireContext().getApplicationContext();
        mSearching = true;
        mCity.setSummary(R.string.sleep_weather_searching);
        new Thread(() -> {
            List<Weather.City> found = null;
            try {
                found = Weather.search(query);
            } catch (Exception e) {
                Log.w(TAG, "city search failed: " + e);
            }
            final List<Weather.City> result = found;
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (seq != mSearchSeq.get() || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                mSearching = false;
                if (!isAdded()) return;
                if (mCity != null) updateCitySummary();
                if (!isResumed()) return;
                if (result == null) {
                    Toast.makeText(app, R.string.sleep_weather_error, Toast.LENGTH_LONG).show();
                } else if (result.isEmpty()) {
                    Toast.makeText(app, getString(R.string.sleep_weather_no_match, query),
                            Toast.LENGTH_LONG).show();
                } else {
                    choose(result);
                }
            });
        }, "HiBreakCitySearch").start();
    }

    private void choose(List<Weather.City> cities) {
        CharSequence[] labels = new CharSequence[cities.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = cities.get(i).label();
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sleep_weather_city)
                .setItems(labels, (d, which) -> {
                    Settings.System.putString(requireContext().getContentResolver(),
                            Keys.SLEEP_WEATHER_CITY, cities.get(which).encode());
                    updateCitySummary();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) mDialog = null;
        });
        if (mDialog != null) mDialog.dismiss();
        mDialog = dialog;
        dialog.show();
    }
}
