/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Applies the e-ink profile to the composer: the global profile (Default or Reading), overridden
 * per app while that app's task is in front, and the fixed settings (Eink.applyFixedSettings)
 * that used to be switches. Full refreshes happen only when asked (Refresh tile, side key,
 * Keys.EINK_REFRESH_REQUEST), never automatically: the owner wants no unasked flashes (2026-11-23),
 * although white areas get darker over app switches without one (scripts/eink-bench/ghost.py).
 */
class EinkPolicy {
    private static final String TAG = "HiBreakEinkPolicy";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    private String mTopPackage;
    private int mAppliedValue = Integer.MIN_VALUE;
    private boolean mAppliedInvert;
    // Battery temperature (deg C) the fast-grey band was last picked for; NaN = not known yet.
    private float mBatteryTemp = Float.NaN;
    private float mBandTemp = Float.NaN;
    private String mAppliedFastBand;
    private String mCachedAppProfilesRaw;
    private Map<String, Integer> mAppProfiles = Collections.emptyMap();
    private final Runnable mUpdateTop = this::updateTopPackage;

    EinkPolicy(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
    }

    void start() {
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
        ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                applyAll();
            }
        };
        for (String key : new String[] {Keys.EINK_PROFILE, Keys.EINK_APP_PROFILES,
                Keys.EINK_BENCH_MODE, Keys.EINK_FAST_MAPS, Keys.EINK_READING_MODE}) {
            mResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskStackChanged() {
                    mHandler.removeCallbacks(mUpdateTop);
                    mHandler.post(mUpdateTop);
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "registerTaskStackListener failed", e);
        }
        mResolver.registerContentObserver(Settings.System.getUriFor(Keys.EINK_REFRESH_REQUEST),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        if (mPowerManager != null && !mPowerManager.isInteractive()) return;
                        Eink.fullRefresh();
                    }
                });
        // Sleep/wake and unlock: re-evaluate per-app inversion so an inverted foreground app
        // (e.g. Clock) never inverts the sleep screen or lock screen.
        // Default's greys drift with the panel's temperature (the kernel's synthesised A2 table
        // is the same from 12 deg C up, the panel isn't): follow the battery temperature, which
        // is one of the two the kernel picks its waveform by.
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int t = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,
                        Integer.MIN_VALUE);
                if (t == Integer.MIN_VALUE) return;
                mBatteryTemp = t / 10f;
                applyFastGreyBand();
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter power = new IntentFilter(Intent.ACTION_USER_PRESENT);
        power.addAction(Intent.ACTION_SCREEN_ON);
        power.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyMode();
            }
        }, power, Context.RECEIVER_NOT_EXPORTED);
        Eink.applyFixedSettings();
        updateTopPackage();
        applyAll();
    }

    private void updateTopPackage() {
        final String pkg;
        try {
            ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            pkg = (info != null && info.topActivity != null)
                    ? info.topActivity.getPackageName() : null;
        } catch (RemoteException e) {
            Log.w(TAG, "getFocusedRootTaskInfo failed", e);
            return;
        }
        if (!Objects.equals(pkg, mTopPackage)) {
            mTopPackage = pkg;
            applyMode();
        }
    }

    private void applyFastGreyBand() {
        if (Float.isNaN(mBatteryTemp)) return;
        // 0.5 deg C of hysteresis against flapping at a band edge
        float t = (!Float.isNaN(mBandTemp) && Math.abs(mBatteryTemp - mBandTemp) < 0.5f)
                ? mBandTemp : mBatteryTemp;
        String maps = Settings.System.getString(mResolver, Keys.EINK_FAST_MAPS);
        String[] band = Eink.fastGreyBand(maps != null ? maps : Keys.DEFAULT_FAST_MAPS, t);
        String key = band == null ? "" : band[0] + "/" + band[1];
        mBandTemp = t;
        if (key.equals(mAppliedFastBand)) return;
        mAppliedFastBand = key;
        SystemProperties.set(Eink.PROP_FAST_LEVELS, band == null ? "" : band[0]);
        SystemProperties.set(Eink.PROP_FAST_GAMMA, band == null ? "" : band[1]);
        Eink.requestRepaint();
    }

    private Map<String, Integer> getCachedAppProfiles() {
        String raw = Settings.System.getString(mResolver, Keys.EINK_APP_PROFILES);
        if (mCachedAppProfilesRaw == null || !Objects.equals(raw, mCachedAppProfilesRaw)) {
            mCachedAppProfilesRaw = raw;
            mAppProfiles = Eink.parseAppProfiles(raw);
        }
        return mAppProfiles;
    }

    private void applyAll() {
        applyFastGreyBand();
        applyMode();
    }

    private void applyMode() {
        int profile = Eink.profileFor(getCachedAppProfiles(), mTopPackage,
                Eink.getProfile(mResolver));
        boolean invert = profile == Eink.APP_INVERTED;
        if (invert && ((mPowerManager != null && !mPowerManager.isInteractive())
                || (mKeyguardManager != null && mKeyguardManager.isKeyguardLocked()))) {
            // Never invert the sleep screen or lock screen when an inverted app (e.g. Clock)
            // happens to be in the foreground.
            invert = false;
        }
        int value = Eink.modeFor(profile, mResolver);
        int bench = Eink.benchMode(Settings.System.getInt(mResolver, Keys.EINK_BENCH_MODE, -1));
        if (bench >= 0) {
            value = bench;
            invert = false;
        }
        boolean valueChanged = value != mAppliedValue;
        boolean invertChanged = mAppliedValue == Integer.MIN_VALUE || invert != mAppliedInvert;
        if (!valueChanged && !invertChanged) return;
        mAppliedValue = value;
        mAppliedInvert = invert;
        if (invertChanged) SystemProperties.set(Eink.PROP_INVERT, invert ? "1" : "0");
        if (valueChanged) SystemProperties.set(Eink.PROP_GLOBAL_MODE, Integer.toString(value));
        Eink.requestRepaint();
    }
}
