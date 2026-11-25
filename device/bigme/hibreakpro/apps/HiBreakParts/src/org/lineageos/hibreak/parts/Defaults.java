/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.UserManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.IOException;

/**
 * E-ink friendly defaults, applied once (first start after install or wipe); the user can change
 * them afterwards in Settings > Display > E-ink & front light or in Accessibility.
 * Verified on camera 2026-11-13: high-contrast + bold text turn grey anti-aliased text into
 * solid black; animator duration 0 removes keyboard pop-up and other animation frames.
 * Version 2: plain white wallpaper, monochrome system theme.
 * Version 3: contrast level 0.5. At 1.0 SystemUI draws the lock screen clock in the background
 * grey (measured from screenshots), so it disappears.
 * Version 4: the two-tone e-ink palette in SystemUI (debug.sysui.eink_palette) replaces the
 * accessibility stopgaps, so contrast level and high-contrast text go back to off. Those stay the
 * user's choice in Accessibility. Bold text stays for now: it measurably thickens text strokes.
 * Version 5: themed (monochrome) app icons in the launcher, which follow the two-tone palette.
 * The launcher isn't direct-boot aware, so this step waits for the first unlock.
 * Version 6: text is drawn black or white by hwui itself (debug.hwui.eink_text), so the "Crisp
 * text" switch, which turned on high-contrast text and its grey backing pills, is gone; turn
 * high-contrast text off again where it was switched on.
 * Version 7 (2026-11-17): curated out-of-the-box defaults, so a fresh
 * install starts the same way: animations on (the e-ink composer's fast mode handles motion; off
 * felt broken), text size 115 %, contrast level 1.0, auto brightness for the front light, no
 * lift to wake, no QR scanner shortcut on the lock screen, fingerprint unlock with the screen
 * off (the sensor is in the power key). Front light warmth 70 and the sleep screen's next event
 * are defaults in Keys / the settings XML. Not taken over: anything personal (wallpaper and its
 * colour theme, language, sounds and volumes, accounts, the weather city).
 * Version 8 (2026-11-20): the Clock app, which is always dark (heavy, blotchy ghosting on black,
 * camera-checked), gets the per-app "colours inverted" mode unless it already has a mode.
 * Version 9 (2026-11-21): bold text off again. The system font (IBM Plex Sans then, Roboto, Noto Sans
 * since) is drawn half a weight step darker for e-ink by the font configuration itself (the
 * 450 curve); with +300 on top, regular, medium and bold text came out alike on the 1-bit panel.
 * The switch in Settings > Display > E-ink stays the user's choice.
 * Version 10 (2026-11-22): two e-ink profiles, Default and Reading, replace the four refresh modes
 * and their switches; the old global and per-app modes are carried over (migrateToProfiles).
 */
final class Defaults {
    private static final String APPLIED = Keys.PREFIX + "defaults_applied";
    private static final String APPLIED_CE = Keys.PREFIX + "defaults_ce_applied";
    private static final int VERSION = 10;
    private static final String TAG = "HiBreakDefaults";
    private static final String THEME_SETTING = "theme_customization_overlay_packages";
    private static final String CONTRAST_SETTING = "contrast_level"; // Settings.Secure, -1..1
    static final String HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"; // Settings.Secure
    static final int BOLD_WEIGHT_ADJUSTMENT = 300;
    private static final String LOCK_SCREEN_QR_SCANNER = "lock_screen_show_qr_code_scanner";
    /** Settings > Security > Fingerprint: unlock without waking the screen first. */
    private static final String SFPS_UNLOCK_ANYTIME = "sfps_performant_auth_enabled_v2";
    private static final String DESK_CLOCK = "com.android.deskclock";
    private static final String LEGACY_FAST_MAPS =
            "100:0,0,0,4,4,5,6,6,7,7,8,9,10,11,12,15:100";
    private static final Object CE_LOCK = new Object();
    private static boolean sUnlockReceiverRegistered;

    private Defaults() {}

    static void applyOnce(Context context) {
        ContentResolver cr = context.getContentResolver();
        int applied = Settings.System.getInt(cr, APPLIED, 0);
        if (applied < VERSION) {
            if (applied < 2) {
                Settings.Secure.putString(cr, THEME_SETTING,
                        "{\"android.theme.customization.theme_style\":\"MONOCHROMATIC\"}");
            }
            if (applied < 6) {
                Settings.Secure.putInt(cr, HIGH_TEXT_CONTRAST, 0);
            }
            if (applied < 7) {
                Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
                Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
                // Font scale: SettingsProviderHiBreakPro's def_device_font_scale (1.3) applies.
                Settings.Secure.putFloat(cr, CONTRAST_SETTING, 1f);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                Settings.Secure.putInt(cr, Settings.Secure.WAKE_GESTURE_ENABLED, 0);
                Settings.Secure.putInt(cr, LOCK_SCREEN_QR_SCANNER, 0);
                Settings.Secure.putInt(cr, SFPS_UNLOCK_ANYTIME, 1);
            }
            if (applied < 8) {
                java.util.Map<String, Integer> profiles = Eink.getAppProfiles(cr);
                if (!profiles.containsKey(DESK_CLOCK)) {
                    profiles.put(DESK_CLOCK, Eink.APP_INVERTED);
                    Eink.putAppProfiles(cr, profiles);
                }
            }
            if (applied < 9) {
                Settings.Secure.putInt(cr, Settings.Secure.FONT_WEIGHT_ADJUSTMENT, 0);
            }
            if (applied < 10) {
                migrateToProfiles(cr);
            }
            Settings.System.putInt(cr, APPLIED, VERSION);
        }

        int ce = Settings.System.getInt(cr, APPLIED_CE, -1);
        if (ce < 0) {
            ce = applied >= 5 ? 2 : 0;
            if (ce > 0) Settings.System.putInt(cr, APPLIED_CE, ce);
        }
        if (ce < 2) {
            UserManager um = context.getSystemService(UserManager.class);
            if (um != null && um.isUserUnlocked()) {
                applyCeDefaults(context);
            } else {
                retryWhenUnlocked(context);
            }
        }
    }

    /**
     * The four modes and their switches became two profiles (version 10): Clear and Balanced
     * become Reading, Default (DU) and Fast become Default, per-app "inverted" stays. Choices
     * already made in the new settings win.
     */
    private static void migrateToProfiles(ContentResolver cr) {
        if (LEGACY_FAST_MAPS.equals(Settings.System.getString(cr, Keys.EINK_FAST_MAPS))) {
            Settings.System.putString(cr, Keys.EINK_FAST_MAPS, null);
        }
        int mode = Settings.System.getInt(cr, Keys.LEGACY_EINK_MODE, 0);
        if (Settings.System.getString(cr, Keys.EINK_PROFILE) == null) {
            Eink.setProfile(cr, mode == 1 || mode == 2 ? Eink.PROFILE_READING : Eink.PROFILE_DEFAULT);
        }
        String legacy = Settings.System.getString(cr, Keys.LEGACY_EINK_APP_MODES);
        if (legacy == null || legacy.isEmpty()) return;
        java.util.Map<String, Integer> profiles = Eink.getAppProfiles(cr);
        for (String entry : legacy.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String pkg = entry.substring(0, eq);
            int old;
            try {
                old = Integer.parseInt(entry.substring(eq + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            int profile = old == 4 ? Eink.APP_INVERTED
                    : (old == 1 || old == 2) ? Eink.PROFILE_READING : Eink.PROFILE_DEFAULT;
            profiles.putIfAbsent(pkg, profile);
        }
        Eink.putAppProfiles(cr, profiles);
    }

    private static void applyCeDefaults(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            synchronized (CE_LOCK) {
                ContentResolver cr = app.getContentResolver();
                int ce = Settings.System.getInt(cr, APPLIED_CE, 0);
                if (ce < 1) {
                    // Done only once it worked: a failure (the wallpaper service not ready) is
                    // tried again at the next start.
                    if (!applyWhiteWallpaper(app)) return;
                    Settings.System.putInt(cr, APPLIED_CE, 1);
                    ce = 1;
                }
                if (ce < 2) {
                    // Best effort: Launcher3 may be replaced or disabled; record completion either way.
                    setThemedIcons(app);
                    Settings.System.putInt(cr, APPLIED_CE, 2);
                }
            }
        }, "HiBreakDefaultsCe").start();
    }

    private static void retryWhenUnlocked(Context context) {
        synchronized (CE_LOCK) {
            if (sUnlockReceiverRegistered) return;
            sUnlockReceiverRegistered = true;
        }
        Context app = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    app.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                    return;
                }
                applyCeDefaults(app);
            }
        };
        app.registerReceiver(receiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED);
        // Close the race window if the user unlocked between the check and registerReceiver.
        UserManager um = app.getSystemService(UserManager.class);
        if (um != null && um.isUserUnlocked()) {
            try {
                app.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                return; // already fired
            }
            applyCeDefaults(app);
        }
    }

    /** Launcher3's customization provider, the same call the wallpaper & style picker makes. */
    static boolean setThemedIcons(Context context) {
        ContentValues values = new ContentValues();
        values.put("boolean_value", true);
        try {
            return context.getContentResolver().update(
                    Uri.parse("content://com.android.launcher3.grid_control/set_icon_themed"),
                    values, null, null) > 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "enabling themed icons failed", e);
            return false;
        }
    }

    private static boolean applyWhiteWallpaper(Context app) {
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        Bitmap white = Bitmap.createBitmap(Math.max(1, dm.widthPixels),
                Math.max(1, dm.heightPixels), Bitmap.Config.ARGB_8888);
        white.eraseColor(Color.WHITE);
        try {
            return WallpaperManager.getInstance(app).setBitmap(white, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK) != 0;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "setting the white wallpaper failed", e);
            return false;
        } finally {
            white.recycle();
        }
    }
}
