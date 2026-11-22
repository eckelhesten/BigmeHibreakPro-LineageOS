/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.hibreak.parts;

/**
 * Settings.System names shared by the app process and KeyHandler (system_server).
 * No resource references here: KeyHandler is loaded without the app's resources.
 * Preference keys in res/xml/settings.xml are these names without PREFIX.
 */
final class Keys {
    static final String PREFIX = "hibreak_";

    static final String FRONTLIGHT_ENABLED = PREFIX + "frontlight_enabled"; // 1/0, default 1
    static final String FRONTLIGHT_WARMTH = PREFIX + "frontlight_warmth";   // 0..100, default 70
    static final String EINK_MODE = PREFIX + "eink_mode";                   // mode index, default 0
    static final String EINK_AUTO_CLEAN = PREFIX + "eink_auto_clean";       // 1/0, default 1
    static final String EINK_ANTI_FLICKER = PREFIX + "eink_anti_flicker";   // 1/0, default 1
    static final String EINK_APP_MODES = PREFIX + "eink_app_modes";         // "pkg=idx;pkg=idx"
    static final String EINK_MOTION_FAST = PREFIX + "eink_motion_fast";     // 1/0, default 1
    static final String EINK_IME_FAST = PREFIX + "eink_ime_fast";           // 1/0, default 1
    static final String EINK_FAST_GREYS = PREFIX + "eink_fast_greys";       // 1/0, default 0
    // Greys in Fast mode, per temperature band: "maxC:l0,...,l15:gamma_x100;..." (bands in
    // rising order, the first whose maxC is at or above the battery temperature applies).
    // Written by the bench (scripts/eink-bench/greys.py) after calibrating; empty = identity.
    static final String EINK_FAST_MAPS = PREFIX + "eink_fast_maps";
    static final String EINK_CONTRAST = PREFIX + "eink_contrast";           // 0..2, default 2
    // Automatic full (GC16) refresh against ghosting: -1 only when asked, 0 after unlocking,
    // N > 0 after unlocking and after every Nth app switch. Default DEFAULT_CLEAN.
    static final String EINK_CLEAN = PREFIX + "eink_clean";
    // Written by the shell or the bench (any new value): one full refresh now.
    static final String EINK_REFRESH_REQUEST = PREFIX + "eink_refresh";
    static final String KEY_F1 = PREFIX + "key_f1";                         // action, see KeyHandler
    static final String KEY_F2 = PREFIX + "key_f2";

    // Sleep screen corners (SleepScreenInfo; drawn by SystemUI's EinkSleepScreen).
    static final String SLEEP_WEATHER = PREFIX + "sleep_weather";           // 1/0, default 0
    static final String SLEEP_WEATHER_UNIT = PREFIX + "sleep_weather_unit"; // c/f, default: regional preferences
    static final String SLEEP_WEATHER_CITY = PREFIX + "sleep_weather_city"; // "lat;lon;name"
    static final String SLEEP_CALENDAR = PREFIX + "sleep_calendar";         // 1/0, default 1
    // Written for SystemUI, read at every sleep screen redraw. Empty = nothing to show.
    // "2;kind;day 1/0;temperature in C;unit c/f;fetched at ms;WMO code;condition;city"
    static final String SLEEP_WEATHER_DATA = PREFIX + "sleep_weather_data";
    // "1;begin ms;end ms;all day 1/0;title" (all-day events in local midnights)
    static final String SLEEP_CALENDAR_DATA = PREFIX + "sleep_calendar_data";

    static final int DEFAULT_WARMTH = 70;
    static final int DEFAULT_CONTRAST = 2;
    static final int DEFAULT_CLEAN = 4;
    static final String DEFAULT_KEY_F1 = "eink_refresh";
    static final String DEFAULT_KEY_F2 = "eink_mode";

    private Keys() {}
}
