/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.PreferenceDataStore;

/**
 * Stores preference "foo" as Settings.System "hibreak_foo", where the policy code reads it: the
 * system user's settings (deviceResolver), except the side keys, which KeyHandler reads per user.
 */
class SystemSettingsStore extends PreferenceDataStore {
    private final ContentResolver mDevice;
    private final ContentResolver mUser;

    SystemSettingsStore(Context context) {
        mDevice = deviceResolver(context);
        mUser = context.getContentResolver();
    }

    /**
     * The system user's settings: the front light, the e-ink profiles and the sleep screen are
     * the device's, and their controllers (PartsApp) run as the system user and read only its
     * settings. A tile or the settings screen of a secondary user wrote that user's settings,
     * which changed nothing.
     */
    static ContentResolver deviceResolver(Context context) {
        if (context.getUserId() == UserHandle.USER_SYSTEM) return context.getContentResolver();
        try {
            return context.createContextAsUser(UserHandle.SYSTEM, 0).getContentResolver();
        } catch (RuntimeException e) {
            return context.getContentResolver();
        }
    }

    /** The side keys' actions are the current user's (KeyHandler reads them per user). */
    static boolean isPerUser(String name) {
        return Keys.KEY_F1.equals(name) || Keys.KEY_F2.equals(name);
    }

    private static String name(String key) {
        return Keys.PREFIX + key;
    }

    private ContentResolver resolver(String name) {
        return isPerUser(name) ? mUser : mDevice;
    }

    @Override
    public void putString(String key, String value) {
        Settings.System.putString(resolver(name(key)), name(key), value);
    }

    @Override
    public String getString(String key, String defValue) {
        String v = Settings.System.getString(resolver(name(key)), name(key));
        return v != null ? v : defValue;
    }

    @Override
    public void putInt(String key, int value) {
        Settings.System.putInt(resolver(name(key)), name(key), value);
    }

    @Override
    public int getInt(String key, int defValue) {
        return Settings.System.getInt(resolver(name(key)), name(key), defValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        Settings.System.putInt(resolver(name(key)), name(key), value ? 1 : 0);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return Settings.System.getInt(resolver(name(key)), name(key), defValue ? 1 : 0) != 0;
    }
}
