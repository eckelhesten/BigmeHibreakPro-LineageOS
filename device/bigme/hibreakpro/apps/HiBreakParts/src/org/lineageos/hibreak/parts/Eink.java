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
import java.util.TreeMap;

/**
 * Control of the stock XRZ e-ink composer through its vendor properties
 * (see docs/eink-interface.md, "Live test: controlling the HWC"). Used by the app and by
 * KeyHandler inside system_server, so no resource references.
 */
final class Eink {
    private static final String TAG = "HiBreakEink";
    static final String PROP_GLOBAL_MODE = "vendor.xrz.global_refresh_mode";
    private static final String PROP_FORCE_MODE = "vendor.xrz.force_global_refresh_mode";
    static final String PROP_AUTO_CLEAN = "vendor.xrz.auto_clean_enabled";
    static final String PROP_ANTI_FLICKER = "vendor.xrz.anti_flicker_enabled";
    /** Read by SurfaceFlinger (frameworks/native patch 0006): the fast mode while the screen
     *  moves, and while the keyboard is up. */
    static final String PROP_MOTION_FAST = "vendor.xrz.hibreak_motion_fast";
    static final String PROP_IME_FAST = "vendor.xrz.hibreak_ime_fast";
    /** Read by SurfaceFlinger's render pass (frameworks/native 0019): Fast mode (A2) draws
     *  16 greys instead of black and white dots; the panel drives them in 35 ms passes. */
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
    /** Read by SurfaceFlinger's e-ink pass: the grey levels that become black and white. */
    private static final String PROP_CONTRAST_BLACK = "vendor.xrz.hibreak_contrast_black";
    private static final String PROP_CONTRAST_WHITE = "vendor.xrz.hibreak_contrast_white";
    /** Read by SurfaceFlinger's render pass (frameworks/native 0013): light greys from the
     *  bleach level up become white (keeping an outline), greys below the crush level black. */
    private static final String PROP_BLEACH = "vendor.xrz.hibreak_eink_bleach";
    private static final String PROP_CRUSH = "vendor.xrz.hibreak_eink_crush";

    /** XRZ policy mode per mode index, as the stock firmware sets them: Default (0 = the
     *  composer's NORMAL, DU with the kernel's cleanup), Clear (0xB0 CLEAN, GC16), Balanced
     *  (0xB1 HD, grey 0x21), Fast (0xB3 FAST, A2 with cleanup). Policy modes, not raw waveform
     *  numbers: only with them the composer keeps whole-panel updates, which is the kernel's
     *  fast, self-cleaning path (docs/eink-stock-policy.md). Never write -1 (garbage waveform). */
    static final int[] MODE_VALUES = {0, 0xB0, 0xB1, 0xB3};
    /** Per-app only: Default, with the frame's lightness inverted by SurfaceFlinger's render
     *  pass (frameworks/native 0013), for apps that are always dark. */
    static final int MODE_INVERTED = 4;
    static final String PROP_INVERT = "vendor.xrz.hibreak_eink_invert";
    private static final int WAVEFORM_GC16 = 4;
    private static final int SF_TRANSACTION_REPAINT = 1004;
    /** Grey levels that become black and white per contrast level: off, medium, strong.
     *  Strong is what SurfaceFlinger's former e-ink engine used. */
    private static final int[] CONTRAST_BLACK = {0, 24, 48};
    private static final int[] CONTRAST_WHITE = {255, 232, 208};
    /** The same levels for the render pass: off, medium, strong (scored offline in
     *  scripts/eink-bench: strong keeps 71 % of faint structure that the old stretch erased). */
    private static final int[] BLEACH = {255, 214, 194};
    private static final int[] CRUSH = {0, 56, 92};

    private Eink() {}

    static int clampMode(int index) {
        return (index >= 0 && index < MODE_VALUES.length) ? index : 0;
    }

    static int getGlobalMode(ContentResolver cr) {
        return clampMode(Settings.System.getInt(cr, Keys.EINK_MODE, 0));
    }

    static void setGlobalMode(ContentResolver cr, int index) {
        Settings.System.putInt(cr, Keys.EINK_MODE, clampMode(index));
    }

    static Map<String, Integer> getAppModes(ContentResolver cr) {
        return parseAppModes(Settings.System.getString(cr, Keys.EINK_APP_MODES));
    }

    static Map<String, Integer> parseAppModes(String s) {
        Map<String, Integer> modes = new TreeMap<>();
        if (TextUtils.isEmpty(s)) return modes;
        for (String entry : s.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                int mode = Integer.parseInt(entry.substring(eq + 1));
                modes.put(entry.substring(0, eq), mode == MODE_INVERTED ? mode : clampMode(mode));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return modes;
    }

    static void putAppModes(ContentResolver cr, Map<String, Integer> modes) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : modes.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Settings.System.putString(cr, Keys.EINK_APP_MODES, sb.toString());
    }

    /** One full GC16 refresh of whatever is on screen now (clears ghosting). */
    static void fullRefresh() {
        SystemProperties.set(PROP_FORCE_MODE, Integer.toString(WAVEFORM_GC16));
        requestRepaint();
    }

    static void setAntiFlicker(boolean enabled) {
        SystemProperties.set(PROP_ANTI_FLICKER, enabled ? "1" : "0");
    }

    /**
     * Text contrast: SurfaceFlinger's e-ink render pass uses the bleach/crush properties (and the
     * legacy enhancement pass uses contrast_black/white), applied on the GPU after stroke
     * sharpening. Level 0 turns it off. Does not touch SurfaceFlinger's colour matrix, so
     * Android's own colour transforms (colour correction, inversion) stay intact.
     */
    static void setContrast(int level) {
        if (level < 0 || level >= CONTRAST_BLACK.length) level = 0;
        SystemProperties.set(PROP_CONTRAST_BLACK, Integer.toString(CONTRAST_BLACK[level]));
        SystemProperties.set(PROP_CONTRAST_WHITE, Integer.toString(CONTRAST_WHITE[level]));
        SystemProperties.set(PROP_BLEACH, Integer.toString(BLEACH[level]));
        SystemProperties.set(PROP_CRUSH, Integer.toString(CRUSH[level]));
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
