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
 * Applies the e-ink settings to the composer: the global refresh mode, overridden per app while
 * that app's task is in front, plus the auto-clean and anti-flicker switches. Also clears
 * ghosting with a full refresh after unlocking and every Nth app switch (Keys.EINK_CLEAN):
 * measured on camera (scripts/eink-bench/ghost.py, 2026-11-20), white areas got 70 % darker
 * over seven app switches without one, and a full refresh brought them back to clean.
 */
class EinkPolicy {
    private static final String TAG = "HiBreakEinkPolicy";
    private static volatile EinkPolicy sInstance;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    private String mTopPackage;
    private int mAppliedValue = Integer.MIN_VALUE;
    private boolean mAppliedInvert;
    private int mAppliedContrast = Integer.MIN_VALUE;
    private Boolean mAppliedAutoClean;
    private Boolean mAppliedAntiFlicker;
    private Boolean mAppliedMotionFast;
    private Boolean mAppliedImeFast;
    private Boolean mAppliedFastGreys;
    // Battery temperature (deg C) the fast-grey band was last picked for; NaN = not known yet.
    private float mBatteryTemp = Float.NaN;
    private float mBandTemp = Float.NaN;
    private String mAppliedFastBand;
    private String mCachedAppModesRaw;
    private Map<String, Integer> mAppModes = Collections.emptyMap();
    private int mSwitches;
    /** After a switch the new app draws for a while; refresh once it has settled. */
    private static final long CLEAN_AFTER_SWITCH_MS = 900;
    private static final long CLEAN_AFTER_UNLOCK_MS = 900;
    private final Runnable mUpdateTop = this::updateTopPackage;
    private final Runnable mClean = () -> {
        if (mPowerManager != null && !mPowerManager.isInteractive()) return;
        mSwitches = 0;
        Eink.fullRefresh();
    };

    EinkPolicy(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
    }

    /** Cancels any pending scheduled clean and resets the app-switch counter after a manual flash. */
    static void onManualRefresh() {
        EinkPolicy p = sInstance;
        if (p != null) {
            p.mHandler.post(() -> {
                p.mHandler.removeCallbacks(p.mClean);
                p.mSwitches = 0;
            });
        }
    }

    void start() {
        sInstance = this;
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
        ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                applyAll();
            }
        };
        for (String key : new String[] {Keys.EINK_MODE, Keys.EINK_APP_MODES,
                Keys.EINK_AUTO_CLEAN, Keys.EINK_ANTI_FLICKER, Keys.EINK_MOTION_FAST,
                Keys.EINK_IME_FAST, Keys.EINK_FAST_GREYS, Keys.EINK_FAST_MAPS,
                Keys.EINK_CONTRAST}) {
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
                        mSwitches = 0;
                        scheduleClean(0);
                    }
                });
        // Sleep/wake and unlock:
        // - Cancel any pending clean timer on sleep so it doesn't flash the sleep screen, and
        //   re-evaluate per-app inversion so an inverted foreground app (e.g. Clock) never
        //   inverts the sleep screen or lock screen.
        // - After waking up, the sleep screen's big clock (and the lock screen) are still in the
        //   ink. With a lock screen that is after "user present"; without one (no keyguard at all,
        //   as on the owner's phone) the screen goes straight from the sleep screen to the app and
        //   no "user present" is sent, so the screen coming on counts then.
        // Greys in Fast mode drift with the panel's temperature (the kernel's synthesised A2 table
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
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mHandler.removeCallbacks(mClean);
                    applyMode();
                    return;
                }
                applyMode();
                if (cleanEvery() < 0) return;
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (mKeyguardManager == null || mKeyguardManager.isKeyguardLocked()) {
                        return; // wait for user present
                    }
                }
                mSwitches = 0;
                scheduleClean(CLEAN_AFTER_UNLOCK_MS);
            }
        }, power, Context.RECEIVER_NOT_EXPORTED);
        updateTopPackage();
        applyAll();
    }

    private int cleanEvery() {
        return Settings.System.getInt(mResolver, Keys.EINK_CLEAN, Keys.DEFAULT_CLEAN);
    }

    private void scheduleClean(long delayMs) {
        mHandler.removeCallbacks(mClean);
        mHandler.postDelayed(mClean, delayMs);
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
            boolean switched = mTopPackage != null && pkg != null;
            mTopPackage = pkg;
            applyMode();
            int every = cleanEvery();
            boolean interactive = mPowerManager == null || mPowerManager.isInteractive();
            if (switched && interactive && every > 0 && ++mSwitches >= every) {
                mSwitches = 0;
                scheduleClean(CLEAN_AFTER_SWITCH_MS);
            }
        }
    }

    private void applyFastGreyBand() {
        if (Float.isNaN(mBatteryTemp)) return;
        // 0.5 deg C of hysteresis against flapping at a band edge
        float t = (!Float.isNaN(mBandTemp) && Math.abs(mBatteryTemp - mBandTemp) < 0.5f)
                ? mBandTemp : mBatteryTemp;
        String[] band = Eink.fastGreyBand(
                Settings.System.getString(mResolver, Keys.EINK_FAST_MAPS), t);
        String key = band == null ? "" : band[0] + "/" + band[1];
        mBandTemp = t;
        if (key.equals(mAppliedFastBand)) return;
        mAppliedFastBand = key;
        SystemProperties.set(Eink.PROP_FAST_LEVELS, band == null ? "" : band[0]);
        SystemProperties.set(Eink.PROP_FAST_GAMMA, band == null ? "" : band[1]);
        if (Boolean.TRUE.equals(mAppliedFastGreys)) Eink.requestRepaint();
    }

    private Boolean syncBoolProp(String key, String prop, Boolean prev) {
        return syncBoolProp(key, prop, prev, true);
    }

    private Boolean syncBoolProp(String key, String prop, Boolean prev, boolean def) {
        boolean val = Settings.System.getInt(mResolver, key, def ? 1 : 0) != 0;
        if (prev == null || val != prev) {
            SystemProperties.set(prop, val ? "1" : "0");
            return val;
        }
        return prev;
    }

    private Map<String, Integer> getCachedAppModes() {
        String raw = Settings.System.getString(mResolver, Keys.EINK_APP_MODES);
        if (mCachedAppModesRaw == null || !Objects.equals(raw, mCachedAppModesRaw)) {
            mCachedAppModesRaw = raw;
            mAppModes = Eink.parseAppModes(raw);
        }
        return mAppModes;
    }

    private void applyAll() {
        mAppliedAutoClean = syncBoolProp(Keys.EINK_AUTO_CLEAN, Eink.PROP_AUTO_CLEAN,
                mAppliedAutoClean);
        boolean antiFlicker = Settings.System.getInt(mResolver, Keys.EINK_ANTI_FLICKER, 1) != 0;
        if (mAppliedAntiFlicker == null || antiFlicker != mAppliedAntiFlicker) {
            mAppliedAntiFlicker = antiFlicker;
            Eink.setAntiFlicker(antiFlicker);
        }
        mAppliedMotionFast = syncBoolProp(Keys.EINK_MOTION_FAST, Eink.PROP_MOTION_FAST,
                mAppliedMotionFast);
        mAppliedImeFast = syncBoolProp(Keys.EINK_IME_FAST, Eink.PROP_IME_FAST,
                mAppliedImeFast);
        boolean greysBefore = Boolean.TRUE.equals(mAppliedFastGreys);
        mAppliedFastGreys = syncBoolProp(Keys.EINK_FAST_GREYS, Eink.PROP_FAST_GREYS,
                mAppliedFastGreys, false);
        if (mAppliedFastGreys != greysBefore) Eink.requestRepaint();
        applyFastGreyBand();
        int contrast = Settings.System.getInt(mResolver, Keys.EINK_CONTRAST,
                Keys.DEFAULT_CONTRAST);
        if (contrast != mAppliedContrast) {
            mAppliedContrast = contrast;
            Eink.setContrast(contrast);
            Eink.requestRepaint();
        }
        applyMode();
    }

    private void applyMode() {
        int index = Eink.getGlobalMode(mResolver);
        if (mTopPackage != null) {
            Integer appIndex = getCachedAppModes().get(mTopPackage);
            if (appIndex != null) index = appIndex;
        }
        boolean invert = index == Eink.MODE_INVERTED;
        if (invert && ((mPowerManager != null && !mPowerManager.isInteractive())
                || (mKeyguardManager != null && mKeyguardManager.isKeyguardLocked()))) {
            // Never invert the sleep screen or lock screen when an inverted app (e.g. Clock)
            // happens to be in the foreground.
            invert = false;
        }
        int value = Eink.MODE_VALUES[index == Eink.MODE_INVERTED ? 0 : index];
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
