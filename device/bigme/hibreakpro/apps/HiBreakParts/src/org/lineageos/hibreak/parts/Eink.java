/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

import android.content.ContentResolver;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Control of the stock XRZ e-ink composer through its vendor properties.
 * Used by the app and by KeyHandler inside system_server, so no resource references.
 */
final class Eink {
    private static final String TAG = "HiBreakEink";
    static final String PROP_GLOBAL_MODE = "vendor.xrz.global_refresh_mode";
    private static final String PROP_FORCE_MODE = "vendor.xrz.force_global_refresh_mode";
    /** The current temperature band's level map and tone curve for greys in Fast mode. */
    static final String PROP_FAST_LEVELS = "vendor.xrz.hibreak_fast_levels";
    static final String PROP_FAST_GAMMA = "vendor.xrz.hibreak_fast_gamma_x100";

    /**
     * The band of {@code maps} ("maxC:levels:gamma;...") for a battery temperature in deg C:
     * {levels, gamma}, or null for none (SurfaceFlinger's identity map and default curve).
     */
    static String[] fastGreyBand(String maps, float tempC) {
        if (TextUtils.isEmpty(maps)) return null;
        String[] last = null;
        for (String band : maps.split(";")) {
            String[] f = band.split(":");
            if (f.length != 3) continue;
            final float maxC;
            try {
                maxC = Float.parseFloat(f[0].trim());
            } catch (NumberFormatException e) {
                continue; // a broken band is skipped; it doesn't drop the one before
            }
            if (!Float.isFinite(maxC)) continue;
            last = new String[] {f[1].trim(), f[2].trim()};
            if (tempC <= maxC) return last;
        }
        return last; // warmer than every band: the warmest
    }

    /** The two profiles, and the per-app extra for dark-only apps. */
    static final int PROFILE_DEFAULT = 0;
    static final int PROFILE_READING = 1;
    static final int APP_INVERTED = 2;
    /** XRZ policy mode per profile, as the stock firmware sets them. Default: FAST (0xB3, A2 with
     *  the kernel's cleanup) with SurfaceFlinger's greys, ~0.23 s. Reading: HD (0xB1, 16 greys,
     *  no flash) for everything on screen, while every page (a held page turn, the page back
     *  after a menu, the end of a scroll) is shown as one GC16 by SurfaceFlinger
     *  (frameworks/native 0021, 0024): zero ghosting on pages, ~0.55 s. Until 1.0.3 Reading was
     *  CLEAN (0xB0) as the mode itself, so everything else flashed too: the composer's idle
     *  refresh 0.6 s after FAST frames gave a second GC16 of a still page after a menu. Pages in
     *  HD alone (tried 2026-11-24) left visible ghosts. Policy modes, not raw waveform numbers:
     *  only with them the composer keeps whole-panel updates, the kernel's fast, self-cleaning
     *  path. Never write -1 (garbage waveform). */
    static final int READING_MODE = 0xB1;
    private static final int[] PROFILE_MODE = {0xB3, READING_MODE};
    /** Modes Keys.EINK_READING_MODE may choose for Reading: HD or CLEAN. */
    private static final Set<Integer> READING_MODES = Set.of(0xB1, 0xB0);
    /** The bench's raw mode indexes (Keys.EINK_BENCH_MODE): Normal (DU), Clean, HD, Fast. */
    private static final int[] BENCH_MODE = {0, 0xB0, 0xB1, 0xB3};
    /** E-readers that use Reading unless the user picked something else for them. */
    static final Set<String> READING_APPS = Set.of(
            "com.amazon.kindle",
            "com.google.android.apps.books",
            "com.kobobooks.android",
            "org.koreader.launcher",
            "org.koreader.launcher.fdroid",
            "com.overdrive.mobile.android.libby",
            "com.flyersoft.moonreader",
            "org.readera");
    static final String PROP_INVERT = "vendor.xrz.hibreak_eink_invert";
    private static final int WAVEFORM_GC16 = 4;
    private static final int SF_TRANSACTION_REPAINT = 1004;

    private Eink() {}

    static int clampProfile(int profile) {
        return profile == PROFILE_READING ? PROFILE_READING : PROFILE_DEFAULT;
    }

    static int getProfile(ContentResolver cr) {
        return clampProfile(Settings.System.getInt(cr, Keys.EINK_PROFILE, PROFILE_DEFAULT));
    }

    static void setProfile(ContentResolver cr, int profile) {
        Settings.System.putInt(cr, Keys.EINK_PROFILE, clampProfile(profile));
    }

    /** The composer mode for a profile (or APP_INVERTED, which is Default with inverted colours). */
    static int modeFor(int profile) {
        return PROFILE_MODE[profile == PROFILE_READING ? PROFILE_READING : PROFILE_DEFAULT];
    }

    /** modeFor, with Reading's mode from Keys.EINK_READING_MODE when that holds a valid one. */
    static int modeFor(int profile, ContentResolver cr) {
        if (profile != PROFILE_READING) return modeFor(profile);
        int mode = Settings.System.getInt(cr, Keys.EINK_READING_MODE, READING_MODE);
        return READING_MODES.contains(mode) ? mode : READING_MODE;
    }

    /** The bench's raw mode for an index, or -1 for none (back to the profiles). */
    static int benchMode(int index) {
        return (index >= 0 && index < BENCH_MODE.length) ? BENCH_MODE[index] : -1;
    }

    /** What an app runs in: its own choice, Reading for known e-readers, else the global one. */
    static int profileFor(Map<String, Integer> appProfiles, String pkg, int global) {
        if (pkg == null) return global;
        Integer own = appProfiles.get(pkg);
        if (own != null) return own;
        return READING_APPS.contains(pkg) ? PROFILE_READING : global;
    }

    static Map<String, Integer> getAppProfiles(ContentResolver cr) {
        return parseAppProfiles(Settings.System.getString(cr, Keys.EINK_APP_PROFILES));
    }

    static Map<String, Integer> parseAppProfiles(String s) {
        Map<String, Integer> profiles = new TreeMap<>();
        if (TextUtils.isEmpty(s)) return profiles;
        for (String entry : s.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                int p = Integer.parseInt(entry.substring(eq + 1));
                if (p >= PROFILE_DEFAULT && p <= APP_INVERTED) profiles.put(entry.substring(0, eq), p);
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return profiles;
    }

    static void putAppProfiles(ContentResolver cr, Map<String, Integer> profiles) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : profiles.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Settings.System.putString(cr, Keys.EINK_APP_PROFILES, sb.toString());
    }

    /** One full GC16 refresh of whatever is on screen now (clears ghosting). */
    static void fullRefresh() {
        SystemProperties.set(PROP_FORCE_MODE, Integer.toString(WAVEFORM_GC16));
        requestRepaint();
    }

    /** SurfaceFlinger's repaint transaction, so the composer commits even on a static screen. */
    static void requestRepaint() {
        IBinder sf = ServiceManager.getService("SurfaceFlinger");
        if (sf == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.ui.ISurfaceComposer");
            sf.transact(SF_TRANSACTION_REPAINT, data, null, 0);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "repaint request failed", e);
        } finally {
            data.recycle();
        }
    }
}
