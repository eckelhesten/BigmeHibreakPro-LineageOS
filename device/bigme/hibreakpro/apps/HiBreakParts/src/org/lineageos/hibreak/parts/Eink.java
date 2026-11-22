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
    static final String PROP_AUTO_CLEAN = "vendor.xrz.auto_clean_enabled";
    static final String PROP_ANTI_FLICKER = "vendor.xrz.anti_flicker_enabled";
    /** Read by SurfaceFlinger (frameworks/native patch 0006): the fast mode while the screen
     *  moves, and while the keyboard is up. Always on (Reading scrolls and types in FAST). */
    static final String PROP_MOTION_FAST = "vendor.xrz.hibreak_motion_fast";
    static final String PROP_IME_FAST = "vendor.xrz.hibreak_ime_fast";
    /** Read by SurfaceFlinger's render pass (frameworks/native 0019 + 0022): FAST (A2) draws
     *  16 greys instead of black and white dots (including while typing); the panel drives them
     *  in 35 ms passes. Always on: it is what makes Default look good. */
    static final String PROP_FAST_GREYS = "vendor.xrz.hibreak_fast_greys";
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
            try {
                last = new String[] {f[1].trim(), f[2].trim()};
                if (tempC <= Float.parseFloat(f[0].trim())) return last;
            } catch (NumberFormatException e) {
                last = null;
            }
        }
        return last; // warmer than every band: the warmest
    }
    /** Read by SurfaceFlinger's e-ink passes: the grey levels that become black and white in the
     *  1-bit passes (typing in FAST; legacy enhancement pass). Fixed at the former "Strong": with
     *  no crush the dither punched white dots into dark-grey web text. The grey passes (Default's
     *  greys, Reading) ignore them. */
    private static final String PROP_CONTRAST_BLACK = "vendor.xrz.hibreak_contrast_black";
    private static final String PROP_CONTRAST_WHITE = "vendor.xrz.hibreak_contrast_white";
    private static final String PROP_BLEACH = "vendor.xrz.hibreak_eink_bleach";
    private static final String PROP_CRUSH = "vendor.xrz.hibreak_eink_crush";
    private static final int CONTRAST_BLACK = 48;
    private static final int CONTRAST_WHITE = 208;
    private static final int BLEACH = 194;
    private static final int CRUSH = 92;

    /** The two profiles, and the per-app extra for dark-only apps. */
    static final int PROFILE_DEFAULT = 0;
    static final int PROFILE_READING = 1;
    static final int APP_INVERTED = 2;
    /** XRZ policy mode per profile, as the stock firmware sets them. Default: FAST (0xB3, A2 with
     *  the kernel's cleanup) with SurfaceFlinger's greys, ~0.23 s. Reading: CLEAN (0xB0, GC16),
     *  page turns held into one clean refresh (frameworks/native 0021), ~0.5 s. Policy modes, not
     *  raw waveform numbers: only with them the composer keeps whole-panel updates, the kernel's
     *  fast, self-cleaning path. Never write -1 (garbage waveform). */
    private static final int[] PROFILE_MODE = {0xB3, 0xB0};
    /** The bench's raw mode indexes (Keys.EINK_BENCH_MODE): Normal (DU), Clean, HD, Fast. */
    private static final int[] BENCH_MODE = {0, 0xB0, 0xB1, 0xB3};
    /** E-readers that use Reading unless the user picked something else for them. */
    static final Set<String> READING_APPS = Set.of(
            "com.amazon.kindle",
            "com.google.android.apps.books",
            "com.kobobooks.android",
            "org.koreader.launcher",
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

    /**
     * Everything that used to be a switch, set the one way that works: the kernel's cleanup and
     * anti-flicker on, FAST while the screen moves and while the keyboard is up, greys in FAST,
     * and the 1-bit passes' black and white levels. Doesn't touch SurfaceFlinger's colour
     * matrix, so Android's own colour transforms (colour correction, inversion) stay intact.
     */
    static void applyFixedSettings() {
        SystemProperties.set(PROP_AUTO_CLEAN, "1");
        SystemProperties.set(PROP_ANTI_FLICKER, "1");
        SystemProperties.set(PROP_MOTION_FAST, "1");
        SystemProperties.set(PROP_IME_FAST, "1");
        SystemProperties.set(PROP_FAST_GREYS, "1");
        SystemProperties.set(PROP_CONTRAST_BLACK, Integer.toString(CONTRAST_BLACK));
        SystemProperties.set(PROP_CONTRAST_WHITE, Integer.toString(CONTRAST_WHITE));
        SystemProperties.set(PROP_BLEACH, Integer.toString(BLEACH));
        SystemProperties.set(PROP_CRUSH, Integer.toString(CRUSH));
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
