/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.content.ContentResolver;
import android.provider.Settings;

import androidx.preference.PreferenceDataStore;

/** Stores preference "foo" as Settings.System "hibreak_foo", where the policy code reads it. */
class SystemSettingsStore extends PreferenceDataStore {
    private final ContentResolver mResolver;

    SystemSettingsStore(ContentResolver resolver) {
        mResolver = resolver;
    }

    private static String name(String key) {
        return Keys.PREFIX + key;
    }

    @Override
    public void putString(String key, String value) {
        Settings.System.putString(mResolver, name(key), value);
    }

    @Override
    public String getString(String key, String defValue) {
        String v = Settings.System.getString(mResolver, name(key));
        return v != null ? v : defValue;
    }

    @Override
    public void putInt(String key, int value) {
        Settings.System.putInt(mResolver, name(key), value);
    }

    @Override
    public int getInt(String key, int defValue) {
        return Settings.System.getInt(mResolver, name(key), defValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        Settings.System.putInt(mResolver, name(key), value ? 1 : 0);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return Settings.System.getInt(mResolver, name(key), defValue ? 1 : 0) != 0;
    }
}
